package com.paperlight.reader.webnovel

import org.jsoup.nodes.Document
import java.net.URI

/** NovelPing builds its complete chapter list only after its chapter tab loads an AJAX archive. */
internal object NovelPingChapterArchive {
    private val allowedHosts = setOf("novelping.com", "www.novelping.com")
    private val idPattern = Regex("[A-Za-z0-9_-]{1,200}")

    fun urlFor(page: Document): String? {
        val pageUri = runCatching { URI(page.baseUri()).normalize() }.getOrNull() ?: return null
        val host = pageUri.host?.lowercase() ?: return null
        if (!pageUri.scheme.equals("https", true) || host !in allowedHosts) return null
        val novelId = page.selectFirst("#rating[data-novel-id]")?.attr("data-novel-id")?.trim()
            ?.takeIf(idPattern::matches) ?: return null
        return pageUri.resolve("/ajax/chapter-archive?novelId=$novelId").toString()
    }

    /** Resolve archive anchors against the book page and retain the order supplied by the site. */
    fun extractLinks(archive: Document, bookUri: URI, selector: String): List<Pair<String, String?>> {
        val candidates = archive.select(selector).mapNotNull { element ->
            val anchor = if (element.normalName() == "a") element
                else element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            val uri = runCatching { bookUri.resolve(URI(href)).normalize() }.getOrNull() ?: return@mapNotNull null
            if (!uri.scheme.equals(bookUri.scheme, true) || uri.host?.equals(bookUri.host, true) != true ||
                effectivePort(uri) != effectivePort(bookUri) || uri.userInfo != null
            ) {
                return@mapNotNull null
            }
            if (uri.path == bookUri.path && uri.query == bookUri.query) return@mapNotNull null
            val label = anchor.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
                .take(160).takeIf(String::isNotBlank) ?: return@mapNotNull null
            uri.toString() to label
        }.distinctBy { it.first }

        val chapterHint = Regex("chapter|chap\\.?|episode|ep\\.?|part|volume|第.{0,8}[章話回卷]", RegexOption.IGNORE_CASE)
        val hinted = candidates.filter { (url, label) ->
            chapterHint.containsMatchIn(label.orEmpty()) || chapterHint.containsMatchIn(url)
        }
        return hinted.ifEmpty { candidates }
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }
}
