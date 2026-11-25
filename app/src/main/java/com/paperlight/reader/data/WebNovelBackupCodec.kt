package com.paperlight.reader.data

import com.paperlight.reader.webnovel.WebNovelChapterManifestCodec
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class WebNovelBackupCheckpointFile(
    val key: String,
    val name: String,
    val archivePath: String,
    val size: Long,
    val sha256: String,
)

internal data class WebNovelBackupPayload(
    val sources: List<DiscoverySourceEntity>,
    val jobs: List<WebNovelJobEntity>,
    val checkpointFiles: List<WebNovelBackupCheckpointFile>,
    val cooldowns: Map<String, Long>,
    val builtinEnabled: Map<String, Boolean>,
    val capturedAt: Long,
    val extension: JSONObject,
) {
    val checkpointBytes: Long get() = checkpointFiles.sumOf { it.size }
}

internal data class WebNovelBackupPublishPlan(
    val filesToPublish: List<WebNovelBackupCheckpointFile>,
    val conflictingKeys: Set<String>,
)

/** Versioned optional backup section for custom catalogs and resumable website conversions. */
internal object WebNovelBackupCodec {
    const val SECTION_VERSION = 1
    const val MAX_SOURCES = 1_000
    const val MAX_JOBS = 10_000
    const val MAX_CHECKPOINT_FILES = 100_000
    const val MAX_CHECKPOINT_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_CHAPTER_BYTES = 64L * 1024 * 1024
    const val MAX_SOURCE_CONFIG_BYTES = 32 * 1024
    const val MAX_SOURCE_CACHE_BYTES = 256 * 1024
    const val MAX_ARCHIVE_ENTRIES = 10_002 + MAX_CHECKPOINT_FILES

    fun export(
        sources: List<DiscoverySourceEntity>,
        jobs: List<WebNovelJobEntity>,
        checkpointRoot: File,
        cooldownPreferenceSnapshot: JSONObject?,
        ensureActive: () -> Unit = {},
    ): Pair<JSONObject, List<File>> {
        require(sources.size <= MAX_SOURCES) { "Too many Discover sources to back up." }
        require(jobs.size <= MAX_JOBS) { "Too many conversion jobs to back up." }
        val sourceIds = HashSet<String>()
        val encodedSources = JSONArray()
        val builtinEnabled = linkedMapOf<String, Boolean>()
        sources.sortedBy { it.id }.forEach { source ->
            validateSource(source)
            require(sourceIds.add(source.id)) { "Duplicate Discover source identifier." }
            if (source.id in BUILTIN_IDS) {
                builtinEnabled[source.id] = source.enabled
            }
            encodedSources.put(sourceToJson(source))
        }
        val jobIds = HashSet<String>()
        jobs.forEach { job ->
            validateJob(job)
            require(jobIds.add(job.id)) { "Duplicate conversion job identifier." }
        }

        val resumableJobs = jobs.filter { it.status !in TERMINAL_STATES }
        val checkpointKeys = resumableJobs.map { it.checkpointKey }.distinct()
        validateCheckpointRoot(checkpointRoot)
        val files = ArrayList<Pair<WebNovelBackupCheckpointFile, File>>()
        checkpointKeys.forEach { key ->
            ensureActive()
            val directory = File(checkpointRoot, key)
            val keyJobs = resumableJobs.filter { it.checkpointKey == key }
            if (!directory.exists()) {
                require(keyJobs.all { it.completed == 0 }) {
                    "A conversion with saved progress is missing its chapter list. Reset its chapter list before backing up."
                }
                return@forEach
            }
            require(directory.canonicalFile.parentFile == checkpointRoot.canonicalFile && directory.isDirectory &&
                !java.nio.file.Files.isSymbolicLink(directory.toPath())) { "A conversion checkpoint directory is unsafe." }
            val manifestFile = File(directory, WebNovelChapterManifestStoreFileName)
            if (!manifestFile.exists()) {
                require(resumableJobs.filter { it.checkpointKey == key }.all { it.completed == 0 }) {
                    "A conversion with saved progress is missing its chapter list. Reset its chapter list before backing up."
                }
                return@forEach // Reset intentionally leaves old chapter files behind; they are not this job's checkpoint.
            }
            validateLocalFile(directory, manifestFile, WebNovelChapterManifestStoreFileName, ensureActive)
            val manifest = WebNovelChapterManifestCodec.decode(
                readUtf8Bounded(manifestFile, WebNovelChapterManifestCodec.MAX_SERIALIZED_BYTES, ensureActive),
                canonicalHttpUrl(keyJobs.first().sourceUrl), key,
            )
            files += checkpointRecord(key, manifestFile, ensureActive)
            val availablePairs = mutableSetOf<Int>()
            manifest.chapters.indices.forEach chapterLoop@{ index ->
                ensureActive()
                val xhtml = File(directory, chapterFileName(index, "xhtml"))
                val url = File(directory, chapterFileName(index, "url"))
                if (!xhtml.exists() && !url.exists()) return@chapterLoop
                if (!xhtml.isFile || !url.isFile || isSymlinkOrEscapes(directory, xhtml) || isSymlinkOrEscapes(directory, url)) {
                    return@chapterLoop
                }
                val savedUrl = readUtf8Bounded(url, MAX_URL_BYTES, ensureActive)
                if (savedUrl != manifest.chapters[index].url || xhtml.length() !in 1..MAX_CHAPTER_BYTES) return@chapterLoop
                validateUtf8NonBlank(xhtml, ensureActive)
                files += checkpointRecord(key, xhtml, ensureActive)
                files += checkpointRecord(key, url, ensureActive)
                availablePairs += index
            }
            keyJobs.forEach { job ->
                require(job.completed <= manifest.chapters.size && (0 until job.completed).all { it in availablePairs }) {
                    "A conversion with saved progress is missing or has corrupt chapter checkpoints."
                }
            }
        }
        require(files.size <= MAX_CHECKPOINT_FILES) { "The conversion checkpoints contain too many files." }
        require(files.sumOf { it.first.size } <= MAX_CHECKPOINT_BYTES) { "Conversion checkpoints exceed the backup limit." }

        val encodedJobs = JSONArray().apply { jobs.sortedBy { it.createdAt }.forEach { put(jobToJson(it)) } }
        val orderedFiles = files.sortedWith(compareBy({ it.first.key }, { it.first.name }))
        val encodedFiles = JSONArray().apply {
            orderedFiles.forEach { (record, _) ->
                put(JSONObject().put("key", record.key).put("name", record.name).put("path", record.archivePath)
                    .put("size", record.size).put("sha256", record.sha256))
            }
        }
        val capturedAt = System.currentTimeMillis()
        val cooldowns = cooldownsFromPreferences(cooldownPreferenceSnapshot, capturedAt)
        val encodedCooldowns = JSONArray().apply {
            cooldowns.toSortedMap().forEach { (host, until) -> put(JSONObject().put("host", host).put("waitUntil", until)) }
        }
        val extension = JSONObject().put("version", SECTION_VERSION).put("capturedAt", capturedAt)
            .put("sources", encodedSources).put("jobs", encodedJobs).put("checkpoints", encodedFiles)
            .put("cooldowns", encodedCooldowns)
        return extension to orderedFiles.map { it.second }
    }

    fun parse(extension: JSONObject): WebNovelBackupPayload {
        val version = extension.optInt("version", -1)
        require(version == SECTION_VERSION) { "This backup contains an unsupported website backup extension." }
        val capturedAt = extension.getLong("capturedAt")
        require(capturedAt >= 0L) { "Invalid website backup timestamp." }
        val sourceArray = extension.getJSONArray("sources")
        require(sourceArray.length() <= MAX_SOURCES) { "Too many Discover sources in this backup." }
        val sources = ArrayList<DiscoverySourceEntity>(sourceArray.length())
        val seenSourceIds = HashSet<String>()
        val builtinEnabled = linkedMapOf<String, Boolean>()
        for (index in 0 until sourceArray.length()) {
            val source = sourceFromJson(sourceArray.getJSONObject(index))
            require(seenSourceIds.add(source.id)) { "Duplicate Discover source identifier in this backup." }
            if (source.id in BUILTIN_IDS) builtinEnabled[source.id] = source.enabled else sources += source
        }

        val jobArray = extension.getJSONArray("jobs")
        require(jobArray.length() <= MAX_JOBS) { "Too many conversion jobs in this backup." }
        val jobs = ArrayList<WebNovelJobEntity>(jobArray.length())
        val seenJobIds = HashSet<String>()
        for (index in 0 until jobArray.length()) {
            val job = jobFromJson(jobArray.getJSONObject(index), capturedAt)
            require(seenJobIds.add(job.id)) { "Duplicate conversion job identifier in this backup." }
            jobs += job
        }

        val fileArray = extension.getJSONArray("checkpoints")
        require(fileArray.length() <= MAX_CHECKPOINT_FILES) { "Too many conversion checkpoint files in this backup." }
        val files = ArrayList<WebNovelBackupCheckpointFile>(fileArray.length())
        val paths = HashSet<String>()
        var totalBytes = 0L
        for (index in 0 until fileArray.length()) {
            val item = fileArray.getJSONObject(index)
            val key = item.getString("key")
            val name = item.getString("name")
            val path = item.getString("path")
            val size = item.getLong("size")
            val hash = item.getString("sha256")
            require(key.matches(CHECKPOINT_KEY) && isCheckpointName(name) && path == "checkpoints/$key/$name" &&
                paths.add(path) && size in 1..maxSize(name) && hash.matches(SHA256)) {
                "Invalid conversion checkpoint metadata in this backup."
            }
            totalBytes = Math.addExact(totalBytes, size)
            require(totalBytes <= MAX_CHECKPOINT_BYTES) { "Conversion checkpoints exceed the backup limit." }
            files += WebNovelBackupCheckpointFile(key, name, path, size, hash.lowercase(Locale.ROOT))
        }
        val knownKeys = jobs.map { it.checkpointKey }.toSet()
        require(files.all { it.key in knownKeys }) { "This backup contains an unowned conversion checkpoint." }

        val cooldownArray = extension.optJSONArray("cooldowns") ?: JSONArray()
        require(cooldownArray.length() <= 4_096) { "Too many website cooldown records." }
        val cooldowns = linkedMapOf<String, Long>()
        val maximumDeadline = (capturedAt + MAX_RATE_LIMIT_DELAY_MILLIS).coerceAtLeast(capturedAt)
        for (index in 0 until cooldownArray.length()) {
            val item = cooldownArray.getJSONObject(index)
            val host = validHost(item.getString("host"))
            val waitUntil = item.getLong("waitUntil")
            require(waitUntil in 0..maximumDeadline && cooldowns.put(host, waitUntil) == null) {
                "Invalid or duplicate website cooldown in this backup."
            }
        }
        return WebNovelBackupPayload(sources, jobs, files, cooldowns, builtinEnabled, capturedAt, JSONObject(extension.toString()))
    }

    fun validateStagedCheckpoints(payload: WebNovelBackupPayload, stagedRoot: File, ensureActive: () -> Unit = {}) {
        val fileMap = payload.checkpointFiles.associate { record ->
            ensureActive()
            val file = safeCheckpointFile(stagedRoot, record.key, record.name)
            require(file.isFile && file.length() == record.size && sha256(file, ensureActive).equals(record.sha256, true)) {
                "A saved conversion checkpoint failed its integrity check."
            }
            (record.key to record.name) to file
        }
        validateCheckpointSet(payload.jobs, fileMap, ensureActive)
    }

    fun planCheckpointPublication(payload: WebNovelBackupPayload, destinationRoot: File, ensureActive: () -> Unit = {}): WebNovelBackupPublishPlan {
        val filesToPublish = mutableListOf<WebNovelBackupCheckpointFile>()
        val conflictingKeys = linkedSetOf<String>()
        val root = validateCheckpointRoot(destinationRoot)
        val byKey = payload.checkpointFiles.groupBy { it.key }
        byKey.forEach { (key, records) ->
            ensureActive()
            val directory = File(destinationRoot, key)
            if (directory.exists() && (!directory.isDirectory || java.nio.file.Files.isSymbolicLink(directory.toPath()) ||
                    directory.canonicalFile.parentFile != root)) {
                conflictingKeys += key
                return@forEach
            }
            val collisions = records.mapNotNull { record ->
                val existing = safeCheckpointFile(destinationRoot, key, record.name)
                if (!existing.exists()) return@mapNotNull null
                if (existing.isFile && !java.nio.file.Files.isSymbolicLink(existing.toPath()) &&
                    existing.length() == record.size && sha256(existing, ensureActive).equals(record.sha256, true)) null
                else key
            }.isNotEmpty()
            if (collisions) {
                conflictingKeys += key
            } else {
                records.forEach { record ->
                    if (!safeCheckpointFile(destinationRoot, key, record.name).exists()) filesToPublish += record
                }
            }
        }
        return WebNovelBackupPublishPlan(filesToPublish, conflictingKeys)
    }

    fun validatePublishedCheckpoints(payload: WebNovelBackupPayload, root: File, skippedKeys: Set<String>, ensureActive: () -> Unit = {}) {
        payload.checkpointFiles.filterNot { it.key in skippedKeys }.forEach { record ->
            ensureActive()
            val file = safeCheckpointFile(root, record.key, record.name)
            require(file.isFile && file.length() == record.size && sha256(file, ensureActive).equals(record.sha256, true)) {
                "A restored conversion checkpoint is missing or corrupt."
            }
        }
    }

    private fun validateCheckpointSet(jobs: List<WebNovelJobEntity>, files: Map<Pair<String, String>, File>, ensureActive: () -> Unit) {
        val keys = jobs.associateBy { it.checkpointKey }
        for ((key, _) in keys) {
            ensureActive()
            val keyJobs = jobs.filter { it.checkpointKey == key && it.status !in TERMINAL_STATES }
            if (keyJobs.isEmpty()) continue
            val job = keyJobs.first()
            val manifestFile = files[key to WebNovelChapterManifestStoreFileName]
            val chapterIndexes = files.keys.filter { it.first == key && CHAPTER_FILE.matches(it.second) }
            if (manifestFile == null) {
                require(chapterIndexes.isEmpty() && keyJobs.all { it.completed == 0 }) {
                    "A conversion with saved progress is missing its chapter list or downloaded chapters."
                }
                continue
            }
            val canonicalSource = canonicalHttpUrl(job.sourceUrl)
            val manifest = WebNovelChapterManifestCodec.decode(
                manifestFile.readText(Charsets.UTF_8), canonicalSource, key,
            )
            val pairedIndexes = linkedSetOf<Int>()
            for ((fileKey, name) in chapterIndexes) {
                require(fileKey == key)
                val match = CHAPTER_FILE.matchEntire(name) ?: error("Invalid checkpoint filename.")
                val index = match.groupValues[1].toInt()
                require(index in manifest.chapters.indices) { "A downloaded chapter is not in its saved chapter list." }
                val xhtml = files[key to chapterFileName(index, "xhtml")]
                val url = files[key to chapterFileName(index, "url")]
                require(xhtml != null && url != null) { "A downloaded chapter checkpoint is incomplete." }
                if (name.endsWith(".url")) {
                    val savedUrl = readUtf8Bounded(url, MAX_URL_BYTES, ensureActive)
                    require(savedUrl == manifest.chapters[index].url && pairedIndexes.add(index)) {
                        "A downloaded chapter checkpoint does not match its saved chapter list."
                    }
                }
                if (name.endsWith(".xhtml")) validateUtf8NonBlank(xhtml, ensureActive)
            }
            keyJobs.forEach { savedJob ->
                require(savedJob.completed <= manifest.chapters.size &&
                    (0 until savedJob.completed).all { it in pairedIndexes }) {
                    "A conversion's saved progress does not have its downloaded chapter checkpoints."
                }
            }
        }
    }

    private fun sourceToJson(source: DiscoverySourceEntity) = JSONObject()
        .put("id", source.id).put("name", source.name).put("kind", source.kind).put("baseUrl", source.baseUrl)
        .put("enabled", source.enabled).put("configJson", source.configJson)
        .put("cacheKey", source.cacheKey ?: JSONObject.NULL).put("cacheJson", source.cacheJson ?: JSONObject.NULL)
        .put("cacheUpdatedAt", source.cacheUpdatedAt ?: JSONObject.NULL).put("createdAt", source.createdAt).put("updatedAt", source.updatedAt)

    private fun sourceFromJson(item: JSONObject): DiscoverySourceEntity {
        val source = DiscoverySourceEntity(
            id = item.getString("id"), name = item.getString("name"), kind = item.getString("kind"), baseUrl = item.getString("baseUrl"),
            enabled = item.getBoolean("enabled"), configJson = item.getString("configJson"),
            cacheKey = item.optString("cacheKey").takeIf { !item.isNull("cacheKey") },
            cacheJson = item.optString("cacheJson").takeIf { !item.isNull("cacheJson") },
            cacheUpdatedAt = if (item.isNull("cacheUpdatedAt")) null else item.getLong("cacheUpdatedAt"),
            createdAt = item.getLong("createdAt"), updatedAt = item.getLong("updatedAt"),
        )
        validateSource(source)
        return source
    }

    private fun validateSource(source: DiscoverySourceEntity) {
        require(source.id.matches(SOURCE_ID) && source.name.isNotBlank() && source.name.length <= 120 &&
            source.kind in setOf(DiscoverySourceKind.STANDARD_EBOOKS, DiscoverySourceKind.GUTENBERG,
                DiscoverySourceKind.OPDS, DiscoverySourceKind.HTML)) { "Invalid Discover source metadata." }
        val uri = URI(source.baseUrl)
        require(source.baseUrl.length <= 4_096 && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid Discover source URL." }
        require(source.configJson.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_CONFIG_BYTES) { "Discover source settings exceed the backup limit." }
        JSONObject(source.configJson)
        require(source.cacheJson == null || source.cacheJson.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_CACHE_BYTES) {
            "Discover source cached results exceed the backup limit."
        }
        source.cacheJson?.let(::JSONObject)
        require(source.cacheKey == null || source.cacheKey.length <= 4_096)
        require(source.cacheUpdatedAt == null || source.cacheUpdatedAt >= 0L)
        require(source.createdAt >= 0L && source.updatedAt >= 0L)
        when (source.id) {
            DiscoverySourceIds.STANDARD_EBOOKS -> require(source.kind == DiscoverySourceKind.STANDARD_EBOOKS)
            DiscoverySourceIds.NOVELPING -> require(source.kind == DiscoverySourceKind.HTML)
            DiscoverySourceIds.GUTENBERG -> require(source.kind == DiscoverySourceKind.GUTENBERG)
            else -> require(source.kind in setOf(DiscoverySourceKind.OPDS, DiscoverySourceKind.HTML)) {
                "Only custom OPDS and HTML sources can be restored."
            }
        }
    }

    private fun jobToJson(job: WebNovelJobEntity) = JSONObject()
        .put("id", job.id).put("sourceUrl", job.sourceUrl).put("optionsJson", job.optionsJson)
        .put("libraryId", job.libraryId).put("status", job.status).put("completed", job.completed).put("total", job.total)
        .put("label", job.label).put("error", job.error ?: JSONObject.NULL).put("waitUntil", job.waitUntil ?: JSONObject.NULL)
        .put("checkpointKey", job.checkpointKey).put("resultBookId", job.resultBookId)
        .put("bookTitle", job.bookTitle ?: JSONObject.NULL).put("createdAt", job.createdAt).put("updatedAt", job.updatedAt)

    private fun jobFromJson(item: JSONObject, capturedAt: Long): WebNovelJobEntity {
        val id = item.getString("id")
        val sourceUrl = item.getString("sourceUrl")
        val optionsJson = item.getString("optionsJson")
        require(id.matches(JOB_ID) && sourceUrl.length in 1..4_000 &&
            optionsJson.toByteArray(Charsets.UTF_8).size <= MAX_OPTIONS_BYTES) { "Invalid saved conversion job metadata." }
        val options = WebNovelScrapeOptionsJson.decode(optionsJson)
        val key = item.getString("checkpointKey")
        require(key.matches(CHECKPOINT_KEY) && key == WebNovelEpubUtility.checkpointKey(sourceUrl, options)) {
            "A saved conversion checkpoint key does not match its scrape settings."
        }
        canonicalHttpUrl(sourceUrl)
        val status = item.getString("status")
        require(status in JOB_STATES) { "Unsupported saved conversion job state." }
        val completed = item.getInt("completed")
        val total = item.getInt("total")
        require(completed >= 0 && total in completed..9_000) { "Invalid saved conversion progress." }
        val title = item.optString("bookTitle").takeIf { !item.isNull("bookTitle") }
        require(title == null || title.length <= 512)
        val rawError = item.optString("error").takeIf { !item.isNull("error") }
        require(rawError == null || rawError.length <= 1_000)
        val originalWait = if (item.isNull("waitUntil")) null else item.getLong("waitUntil")
        val maximumWait = (capturedAt + MAX_RATE_LIMIT_DELAY_MILLIS).coerceAtLeast(capturedAt)
        require(originalWait == null || originalWait in 0..maximumWait)
        val resultBookId = item.getString("resultBookId")
        require(resultBookId.matches(JOB_ID)) { "Invalid saved conversion result identifier." }
        val libraryId = item.getString("libraryId")
        require(libraryId.matches(JOB_ID)) { "Invalid saved conversion library." }
        val createdAt = item.getLong("createdAt")
        val updatedAt = item.getLong("updatedAt")
        require(createdAt >= 0L && updatedAt >= 0L)
        val restoredStatus = when {
            status == WebNovelJobState.COMPLETED -> WebNovelJobState.COMPLETED
            status == WebNovelJobState.CANCELLED || status == WebNovelJobState.CANCEL_REQUESTED -> WebNovelJobState.CANCELLED
            status == WebNovelJobState.FAILED -> WebNovelJobState.FAILED
            status == WebNovelJobState.PAUSED -> WebNovelJobState.PAUSED
            else -> WebNovelJobState.PAUSED
        }
        return WebNovelJobEntity(
            id = id, sourceUrl = sourceUrl, optionsJson = optionsJson,
            libraryId = libraryId, status = restoredStatus,
            completed = completed, total = total,
            label = if (restoredStatus == WebNovelJobState.PAUSED && status != WebNovelJobState.PAUSED)
                "Restored paused; resume to continue" else item.getString("label").take(512),
            error = if (restoredStatus == WebNovelJobState.FAILED) rawError else null,
            waitUntil = originalWait, runnerToken = null, checkpointKey = key, resultBookId = resultBookId,
            bookTitle = title, createdAt = createdAt, updatedAt = updatedAt, runnerType = null, autoRun = false,
        )
    }

    private fun validateJob(job: WebNovelJobEntity) {
        require(job.id.matches(JOB_ID) && job.sourceUrl.length in 1..4_000 &&
            job.optionsJson.toByteArray(Charsets.UTF_8).size <= MAX_OPTIONS_BYTES && job.libraryId.matches(JOB_ID) &&
            job.status in JOB_STATES && job.completed >= 0 && job.total in job.completed..9_000 &&
            job.label.length <= 512 && (job.error == null || job.error.length <= 1_000) &&
            (job.waitUntil == null || job.waitUntil >= 0L) && job.createdAt >= 0L && job.updatedAt >= 0L) {
            "Invalid saved conversion job metadata."
        }
        val options = WebNovelScrapeOptionsJson.decode(job.optionsJson)
        canonicalHttpUrl(job.sourceUrl)
        require(job.checkpointKey.matches(CHECKPOINT_KEY) &&
            job.checkpointKey == WebNovelEpubUtility.checkpointKey(job.sourceUrl, options) && job.resultBookId.matches(JOB_ID)) {
            "Invalid saved conversion checkpoint metadata."
        }
        job.bookTitle?.let { require(it.length <= 512) }
    }

    private fun cooldownsFromPreferences(snapshot: JSONObject?, capturedAt: Long): Map<String, Long> {
        val entries = snapshot ?: return emptyMap()
        val result = linkedMapOf<String, Long>()
        for (index in 0 until entries.length()) {
            val host = validHost(entries.names()?.getString(index) ?: continue)
            val item = entries.getJSONObject(host)
            require(item.optString("type") in setOf("long", "int")) { "Invalid website cooldown preference." }
            val deadline = (item.opt("value") as? Number)?.toLong() ?: error("Invalid website cooldown deadline.")
            require(deadline in 0..(capturedAt + MAX_RATE_LIMIT_DELAY_MILLIS)) { "Invalid website cooldown deadline." }
            result[host] = deadline
        }
        return result
    }

    private fun validHost(raw: String): String {
        require(raw.length in 1..255 && raw.none { it.isWhitespace() || it in "/?#@\\" }) { "Invalid website cooldown host." }
        val uri = URI("https://$raw")
        val host = uri.host ?: error("Invalid website cooldown host.")
        require(uri.userInfo == null && uri.port == -1 && uri.path.isNullOrEmpty() &&
            host.equals(raw, ignoreCase = true)) { "Invalid website cooldown host." }
        return host.lowercase(Locale.ROOT)
    }

    private fun canonicalHttpUrl(raw: String): String {
        val uri = URI(raw.trim()).normalize()
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Invalid saved conversion website URL."
        }
        return uri.toString()
    }

    private fun validateLocalFile(directory: File, file: File, name: String, ensureActive: () -> Unit) {
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == directory.canonicalFile) { "A conversion checkpoint file is unsafe." }
        require(file.length() in 1..maxSize(name)) { "A conversion checkpoint file is empty or too large." }
        if (name.endsWith(".url")) readUtf8Bounded(file, MAX_URL_BYTES, ensureActive)
    }

    private fun checkpointRecord(key: String, file: File, ensureActive: () -> Unit): Pair<WebNovelBackupCheckpointFile, File> =
        WebNovelBackupCheckpointFile(key, file.name, "checkpoints/$key/${file.name}", file.length(), sha256(file, ensureActive)) to file

    private fun isSymlinkOrEscapes(directory: File, file: File) = java.nio.file.Files.isSymbolicLink(file.toPath()) ||
        file.canonicalFile.parentFile != directory.canonicalFile

    private fun readUtf8Bounded(file: File, maximum: Int, ensureActive: () -> Unit): String {
        require(file.isFile && file.length() in 1..maximum.toLong()) { "A conversion checkpoint file is empty or too large." }
        val bytes = java.io.ByteArrayOutputStream()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(4_096)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size().toLong() + count <= maximum) { "A conversion checkpoint file is too large." }
                bytes.write(buffer, 0, count)
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    private fun validateUtf8NonBlank(file: File, ensureActive: () -> Unit) {
        require(file.isFile && file.length() in 1..MAX_CHAPTER_BYTES) { "A chapter checkpoint is empty or too large." }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        var hasContent = false
        java.io.InputStreamReader(file.inputStream().buffered(), decoder).use { reader ->
            val buffer = CharArray(8_192)
            while (true) {
                ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) if (!buffer[index].isWhitespace()) hasContent = true
            }
        }
        require(hasContent) { "A chapter checkpoint is empty or invalid." }
    }

    private fun safeCheckpointFile(root: File, key: String, name: String): File {
        require(key.matches(CHECKPOINT_KEY) && isCheckpointName(name)) { "Unsafe conversion checkpoint path." }
        validateCheckpointRoot(root)
        val directory = File(root, key)
        val file = File(directory, name)
        require(!directory.exists() || (directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath()))) {
            "Unsafe conversion checkpoint path."
        }
        require(file.canonicalFile.parentFile == directory.canonicalFile && directory.canonicalFile.parentFile == root.canonicalFile) {
            "Unsafe conversion checkpoint path."
        }
        return file
    }

    private fun validateCheckpointRoot(root: File): File {
        val absoluteParent = root.absoluteFile.parentFile ?: error("Unsafe conversion checkpoint root.")
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == absoluteParent.canonicalFile &&
            (!root.exists() || root.isDirectory)) { "Unsafe conversion checkpoint root." }
        return root.canonicalFile
    }

    private fun chapterFileName(index: Int, extension: String) = "chapter-${index.toString().padStart(5, '0')}.$extension"
    private fun isCheckpointName(name: String) = name == WebNovelChapterManifestStoreFileName || CHAPTER_FILE.matches(name)
    private fun maxSize(name: String) = when {
        name == WebNovelChapterManifestStoreFileName -> WebNovelChapterManifestCodec.MAX_SERIALIZED_BYTES.toLong()
        name.endsWith(".url") -> MAX_URL_BYTES.toLong()
        else -> MAX_CHAPTER_BYTES
    }

    private fun sha256(file: File, ensureActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val BUILTIN_IDS = DiscoverySourceIds.BUILT_INS + DiscoverySourceIds.GUTENBERG
    private val JOB_STATES = setOf(WebNovelJobState.QUEUED, WebNovelJobState.RUNNING,
        WebNovelJobState.WAITING_RATE_LIMIT, WebNovelJobState.PAUSE_REQUESTED, WebNovelJobState.PAUSED,
        WebNovelJobState.CANCEL_REQUESTED, WebNovelJobState.CANCELLED, WebNovelJobState.INTERRUPTED,
        WebNovelJobState.FAILED, WebNovelJobState.COMPLETED)
    private val TERMINAL_STATES = setOf(WebNovelJobState.COMPLETED, WebNovelJobState.CANCELLED)
    private val SOURCE_ID = Regex("[a-zA-Z0-9_.-]{1,128}")
    private val JOB_ID = Regex("[a-zA-Z0-9-]{1,80}")
    private val CHECKPOINT_KEY = Regex("[0-9a-f]{64}")
    private val CHAPTER_FILE = Regex("chapter-([0-9]{5})\\.(xhtml|url)")
    private val SHA256 = Regex("[a-fA-F0-9]{64}")
    private const val WebNovelChapterManifestStoreFileName = "chapter-manifest.json"
    private const val MAX_URL_BYTES = 16_000
    private const val MAX_OPTIONS_BYTES = 32 * 1024
    private const val MAX_RATE_LIMIT_DELAY_MILLIS = 7L * 24 * 60 * 60 * 1_000
}
