package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.WebNovelChapterManifestCodec
import com.paperlight.reader.webnovel.WebNovelChapterManifestStore
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import com.paperlight.reader.webnovel.WebNovelWorkerSliceYieldException
import com.paperlight.reader.discovery.DiscoveryCatalogClient
import com.paperlight.reader.discovery.DiscoverySourceConfigCodec
import com.paperlight.reader.discovery.DiscoverySourcePurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

private object BookRepositoryOperationLock {
    val mutex = Mutex()
    private val activeRunnerTokens = mutableSetOf<String>()
    fun register(token: String) = synchronized(activeRunnerTokens) { activeRunnerTokens.add(token) }
    fun release(token: String) = synchronized(activeRunnerTokens) { activeRunnerTokens.remove(token) }
    fun isActive(token: String) = synchronized(activeRunnerTokens) { token in activeRunnerTokens }
}

class BookRepository(private val context: Context, private val dao: BookDao) {
    companion object {
        const val MAX_DISCOVERY_CACHE_BYTES = 256 * 1024
        private const val WORKER_SLICE_RETRY_MILLIS = 15 * 1_000L

        fun formatOf(name: String): String? = when (val extension = name.substringAfterLast('.', "").uppercase(java.util.Locale.ROOT)) {
            "EPUB", "PDF", "MOBI", "AZW3", "TXT", "CBZ", "CBR" -> extension
            "AZW" -> "AZW3"
            else -> null
        }

        fun webNovelBookId(url: String, options: WebNovelScrapeOptions): String =
            "webnovel-${UUID.nameUUIDFromBytes((url + "\n" + options.toString()).toByteArray(Charsets.UTF_8))}"
    }

    val books: Flow<List<BookEntity>> = dao.observeBooks()
    val libraries: Flow<List<LibraryEntity>> = dao.observeLibraries()
    val webNovelJobs: Flow<List<WebNovelJobEntity>> = dao.observeWebNovelJobs()
    val discoverySources: Flow<List<DiscoverySourceEntity>> = dao.observeDiscoverySources()

    private suspend fun <T> withLibraryOperation(allowPendingRestore: Boolean = false, operation: suspend () -> T): T {
        BookRepositoryOperationLock.mutex.lock()
        try {
            if (!allowPendingRestore) {
                check(!LibraryBackup(context, dao).hasPendingRestore()) {
                    "A backup restore is pending recovery. Recover it before using the library."
                }
            }
            return operation()
        } finally {
            BookRepositoryOperationLock.mutex.unlock()
        }
    }

    private suspend fun ensureDefaultLibraryUnlocked() {
        if (dao.findLibrary("default") == null) {
            dao.insertLibrary(LibraryEntity("default", "My Library", 0L))
        }
    }

    suspend fun ensureDefaultLibrary() = withContext(Dispatchers.IO) {
        withLibraryOperation { ensureDefaultLibraryUnlocked() }
    }

    suspend fun saveLibrary(id: String?, name: String): Result<LibraryEntity> = withContext(Dispatchers.IO) {
        try {
            withLibraryOperation {
                val cleanName = name.trim().take(60)
                require(cleanName.isNotBlank()) { "Enter a name for this library." }
                require(dao.libraryNameCount(cleanName, id.orEmpty()) == 0) { "A library with this name already exists." }
                val library = if (id == null) {
                    LibraryEntity(UUID.randomUUID().toString(), cleanName).also { dao.insertLibrary(it) }
                } else {
                    val existing = requireNotNull(dao.findLibrary(id)) { "This library no longer exists." }
                    dao.renameLibrary(id, cleanName)
                    existing.copy(name = cleanName)
                }
                Result.success(library)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun deleteLibrary(id: String) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            ensureDefaultLibraryUnlocked()
            dao.deleteLibrary(id)
        }
    }

    suspend fun moveBooks(ids: List<String>, libraryId: String) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            require(ids.isNotEmpty()) { "Select at least one book." }
            require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
            dao.moveBooks(ids.distinct(), libraryId)
        }
    }

    suspend fun importGeneratedEpub(
        file: File,
        title: String,
        author: String = "",
        libraryId: String = "default",
        stableId: String? = null,
        webNovelJob: Pair<String, String>? = null,
    ): Result<BookEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                withLibraryOperation {
                    require(file.isFile && file.length() > 0L) { "EPUB generation produced an empty file." }
                    ensureDefaultLibraryUnlocked()
                    require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
                    val id = stableId ?: UUID.randomUUID().toString()
                    require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid generated-book identifier." }
                    webNovelJob?.let { (jobId, token) ->
                        val job = requireNotNull(dao.webNovelJobById(jobId)) { "This conversion job no longer exists." }
                        require(job.runnerToken == token && job.status in setOf(WebNovelJobState.RUNNING, WebNovelJobState.WAITING_RATE_LIMIT)) {
                            "This conversion was paused, cancelled, or interrupted before import."
                        }
                    }
                    suspend fun markConversionCompleted(bookId: String) {
                        webNovelJob?.let { (jobId, token) ->
                            check(dao.completeWebNovelJobWithExistingBook(jobId, token, bookId)) {
                                "This conversion was paused, cancelled, or interrupted before import."
                            }
                            BookRepositoryOperationLock.release(token)
                        }
                    }
                    dao.bookById(id)?.let { existing ->
                        file.delete()
                        markConversionCompleted(existing.id)
                        return@withLibraryOperation existing
                    }
                    val destination = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.epub")
                    val incoming = File(destination.parentFile, ".$id.importing")
                    try {
                        file.copyTo(incoming, overwrite = true)
                        currentCoroutineContext().ensureActive()
                        val generatedBook = BookEntity(
                            id = id,
                            title = title.ifBlank { file.nameWithoutExtension },
                            author = author,
                            fileName = "${title.ifBlank { file.nameWithoutExtension }}.epub",
                            localPath = destination.absolutePath,
                            format = "EPUB",
                            importedAt = System.currentTimeMillis(),
                            libraryId = libraryId
                        )
                        withContext(NonCancellable) {
                            runCatching {
                                java.nio.file.Files.move(incoming.toPath(), destination.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                            }.getOrElse {
                                java.nio.file.Files.move(incoming.toPath(), destination.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                            if (webNovelJob == null) dao.insertBook(generatedBook)
                            else {
                                check(dao.insertGeneratedWebNovelBookAndFinish(generatedBook, webNovelJob.first, webNovelJob.second)) {
                                    "This conversion was paused, cancelled, or interrupted before import."
                                }
                                BookRepositoryOperationLock.release(webNovelJob.second)
                            }
                            file.delete()
                        }
                        generatedBook
                    } catch (failure: Exception) {
                        withContext(NonCancellable) {
                            incoming.delete()
                            if (dao.bookById(id)?.localPath != destination.absolutePath) destination.delete()
                        }
                        throw failure
                    }
                }
            }
        }

    suspend fun convertPdfToEpub(
        source: File,
        fallbackTitle: String,
        mode: PdfEpubMode,
        libraryId: String,
        onProgress: (Int, Int) -> Unit,
    ): Result<BookEntity> {
        val destination = File(context.cacheDir, "converted-${UUID.randomUUID()}.epub")
        return try {
            val (title, author) = PdfEpubConverter(context).convert(source, destination, fallbackTitle, mode, onProgress)
            importGeneratedEpub(destination, title, author, libraryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            destination.delete()
        }
    }

    suspend fun createEpubFromUrl(
        url: String,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        libraryId: String = "default",
        webNovelJob: Pair<String, String>? = null,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> },
        onRateLimitWait: suspend (String, Long?) -> Unit = { _, _ -> },
        onManifestReady: suspend (String) -> Unit = {},
        shouldYieldAtChapterBoundary: suspend () -> Boolean = { false },
    ): Result<BookEntity> {
        val tempFile = File(context.cacheDir, "webnovel-${UUID.randomUUID()}.epub")
        return try {
            val generated = WebNovelEpubUtility(context).createEpub(
                url, tempFile, options, onProgress, onRateLimitWait, onManifestReady, shouldYieldAtChapterBoundary)
            val stableId = webNovelBookId(url, options)
            importGeneratedEpub(generated.file, generated.title, libraryId = libraryId, stableId = stableId,
                webNovelJob = webNovelJob)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (deferred: com.paperlight.reader.webnovel.RateLimitDeferredException) {
            throw deferred
        } catch (yield: WebNovelWorkerSliceYieldException) {
            throw yield
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun enqueueWebNovelConversion(url: String, options: WebNovelScrapeOptions, libraryId: String): WebNovelJobEntity =
        withContext(Dispatchers.IO) {
            withLibraryOperation {
                ensureDefaultLibraryUnlocked()
                require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
                require(url.length <= 4_000) { "The website address is too long." }
                val bookId = webNovelBookId(url, options)
                dao.activeWebNovelJobForBook(bookId)?.let { return@withLibraryOperation it }
                val now = System.currentTimeMillis()
                val existingBook = dao.bookById(bookId)
                WebNovelJobEntity(
                    id = UUID.randomUUID().toString(), sourceUrl = url,
                    optionsJson = WebNovelScrapeOptionsJson.encode(options), libraryId = libraryId,
                    status = if (existingBook == null) WebNovelJobState.QUEUED else WebNovelJobState.COMPLETED,
                    completed = 0, total = 0,
                    label = if (existingBook == null) "Queued" else "Already in library",
                    error = null, waitUntil = null, runnerToken = null,
                    checkpointKey = WebNovelEpubUtility.checkpointKey(url, options),
                    resultBookId = bookId, bookTitle = existingBook?.title, createdAt = now, updatedAt = now,
                    autoRun = existingBook == null,
                ).also { dao.insertWebNovelJob(it) }
            }
        }

    suspend fun claimNextWebNovelJob(): WebNovelJobEntity? = claimWebNovelRunner { token, now ->
        dao.claimNextWebNovelJob(token, now)
    }

    suspend fun claimRedeliveredWebNovelJob(id: String): WebNovelJobEntity? = claimWebNovelRunner { token, now ->
        dao.claimRedeliveredWebNovelJob(id, token, now)
    }

    suspend fun claimAutomaticWebNovelJob(id: String): WebNovelJobEntity? = claimWebNovelRunner { token, now ->
        dao.claimAutomaticWebNovelJob(id, token, now)
    }

    private suspend fun claimWebNovelRunner(
        claim: suspend (token: String, now: Long) -> WebNovelJobEntity?,
    ): WebNovelJobEntity? {
        val token = UUID.randomUUID().toString()
        return try {
            withContext(Dispatchers.IO) {
                withLibraryOperation {
                    reconcileOrphanedWebNovelJobsUnlocked()
                    claim(token, System.currentTimeMillis())?.also { BookRepositoryOperationLock.register(token) }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    withLibraryOperation(allowPendingRestore = true) {
                        try {
                            dao.allWebNovelJobs().firstOrNull { it.runnerToken == token }?.let { job ->
                                dao.reconcileOrphanedWebNovelJob(job.id, token, System.currentTimeMillis())
                            }
                        } finally {
                            BookRepositoryOperationLock.release(token)
                        }
                    }
                }
            }
            throw cancelled
        }
    }

    suspend fun automaticWebNovelJobs(): List<WebNovelJobEntity> = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.automaticWebNovelJobs() }
    }

    suspend fun postponeAutomaticWebNovelJob(id: String, delayMillis: Long = 30_000L): WebNovelJobEntity? = withContext(Dispatchers.IO) {
        withLibraryOperation {
            val now = System.currentTimeMillis()
            if (dao.postponeAutomaticWebNovelJob(id, now, now + delayMillis.coerceIn(10_000L, 5 * 60_000L)) == 0) null
            else dao.webNovelJobById(id)
        }
    }

    suspend fun continueWebNovelJobAfterWorkerSlice(id: String, token: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            val now = System.currentTimeMillis()
            (dao.continueWebNovelJobAfterWorkerSlice(id, token, now + WORKER_SLICE_RETRY_MILLIS, now) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun forgetWebNovelRunnerToken(token: String) = withContext(Dispatchers.IO) {
        try {
            withLibraryOperation(allowPendingRestore = true) {
                dao.allWebNovelJobs().firstOrNull { it.runnerToken == token }?.let { job ->
                    dao.reconcileOrphanedWebNovelJob(job.id, token, System.currentTimeMillis())
                }
            }
        } finally {
            BookRepositoryOperationLock.mutex.lock()
            try {
                BookRepositoryOperationLock.release(token)
            } finally {
                BookRepositoryOperationLock.mutex.unlock()
            }
        }
    }

    suspend fun updateWebNovelJobProgress(
        id: String, token: String, status: String, completed: Int, total: Int, label: String, waitUntil: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            dao.updateWebNovelJobProgress(id, token, status, completed, total, label, waitUntil, System.currentTimeMillis()) > 0
        }
    }

    suspend fun updateWebNovelJobTitle(id: String, token: String, title: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            dao.updateWebNovelJobTitle(id, token, title.take(512), System.currentTimeMillis()) > 0
        }
    }

    suspend fun failWebNovelJob(id: String, token: String, error: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.finishWebNovelJob(id, token, WebNovelJobState.FAILED, "Failed", error.take(1_000),
                requireNotNull(dao.webNovelJobById(id)).resultBookId, System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun settleWebNovelJobRequest(id: String, token: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.settleWebNovelJobRequest(id, token, System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun interruptWebNovelJob(id: String, token: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.interruptWebNovelJob(id, token, System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun deferWebNovelJobForRateLimit(id: String, token: String, host: String, waitUntil: Long): Boolean =
        withContext(Dispatchers.IO) {
            withLibraryOperation {
                (dao.deferWebNovelJobForRateLimit(id, token, "Waiting for $host rate limit", waitUntil,
                    System.currentTimeMillis()) > 0).also { if (it) BookRepositoryOperationLock.release(token) }
            }
        }

    suspend fun webNovelJobById(id: String): WebNovelJobEntity? = withContext(Dispatchers.IO) {
        dao.webNovelJobById(id)
    }

    suspend fun requestPauseWebNovelJob(id: String): WebNovelJobEntity? = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.requestPauseWebNovelJob(id, System.currentTimeMillis()) }
    }

    suspend fun requestCancelWebNovelJob(id: String): WebNovelJobEntity? = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.requestCancelWebNovelJob(id, System.currentTimeMillis()) }
    }

    suspend fun resumeWebNovelJob(id: String): WebNovelJobEntity? = withContext(Dispatchers.IO) {
        withLibraryOperation {
            val existing = dao.webNovelJobById(id) ?: return@withLibraryOperation null
            check(dao.findLibrary(existing.libraryId) != null) { "The saved destination library no longer exists." }
            dao.resumeWebNovelJob(id, System.currentTimeMillis())
        }
    }

    suspend fun resetInvalidWebNovelChapterManifest(id: String): WebNovelJobEntity? = withContext(Dispatchers.IO) {
        withLibraryOperation {
            val job = dao.webNovelJobById(id) ?: return@withLibraryOperation null
            check(job.status == WebNovelJobState.FAILED &&
                job.error?.startsWith(WebNovelChapterManifestCodec.INVALID_MANIFEST_ERROR_PREFIX) == true) {
                "Only a failed conversion with an invalid saved chapter list can be reset."
            }
            check(dao.findLibrary(job.libraryId) != null) { "The saved destination library no longer exists." }
            val options = WebNovelScrapeOptionsJson.decode(job.optionsJson)
            val checkpointKey = WebNovelEpubUtility.checkpointKey(job.sourceUrl, options)
            check(checkpointKey.matches(Regex("[0-9a-f]{64}"))) { "The saved checkpoint path is invalid." }
            check(!dao.hasOtherNonterminalWebNovelJobForCheckpoint(id, checkpointKey)) {
                "Finish or cancel other website conversions before resetting this chapter list."
            }
            val directory = File(context.filesDir, "webnovel-checkpoints/$checkpointKey")
            check(WebNovelChapterManifestStore(directory).clear()) { "Could not reset the saved chapter list." }
            check(dao.requeueFailedWebNovelJobAfterManifestReset(id, System.currentTimeMillis()) > 0) {
                "This conversion changed while its chapter list was being reset."
            }
            dao.webNovelJobById(id)
        }
    }

    suspend fun reconcileWebNovelJobs(@Suppress("UNUSED_PARAMETER") serviceActive: Boolean) = withContext(Dispatchers.IO) {
        withLibraryOperation { reconcileOrphanedWebNovelJobsUnlocked() }
    }

    private suspend fun reconcileOrphanedWebNovelJobsUnlocked() {
        val now = System.currentTimeMillis()
        dao.allWebNovelJobs().forEach { job ->
            val token = job.runnerToken ?: return@forEach
            if (!BookRepositoryOperationLock.isActive(token) && dao.reconcileOrphanedWebNovelJob(job.id, token, now) > 0) {
                BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun hasQueuedOrActiveWebNovelJobs(): Boolean = withContext(Dispatchers.IO) {
        dao.hasWebNovelJobsInStates(listOf(WebNovelJobState.QUEUED, WebNovelJobState.RUNNING,
            WebNovelJobState.WAITING_RATE_LIMIT, WebNovelJobState.PAUSE_REQUESTED, WebNovelJobState.CANCEL_REQUESTED))
    }

    suspend fun import(uri: Uri, libraryId: String = "default"): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            withLibraryOperation {
                ensureDefaultLibraryUnlocked()
                require(dao.findLibrary(libraryId) != null) { "This library no longer exists." }
                val resolver = context.contentResolver
                val localFile = if (uri.scheme == "file") File(requireNotNull(uri.path)) else null
                val displayName = localFile?.name ?: resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                } ?: "Imported book"
                val extension = displayName.substringAfterLast('.', "").lowercase()
                val format = formatOf(displayName) ?: error("Unsupported file type: .$extension")
                val id = UUID.randomUUID().toString()
                val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                val destination = File(booksDir, "$id.$extension")
                try {
                    (localFile?.inputStream() ?: resolver.openInputStream(uri))?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to read selected file")
                    val metadata = if (format == "EPUB") BookArtwork.metadata(destination) else null
                    val book = BookEntity(
                        id = id,
                        title = metadata?.first?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.', displayName),
                        author = metadata?.second.orEmpty(),
                        fileName = displayName,
                        localPath = destination.absolutePath,
                        format = format,
                        importedAt = System.currentTimeMillis(),
                        libraryId = libraryId
                    )
                    dao.insertBook(book)
                    book
                } catch (failure: Exception) {
                    destination.delete()
                    throw failure
                }
            }
        }
    }

    suspend fun exportBackup(
        uri: Uri,
        includeApiKey: Boolean = false,
        password: String? = null,
        onProgress: (BackupProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            check(!dao.hasActiveWebNovelJob()) { "Pause or cancel the active website EPUB job before exporting a backup." }
            LibraryBackup(context, dao).export(uri, includeApiKey, password, onProgress)
        }
    }

    suspend fun recoverInterruptedBackupRestore() = withContext(Dispatchers.IO) {
        withLibraryOperation(allowPendingRestore = true) { LibraryBackup(context, dao).recoverInterruptedRestore() }
    }

    fun backupRestorePending(): Boolean = LibraryBackup(context, dao).hasPendingRestore()
    suspend fun restoreBackup(
        uri: Uri,
        password: String? = null,
        replaceExistingSettings: Boolean = false,
        replaceExistingGroqKey: Boolean = false,
        onProgress: (BackupProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        withLibraryOperation {
            check(!dao.hasActiveWebNovelJob()) {
                "Pause or cancel the active website EPUB job before restoring a backup."
            }
            LibraryBackup(context, dao).restore(uri, password, replaceExistingSettings, replaceExistingGroqKey, onProgress)
        }
    }

    suspend fun refreshMetadata() = withContext(Dispatchers.IO) {
        withLibraryOperation {
            dao.allBooks().filter { it.format == "EPUB" && it.author.isBlank() }.forEach { book ->
                BookArtwork.metadata(File(book.localPath))?.let { (title, author) ->
                    if (title.isNotBlank() || author.isNotBlank()) dao.updateMetadata(book.id, title.ifBlank { book.title }, author)
                }
            }
        }
    }

    suspend fun updateProgress(bookId: String, progress: Float) = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.updateProgress(bookId, progress.coerceIn(0f, 1f)) }
    }

    suspend fun saveDiscoverySource(source: DiscoverySourceEntity) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            require(source.name.isNotBlank() && source.name.length <= 120) { "Source names must contain at most 120 characters." }
            require(source.baseUrl.length <= 4_096 && source.configJson.toByteArray(Charsets.UTF_8).size <= 32 * 1024) {
                "Source settings exceed the size limit."
            }
            val sourceUri = java.net.URI(source.baseUrl.trim())
            require((sourceUri.scheme.equals("http", true) || sourceUri.scheme.equals("https", true)) &&
                !sourceUri.host.isNullOrBlank() && sourceUri.userInfo == null) {
                "Source URLs must be HTTP(S), include a host, and contain no credentials."
            }
            require(source.kind in setOf(DiscoverySourceKind.OPDS, DiscoverySourceKind.HTML)) {
                "Only detected OPDS catalogs and HTML websites can be added as custom sources."
            }
            val config = org.json.JSONObject(source.configJson)
            val purpose = config.optString("purpose", DiscoverySourcePurpose.DOWNLOAD_EPUB)
            require(DiscoverySourcePurpose.isValid(purpose)) { "Choose a supported source purpose." }
            if (config.has("scrapeOptions")) {
                val scrapeOptions = config.optJSONObject("scrapeOptions")
                    ?: error("Saved website scraper settings are invalid.")
                val options = WebNovelScrapeOptionsJson.decode(scrapeOptions.toString())
                require(options.chapterLinkSelector.isNotBlank() && options.chapterLinkSelector.length <= 300 &&
                    options.chapterContentSelector.isNotBlank() && options.chapterContentSelector.length <= 500 &&
                    options.chapterTitleSelector.length <= 300 && options.removeSelector.length <= 500) {
                    "Scraper selectors exceed the size limits."
                }
            }
            if (source.kind == DiscoverySourceKind.HTML) {
                require(config.optString("htmlBookSelector").isNotBlank() &&
                    config.optString("htmlTitleSelector").isNotBlank() &&
                    config.optString("htmlDetailLinkSelector").isNotBlank()) {
                    "HTML sources need book-card, title, and details-link selectors."
                }
            }
            require(source.id !in DiscoverySourceIds.BUILT_INS) {
                "Built-in sources cannot be edited."
            }
            val existing = dao.discoverySourceById(source.id)
            if (existing == null) dao.insertDiscoverySource(source) else dao.updateDiscoverySource(source)
        }
    }

    suspend fun updateDiscoveryScrapeOptions(
        sourceId: String,
        expectedBaseUrl: String,
        pageUrl: String,
        options: WebNovelScrapeOptions,
    ): DiscoverySourceEntity = withContext(Dispatchers.IO) {
        withLibraryOperation {
            val source = dao.discoverySourceById(sourceId)
                ?: error("This discovery source was removed. Reopen Discover before saving its scraper settings.")
            require(source.baseUrl == expectedBaseUrl) {
                "This discovery source changed. Reopen it before saving scraper settings."
            }
            require(source.id != DiscoverySourceIds.STANDARD_EBOOKS) {
                "Built-in catalogs do not have website conversion settings."
            }
            val page = java.net.URI(pageUrl.trim())
            require((page.scheme.equals("http", true) || page.scheme.equals("https", true)) &&
                !page.host.isNullOrBlank() && page.userInfo == null && DiscoveryCatalogClient.sameOrigin(source.baseUrl, page.toString())) {
                "Save these settings only for pages on the source website."
            }
            val normalizedOptions = options.copy(firstChapter = 1, lastChapter = null)
            require(normalizedOptions.chapterLinkSelector.isNotBlank() && normalizedOptions.chapterLinkSelector.length <= 300 &&
                normalizedOptions.chapterContentSelector.isNotBlank() && normalizedOptions.chapterContentSelector.length <= 500 &&
                normalizedOptions.chapterTitleSelector.length <= 300 && normalizedOptions.removeSelector.length <= 500) {
                "Scraper selectors must be valid non-empty values within their size limits."
            }
            WebNovelScrapeOptionsJson.decode(WebNovelScrapeOptionsJson.encode(normalizedOptions))
            val currentConfig = DiscoverySourceConfigCodec.decode(source.configJson)
            val updatedConfigJson = DiscoverySourceConfigCodec.encode(currentConfig.copy(scrapeOptions = normalizedOptions))
            require(updatedConfigJson.toByteArray(Charsets.UTF_8).size <= 32 * 1024) {
                "Source settings exceed the size limit."
            }
            source.copy(configJson = updatedConfigJson, updatedAt = System.currentTimeMillis()).also {
                dao.updateDiscoverySource(it)
            }
        }
    }

    suspend fun setDiscoverySourceEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.setDiscoverySourceEnabled(id, enabled, System.currentTimeMillis()) > 0 }
    }

    suspend fun deleteDiscoverySource(id: String) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            require(id !in DiscoverySourceIds.BUILT_INS) {
                "Built-in sources cannot be deleted."
            }
            dao.deleteDiscoverySource(id) > 0
        }
    }

    suspend fun cacheDiscoverySourceResults(source: DiscoverySourceEntity, cacheKey: String, cacheJson: String): Boolean =
        withContext(Dispatchers.IO) {
            if (cacheJson.toByteArray(Charsets.UTF_8).size > MAX_DISCOVERY_CACHE_BYTES) return@withContext false
            withLibraryOperation {
                dao.cacheDiscoverySourceResults(source.id, source.kind, source.baseUrl, source.configJson, source.updatedAt, cacheKey, cacheJson,
                    System.currentTimeMillis()) > 0
            }
        }

    suspend fun exportBook(bookId: String, destination: Uri): String = withContext(Dispatchers.IO) {
        withLibraryOperation {
            val book = dao.bookById(bookId) ?: error("This book is no longer in the library.")
            val source = File(book.localPath)
            require(source.isFile && source.canRead()) { "The original book file is missing or unreadable." }
            source.inputStream().use { input ->
                val output = context.contentResolver.openOutputStream(destination, "wt")
                    ?: error("Could not write the exported book.")
                output.use { input.copyTo(it) }
            }
            book.fileName
        }
    }

    suspend fun remove(book: BookEntity) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            removeFilesAndState(listOf(book))
            dao.deleteById(book.id)
        }
    }

    suspend fun removeBooks(ids: List<String>) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            if (ids.isEmpty()) return@withLibraryOperation
            val selected = ids.distinct().chunked(500).flatMap { dao.booksByIds(it) }
            removeFilesAndState(selected)
            selected.map { it.id }.chunked(500).forEach { dao.deleteBooks(it) }
        }
    }

    private fun removeFilesAndState(selected: List<BookEntity>) {
        selected.forEach { book ->
            File(book.localPath).delete()
            File(context.filesDir, "epub/${book.id}").deleteRecursively()
            File(context.filesDir, "epub/${book.id}.prepared-epub").delete()
            BookmarkStore(context).write(book.id, emptyList())
            ReaderAnnotationStore(context).write(book.id, emptyList())
        }
    }
}
