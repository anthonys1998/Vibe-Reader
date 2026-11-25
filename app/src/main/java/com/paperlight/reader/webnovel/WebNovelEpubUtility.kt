package com.paperlight.reader.webnovel

import android.content.Context
import io.documentnode.epub4j.domain.Book
import io.documentnode.epub4j.domain.MediaType
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.epub.EpubWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.coroutines.coroutineContext

data class WebNovelScrapeOptions(
    val chapterLinkSelector: String = "a[href]",
    val chapterContentSelector: String = ".chapter-content, .chapter-body, #chapter-content, .read-content, .entry-content, article, main, #content",
    val chapterTitleSelector: String = "h1, h2",
    val removeSelector: String = "script, style, noscript, nav, header, footer, aside, .ads, .advertisement, .share, .comments",
    val firstChapter: Int = 1,
    val lastChapter: Int? = null,
    val requestDelayMillis: Long = 500L,
    val renderInBrowser: Boolean = false
) : java.io.Serializable

data class WebNovelEpubResult(val file: File, val title: String, val chapterCount: Int)
data class WebNovelPreview(val chapterCount: Int, val chapterTitle: String, val excerpt: String)
data class WebNovelAiSamples(val tocHtml: String, val chapterHtml: String)

class RateLimitDeferredException(val host: String, val waitUntil: Long) : Exception("The site asked the reader to wait before retrying.")
class WebNovelWorkerSliceYieldException : Exception("This conversion saved its chapter checkpoint and will continue automatically.")
private class SecureFetchFallbackException(message: String, cause: Throwable) : IOException(message, cause)

/** Scrapes a user-supplied novel TOC and writes a DRM-free EPUB without blocking the UI dispatcher. */
class WebNovelEpubUtility(context: Context) {
    private val context = context.applicationContext
    private val renderer = RenderedNovelPage(this.context)
    private val cooldownPreferences = this.context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE)

    suspend fun inspectForAi(
        tableOfContentsUrl: String,
        sampleChapterUrl: String = "",
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
    ): WebNovelAiSamples = withContext(Dispatchers.IO) {
        val discoveryOptions = WebNovelScrapeOptions(renderInBrowser = options.renderInBrowser)
        val (toc, links) = chapterIndex(tableOfContentsUrl, discoveryOptions)
        val chapter = chapterDocument(sampleUrl(toc, links, sampleChapterUrl), discoveryOptions)
        WebNovelAiSamples(
            tocHtml = sanitizedSample(toc, 9_000),
            chapterHtml = sanitizedSample(chapter, 9_000),
        )
    }

    suspend fun preview(
        tableOfContentsUrl: String,
        sampleChapterUrl: String,
        options: WebNovelScrapeOptions,
    ): WebNovelPreview = withContext(Dispatchers.IO) {
        val (toc, links) = chapterIndex(tableOfContentsUrl, options)
        val url = sampleUrl(toc, links.inRange(options), sampleChapterUrl)
        val chapter = chapterDocument(url, options)
        val title = chapterTitle(chapter, options) ?: links.firstOrNull { it.first == url }?.second ?: chapter.title()
        val paragraphs = chapterParagraphs(chapter, options, title)
        require(paragraphs.isNotEmpty()) { "No readable text found in $title ($url)." }
        WebNovelPreview(links.size, title, paragraphs.joinToString("\n\n").take(550))
    }

    suspend fun createEpub(
        tableOfContentsUrl: String,
        destination: File,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> },
        onRateLimitWait: suspend (String, Long?) -> Unit = { _, _ -> },
        onManifestReady: suspend (String) -> Unit = {},
        shouldYieldAtChapterBoundary: suspend () -> Boolean = { false },
    ): WebNovelEpubResult = withContext(Dispatchers.IO) {
        val start = validateHttpUrl(tableOfContentsUrl)
        require(options.requestDelayMillis in 0L..10_000L) { "Request delay must be between 0 and 10 seconds." }
        val checkpointKey = checkpointKey(start.toString(), options)
        val checkpointDirectory = File(context.filesDir, "webnovel-checkpoints/$checkpointKey")
            .apply { check(isDirectory || mkdirs()) { "Could not create conversion checkpoint storage." } }

        var completed = false
        try {
            onProgress(0, 0, "Loading chapter list…")
            val manifest = WebNovelChapterManifestStore(checkpointDirectory).loadOrCreate(
                sourceUrl = start.toString(),
                checkpointKey = checkpointKey,
            ) {
                val (toc, allLinks) = chapterIndex(start.toString(), options, onRateLimitWait)
                val links = allLinks.inRange(options)
                val tocUrl = validateHttpUrl(toc.baseUri()).toString()
                val bookTitle = toc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: toc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
                        ?.takeIf(String::isNotBlank)
                    ?: toc.title().trim().ifBlank { start.host }
                val title = if (links.size == allLinks.size) bookTitle
                    else "$bookTitle (Chapters ${options.firstChapter}–${options.lastChapter ?: allLinks.size})"
                WebNovelChapterManifest(
                    sourceUrl = start.toString(), tocUrl = tocUrl, checkpointKey = checkpointKey, title = title.take(512),
                    chapters = links.mapIndexed { index, (url, label) ->
                        WebNovelChapterLink(url, label ?: "Chapter ${options.firstChapter + index}")
                    },
                    coverImageUrl = coverImageUrl(toc, validateHttpUrl(tocUrl)),
                )
            }
            val links = manifest.chapters.map { it.url to it.label }
            val lastUncachedChapter = links.indices.lastOrNull { index ->
                val stem = "chapter-${index.toString().padStart(5, '0')}"
                File(checkpointDirectory, "$stem.xhtml").isFile && File(checkpointDirectory, "$stem.url").let { urlFile ->
                    !urlFile.isFile || runCatching { urlFile.readText() != links[index].first }.getOrDefault(true)
                } || !File(checkpointDirectory, "$stem.xhtml").isFile
            } ?: -1
            val title = manifest.title
            onManifestReady(title)
            val epub = Book().apply { metadata.addTitle(title) }
            fetchCoverImage(manifest.coverImageUrl)?.let(epub::setCoverImage)
            onProgress(0, links.size, title)
            var totalTextBytes = 0L
            links.forEachIndexed { index, (url, linkLabel) ->
                coroutineContext.ensureActive()
                val cacheFile = File(checkpointDirectory, "chapter-${index.toString().padStart(5, '0')}.xhtml")
                val urlFile = File(checkpointDirectory, "chapter-${index.toString().padStart(5, '0')}.url")
                val cached = cacheFile.isFile && urlFile.isFile &&
                    runCatching { urlFile.readText() == url }.getOrDefault(false)
                val xhtml = if (cached) cacheFile.readText(Charsets.UTF_8) else {
                    if (index > 0 && options.requestDelayMillis > 0L) delay(options.requestDelayMillis)
                    val chapterDoc = chapterDocument(url, options, onRateLimitWait)
                    val chapterTitle = chapterTitle(chapterDoc, options)
                        ?: linkLabel
                        ?: "Chapter ${options.firstChapter + index}"
                    val paragraphs = chapterParagraphs(chapterDoc, options, chapterTitle)
                    require(paragraphs.isNotEmpty()) { "No readable text found in $chapterTitle ($url)." }
                    chapterXhtml(chapterTitle, paragraphs).also { html ->
                        atomicWrite(cacheFile, html.toByteArray(Charsets.UTF_8))
                        atomicWrite(urlFile, url.toByteArray(Charsets.UTF_8))
                    }
                }
                require(xhtml.isNotBlank()) { "A saved chapter checkpoint is empty." }
                val chapterTitle = Jsoup.parse(xhtml).selectFirst("h1")?.text()?.takeIf(String::isNotBlank)
                    ?: linkLabel ?: "Chapter ${options.firstChapter + index}"
                val bytes = xhtml.toByteArray(Charsets.UTF_8)
                totalTextBytes += bytes.size
                require(totalTextBytes <= 64L * 1024 * 1024) { "This novel is too large to generate on this device in one EPUB." }
                val resource = Resource(ByteArrayInputStream(bytes), "chapter-${(index + 1).toString().padStart(4, '0')}.xhtml")
                epub.addSection(chapterTitle, resource)
                onProgress(index + 1, links.size, if (cached) "Restored $chapterTitle" else chapterTitle)
                if (!cached && index < lastUncachedChapter && shouldYieldAtChapterBoundary()) throw WebNovelWorkerSliceYieldException()
            }

            coroutineContext.ensureActive()
            onProgress(links.size, links.size, "Writing EPUB…")
            destination.parentFile?.mkdirs()
            destination.outputStream().buffered().use { output -> EpubWriter().write(epub, output) }
            require(destination.isFile && destination.length() > 0L) { "The EPUB writer produced an empty file." }
            completed = true
            WebNovelEpubResult(destination, title, links.size)
        } finally {
            if (!completed) destination.delete()
        }
    }

    private suspend fun chapterIndex(
        url: String,
        options: WebNovelScrapeOptions,
        onRateLimitWait: suspend (String, Long?) -> Unit = { _, _ -> },
    ): Pair<Document, List<Pair<String, String?>>> {
        val start = validateHttpUrl(url)
        fun links(document: Document): List<Pair<String, String?>> {
            val origin = validateHttpUrl(document.baseUri())
            val selector = if (origin.host.equals("novelbin.xyz", true) &&
                options.chapterLinkSelector == "a[href]"
            ) ".chapter-list a.chapter-item[href]" else options.chapterLinkSelector
            return extractChapterLinks(document, origin, selector)
        }
        val toc = if (options.renderInBrowser) {
            awaitCooldown(start.toString(), onRateLimitWait)
            renderer.load(start.toString()) { document ->
                val found = links(document).size
                val expected = if (isNovelBin(document) && options.chapterLinkSelector == "a[href]") {
                    document.selectFirst(".chapter-box-count")?.text()?.substringBefore(' ')?.toIntOrNull()
                } else null
                found > 0 && (expected == null || found >= expected)
            }
        } else fetch(start.toString(), onRateLimitWait)
        val chapterLinks = novelPingChapterLinks(toc, options, onRateLimitWait) ?: links(toc)
        require(chapterLinks.isNotEmpty()) {
            "No chapter links found. Check the table of contents URL and selectors, or enable browser rendering."
        }
        return toc to chapterLinks
    }

    private suspend fun novelPingChapterLinks(
        toc: Document,
        options: WebNovelScrapeOptions,
        onRateLimitWait: suspend (String, Long?) -> Unit,
    ): List<Pair<String, String?>>? {
        val archiveUrl = NovelPingChapterArchive.urlFor(toc) ?: return null
        val archiveRequired = toc.selectFirst("#chapter-archive .loading") != null
        return try {
            val archive = fetch(archiveUrl, onRateLimitWait)
            NovelPingChapterArchive.extractLinks(archive, validateHttpUrl(toc.baseUri()), options.chapterLinkSelector)
                .takeIf { it.isNotEmpty() }
                ?: if (archiveRequired) error("NovelPing returned an empty chapter archive.") else null
        } catch (failure: RateLimitDeferredException) {
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: SecureFetchFallbackException) {
            throw failure
        } catch (failure: Exception) {
            if (archiveRequired) throw IOException("Could not load NovelPing's complete chapter archive.", failure)
            null
        }
    }

    private fun <T> List<T>.inRange(options: WebNovelScrapeOptions): List<T> {
        val first = options.firstChapter
        val last = options.lastChapter ?: size
        require(first in 1..size && last in first..size) {
            "Found $size chapters. Choose a range within 1–$size."
        }
        require(last - first + 1 <= 9_000) { "Choose at most 9,000 chapters per export." }
        return subList(first - 1, last)
    }

    private fun sampleUrl(toc: Document, links: List<Pair<String, String?>>, sample: String): String {
        if (sample.isBlank()) return links.first().first
        val chapter = validateHttpUrl(sample)
        require(sameOrigin(validateHttpUrl(toc.baseUri()), chapter)) {
            "The sample chapter must be on the same site as the table of contents."
        }
        return chapter.toString()
    }

    private suspend fun chapterDocument(
        url: String,
        options: WebNovelScrapeOptions,
        onRateLimitWait: suspend (String, Long?) -> Unit = { _, _ -> },
    ): Document {
        if (!options.renderInBrowser) return fetch(url, onRateLimitWait)
        awaitCooldown(url, onRateLimitWait)
        return renderer.load(url) { document ->
            val paragraphs = chapterParagraphs(document, options, chapterTitle(document, options))
            paragraphs.sumOf { it.length } >= 40
        }
    }

    private fun chapterTitle(document: Document, options: WebNovelScrapeOptions): String? {
        val selector = if (isNovelBin(document) && options.chapterTitleSelector == "h1, h2")
            ".reader-header-chapter" else options.chapterTitleSelector
        return document.selectFirst(selector)?.text()?.cleanText()?.takeIf(String::isNotBlank)
    }

    private fun chapterParagraphs(document: Document, options: WebNovelScrapeOptions, title: String?): List<String> {
        val selector = if (isNovelBin(document) && options.chapterContentSelector == WebNovelScrapeOptions().chapterContentSelector)
            ".reader-content" else options.chapterContentSelector
        val content = selectContent(document, selector, allowBodyFallback = !options.renderInBrowser)
            ?: return emptyList()
        content.select(options.removeSelector).remove()
        if (isNovelBin(document)) content.select("h1.sr-only").remove()
        return extractParagraphs(content).let { if (it.firstOrNull() == title) it.drop(1) else it }
    }

    private fun isNovelBin(document: Document): Boolean =
        validateHttpUrl(document.baseUri()).host.equals("novelbin.xyz", true)

    private suspend fun fetch(url: String, onRateLimitWait: suspend (String, Long?) -> Unit = { _, _ -> }): Document {
        var attempts = 0
        val host = requireNotNull(validateHttpUrl(url).host).lowercase(java.util.Locale.ROOT)
        while (true) {
            coroutineContext.ensureActive()
            awaitCooldown(url, onRateLimitWait)
            val response = try {
                Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(20_000)
                    .maxBodySize(MAX_PAGE_BYTES)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute()
            } catch (failure: IOException) {
                if (validateHttpUrl(url).scheme.equals("https", true) && failure.hasTlsFailure()) {
                    return loadAfterTlsFailure(url, failure)
                }
                attempts++
                if (attempts > MAX_RATE_LIMIT_RETRIES) throw failure
                persistCooldown(host, retryAfterMillis(null, attempts), onRateLimitWait)
                continue
            }
            if (response.statusCode() == 429) {
                attempts++
                require(attempts <= MAX_RATE_LIMIT_RETRIES) { "The website is still rate limiting requests. Resume this conversion later." }
                persistCooldown(host, retryAfterMillis(response.header("Retry-After"), attempts), onRateLimitWait)
                continue
            }
            if (response.statusCode() in 500..599) {
                attempts++
                if (attempts > MAX_RATE_LIMIT_RETRIES) error("Website returned HTTP ${response.statusCode()} after repeated retries.")
                persistCooldown(host, retryAfterMillis(null, attempts), onRateLimitWait)
                continue
            }
            require(response.statusCode() in 200..299) { "Website returned HTTP ${response.statusCode()} for $url." }
            cooldownPreferences.edit().remove(host).apply()
            onRateLimitWait(host, null)
            return response.parse()
        }
    }

    private suspend fun loadAfterTlsFailure(url: String, directFailure: IOException): Document {
        val requestedUri = validateHttpUrl(url)
        return try {
            val document = renderer.load(url) { it.body()?.text()?.isNotBlank() == true }
            val finalUri = validateHttpUrl(document.baseUri())
            require(sameOrigin(requestedUri, finalUri)) {
                "The secure browser retry redirected to a different origin."
            }
            document
        } catch (retryFailure: kotlinx.coroutines.CancellationException) {
            throw retryFailure
        } catch (retryFailure: Exception) {
            val directMessage = directFailure.message ?: directFailure.javaClass.simpleName
            val retryMessage = retryFailure.message ?: retryFailure.javaClass.simpleName
            throw SecureFetchFallbackException(
                "Secure direct loading failed ($directMessage), and the browser retry failed ($retryMessage).",
                retryFailure,
            )
        }
    }

    private fun Throwable.hasTlsFailure(): Boolean = generateSequence(this) { it.cause }.any {
        it is SSLException || it is CertificateException || it is CertPathValidatorException
    }

    private suspend fun awaitCooldown(url: String, onRateLimitWait: suspend (String, Long?) -> Unit) {
        val host = requireNotNull(validateHttpUrl(url).host).lowercase(java.util.Locale.ROOT)
        val cooldownUntil = cooldownPreferences.getLong(host, 0L)
        if (cooldownUntil > System.currentTimeMillis()) {
            onRateLimitWait(host, cooldownUntil)
            val remaining = cooldownUntil - System.currentTimeMillis()
            if (remaining > MAX_INLINE_WAIT_MILLIS) throw RateLimitDeferredException(host, cooldownUntil)
            delay(remaining)
            onRateLimitWait(host, null)
        }
    }

    private suspend fun persistCooldown(host: String, delayMillis: Long, onRateLimitWait: suspend (String, Long?) -> Unit) {
        val until = System.currentTimeMillis() + delayMillis
        check(cooldownPreferences.edit().putLong(host, until).commit()) { "Could not save the website's retry deadline." }
        onRateLimitWait(host, until)
        if (delayMillis > MAX_INLINE_WAIT_MILLIS) throw RateLimitDeferredException(host, until)
        delay((until - System.currentTimeMillis()).coerceAtLeast(1L))
        onRateLimitWait(host, null)
    }

    private fun retryAfterMillis(header: String?, attempt: Int): Long {
        val seconds = header?.trim()?.toLongOrNull()
        val serverDelay = seconds?.let { it.coerceIn(1L, MAX_RATE_LIMIT_DELAY_MILLIS / 1_000L) * 1_000L } ?: runCatching {
            val target = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            Duration.between(java.time.Instant.now(), target.toInstant()).toMillis().coerceAtLeast(1_000L)
        }.getOrNull()
        val backoff = (1_000L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(MAX_TRANSIENT_RETRY_DELAY_MILLIS)
        val jitter = if (serverDelay == null) Random.nextLong(0L, (backoff / 4L).coerceAtLeast(1L)) else 0L
        return (serverDelay ?: (backoff + jitter)).coerceIn(1_000L, MAX_RATE_LIMIT_DELAY_MILLIS)
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.outputStream().buffered().use { it.write(bytes) }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not save a completed chapter checkpoint.")
        }
    }

    private fun coverImageUrl(toc: Document, pageUrl: URI): String? {
        val imageUrl = sequenceOf(
            toc.selectFirst("meta[property=og:image]")?.attr("content"),
            toc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            toc.selectFirst("meta[property=twitter:image]")?.attr("content"),
            toc.selectFirst("img[src]")?.absUrl("src"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .mapNotNull { raw -> runCatching { pageUrl.resolve(raw) }.getOrNull() }
            .firstOrNull { uri ->
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null
            } ?: return null

        return imageUrl.toString()
    }

    private fun fetchCoverImage(imageUrl: String?): Resource? {
        if (imageUrl == null) return null
        val host = runCatching { requireNotNull(validateHttpUrl(imageUrl).host).lowercase(java.util.Locale.ROOT) }.getOrNull()
        if (host != null && cooldownPreferences.getLong(host, 0L) > System.currentTimeMillis()) return null
        return runCatching {
            val response = Jsoup.connect(imageUrl)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .maxBodySize(MAX_COVER_BYTES)
                .ignoreContentType(true)
                .execute()
            val mimeType = response.contentType().orEmpty().substringBefore(';').trim().lowercase()
            val extension = when (mimeType) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                else -> return@runCatching null
            }
            Resource(response.bodyAsBytes(), "cover.$extension").apply {
                mediaType = MediaType(mimeType, extension)
            }
        }.getOrNull()
    }

    private fun sanitizedSample(document: Document, limit: Int): String {
        val sample = document.clone()
        sample.select("script, style, noscript, svg, iframe, object, embed, form, nav, footer, header, aside, img, video, audio").remove()
        sample.select("*").forEach { element ->
            element.attributes().asList().map { it.key }.filterNot { it in setOf("class", "id", "href") }.forEach(element::removeAttr)
            if (element.hasAttr("href")) {
                val path = runCatching { URI(element.attr("href")).path }.getOrNull().orEmpty()
                element.attr("href", path.take(240))
            }
        }
        return sample.body().html().take(limit)
    }

    private fun extractChapterLinks(
        toc: Document,
        tocUri: URI,
        selector: String
    ): List<Pair<String, String?>> {
        val candidates = toc.select(selector).mapNotNull { element ->
            val anchor = if (element.normalName() == "a") element else element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").ifBlank { return@mapNotNull null }
            val uri = runCatching { validateHttpUrl(href) }.getOrNull() ?: return@mapNotNull null
            if (!sameOrigin(tocUri, uri) || (uri.path == tocUri.path && uri.query == tocUri.query)) return@mapNotNull null
            val label = anchor.text().cleanText().take(160).takeIf(String::isNotBlank)
            if (label == null) return@mapNotNull null
            uri.normalize().toString() to label
        }.distinctBy { it.first }

        val chapterHint = Regex("chapter|chap\\.?|episode|ep\\.?|part|volume|第.{0,8}[章話回卷]", RegexOption.IGNORE_CASE)
        val hinted = candidates.filter { (url, label) -> chapterHint.containsMatchIn(label.orEmpty()) || chapterHint.containsMatchIn(url) }
        return hinted.ifEmpty { candidates }
    }

    private fun selectContent(document: Document, selectors: String, allowBodyFallback: Boolean): Element? {
        selectors.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { selector ->
            document.selectFirst(selector)?.let { return it.clone() }
        }
        return if (allowBodyFallback) document.body().clone() else null
    }

    private fun extractParagraphs(content: Element): List<String> {
        val blocks = content.select("p, h1, h2, h3, h4, blockquote, li")
            .map { it.text().cleanText() }
            .filter { it.length >= 2 }
        val raw = if (blocks.isNotEmpty()) blocks else content.wholeText().split(Regex("\\R+"))
            .map { it.cleanText() }.filter { it.length >= 2 }
        return raw.fold(mutableListOf<String>()) { result, paragraph ->
            if (result.lastOrNull() != paragraph) result += paragraph
            result
        }
    }

    private fun chapterXhtml(title: String, paragraphs: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
        append(title.xmlEscape())
        append("</title><meta charset=\"UTF-8\"/><style>body{margin:5%;line-height:1.7;font-family:serif}h1{text-align:center;margin:1.5em 0}p{margin:0 0 1em;text-indent:1em}</style></head><body><h1>")
        append(title.xmlEscape())
        append("</h1>")
        paragraphs.forEach { append("<p>").append(it.xmlEscape()).append("</p>") }
        append("</body></html>")
    }

    private fun validateHttpUrl(value: String): URI {
        val uri = URI(value.trim()).normalize()
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Use an http or https URL."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Enter a valid website URL without embedded credentials." }
        return uri
    }

    private fun sameOrigin(base: URI, candidate: URI): Boolean =
        base.scheme.equals(candidate.scheme, ignoreCase = true) &&
            base.host.equals(candidate.host, ignoreCase = true) && effectivePort(base) == effectivePort(candidate)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    private fun String.cleanText(): String = replace('\u00a0', ' ')
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex(" *\\n *"), " ")
        .trim()

    private fun String.xmlEscape(): String = replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        const val MAX_INLINE_WAIT_MILLIS = 60_000L
        fun checkpointKey(url: String, options: WebNovelScrapeOptions): String {
            val canonicalUrl = runCatching { URI(url.trim()).normalize().toString() }.getOrDefault(url)
            val config = listOf(canonicalUrl, options.chapterLinkSelector, options.chapterContentSelector,
                options.chapterTitleSelector, options.removeSelector, options.firstChapter.toString(),
                options.lastChapter?.toString().orEmpty(), options.renderInBrowser.toString()).joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        const val MAX_RATE_LIMIT_RETRIES = 5
        const val MAX_TRANSIENT_RETRY_DELAY_MILLIS = 30L * 60 * 1_000
        const val MAX_RATE_LIMIT_DELAY_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_PAGE_BYTES = 8 * 1024 * 1024
        const val MAX_COVER_BYTES = 8 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 PaperlightReader/0.10"
    }
}
