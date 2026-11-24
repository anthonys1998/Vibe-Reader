package com.paperlight.reader.discovery

import android.content.Context
import com.paperlight.reader.data.DiscoverySourceEntity
import com.paperlight.reader.data.DiscoverySourceKind
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class DiscoveryBookDetails(
    val synopsis: String?,
    val genres: List<String>,
)

object DiscoveryBookDetailsClient {
    private const val MAX_SYNOPSIS_CHARS = 12_000
    private const val MAX_GENRES = 24
    private const val MAX_GENRE_CHARS = 80

    suspend fun load(
        source: DiscoverySourceEntity,
        book: DiscoveryBook,
        renderedContext: Context? = null,
    ): DiscoveryBookDetails = withContext(Dispatchers.IO) {
        if (source.kind !in setOf(
                DiscoverySourceKind.HTML,
                DiscoverySourceKind.STANDARD_EBOOKS,
                DiscoverySourceKind.GUTENBERG,
                DiscoverySourceKind.OPDS,
            ) || isDownloadOnly(book) || !DiscoveryCatalogClient.sameOrigin(source.baseUrl, book.detailsUrl)
        ) return@withContext emptyDetails()
        if (source.kind == DiscoverySourceKind.OPDS && book.epubUrl.isNullOrBlank()) {
            return@withContext emptyDetails()
        }

        val config = DiscoverySourceConfigCodec.decode(source.configJson)
        val useRenderer = source.kind == DiscoverySourceKind.HTML && config.renderJavaScript
        if (useRenderer && renderedContext == null) return@withContext emptyDetails()

        currentCoroutineContext().ensureActive()
        val fetched = DiscoveryCatalogClient.fetchPublicDocument(
            url = book.detailsUrl,
            renderJavaScript = useRenderer,
            renderedContext = renderedContext,
            ready = ::hasPotentialDetails,
        )
        currentCoroutineContext().ensureActive()
        if (!DiscoveryCatalogClient.sameOrigin(source.baseUrl, fetched.finalUrl) ||
            !isHtmlContentType(fetched.contentType, allowMissing = useRenderer)
        ) return@withContext emptyDetails()
        parse(fetched.document, source.kind)
    }

    internal fun parse(document: Document, sourceKind: String? = null): DiscoveryBookDetails = DiscoveryBookDetails(
        synopsis = extractSynopsis(document)?.let { cleanSynopsis(it, sourceKind) },
        genres = extractGenres(document),
    )

    private fun isDownloadOnly(book: DiscoveryBook): Boolean {
        val details = book.detailsUrl.trim()
        if (book.epubUrl?.trim() == details) return true
        val path = runCatching { URI(details).path.orEmpty() }.getOrDefault("")
        return DOWNLOAD_EXTENSIONS.any { extension -> path.endsWith(extension, ignoreCase = true) }
    }

    private fun isHtmlContentType(contentType: String, allowMissing: Boolean): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return mime in setOf("text/html", "application/xhtml+xml") || (allowMissing && mime.isBlank())
    }

    private fun hasPotentialDetails(document: Document): Boolean =
        document.select("#novel-description-content, [itemprop=description], [itemprop=abstract], #synopsis, .synopsis, #description, .description").isNotEmpty() ||
            document.select("[itemprop=genre], a.meta-chip[href*=genre], .genres a, .genre a").isNotEmpty()

    private fun extractSynopsis(document: Document): String? {
        val selectors = listOf(
            "#novel-description-content",
            "[itemprop=description]:not(meta)",
            "[itemprop=abstract]:not(meta)",
            "#synopsis",
            ".synopsis",
            "#description",
            ".description",
        )
        selectors.forEach { selector ->
            document.select(selector).firstNotNullOfOrNull { elementText(it) }
                ?.let { return it.take(MAX_SYNOPSIS_CHARS) }
        }
        val metaSelectors = listOf(
            "meta[name=description][content]",
            "meta[property=og:description][content]",
            "meta[itemprop=description][content]",
        )
        metaSelectors.forEach { selector ->
            document.select(selector).firstNotNullOfOrNull { elementText(it, "content") }
                ?.let { return it.take(MAX_SYNOPSIS_CHARS) }
        }
        return null
    }

    private fun extractGenres(document: Document): List<String> {
        val values = linkedMapOf<String, String>()
        val selectors = listOf(
            "a.meta-chip[href*=genre]",
            ".genres a, .genre a, [class~=genres] a, [class~=genre] a",
            "meta[property=book:tag][content], meta[name=genre][content]",
            "[itemprop=genre]",
        )
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                val raw = if (element.tagName().equals("meta", ignoreCase = true)) {
                    element.attr("content")
                } else {
                    element.text()
                }.ifBlank { element.attr("content") }
                displayGenre(raw)?.let { label ->
                    val key = label.lowercase()
                    if (!values.containsKey(key)) values[key] = label
                }
            }
        }
        return values.values.take(MAX_GENRES)
    }

    private fun cleanSynopsis(value: String, sourceKind: String?): String {
        val cleaned = if (sourceKind == DiscoverySourceKind.STANDARD_EBOOKS) {
            STANDARD_EBOOKS_DESCRIPTION_PREFIX.replaceFirst(value, "")
        } else value
        return cleaned.trim().takeIf(String::isNotBlank)?.take(MAX_SYNOPSIS_CHARS).orEmpty()
    }

    private fun displayGenre(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val fromUrl = runCatching {
            val uri = URI(value)
            if (uri.isAbsolute) uri.path.orEmpty().substringAfterLast('/').takeIf(String::isNotBlank) else null
        }.getOrNull()
        val label = (fromUrl ?: value)
            .replace(Regex("[\\s_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_GENRE_CHARS)
        if (label.isBlank()) return null
        return if (fromUrl != null || label == label.lowercase()) {
            label.split(' ').joinToString(" ") { part ->
                part.replaceFirstChar { character -> character.titlecase() }
            }
        } else label
    }

    private fun elementText(element: Element, contentAttribute: String? = null): String? {
        val clone = element.clone()
        clone.select("script, style, noscript, iframe, form, nav, footer, aside, .comments, .advertisement, .ads").remove()
        val text = (contentAttribute?.let { clone.attr(it) } ?: clone.text())
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.takeIf { it.isNotBlank() && !it.equals("description", true) && !it.equals("synopsis", true) }
    }

    private fun emptyDetails() = DiscoveryBookDetails(synopsis = null, genres = emptyList())

    private val DOWNLOAD_EXTENSIONS = setOf(".epub", ".pdf", ".mobi", ".azw", ".azw3", ".cbz", ".cbr", ".zip")
    private val STANDARD_EBOOKS_DESCRIPTION_PREFIX = Regex(
        "^Free epub ebook download of the Standard Ebooks edition of .+?:\\s*",
        RegexOption.IGNORE_CASE,
    )
}
