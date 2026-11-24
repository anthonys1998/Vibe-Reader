package com.paperlight.reader.webnovel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A frozen, ordered chapter list shared by jobs with the same checkpoint key. */
data class WebNovelChapterManifest(
    val sourceUrl: String,
    val tocUrl: String,
    val checkpointKey: String,
    val title: String,
    val chapters: List<WebNovelChapterLink>,
    val coverImageUrl: String?,
)

data class WebNovelChapterLink(val url: String, val label: String)

class InvalidSavedChapterManifestException(cause: Throwable? = null) : IllegalArgumentException(
    "${WebNovelChapterManifestCodec.INVALID_MANIFEST_ERROR_PREFIX} This conversion cannot resume safely. Use Reset chapter list to fetch the current table of contents.",
    cause,
)

class ChapterManifestTooLargeException : IllegalArgumentException(
    "This chapter list is too large to save. Choose a smaller chapter range and start a new conversion."
)

object WebNovelChapterManifestCodec {
    const val VERSION = 1
    const val MAX_CHAPTERS = 9_000
    const val MAX_SERIALIZED_BYTES = 4 * 1024 * 1024
    const val INVALID_MANIFEST_ERROR_PREFIX = "Saved chapter list is invalid:"
    private const val MAX_URL_CHARS = 4_000
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_LABEL_CHARS = 160

    fun encode(manifest: WebNovelChapterManifest): String {
        validate(manifest)
        val characterCount = manifest.sourceUrl.length.toLong() + manifest.tocUrl.length +
            manifest.checkpointKey.length + manifest.title.length + (manifest.coverImageUrl?.length ?: 0) +
            manifest.chapters.sumOf { it.url.length.toLong() + it.label.length }
        val estimatedMaxBytes = characterCount * 3L + manifest.chapters.size * 48L + 512L
        if (estimatedMaxBytes > MAX_SERIALIZED_BYTES) throw ChapterManifestTooLargeException()
        val chapters = JSONArray()
        manifest.chapters.forEach { chapters.put(JSONObject().put("url", it.url).put("label", it.label)) }
        val json = JSONObject()
            .put("version", VERSION)
            .put("sourceUrl", manifest.sourceUrl)
            .put("tocUrl", manifest.tocUrl)
            .put("checkpointKey", manifest.checkpointKey)
            .put("title", manifest.title)
            .put("coverImageUrl", manifest.coverImageUrl ?: JSONObject.NULL)
            .put("chapters", chapters)
            .toString()
        if (json.length > MAX_SERIALIZED_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES) {
            throw ChapterManifestTooLargeException()
        }
        return json
    }

    fun decode(serialized: String, expectedSourceUrl: String, expectedCheckpointKey: String): WebNovelChapterManifest {
        if (serialized.length > MAX_SERIALIZED_BYTES || serialized.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES) {
            throw InvalidSavedChapterManifestException()
        }
        return try {
            val json = JSONObject(serialized)
            require(json.getInt("version") == VERSION)
            val chaptersJson = json.getJSONArray("chapters")
            require(chaptersJson.length() in 1..MAX_CHAPTERS)
            val chapters = ArrayList<WebNovelChapterLink>(chaptersJson.length())
            for (index in 0 until chaptersJson.length()) {
                val item = chaptersJson.getJSONObject(index)
                chapters += WebNovelChapterLink(item.getString("url"), item.getString("label"))
            }
            val manifest = WebNovelChapterManifest(
                sourceUrl = json.getString("sourceUrl"),
                tocUrl = json.getString("tocUrl"),
                checkpointKey = json.getString("checkpointKey"),
                title = json.getString("title"),
                chapters = chapters,
                coverImageUrl = if (json.isNull("coverImageUrl")) null else json.getString("coverImageUrl"),
            )
            require(manifest.sourceUrl == expectedSourceUrl && manifest.checkpointKey == expectedCheckpointKey)
            validate(manifest)
            manifest
        } catch (invalid: InvalidSavedChapterManifestException) {
            throw invalid
        } catch (failure: Exception) {
            throw InvalidSavedChapterManifestException(failure)
        }
    }

    private fun validate(manifest: WebNovelChapterManifest) {
        require(manifest.sourceUrl.length in 1..MAX_URL_CHARS)
        checkedHttpUrl(manifest.sourceUrl)
        require(manifest.tocUrl.length in 1..MAX_URL_CHARS)
        val toc = checkedHttpUrl(manifest.tocUrl)
        require(manifest.checkpointKey.matches(Regex("[0-9a-f]{64}")))
        require(manifest.title.isNotBlank() && manifest.title.length <= MAX_TITLE_CHARS && manifest.title.none { it.code < 0x20 })
        require(manifest.chapters.size in 1..MAX_CHAPTERS)
        val chapterUrls = HashSet<String>(manifest.chapters.size)
        manifest.chapters.forEach { chapter ->
            require(chapter.url.length in 1..MAX_URL_CHARS)
            require(chapter.label.isNotBlank() && chapter.label.length <= MAX_LABEL_CHARS && chapter.label.none { it.code < 0x20 })
            val uri = checkedHttpUrl(chapter.url)
            require(sameOrigin(toc, uri))
            require(chapterUrls.add(chapter.url))
        }
        manifest.coverImageUrl?.let {
            require(it.length in 1..MAX_URL_CHARS)
            checkedHttpUrl(it)
        }
    }

    private fun checkedHttpUrl(value: String): URI {
        val uri = URI(value).normalize()
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null)
        return uri
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, true) && first.host.equals(second.host, true) && effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }
}

/** Reads an existing manifest without re-indexing; a corrupt file is a visible failure, never a silent refresh. */
class WebNovelChapterManifestStore(private val checkpointDirectory: File) {
    private val manifestFile = File(checkpointDirectory, FILE_NAME)

    suspend fun loadOrCreate(
        sourceUrl: String,
        checkpointKey: String,
        create: suspend () -> WebNovelChapterManifest,
    ): WebNovelChapterManifest {
        check(checkpointDirectory.isDirectory || checkpointDirectory.mkdirs()) { "Could not create conversion checkpoint storage." }
        if (manifestFile.exists()) {
            if (!manifestFile.isFile || manifestFile.length() > WebNovelChapterManifestCodec.MAX_SERIALIZED_BYTES) {
                throw InvalidSavedChapterManifestException()
            }
            return WebNovelChapterManifestCodec.decode(manifestFile.readText(Charsets.UTF_8), sourceUrl, checkpointKey)
        }

        val manifest = create()
        require(manifest.sourceUrl == sourceUrl && manifest.checkpointKey == checkpointKey) {
            "The chapter list was created for different scrape options."
        }
        val serialized = WebNovelChapterManifestCodec.encode(manifest)
        writeAtomically(serialized)
        return manifest
    }

    fun clear(): Boolean = !manifestFile.exists() || manifestFile.delete()

    private fun writeAtomically(contents: String) {
        val temporary = File(checkpointDirectory, "$FILE_NAME.tmp")
        try {
            temporary.outputStream().buffered().use { it.write(contents.toByteArray(Charsets.UTF_8)) }
            try {
                Files.move(temporary.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), manifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "chapter-manifest.json"
    }
}
