package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import androidx.core.content.ContextCompat
import com.paperlight.reader.data.pdf.PdfConversionRecoveryWorker
import com.paperlight.reader.data.pdf.PdfConversionService
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
        private const val MAX_PDF_INPUT_BYTES = 256L * 1024L * 1024L
        private const val MAX_PDF_SNAPSHOT_BYTES = 1024L * 1024L * 1024L
        private const val MAX_PDF_JOBS = 20

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
    val pdfConversionJobs: Flow<List<PdfConversionJobEntity>> = dao.observePdfConversionJobs()
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
        pdfConversionJob: Pair<String, String>? = null,
    ): Result<BookEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                withLibraryOperation {
                    require(file.isFile && file.length() > 0L) { "EPUB generation produced an empty file." }
                    require(webNovelJob == null || pdfConversionJob == null) { "A generated book cannot belong to two conversion jobs." }
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
                    pdfConversionJob?.let { (jobId, token) ->
                        val job = requireNotNull(dao.pdfConversionJobById(jobId)) { "This PDF conversion job no longer exists." }
                        require(job.runnerToken == token && job.status == PdfConversionJobState.RUNNING) {
                            "This conversion was paused, cancelled, or interrupted before import."
                        }
                        require(job.resultBookId == id) { "This PDF conversion produced the wrong book identifier." }
                    }
                    suspend fun markConversionCompleted(bookId: String) {
                        webNovelJob?.let { (jobId, token) ->
                            check(dao.completeWebNovelJobWithExistingBook(jobId, token, bookId)) {
                                "This conversion was paused, cancelled, or interrupted before import."
                            }
                            BookRepositoryOperationLock.release(token)
                        }
                        pdfConversionJob?.let { (jobId, token) ->
                            check(dao.completePdfConversionJobWithExistingBook(jobId, token, bookId)) {
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
                            if (webNovelJob == null && pdfConversionJob == null) dao.insertBook(generatedBook)
                            else if (webNovelJob != null) {
                                check(dao.insertGeneratedWebNovelBookAndFinish(generatedBook, webNovelJob.first, webNovelJob.second)) {
                                    "This conversion was paused, cancelled, or interrupted before import."
                                }
                                BookRepositoryOperationLock.release(webNovelJob.second)
                            }
                            pdfConversionJob?.let { (jobId, token) ->
                                check(dao.insertGeneratedPdfBookAndFinish(generatedBook, jobId, token)) {
                                    "This conversion was paused, cancelled, or interrupted before import."
                                }
                                BookRepositoryOperationLock.release(token)
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

    suspend fun importPdfConversionEpub(
        file: File,
        title: String,
        author: String,
        jobId: String,
        token: String,
        inputHash: String,
        fallbackPageNumbers: List<Int>,
    ): Result<BookEntity> {
        return try {
            validateGeneratedPdfEpub(file)
            val job = requireNotNull(dao.pdfConversionJobById(jobId)) { "This PDF conversion job no longer exists." }
            require(job.inputHash == inputHash) { "The PDF conversion input changed while it was running." }
            val warning = fallbackPageNumbers.distinct().sorted().takeIf {
                it.isNotEmpty() && job.mode != PdfEpubMode.Images.name
            }?.let { pages ->
                val shown = pages.take(20).joinToString(", ")
                "Pages kept as images because text order was uncertain: $shown${if (pages.size > 20) ", and ${pages.size - 20} more" else ""}."
            }
            require(updatePdfConversionJobWarning(jobId, token, warning)) { "This conversion was stopped before import." }
            importGeneratedEpub(file, title, author, job.libraryId, stableId = job.resultBookId,
                pdfConversionJob = jobId to token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun validateGeneratedPdfEpub(file: File) {
        require(file.isFile && file.length() in 1L..(512L * 1024L * 1024L)) {
            "The generated EPUB is empty or exceeds the 512 MiB output limit."
        }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.isNotEmpty() && entries.size <= 50_000) { "The generated EPUB has an invalid entry count." }
            require(entries.none { it.name.startsWith('/') || it.name.split('/').any { part -> part == ".." } }) {
                "The generated EPUB contains an unsafe file path."
            }
            val mimeEntry = requireNotNull(zip.getEntry("mimetype")) { "The generated EPUB has no MIME declaration." }
            val mime = zip.getInputStream(mimeEntry).bufferedReader(Charsets.US_ASCII).use { it.readText() }
            require(mime == "application/epub+zip") { "The generated file is not an EPUB." }
            val containerEntry = requireNotNull(zip.getEntry("META-INF/container.xml")) {
                "The generated EPUB has no package container."
            }
            val container = zip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val packagePath = Regex("""full-path\s*=\s*[\"']([^\"']+)[\"']""").find(container)?.groupValues?.get(1)
                ?: error("The generated EPUB package path is missing.")
            require(zip.getEntry(packagePath) != null) { "The generated EPUB package file is missing." }
            require(entries.any { it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) }) {
                "The generated EPUB contains no readable content."
            }
        }
    }

    suspend fun enqueuePdfConversion(
        sourceUri: Uri,
        displayName: String? = null,
        libraryId: String = "default",
        mode: PdfEpubMode = PdfEpubMode.Text,
    ): PdfConversionJobEntity = withContext(Dispatchers.IO) {
        val job = withLibraryOperation {
            ensureDefaultLibraryUnlocked()
            require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
            val name = displayName?.takeIf { it.isNotBlank() } ?: displayNameForPdfUri(sourceUri)
            val extension = name.substringAfterLast('.', "")
            require(extension.isBlank() || extension.equals("pdf", ignoreCase = true)) { "Choose a PDF file." }
            val directory = pdfInputDirectory().apply { mkdirs() }
            val temporary = File(directory, "${UUID.randomUUID()}.partial")
            try {
                val source = if (sourceUri.scheme == "file") File(requireNotNull(sourceUri.path)).inputStream()
                    else requireNotNull(context.contentResolver.openInputStream(sourceUri)) { "Unable to open the selected PDF." }
                val snapshot = source.use { copyAndHashPdfInput(it, temporary) }
                validatePdfSnapshot(temporary, snapshot.first)
                enqueuePdfSnapshotUnlocked(temporary, name, snapshot.first, snapshot.second, libraryId, mode)
            } catch (failure: Exception) {
                temporary.delete()
                throw failure
            }
        }
        activatePdfJob(job)
        job
    }

    suspend fun enqueueLibraryPdfConversion(
        bookId: String,
        mode: PdfEpubMode = PdfEpubMode.Text,
    ): PdfConversionJobEntity = withContext(Dispatchers.IO) {
        val job = withLibraryOperation {
            val book = requireNotNull(dao.bookById(bookId)) { "That book no longer exists." }
            require(book.format == "PDF") { "Choose a PDF book." }
            require(dao.findLibrary(book.libraryId) != null) { "That library no longer exists." }
            val source = File(book.localPath)
            require(source.isFile) { "The saved PDF file is missing." }
            val directory = pdfInputDirectory().apply { mkdirs() }
            val temporary = File(directory, "${UUID.randomUUID()}.partial")
            try {
                val snapshot = source.inputStream().use { copyAndHashPdfInput(it, temporary) }
                validatePdfSnapshot(temporary, snapshot.first)
                enqueuePdfSnapshotUnlocked(temporary, book.fileName.ifBlank { source.name }, snapshot.first,
                    snapshot.second, book.libraryId, mode)
            } catch (failure: Exception) {
                temporary.delete()
                throw failure
            }
        }
        activatePdfJob(job)
        job
    }

    private suspend fun enqueuePdfSnapshotUnlocked(
        temporary: File,
        sourceName: String,
        size: Long,
        hash: String,
        libraryId: String,
        mode: PdfEpubMode,
    ): PdfConversionJobEntity {
        val optionsJson = "{}"
        dao.matchingPdfConversionJob(hash, mode.name, optionsJson, libraryId)?.let { existing ->
            if (existing.status != PdfConversionJobState.COMPLETED || dao.bookById(existing.resultBookId) != null) {
                temporary.delete()
                return existing
            }
        }
        val jobs = dao.allPdfConversionJobs()
        require(jobs.size < MAX_PDF_JOBS) { "Remove an older PDF job before adding another." }
        require(jobs.sumOf { it.sourceSize } + size <= MAX_PDF_SNAPSHOT_BYTES) {
            "PDF job snapshots are using too much storage. Remove old jobs to free space."
        }
        require(temporary.usableSpace >= 32L * 1024L * 1024L) { "There is not enough free storage to process this PDF." }
        val id = UUID.randomUUID().toString()
        val directory = pdfInputDirectory().apply { mkdirs() }
        val snapshotFile = File(directory, "$id.pdf")
        moveAtomically(temporary, snapshotFile)
        val now = System.currentTimeMillis()
        val resultBookId = pdfConversionBookId(id)
        val existingBook = dao.bookById(resultBookId)
        val job = PdfConversionJobEntity(
            id = id,
            sourceName = sourceName.take(512),
            sourceSize = size,
            inputHash = hash,
            inputSnapshotPath = snapshotFile.absolutePath,
            mode = mode.name,
            optionsJson = optionsJson,
            libraryId = libraryId,
            status = if (existingBook == null) PdfConversionJobState.QUEUED else PdfConversionJobState.COMPLETED,
            completedPages = 0,
            totalPages = 0,
            stage = if (existingBook == null) "Queued" else "Already in library",
            error = null,
            warning = null,
            runnerToken = null,
            runnerType = null,
            checkpointKey = pdfCheckpointKey(id),
            resultBookId = resultBookId,
            createdAt = now,
            updatedAt = now,
            autoRun = existingBook == null,
        )
        try {
            dao.insertPdfConversionJob(job)
        } catch (failure: Exception) {
            snapshotFile.delete()
            throw failure
        }
        return job
    }

    private fun activatePdfJob(job: PdfConversionJobEntity) {
        if (!job.autoRun || job.status != PdfConversionJobState.QUEUED) return
        PdfConversionRecoveryWorker.schedule(context, job)
        runCatching {
            ContextCompat.startForegroundService(
                context,
                android.content.Intent(context, PdfConversionService::class.java)
                    .putExtra(PdfConversionService.EXTRA_JOB_ID, job.id),
            )
        }
    }

    private fun pdfInputDirectory() = File(context.filesDir, "pdf-conversion-inputs")

    private fun displayNameForPdfUri(uri: Uri): String = if (uri.scheme == "file") {
        File(requireNotNull(uri.path)).name
    } else {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: "Selected PDF"
    }

    private fun copyAndHashPdfInput(input: java.io.InputStream, destination: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(1024)
        var headerLength = 0
        var total = 0L
        input.buffered().use { buffered ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = buffered.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_PDF_INPUT_BYTES) { "PDF files must be 256 MiB or smaller." }
                    if (headerLength < header.size) {
                        val copy = minOf(count, header.size - headerLength)
                        buffer.copyInto(header, headerLength, 0, copy)
                        headerLength += copy
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
        require(total > 0L) { "The selected PDF is empty." }
        return total to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validatePdfSnapshot(file: File, size: Long) {
        require(size <= MAX_PDF_INPUT_BYTES && file.isFile && file.length() == size) { "The PDF snapshot is incomplete." }
        val header = ByteArray(1024)
        val headerLength = file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            read
        }
        require(String(header, 0, headerLength, Charsets.ISO_8859_1).contains("%PDF-")) {
            "The selected file is not a valid PDF."
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            java.nio.file.Files.move(source.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            java.nio.file.Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pdfConversionBookId(jobId: String): String = "pdf-$jobId"

    private fun pdfCheckpointKey(jobId: String): String =
        MessageDigest.getInstance("SHA-256").digest("pdf-job\n$jobId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    suspend fun pdfConversionJobById(id: String): PdfConversionJobEntity? = withContext(Dispatchers.IO) {
        dao.pdfConversionJobById(id)
    }

    suspend fun pausePdfConversionJob(id: String, signalService: Boolean = true): PdfConversionJobEntity? = withContext(Dispatchers.IO) {
        val job = withLibraryOperation { dao.requestPausePdfConversionJob(id, System.currentTimeMillis()) }
        if (job != null) {
            PdfConversionRecoveryWorker.cancel(context, id)
            if (signalService) PdfConversionService.signal(context, id, pause = true)
        }
        job
    }

    suspend fun cancelPdfConversionJob(id: String, signalService: Boolean = true): PdfConversionJobEntity? = withContext(Dispatchers.IO) {
        val job = withLibraryOperation { dao.requestCancelPdfConversionJob(id, System.currentTimeMillis()) }
        if (job != null) {
            PdfConversionRecoveryWorker.cancel(context, id)
            if (signalService) PdfConversionService.signal(context, id, pause = false)
        }
        job
    }

    suspend fun resumePdfConversionJob(id: String): PdfConversionJobEntity? = requeuePdfConversionJob(id, retry = false)

    suspend fun retryPdfConversionJob(id: String): PdfConversionJobEntity? = requeuePdfConversionJob(id, retry = true)

    private suspend fun requeuePdfConversionJob(id: String, retry: Boolean): PdfConversionJobEntity? =
        withContext(Dispatchers.IO) {
            val job = withLibraryOperation {
                val existing = dao.pdfConversionJobById(id) ?: return@withLibraryOperation null
                check(dao.findLibrary(existing.libraryId) != null) { "The saved destination library no longer exists." }
                val snapshot = checkedPdfSnapshot(existing)
                validatePdfSnapshot(snapshot, existing.sourceSize)
                check(sha256(snapshot) == existing.inputHash) { "The saved PDF snapshot failed its integrity check." }
                dao.resumePdfConversionJob(id, System.currentTimeMillis(), retry)
            }
            job?.let { activatePdfJob(it) }
            job
        }

    suspend fun removePdfConversionJob(id: String): Boolean = withContext(Dispatchers.IO) {
        val removed = withLibraryOperation {
            val job = dao.pdfConversionJobById(id) ?: return@withLibraryOperation null
            if (job.runnerToken != null || job.status in setOf(PdfConversionJobState.RUNNING,
                    PdfConversionJobState.PAUSE_REQUESTED, PdfConversionJobState.CANCEL_REQUESTED)) return@withLibraryOperation null
            if (dao.deletePdfConversionJobIfUnclaimed(id) == 0) return@withLibraryOperation null
            job
        } ?: return@withContext false
        PdfConversionRecoveryWorker.cancel(context, id)
        pdfSnapshotFile(removed).delete()
        pdfConversionCheckpointDirectory(removed).deleteRecursively()
        pdfConversionTempOutput(removed).delete()
        true
    }

    private fun checkedPdfSnapshot(job: PdfConversionJobEntity): File {
        val snapshot = pdfSnapshotFile(job)
        require(snapshot.isFile) { "The saved PDF snapshot is missing." }
        return snapshot
    }

    fun pdfConversionInputFile(job: PdfConversionJobEntity): File = checkedPdfSnapshot(job)

    fun pdfConversionTempOutput(job: PdfConversionJobEntity): File {
        val safeId = job.id.replace(Regex("[^A-Za-z0-9-]"), "_").take(80)
        return File(context.cacheDir, "pdf-job-$safeId.epub")
    }

    private fun pdfSnapshotFile(job: PdfConversionJobEntity): File {
        val expectedParent = pdfInputDirectory().canonicalFile
        val snapshot = File(job.inputSnapshotPath).canonicalFile
        require(snapshot.parentFile == expectedParent && snapshot.name == "${job.id}.pdf") {
            "The saved PDF snapshot path is invalid."
        }
        return snapshot
    }

    fun pdfConversionCheckpointDirectory(job: PdfConversionJobEntity): File {
        require(job.checkpointKey.matches(Regex("[0-9a-f]{64}"))) { "The saved PDF checkpoint path is invalid." }
        return File(File(context.filesDir, "pdf-conversion-checkpoints"), job.checkpointKey)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun claimPdfConversionRunner(
        claim: suspend (token: String, now: Long) -> PdfConversionJobEntity?,
    ): PdfConversionJobEntity? {
        val token = UUID.randomUUID().toString()
        return try {
            withContext(Dispatchers.IO) {
                withLibraryOperation {
                    reconcileOrphanedPdfConversionJobsUnlocked()
                    claim(token, System.currentTimeMillis())?.also { BookRepositoryOperationLock.register(token) }
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    withLibraryOperation(allowPendingRestore = true) {
                        try {
                            dao.allPdfConversionJobs().firstOrNull { it.runnerToken == token }?.let { job ->
                                dao.reconcileOrphanedPdfConversionJob(job.id, token, System.currentTimeMillis())
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

    suspend fun claimNextPdfConversionJob(): PdfConversionJobEntity? = claimPdfConversionRunner { token, now ->
        dao.claimNextPdfConversionJob(token, "FOREGROUND", now)
    }

    suspend fun claimRedeliveredPdfConversionJob(id: String): PdfConversionJobEntity? = claimPdfConversionRunner { token, now ->
        dao.claimRedeliveredPdfConversionJob(id, token, now)
    }

    suspend fun claimAutomaticPdfConversionJob(id: String): PdfConversionJobEntity? = claimPdfConversionRunner { token, now ->
        dao.claimAutomaticPdfConversionJob(id, token, now)
    }

    suspend fun automaticPdfConversionJobs(): List<PdfConversionJobEntity> = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.automaticPdfConversionJobs() }
    }

    suspend fun updatePdfConversionJobProgress(
        id: String, token: String, stage: String, completed: Int, total: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            dao.updatePdfConversionJobProgress(id, token, stage.take(120), completed.coerceAtLeast(0),
                total.coerceAtLeast(0), System.currentTimeMillis()) > 0
        }
    }

    suspend fun updatePdfConversionJobWarning(id: String, token: String, warning: String?): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            dao.updatePdfConversionJobWarning(id, token, warning?.take(1_000), System.currentTimeMillis()) > 0
        }
    }

    suspend fun finishPdfConversionJob(id: String, token: String, resultBookId: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.finishPdfConversionJob(id, token, PdfConversionJobState.COMPLETED, "Complete", null,
                resultBookId, System.currentTimeMillis()) > 0).also { if (it) BookRepositoryOperationLock.release(token) }
        }
    }

    suspend fun failPdfConversionJob(id: String, token: String, error: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.failPdfConversionJob(id, token, error.take(1_000), System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun settlePdfConversionJobRequest(id: String, token: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.settlePdfConversionJobRequest(id, token, System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun interruptPdfConversionJob(id: String, token: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.interruptPdfConversionJob(id, token, System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun continuePdfConversionAfterWorkerSlice(id: String, token: String): Boolean = withContext(Dispatchers.IO) {
        withLibraryOperation {
            (dao.continuePdfConversionAfterWorkerSlice(id, token, System.currentTimeMillis()) > 0).also {
                if (it) BookRepositoryOperationLock.release(token)
            }
        }
    }

    suspend fun forgetPdfConversionRunnerToken(token: String) = withContext(Dispatchers.IO) {
        try {
            withLibraryOperation(allowPendingRestore = true) {
                dao.allPdfConversionJobs().firstOrNull { it.runnerToken == token }?.let { job ->
                    dao.reconcileOrphanedPdfConversionJob(job.id, token, System.currentTimeMillis())
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

    suspend fun reconcilePdfConversionJobs() = withContext(Dispatchers.IO) {
        withLibraryOperation { reconcileOrphanedPdfConversionJobsUnlocked() }
    }

    private suspend fun reconcileOrphanedPdfConversionJobsUnlocked() {
        val now = System.currentTimeMillis()
        dao.allPdfConversionJobs().forEach { job ->
            val token = job.runnerToken ?: return@forEach
            if (!BookRepositoryOperationLock.isActive(token) &&
                dao.reconcileOrphanedPdfConversionJob(job.id, token, now) > 0) {
                BookRepositoryOperationLock.release(token)
            }
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
            check(!dao.hasActivePdfConversionJob()) { "Pause or cancel the active PDF conversion before exporting a backup." }
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
            check(!dao.hasActivePdfConversionJob()) {
                "Pause or cancel the active PDF conversion before restoring a backup."
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
