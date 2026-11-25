package com.paperlight.reader.discovery

import android.content.Context
import android.net.Uri
import com.paperlight.reader.data.DiscoverySourceEntity
import com.paperlight.reader.data.DiscoverySourceKind
import com.paperlight.reader.data.WebNovelScrapeOptionsJson
import com.paperlight.reader.webnovel.RenderedNovelPage
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.zip.ZipFile
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

data class DiscoveryBook(
    val id: String,
    val title: String,
    val author: String,
    val detailsUrl: String,
    val coverUrl: String? = null,
    val epubUrl: String? = null,
)

data class DiscoveryLink(val title: String, val url: String)

object DiscoverySourcePurpose {
    const val DOWNLOAD_EPUB = "DOWNLOAD_EPUB"
    const val CONVERT_WEBSITE = "CONVERT_WEBSITE"

    fun isValid(value: String): Boolean = value == DOWNLOAD_EPUB || value == CONVERT_WEBSITE
}

data class DiscoveryPage(
    val books: List<DiscoveryBook>,
    val collections: List<DiscoveryLink> = emptyList(),
    val nextUrl: String? = null,
    val detectedConfig: DiscoverySourceConfig? = null,
)

data class DiscoverySourceConfig(
    val searchUrlTemplate: String = "",
    val htmlBookSelector: String = "article.book, article.ebook, .book-item, .ebook-item, .search-result",
    val htmlTitleSelector: String = "h1, h2, h3, [itemprop=name]",
    val htmlAuthorSelector: String = "[itemprop=author], .author",
    val htmlDetailLinkSelector: String = "a[href]",
    val htmlCoverSelector: String = "img[src]",
    val htmlEpubLinkSelector: String = "a[href$=.epub]",
    val htmlLatestUrl: String = "",
    val htmlPopularUrl: String = "",
    val renderJavaScript: Boolean = false,
    val excludeNonCatalogRegions: Boolean? = null,
    val purpose: String = DiscoverySourcePurpose.DOWNLOAD_EPUB,
    val scrapeOptions: WebNovelScrapeOptions = WebNovelScrapeOptions(),
)

internal data class DiscoveryHtmlAiSample(val finalUrl: String, val html: String)

internal data class DiscoveryFetchedDocument(
    val document: Document,
    val finalUrl: String,
    val contentType: String,
)

data class DetectedDiscoverySource(
    val kind: String,
    val baseUrl: String,
    val suggestedName: String,
    val config: DiscoverySourceConfig,
    val previewTitles: List<String> = emptyList(),
)

internal data class HtmlSourceDetection(
    val config: DiscoverySourceConfig,
    val previewTitles: List<String>,
)

object DiscoverySourceConfigCodec {
    fun encode(value: DiscoverySourceConfig): String = org.json.JSONObject()
        .put("searchUrlTemplate", value.searchUrlTemplate)
        .put("htmlBookSelector", value.htmlBookSelector)
        .put("htmlTitleSelector", value.htmlTitleSelector)
        .put("htmlAuthorSelector", value.htmlAuthorSelector)
        .put("htmlDetailLinkSelector", value.htmlDetailLinkSelector)
        .put("htmlCoverSelector", value.htmlCoverSelector)
        .put("htmlEpubLinkSelector", value.htmlEpubLinkSelector)
        .put("htmlLatestUrl", value.htmlLatestUrl)
        .put("htmlPopularUrl", value.htmlPopularUrl)
        .put("renderJavaScript", value.renderJavaScript)
        .put("purpose", value.purpose)
        .put("scrapeOptions", org.json.JSONObject(WebNovelScrapeOptionsJson.encode(value.scrapeOptions)))
        .apply { value.excludeNonCatalogRegions?.let { put("excludeNonCatalogRegions", it) } }
        .toString()

    fun decode(json: String): DiscoverySourceConfig = runCatching {
        val value = org.json.JSONObject(json)
        DiscoverySourceConfig(
            searchUrlTemplate = value.optString("searchUrlTemplate"),
            htmlBookSelector = value.optString("htmlBookSelector", DiscoverySourceConfig().htmlBookSelector),
            htmlTitleSelector = value.optString("htmlTitleSelector", DiscoverySourceConfig().htmlTitleSelector),
            htmlAuthorSelector = value.optString("htmlAuthorSelector", DiscoverySourceConfig().htmlAuthorSelector),
            htmlDetailLinkSelector = value.optString("htmlDetailLinkSelector", DiscoverySourceConfig().htmlDetailLinkSelector),
            htmlCoverSelector = value.optString("htmlCoverSelector", DiscoverySourceConfig().htmlCoverSelector),
            htmlEpubLinkSelector = value.optString("htmlEpubLinkSelector", DiscoverySourceConfig().htmlEpubLinkSelector),
            htmlLatestUrl = value.optString("htmlLatestUrl"),
            htmlPopularUrl = value.optString("htmlPopularUrl"),
            renderJavaScript = value.optBoolean("renderJavaScript", false),
            excludeNonCatalogRegions = if (value.has("excludeNonCatalogRegions") && !value.isNull("excludeNonCatalogRegions"))
                value.optBoolean("excludeNonCatalogRegions") else null,
            purpose = value.optString("purpose", DiscoverySourcePurpose.DOWNLOAD_EPUB)
                .takeIf(DiscoverySourcePurpose::isValid) ?: DiscoverySourcePurpose.DOWNLOAD_EPUB,
            scrapeOptions = runCatching {
                value.optJSONObject("scrapeOptions")?.let { WebNovelScrapeOptionsJson.decode(it.toString()) }
            }.getOrNull() ?: WebNovelScrapeOptions(),
        )
    }.getOrDefault(DiscoverySourceConfig())
}

object DiscoveryCatalogCache {
    fun key(source: DiscoverySourceEntity, query: String, mode: String, pageUrl: String?): String {
        val raw = listOf("catalog-parser-v2", source.id, source.kind, source.baseUrl, source.configJson, query.trim(), mode, pageUrl.orEmpty())
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun encode(page: DiscoveryPage, maxBytes: Int = 256 * 1024): String? {
        val books = org.json.JSONArray()
        page.books.take(24).filter { book ->
            book.id.length <= 2048 && book.title.length <= 512 && book.author.length <= 512 &&
                book.detailsUrl.length <= 4096 && validHttpUrl(book.detailsUrl) &&
                (book.coverUrl == null || (book.coverUrl.length <= 4096 && validHttpUrl(book.coverUrl))) &&
                (book.epubUrl == null || (book.epubUrl.length <= 4096 && validHttpUrl(book.epubUrl)))
        }.forEach { book -> books.put(org.json.JSONObject()
            .put("id", book.id).put("title", book.title).put("author", book.author)
            .put("detailsUrl", book.detailsUrl).put("coverUrl", book.coverUrl).put("epubUrl", book.epubUrl)) }
        val links = org.json.JSONArray()
        page.collections.take(24).filter { it.title.length <= 512 && it.url.length <= 4096 && validHttpUrl(it.url) }
            .forEach { link -> links.put(org.json.JSONObject().put("title", link.title).put("url", link.url)) }
        val json = org.json.JSONObject().put("books", books).put("collections", links)
            .put("nextUrl", page.nextUrl).toString()
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
    }

    fun decode(json: String): DiscoveryPage? = runCatching {
        val root = org.json.JSONObject(json)
        val books = root.optJSONArray("books") ?: org.json.JSONArray()
        val collections = root.optJSONArray("collections") ?: org.json.JSONArray()
        val nextUrl = root.optString("nextUrl").takeIf(String::isNotBlank)
        require(nextUrl == null || validHttpUrl(nextUrl))
        DiscoveryPage(
            books = List(books.length().coerceAtMost(24)) { index -> books.getJSONObject(index).let { value ->
                val detailsUrl = value.getString("detailsUrl")
                require(validHttpUrl(detailsUrl))
                val coverUrl = value.optString("coverUrl").takeIf(String::isNotBlank)
                val epubUrl = value.optString("epubUrl").takeIf(String::isNotBlank)
                require(coverUrl == null || validHttpUrl(coverUrl))
                require(epubUrl == null || validHttpUrl(epubUrl))
                DiscoveryBook(value.getString("id"), value.getString("title"), value.optString("author"),
                    detailsUrl, coverUrl, epubUrl)
            } },
            collections = List(collections.length().coerceAtMost(24)) { index -> collections.getJSONObject(index).let { value ->
                val url = value.getString("url")
                require(validHttpUrl(url))
                DiscoveryLink(value.getString("title"), url)
            } },
            nextUrl = nextUrl,
        )
    }.getOrNull()

    private fun validHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}

/** Public catalog adapters; no credentials are stored or sent, and access controls are not bypassed. */
object DiscoveryCatalogClient {
    const val MODE_BROWSE = "browse"
    const val MODE_LATEST = "latest"
    const val MODE_POPULAR = "popular"
    private const val MAX_CATALOG_BYTES = 1_000_000
    private const val MAX_EPUB_BYTES = 80L * 1024 * 1024
    private const val USER_AGENT = "VibeReader/1.1 (Android EPUB reader)"

    internal suspend fun htmlSampleForAi(
        url: String,
        renderJavaScript: Boolean,
        renderedContext: Context?,
        currentConfig: DiscoverySourceConfig,
    ): DiscoveryHtmlAiSample = withContext(Dispatchers.IO) {
        val document: Document
        val finalUrl: String
        if (renderJavaScript) {
            val context = renderedContext ?: error("Open the source editor to render this public listing.")
            val rendered = RenderedNovelPage(context, MAX_CATALOG_BYTES).load(validateHttpUrl(url)) { page ->
                validateRenderedDocument(page)
                page.body()?.text()?.isNotBlank() == true
            }
            finalUrl = validateRenderedDocument(rendered)
            document = rendered
        } else {
            val response = fetchHtmlDocument(url, renderedContext)
            require(!looksLikeOpds(response.document, response.contentType)) {
                "AI book-list selector setup is for HTML sources. OPDS catalogs do not need CSS selectors."
            }
            document = response.document
            finalUrl = response.finalUrl
        }
        requirePublicCatalogDocument(document)
        require(!looksLikeOpds(document, "")) {
            "AI book-list selector setup is for HTML sources. OPDS catalogs do not need CSS selectors."
        }
        val sampleConfig = novelPingLayout(finalUrl)?.let { novelPingConfig(document, finalUrl, currentConfig, it) }
            ?: if (usesDefaultHtmlSelectors(currentConfig)) detectHtmlSource(document, finalUrl).config else currentConfig
        val sample = sanitizedCatalogSample(document, sampleConfig)
        require(sample.isNotBlank()) { "This page has no public HTML to configure." }
        DiscoveryHtmlAiSample(finalUrl, sample)
    }

    internal suspend fun fetchPublicDocument(
        url: String,
        renderJavaScript: Boolean = false,
        renderedContext: Context? = null,
        ready: (Document) -> Boolean = { it.body()?.text()?.isNotBlank() == true },
    ): DiscoveryFetchedDocument {
        if (!renderJavaScript) {
            val fetched = fetchHtmlDocument(url, renderedContext)
            requirePublicCatalogDocument(fetched.document)
            return fetched
        }
        val context = renderedContext ?: error("Open this source in Discover to render its public page.")
        val rendered = RenderedNovelPage(context, MAX_CATALOG_BYTES).load(validateHttpUrl(url)) { document ->
            validateRenderedDocument(document)
            requirePublicCatalogDocument(document)
            ready(document)
        }
        val finalUrl = validateRenderedDocument(rendered)
        requirePublicCatalogDocument(rendered)
        return DiscoveryFetchedDocument(rendered, finalUrl, "text/html")
    }

    internal fun validateAiDiscoveryConfig(
        finalUrl: String,
        sampleHtml: String,
        config: DiscoverySourceConfig,
    ) {
        require(config.htmlBookSelector.isNotBlank() && config.htmlTitleSelector.isNotBlank() &&
            config.htmlDetailLinkSelector.isNotBlank() && config.htmlBookSelector.length <= 1_000 &&
            config.htmlTitleSelector.length <= 1_000 && config.htmlDetailLinkSelector.length <= 1_000 &&
            config.htmlAuthorSelector.length <= 1_000 && config.htmlCoverSelector.length <= 1_000 &&
            config.htmlEpubLinkSelector.length <= 1_000) {
            "The suggested book-list selectors are missing or exceed the size limit."
        }
        if (config.searchUrlTemplate.isNotBlank()) {
            val expanded = expandSearchTemplate(config.searchUrlTemplate, "catalog-validation")
            require(sameOrigin(finalUrl, expanded)) { "The search URL must stay on the same website as the source." }
        }
        configuredModeUrl(finalUrl, config.htmlLatestUrl, "Latest")
        configuredModeUrl(finalUrl, config.htmlPopularUrl, "Popular")
        val document = Jsoup.parse(sampleHtml, finalUrl)
        listOf(config.htmlBookSelector, config.htmlTitleSelector, config.htmlAuthorSelector,
            config.htmlDetailLinkSelector, config.htmlCoverSelector, config.htmlEpubLinkSelector)
            .filter(String::isNotBlank).forEach { selector ->
                try {
                    document.select(selector)
                } catch (failure: Exception) {
                    throw IllegalArgumentException("The suggested CSS selector is invalid: ${failure.message}")
                }
            }
        val parsed = parseHtmlList(sampleHtml, finalUrl, config)
        require(parsed.books.isNotEmpty()) {
            "The suggested selectors did not find a book card in the sample. Review them or enter selectors manually."
        }
    }

    suspend fun detectSource(url: String, name: String = "", renderedContext: Context? = null,
        renderJavaScript: Boolean = false): DetectedDiscoverySource = withContext(Dispatchers.IO) {
        val response = fetchHtmlDocument(url, renderedContext)
        val finalUrl = response.finalUrl
        val originName = name.trim().takeIf(String::isNotBlank) ?: URI(finalUrl).host.orEmpty()
        require(!isUnsupportedOpdsJson(response.contentType)) {
            "OPDS JSON feeds are not supported yet. Use an Atom/OPDS catalog URL or an HTML catalog page."
        }
        when {
            isStandardEbooksHost(URI(finalUrl).host.orEmpty()) ->
                DetectedDiscoverySource(DiscoverySourceKind.STANDARD_EBOOKS,
                    "https://standardebooks.org/ebooks", "Standard Ebooks", DiscoverySourceConfig())
            looksLikeOpds(response.document, response.contentType) ->
                DetectedDiscoverySource(DiscoverySourceKind.OPDS, finalUrl, originName,
                    DiscoverySourceConfig(searchUrlTemplate = findSearchTemplate(response.document, finalUrl).orEmpty()))
            else -> {
                var detected = detectHtmlSource(response.document, finalUrl)
                if (renderJavaScript && detected.previewTitles.isEmpty()) {
                    val context = renderedContext ?: error("Open Detect source from Discover to render this public listing.")
                    val rendered = RenderedNovelPage(context, MAX_CATALOG_BYTES).load(finalUrl) { document ->
                        val page = detectHtmlSource(document, validateRenderedDocument(document))
                        if (page.previewTitles.isNotEmpty()) detected = page
                        page.previewTitles.isNotEmpty()
                    }
                    validateRenderedDocument(rendered)
                }
                DetectedDiscoverySource(DiscoverySourceKind.HTML, finalUrl, originName,
                    detected.config.copy(renderJavaScript = renderJavaScript), detected.previewTitles)
            }
        }
    }

    suspend fun search(source: DiscoverySourceEntity, query: String, mode: String = MODE_BROWSE,
        pageUrl: String? = null, renderedContext: Context? = null): DiscoveryPage = withContext(Dispatchers.IO) {
        val config = DiscoverySourceConfigCodec.decode(source.configJson)
        when (source.kind) {
            DiscoverySourceKind.STANDARD_EBOOKS -> searchStandardEbooks(source, query, mode, pageUrl)
            DiscoverySourceKind.GUTENBERG -> searchGutenberg(source, query, pageUrl)
            DiscoverySourceKind.OPDS -> searchOpds(source, config, query, pageUrl)
            DiscoverySourceKind.HTML -> searchHtml(source, config, query, mode, pageUrl, renderedContext)
            else -> error("This discovery source type is not supported.")
        }
    }

    suspend fun downloadEpub(source: DiscoverySourceEntity, book: DiscoveryBook, destination: File,
        renderedContext: Context? = null) = withContext(Dispatchers.IO) {
        val downloadUrl = book.epubUrl ?: when (source.kind) {
            DiscoverySourceKind.STANDARD_EBOOKS -> standardEpubUrl(book.detailsUrl)
            DiscoverySourceKind.HTML -> htmlEpubUrl(book.detailsUrl, DiscoverySourceConfigCodec.decode(source.configJson), renderedContext)
            else -> error("This catalog entry has no EPUB download link.")
        }
        streamEpub(downloadUrl, destination)
    }

    private suspend fun searchStandardEbooks(source: DiscoverySourceEntity, query: String, mode: String,
        pageUrl: String?): DiscoveryPage {
        val url = pageUrl ?: Uri.parse(source.baseUrl).buildUpon()
            .appendQueryParameter("sort", if (mode == MODE_POPULAR) "popularity" else "default")
            .appendQueryParameter("per-page", "24")
            .apply { if (query.isNotBlank()) appendQueryParameter("query", query.trim()) }
            .build().toString()
        val response = fetchDocument(url)
        return parseStandardHtml(response.document.outerHtml(), response.finalUrl)
    }

    internal fun parseStandardHtml(html: String, finalUrl: String): DiscoveryPage {
        val document = Jsoup.parse(html, finalUrl)
        val cards = document.select("ol.ebooks-list > li").filter { card ->
            card.attr("typeof").split(Regex("\\s+")).contains("schema:Book")
        }.take(48)
        val books = cards.mapNotNull { card ->
            val detail = card.attr("about").ifBlank {
                card.selectFirst("a[property='schema:url']")?.attr("href").orEmpty()
            }
            val detailUrl = safeResolve(finalUrl, detail) ?: return@mapNotNull null
            val title = card.selectFirst("p > a[property='schema:url'] > span[property='schema:name']")?.text()?.trim()
                .orEmpty().ifBlank { card.selectFirst("[property='schema:name']")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val author = card.selectFirst("p.author [property='schema:name']")?.text()?.trim().orEmpty()
            val coverRaw = card.selectFirst("img[property='schema:image']")?.attr("src").orEmpty()
            DiscoveryBook(detailUrl, title.take(512), author.take(512), detailUrl,
                safeResolve(finalUrl, coverRaw), null)
        }.distinctBy { it.detailsUrl }.take(24)
        val next = document.selectFirst("a[rel~=next][href]")?.attr("href")
            ?.let { safeResolve(finalUrl, it) }
        return DiscoveryPage(books, nextUrl = next)
    }

    private suspend fun searchGutenberg(source: DiscoverySourceEntity, query: String, pageUrl: String?): DiscoveryPage {
        val url = pageUrl ?: Uri.parse(source.baseUrl).buildUpon().apply {
            if (query.isNotBlank()) appendQueryParameter("query", query.trim())
        }.build().toString()
        return parseOpds(fetchDocument(url))
    }

    private suspend fun searchOpds(source: DiscoverySourceEntity, config: DiscoverySourceConfig, query: String,
        pageUrl: String?): DiscoveryPage {
        val url = pageUrl ?: if (query.isBlank()) source.baseUrl else {
            require(config.searchUrlTemplate.isNotBlank()) {
                "This OPDS source does not advertise search. Add a search URL template in source settings."
            }
            expandSearchTemplate(config.searchUrlTemplate, query)
        }
        return parseOpds(fetchDocument(url))
    }

    private suspend fun searchHtml(source: DiscoverySourceEntity, config: DiscoverySourceConfig, query: String,
        mode: String, pageUrl: String?, renderedContext: Context?): DiscoveryPage {
        val modeTarget = when (mode) {
            MODE_LATEST -> configuredModeUrl(source.baseUrl, config.htmlLatestUrl, "Latest")
            MODE_POPULAR -> configuredModeUrl(source.baseUrl, config.htmlPopularUrl, "Popular")
            else -> null
        }
        val url = pageUrl ?: if (query.isNotBlank() && config.searchUrlTemplate.isNotBlank())
            expandSearchTemplate(config.searchUrlTemplate, query) else modeTarget ?: source.baseUrl
        if (!config.renderJavaScript) {
            val response = fetchHtmlDocument(url, renderedContext)
            return parseHtmlWithAutoDetection(response.document, response.finalUrl, config, query)
        }
        val context = renderedContext ?: error("Enable the rendered listing in a source opened from Discover.")
        var lastScanAt = 0L
        var loadedPage: DiscoveryPage? = null
        var detectedConfig: DiscoverySourceConfig? = null
        val rendered = RenderedNovelPage(context, MAX_CATALOG_BYTES).load(validateHttpUrl(url)) { document ->
            val now = System.currentTimeMillis()
            if (now - lastScanAt < 750L) return@load false
            lastScanAt = now
            val finalUrl = validateRenderedDocument(document)
            val currentConfig = novelPingLayout(finalUrl)?.let { novelPingConfig(document, finalUrl, config, it) }
                ?: if (usesDefaultHtmlSelectors(config)) detectHtmlSource(document, finalUrl).config else config
            val activeConfig = mergeDetectedHtmlConfig(config, currentConfig).copy(renderJavaScript = true)
            val page = runCatching { parseHtmlWithAutoDetection(document, finalUrl, activeConfig, query) }
                .getOrElse { failure -> if (!usesDefaultHtmlSelectors(config)) throw failure else null }
            if (page == null || page.books.isEmpty()) false else {
                loadedPage = page
                if (currentConfig != config) detectedConfig = activeConfig
                true
            }
        }
        validateRenderedDocument(rendered)
        val page = loadedPage ?: parseHtmlWithAutoDetection(rendered, rendered.baseUri(), config, query)
        return page.copy(detectedConfig = detectedConfig ?: page.detectedConfig)
    }

    internal fun parseHtmlList(html: String, finalUrl: String, config: DiscoverySourceConfig,
        query: String = ""): DiscoveryPage {
        val document = Jsoup.parse(html, finalUrl)
        requirePublicCatalogDocument(document)
        require(config.htmlBookSelector.isNotBlank() && config.htmlTitleSelector.isNotBlank() &&
            config.htmlDetailLinkSelector.isNotBlank()) {
            "Configure book-card, title, and details-link selectors for this source."
        }
        val searchResultsRoot = if (query.isNotBlank())
            document.selectFirst("[data-search-results], #search-results") else null
        val catalogRoot = searchResultsRoot ?: document
        val matchingCards = try { catalogRoot.select(config.htmlBookSelector) } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid book-list CSS selector: ${failure.message}")
        }
        val cards = if (config.excludeNonCatalogRegions == true)
            matchingCards.filterNot(::insideExcludedCatalogRegion).take(48) else matchingCards.take(48)
        val nextUrl = nextPageLink(document)?.attr("href")?.let { safeResolve(finalUrl, it) }
        if (cards.isEmpty() && searchResultsRoot != null) return DiscoveryPage(emptyList(), nextUrl = nextUrl)
        require(cards.isNotEmpty()) {
            "No configured book cards matched. Edit this source's selectors or choose a public listing page."
        }
        val results = cards.mapNotNull { card ->
            val titleElement = selectFirst(card, config.htmlTitleSelector) ?: return@mapNotNull null
            val title = titleElement.text().trim().take(512)
            if (title.isBlank()) return@mapNotNull null
            val detailElement = selectFirst(card, config.htmlDetailLinkSelector)
            val detailUrl = safeResolve(finalUrl, detailElement?.attr("href").orEmpty()) ?: return@mapNotNull null
            val author = selectFirst(card, config.htmlAuthorSelector)?.text()?.trim().orEmpty().take(512)
            val coverUrl = selectFirst(card, config.htmlCoverSelector)?.let { element -> imageUrl(element, finalUrl) }
            val epubUrl = selectFirst(card, config.htmlEpubLinkSelector)?.attr("href")
                ?.let { safeResolve(finalUrl, it) }
            DiscoveryBook(detailUrl, title, author, detailUrl, coverUrl, epubUrl)
        }.distinctBy { it.detailsUrl }
        val filtered = if (query.isBlank() || config.searchUrlTemplate.isNotBlank()) results else results.filter {
            it.title.contains(query, true) || it.author.contains(query, true)
        }
        return DiscoveryPage(filtered.take(24), nextUrl = nextUrl)
    }

    internal fun parseHtmlWithAutoDetection(document: Document, finalUrl: String, config: DiscoverySourceConfig,
        query: String): DiscoveryPage {
        parseNovelPingListing(document, finalUrl, config, query)?.let { return it }
        val initial = runCatching { parseHtmlList(document.outerHtml(), finalUrl, config, query) }.getOrNull()
        val hasSearchResultsScope = query.isNotBlank() &&
            document.selectFirst("[data-search-results], #search-results") != null
        if (hasSearchResultsScope) {
            if (initial?.books?.isNotEmpty() == true || !usesDefaultHtmlSelectors(config)) return initial
                ?: parseHtmlList(document.outerHtml(), finalUrl, config, query)
            val resultsRoot = requireNotNull(document.selectFirst("[data-search-results], #search-results"))
            val scopedDetection = detectHtmlSource(Jsoup.parse(resultsRoot.outerHtml(), finalUrl), finalUrl)
            if (scopedDetection.previewTitles.isEmpty()) return initial
                ?: DiscoveryPage(emptyList(), nextUrl = nextPageLink(document)?.attr("href")
                    ?.let { safeResolve(finalUrl, it) })
            val activeConfig = mergeDetectedHtmlConfig(config, scopedDetection.config)
            return parseHtmlList(document.outerHtml(), finalUrl, activeConfig, query)
                .copy(detectedConfig = activeConfig)
        }
        if (initial?.books?.isNotEmpty() == true || !usesDefaultHtmlSelectors(config)) return initial ?:
            parseHtmlList(document.outerHtml(), finalUrl, config, query)
        val detected = detectHtmlSource(document, finalUrl)
        if (detected.previewTitles.isEmpty()) return initial ?:
            parseHtmlList(document.outerHtml(), finalUrl, config, query)
        val activeConfig = mergeDetectedHtmlConfig(config, detected.config).copy(renderJavaScript = config.renderJavaScript)
        return parseHtmlList(document.outerHtml(), finalUrl, activeConfig, query).copy(detectedConfig = activeConfig)
    }

    private data class NovelPingLayout(
        val scopeSelector: String,
        val bookSelector: String,
        val titleSelector: String,
        val authorSelector: String,
        val detailsSelector: String,
        val coverSelector: String,
        val isHome: Boolean = false,
    )

    private fun novelPingLayout(url: String): NovelPingLayout? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        if (host != "novelping.com" && !host.endsWith(".novelping.com")) return null
        when {
            uri.path.isNullOrBlank() || uri.path == "/" -> NovelPingLayout(
                scopeSelector = "#index-novel-hot .index-novel",
                bookSelector = ".item:has(.title h3)",
                titleSelector = ".title h3",
                authorSelector = "",
                detailsSelector = "a[href]",
                coverSelector = "img.item-img, picture source",
                isHome = true,
            )
            uri.path == "/search" -> NovelPingLayout(
                scopeSelector = "#search-results[data-search-results], #search-results",
                bookSelector = ".row:has(h3.novel-title a[href])",
                titleSelector = "h3.novel-title",
                authorSelector = "span.author",
                detailsSelector = "h3.novel-title a[href]",
                coverSelector = "img.cover, picture source",
            )
            uri.path.startsWith("/sort/") -> NovelPingLayout(
                scopeSelector = "#list-page .col-novel-main .list.list-novel",
                bookSelector = ".row:has(h3.novel-title a[href])",
                titleSelector = "h3.novel-title",
                authorSelector = "span.author",
                detailsSelector = "h3.novel-title a[href]",
                coverSelector = "img.cover, picture source",
            )
            else -> null
        }
    }.getOrNull()

    private fun novelPingConfig(
        document: Document,
        finalUrl: String,
        existing: DiscoverySourceConfig,
        layout: NovelPingLayout,
    ): DiscoverySourceConfig {
        val origin = runCatching {
            val uri = URI(finalUrl)
            "${uri.scheme}://${uri.rawAuthority}"
        }.getOrDefault("https://novelping.com")
        return existing.copy(
            htmlBookSelector = layout.bookSelector,
            htmlTitleSelector = layout.titleSelector,
            htmlAuthorSelector = layout.authorSelector,
            htmlDetailLinkSelector = layout.detailsSelector,
            htmlCoverSelector = layout.coverSelector,
            searchUrlTemplate = existing.searchUrlTemplate.ifBlank { "$origin/search?keyword={searchTerms}" },
            htmlLatestUrl = existing.htmlLatestUrl.ifBlank { "$origin/sort/updates" },
            htmlPopularUrl = existing.htmlPopularUrl.ifBlank { "$origin/sort/hot" },
            excludeNonCatalogRegions = true,
        )
    }

    private fun parseNovelPingListing(
        document: Document,
        finalUrl: String,
        config: DiscoverySourceConfig,
        query: String,
    ): DiscoveryPage? {
        val layout = novelPingLayout(finalUrl) ?: return null
        val root = document.selectFirst(layout.scopeSelector) ?: return null
        val current = runCatching {
            parseHtmlList(root.outerHtml(), finalUrl, config, query)
        }.getOrNull()?.filterNovelPingBooks()
        val preferredHasCovers = current?.books?.any { it.coverUrl != null } == true
        if (current != null && current.books.isNotEmpty() && (!layout.isHome || preferredHasCovers)) {
            return current.copy(nextUrl = nextPageLink(document)?.attr("href")?.let { safeResolve(finalUrl, it) })
        }

        val fallbackConfig = novelPingConfig(document, finalUrl, config, layout)
        val detected = runCatching {
            parseHtmlList(root.outerHtml(), finalUrl, fallbackConfig, query)
        }.getOrNull()?.filterNovelPingBooks()
        if (detected != null && detected.books.isNotEmpty()) {
            return detected.copy(
                nextUrl = nextPageLink(document)?.attr("href")?.let { safeResolve(finalUrl, it) },
            )
        }
        if (query.isNotBlank() && layout.scopeSelector.contains("search-results")) {
            return DiscoveryPage(emptyList(), nextUrl = nextPageLink(document)?.attr("href")
                ?.let { safeResolve(finalUrl, it) })
        }
        return current?.copy(nextUrl = nextPageLink(document)?.attr("href")?.let { safeResolve(finalUrl, it) })
            ?: DiscoveryPage(emptyList(), nextUrl = nextPageLink(document)?.attr("href")?.let { safeResolve(finalUrl, it) })
    }

    private fun DiscoveryPage.filterNovelPingBooks(): DiscoveryPage = copy(
        books = books.filter { book ->
            runCatching {
                val uri = URI(book.detailsUrl)
                uri.host.equals("novelping.com", true) || uri.host.endsWith(".novelping.com", true)
            }.getOrDefault(false) && runCatching {
                URI(book.detailsUrl).path.matches(Regex("/book/[^/]+/?"))
            }.getOrDefault(false)
        },
    )

    private fun parseNovelPingScope(
        root: Element,
        document: Document,
        finalUrl: String,
        config: DiscoverySourceConfig,
        layout: NovelPingLayout,
        query: String,
    ): DiscoveryPage = parseHtmlList(root.outerHtml(), finalUrl, config, query).filterNovelPingBooks().copy(
        nextUrl = nextPageLink(document)?.attr("href")?.let { safeResolve(finalUrl, it) },
    )

    private fun srcsetEntries(value: String): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index].isWhitespace() || value[index] == ',')) index++
            if (index >= value.length) break
            val start = index
            while (index < value.length && !value[index].isWhitespace()) index++
            val token = value.substring(start, index)
            val endedWithComma = token.endsWith(',')
            val url = token.trimEnd(',')
            var descriptor = ""
            if (!endedWithComma) {
                while (index < value.length && value[index].isWhitespace()) index++
                val descriptorStart = index
                while (index < value.length && value[index] != ',') index++
                descriptor = value.substring(descriptorStart, index).trim()
            }
            if (url.isNotBlank() && !url.startsWith("data:", true) && !url.startsWith("blob:", true)) {
                candidates += url to descriptor
            }
            if (!endedWithComma && index < value.length && value[index] == ',') index++
        }
        return candidates
    }

    private fun sanitizeSrcset(value: String): String = srcsetEntries(value).joinToString(", ") { (candidate, descriptor) ->
        val path = runCatching { URI(candidate).rawPath }.getOrNull()
            ?.takeIf(String::isNotBlank)?.take(240) ?: "/"
        if (descriptor.isBlank()) path else "$path ${descriptor.take(24)}"
    }

    private fun mergeDetectedHtmlConfig(existing: DiscoverySourceConfig, detected: DiscoverySourceConfig): DiscoverySourceConfig =
        existing.copy(
            htmlBookSelector = detected.htmlBookSelector,
            htmlTitleSelector = detected.htmlTitleSelector,
            htmlAuthorSelector = detected.htmlAuthorSelector,
            htmlDetailLinkSelector = detected.htmlDetailLinkSelector,
            htmlCoverSelector = detected.htmlCoverSelector,
            excludeNonCatalogRegions = existing.excludeNonCatalogRegions ?: detected.excludeNonCatalogRegions,
            searchUrlTemplate = existing.searchUrlTemplate.ifBlank { detected.searchUrlTemplate },
            htmlLatestUrl = existing.htmlLatestUrl.ifBlank { detected.htmlLatestUrl },
            htmlPopularUrl = existing.htmlPopularUrl.ifBlank { detected.htmlPopularUrl },
        )

    private fun usesDefaultHtmlSelectors(config: DiscoverySourceConfig): Boolean {
        val defaults = DiscoverySourceConfig()
        return config.htmlBookSelector == defaults.htmlBookSelector && config.htmlTitleSelector == defaults.htmlTitleSelector &&
            config.htmlDetailLinkSelector == defaults.htmlDetailLinkSelector && config.htmlAuthorSelector == defaults.htmlAuthorSelector &&
            config.htmlCoverSelector == defaults.htmlCoverSelector && config.htmlEpubLinkSelector == defaults.htmlEpubLinkSelector
    }

    internal fun validateRenderedDocument(document: Document): String {
        requirePublicCatalogDocument(document)
        val finalUrl = validateHttpUrl(document.baseUri())
        require(document.outerHtml().toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES) {
            "Rendered listing exceeds the 1 MiB size limit."
        }
        return finalUrl
    }

    internal fun detectHtmlSource(document: Document, finalUrl: String): HtmlSourceDetection {
        requirePublicCatalogDocument(document)
        novelPingLayout(finalUrl)?.let { layout ->
            val root = document.selectFirst(layout.scopeSelector)
            if (root != null) {
                val config = novelPingConfig(document, finalUrl, DiscoverySourceConfig(), layout)
                val preview = runCatching {
                    parseNovelPingScope(root, document, finalUrl, config, layout, "").books.take(4).map { it.title }
                }.getOrDefault(emptyList())
                if (preview.isNotEmpty()) return HtmlSourceDetection(config, preview)
            }
        }
        val defaults = DiscoverySourceConfig()
        val candidates = linkedMapOf<String, MutableList<Element>>()
        val scanned = document.getAllElements().take(20_000)
        if (document.getAllElements().size > 20_000) return HtmlSourceDetection(defaults, emptyList())
        val depths = HashMap<Element, Int>(scanned.size)
        val subtreeSizes = HashMap<Element, Int>(scanned.size)
        scanned.forEach { element -> depths[element] = (depths[element.parent()] ?: -1) + 1 }
        scanned.asReversed().forEach { element ->
            subtreeSizes[element] = (1 + element.children().sumOf { subtreeSizes[it] ?: 1 }).coerceAtMost(121)
        }
        for (element in scanned) {
            val tag = element.normalName().lowercase()
            if (tag !in setOf("article", "li", "div", "section", "tr") ||
                (depths[element] ?: 33) > 32 || (subtreeSizes[element] ?: 121) > 120 || insideExcludedCatalogRegion(element)) continue
            val classes = element.classNames().filter(::isSafeCssIdentifier).take(2)
            val semanticBook = element.attr("typeof").contains("schema:Book", true) ||
                element.attr("itemtype").contains("schema.org/book", true)
            if (classes.isEmpty() && tag !in setOf("article", "li") && !semanticBook) continue
            val selector = when {
                element.attr("typeof").contains("schema:Book", true) -> tag + "[typeof~=schema:Book]"
                element.attr("itemtype").contains("schema.org/book", true) -> tag + "[itemtype]"
                classes.isNotEmpty() -> tag + classes.joinToString("") { "." + it }
                else -> tag
            }
            if (!candidates.containsKey(selector) && candidates.size >= 64) continue
            val links = element.select("a[href]").take(20).filter { anchor ->
                anchor.text().trim().length in 2..180 &&
                    !anchor.attr("href").substringBefore("?").endsWith(".epub", true) &&
                    safeResolve(finalUrl, anchor.attr("href")) != null
            }
            if (links.isEmpty() || element.text().trim().length !in 2..3_000) continue
            val group = candidates.getOrPut(selector) { mutableListOf() }
            if (group.size < 48) group.add(element)
        }
        val best = candidates.entries.mapNotNull { (selector, elements) ->
            val cards = elements.distinctBy { it.attr("about").ifBlank { preferredBookLink(it, finalUrl)?.attr("href").orEmpty() } }
            val semantic = selector.contains("[typeof~=") || selector.contains("[itemtype]")
            val uniqueLinks = cards.mapNotNull { preferredBookLink(it, finalUrl)?.let { link -> safeResolve(finalUrl, link.attr("href")) } }.distinct()
            if ((!semantic && cards.size < 2) || uniqueLinks.isEmpty() || (!semantic && uniqueLinks.size < 2)) return@mapNotNull null
            val score = cards.size.coerceAtMost(24) * 10 +
                (if (Regex("book|ebook|novel|volume|catalog|search[- ]?result|listing", RegexOption.IGNORE_CASE).containsMatchIn(selector)) 120 else 0) +
                cards.count { it.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-image], img[data-cover], img[srcset], img[data-srcset], picture source[srcset], picture source[data-srcset]") != null } * 2 +
                cards.count { it.selectFirst("h1, h2, h3, h4, [itemprop=name]") != null } * 3
            Triple(selector, cards.take(24), score)
        }.maxByOrNull { it.third }
        var config = defaults.copy(
            searchUrlTemplate = findHtmlSearchTemplate(document, finalUrl).orEmpty(),
            htmlLatestUrl = findHtmlModeUrl(document, finalUrl, Regex("latest|new[- ]?releases?|recent(ly)? added", RegexOption.IGNORE_CASE)).orEmpty(),
            htmlPopularUrl = findHtmlModeUrl(document, finalUrl, Regex("popular|hot|best[- ]?seller|trending|top[- ]rated", RegexOption.IGNORE_CASE)).orEmpty(),
            excludeNonCatalogRegions = true,
        )
        val chosen = best
        if (chosen != null) {
            val cards = chosen.second
            val first = cards.first()
            val titleElement = first.select("h1, h2, h3, h4, h5, h6, [itemprop=name]")
                .firstOrNull { it.text().trim().length in 2..512 }
                ?: preferredBookLink(first, finalUrl)
            val titleLink = titleElement?.let(::enclosingBookLink) ?: preferredBookLink(first, finalUrl)
            val titleSelector = titleElement?.let(::elementCssSelector).orEmpty()
            val titleIsInsideLink = titleElement != null && titleLink != null &&
                titleElement.parents().any { it === titleLink }
            val linkSelector = when {
                titleLink == null -> ""
                titleIsInsideLink || titleElement === titleLink -> elementCssSelector(titleLink)
                titleElement != null -> titleSelector + " " + elementCssSelector(titleLink)
                else -> elementCssSelector(titleLink)
            }
            val author = first.getAllElements().firstOrNull { item ->
                item.attr("itemprop").contains("author", true) || item.classNames().any { it.contains("author", true) ||
                    it.contains("byline", true) || it.contains("writer", true) }
            }?.let(::elementCssSelector).orEmpty()
            val cover = first.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-image], img[data-cover], img[srcset], img[data-srcset], picture source[srcset], picture source[data-srcset]")
                ?.let(::elementCssSelector).orEmpty()
            if (titleSelector.isNotBlank() && linkSelector.isNotBlank()) {
                config = config.copy(htmlBookSelector = chosen.first, htmlTitleSelector = titleSelector,
                    htmlAuthorSelector = author, htmlDetailLinkSelector = linkSelector,
                    htmlCoverSelector = cover.ifBlank { defaults.htmlCoverSelector })
            }
        }
        val preview = if (chosen == null) emptyList() else runCatching {
            parseHtmlList(document.outerHtml(), finalUrl, config).books.take(4).map { it.title }
        }.getOrDefault(emptyList())
        return HtmlSourceDetection(config, preview)
    }

    private fun preferredBookLink(card: Element, baseUrl: String): Element? {
        val headingLink = card.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href]").firstOrNull {
            it.text().trim().length in 2..180 && safeResolve(baseUrl, it.attr("href")) != null
        }
        return headingLink ?: card.select("a[href]").firstOrNull { link ->
            link.text().trim().length in 2..180 && !link.attr("href").substringBefore("?").endsWith(".epub", true) &&
                safeResolve(baseUrl, link.attr("href")) != null
        }
    }

    private fun enclosingBookLink(element: Element): Element? {
        var current: Element? = element
        while (current != null) {
            if (current.normalName().equals("a", true) && current.hasAttr("href")) return current
            current = current.parent()
        }
        return null
    }

    private fun elementCssSelector(element: Element): String {
        val classes = element.classNames().filter(::isSafeCssIdentifier).take(2)
        val tag = element.normalName().lowercase()
        val semanticItemProp = element.attr("itemprop").split(Regex("\\s+")).firstOrNull {
            it.equals("name", true) || it.equals("author", true) || it.equals("creator", true)
        }
        return tag + classes.joinToString("") { "." + it } +
            if (semanticItemProp != null) "[itemprop=" + semanticItemProp + "]" else ""
    }

    private fun isSafeCssIdentifier(value: String): Boolean = value.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))

    private fun insideExcludedCatalogRegion(element: Element): Boolean {
        var parent: Element? = element
        var depth = 0
        while (parent != null && depth++ < 32) {
            val labels = parent.classNames().joinToString(" ") + " " + parent.id() + " " + parent.attr("role")
            val role = parent.attr("role").lowercase()
            if (parent.normalName().lowercase() in setOf("nav", "header", "footer", "aside") ||
                role in setOf("navigation", "menu", "menubar", "pagination", "contentinfo", "complementary", "search") ||
                Regex("breadcrumb|pagination|pager|comment|social|share|menu", RegexOption.IGNORE_CASE).containsMatchIn(labels)) return true
            parent = parent.parent()
        }
        return parent != null
    }

    private fun requirePublicCatalogDocument(document: Document) {
        val title = document.title().take(200).lowercase()
        val body = document.body()?.text()?.take(4_000)?.lowercase().orEmpty()
        val challengeTitle = Regex("just a moment|checking your browser|attention required|access denied|security verification|verify you are human")
            .containsMatchIn(title)
        val challengeText = Regex("checking your browser|security verification|verify you are human|unusual traffic")
            .containsMatchIn(body)
        val challengeMarker = document.select("#challenge-form, .cf-challenge, form[action*=challenge]").isNotEmpty()
        val captchaWidget = document.select("iframe[src*=captcha], form [data-sitekey]").isNotEmpty()
        val challengeMarkup = challengeMarker || (captchaWidget && (challengeTitle || challengeText))
        val shortLoginTitle = title in setOf("sign in", "log in", "login", "authentication required", "sign in to continue")
        val signInInterstitial = document.select("input[type=password]").isNotEmpty() && shortLoginTitle && body.length < 1_000
        require(!(challengeMarkup || (challengeTitle && challengeText)) && !signInInterstitial) {
            "This page requires sign-in or a browser challenge. Only public listings are supported."
        }
    }

    internal fun imageUrl(element: Element, baseUrl: String): String? {
        val picture = if (element.normalName().equals("picture", true)) element
            else element.parents().firstOrNull { it.normalName().equals("picture", true) }
        val targets = buildList {
            if (picture != null) addAll(picture.select("source, img"))
            if (none { it === element }) add(element)
        }
        val directAttributes = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-cover", "data-lazy", "src")
        val srcsetAttributes = listOf("data-srcset", "srcset")
        for (target in targets) {
            for (attribute in directAttributes) {
                val raw = target.attr(attribute).trim()
                if (raw.isNotBlank()) safeResolve(baseUrl, raw)?.let { return it }
            }
            for (attribute in srcsetAttributes) {
                for ((candidate, _) in srcsetEntries(target.attr(attribute))) {
                    safeResolve(baseUrl, candidate)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findHtmlModeUrl(document: Document, baseUrl: String, pattern: Regex): String? =
        document.select("a[href]").take(300).firstNotNullOfOrNull { link ->
            if (!pattern.containsMatchIn(link.text().take(100) + " " + link.attr("href"))) null
            else safeResolve(baseUrl, link.attr("href"))?.takeIf { it != baseUrl && sameOrigin(baseUrl, it) }
        }

    internal fun configuredModeUrl(baseUrl: String, raw: String, label: String): String? {
        if (raw.isBlank()) return null
        val resolved = safeResolve(baseUrl, raw) ?: error("The configured $label URL is not a valid HTTP(S) URL.")
        require(sameOrigin(baseUrl, resolved)) { "$label must stay on the same website as the source." }
        return resolved
    }

    internal fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = URI(first)
        val b = URI(second)
        val aPort = if (a.port >= 0) a.port else if (a.scheme.equals("https", true)) 443 else 80
        val bPort = if (b.port >= 0) b.port else if (b.scheme.equals("https", true)) 443 else 80
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && aPort == bPort
    }.getOrDefault(false)

    private fun parseOpds(response: DiscoveryFetchedDocument): DiscoveryPage {
        require(!isUnsupportedOpdsJson(response.contentType)) {
            "This source serves OPDS JSON, which is not supported yet. Use an Atom/OPDS catalog URL."
        }
        return parseOpdsXml(response.document.outerHtml(), response.finalUrl)
    }

    private fun isUnsupportedOpdsJson(contentType: String): Boolean =
        contentType.contains("json", true) && (contentType.contains("opds", true) || contentType.contains("atom", true))

    internal fun parseOpdsXml(xml: String, finalUrl: String): DiscoveryPage {
        val safeBaseUrl = validateHttpUrl(finalUrl)
        val document = Jsoup.parse(xml, safeBaseUrl, Parser.xmlParser())
        val books = mutableListOf<DiscoveryBook>()
        val collections = mutableListOf<DiscoveryLink>()
        localElements(document, "entry").forEach { entry ->
            val title = localElements(entry, "title").firstOrNull()?.text()?.trim().orEmpty().take(512)
            val id = localElements(entry, "id").firstOrNull()?.text()?.trim().orEmpty()
            val author = localElements(entry, "author").firstOrNull()?.let { localElements(it, "name").firstOrNull() }
                ?.text()?.trim().orEmpty().take(512)
            if (title.isBlank()) return@forEach
            val links = localElements(entry, "link")
            val acquisition = links.firstOrNull { link ->
                link.attr("rel").contains("acquisition", true) &&
                    (link.attr("type").contains("epub", true) ||
                        link.attr("href").substringBefore('?').endsWith(".epub", true))
            }
            val cover = links.firstOrNull { link ->
                link.attr("rel").contains("image", true) || link.attr("rel").contains("thumbnail", true)
            }?.attr("href")?.let { safeResolve(safeBaseUrl, it) }
            val navigation = links.firstOrNull { link ->
                link.attr("rel").contains("subsection", true) ||
                    (link.attr("rel").contains("alternate", true) && link.attr("type").contains("atom", true))
            }
            if (acquisition != null) {
                val epub = safeResolve(safeBaseUrl, acquisition.attr("href")) ?: return@forEach
                val htmlDetails = links.firstOrNull { link ->
                    link.attr("rel").contains("alternate", true) &&
                        link.attr("type").substringBefore(';').trim().lowercase() in setOf("text/html", "application/xhtml+xml")
                }?.attr("href")?.let { safeResolve(safeBaseUrl, it) }
                books += DiscoveryBook(id.ifBlank { epub }, title, author, htmlDetails ?: epub, cover, epub)
            } else if (navigation != null) {
                val target = safeResolve(safeBaseUrl, navigation.attr("href")) ?: return@forEach
                collections += DiscoveryLink(title, target)
            }
        }
        localElements(document, "link").filter {
            localName(it.parent()) == "feed" && it.attr("rel").contains("subsection", true)
        }.forEach { link ->
            safeResolve(safeBaseUrl, link.attr("href"))?.let { target ->
                collections += DiscoveryLink(link.attr("title").ifBlank { "More books" }, target)
            }
        }
        val next = localElements(document, "link").firstOrNull {
            it.attr("rel").equals("next", true) && localName(it.parent()) == "feed"
        }
            ?.attr("href")?.let { safeResolve(safeBaseUrl, it) }
        return DiscoveryPage(books.distinctBy { it.id }.take(24), collections.distinctBy { it.url }.take(24), next)
    }

    private suspend fun standardEpubUrl(detailsUrl: String): String {
        val response = fetchDocument(detailsUrl)
        require(isStandardEbooksHost(URI(response.finalUrl).host.orEmpty())) {
            "The book details page left Standard Ebooks."
        }
        val candidate = response.document.getElementsByTag("a").firstOrNull { link ->
            val path = link.attr("href").lowercase().substringBefore('?').substringBefore('#')
            (link.attr("property").contains("contentUrl", true) || path.endsWith(".epub")) &&
                path.endsWith(".epub") && !path.endsWith(".kepub.epub") && !path.endsWith("_advanced.epub")
        }?.attr("href") ?: error("No public EPUB download was found on the Standard Ebooks page.")
        val absolute = safeResolve(response.finalUrl, candidate)
            ?: error("The EPUB link is not a safe HTTP(S) URL.")
        require(isStandardEbooksHost(URI(absolute).host.orEmpty())) { "The EPUB link left Standard Ebooks." }
        return standardEpubDownloadUrl(absolute)
    }

    internal fun standardEpubDownloadUrl(url: String): String {
        val uri = Uri.parse(url)
        val preservedParameters = uri.encodedQuery.orEmpty().takeIf(String::isNotEmpty)
            ?.split('&')
            ?.filterNot { parameter -> Uri.decode(parameter.substringBefore('=')) == "source" }
            .orEmpty()
        val query = (preservedParameters + "source=download").joinToString("&")
        return uri.buildUpon().encodedQuery(query).build().toString()
    }

    private suspend fun htmlEpubUrl(detailsUrl: String, config: DiscoverySourceConfig, renderedContext: Context?): String {
        val document = if (config.renderJavaScript) {
            val context = renderedContext ?: error("Enable the rendered listing in a source opened from Discover.")
            RenderedNovelPage(context, MAX_CATALOG_BYTES).load(validateHttpUrl(detailsUrl)) { page ->
                validateRenderedDocument(page)
                selectFirst(page, config.htmlEpubLinkSelector) != null || page.select("a[href]").any {
                    it.attr("href").substringBefore('?').endsWith(".epub", true)
                }
            }
        } else fetchHtmlDocument(detailsUrl, renderedContext).document
        val finalUrl = validateRenderedDocument(document)
        val link = selectFirst(document, config.htmlEpubLinkSelector)
            ?: document.getElementsByTag("a").firstOrNull {
                it.attr("href").substringBefore('?').endsWith(".epub", true)
            } ?: error("No EPUB link was found. Edit the EPUB-link selector for this website.")
        return safeResolve(finalUrl, link.attr("href"))
            ?: error("The EPUB link is not a safe HTTP(S) URL.")
    }

    private fun looksLikeOpds(document: Document, contentType: String): Boolean =
        contentType.contains("atom+xml", true) || contentType.contains("opds", true) ||
            localElements(document, "feed").isNotEmpty() || localElements(document, "entry").isNotEmpty() ||
            localElements(document, "link").any { it.attr("rel").contains("search", true) &&
                it.attr("type").contains("atom", true) }

    private fun findSearchTemplate(document: Document, baseUrl: String): String? =
        localElements(document, "link").firstOrNull { link ->
            link.attr("rel").contains("search", true) && link.attr("href").contains("{searchTerms}")
        }?.attr("href")?.let { safeResolveTemplate(baseUrl, it) }

    private fun findHtmlSearchTemplate(document: Document, baseUrl: String): String? {
        val link = localElements(document, "link").firstOrNull { it.attr("rel").contains("search", true) }
        if (link != null) {
            val href = link.attr("href")
            if (href.contains("{searchTerms}") || href.contains("{query}")) return safeResolveTemplate(baseUrl, href)
        }
        val form = document.getElementsByTag("form").firstOrNull {
            it.attr("method").ifBlank { "get" }.equals("get", true) &&
                it.getElementsByTag("input").any { input ->
                    isSearchFieldName(input.attr("name"))
                }
        } ?: return null
        val inputName = form.getElementsByTag("input").first {
            isSearchFieldName(it.attr("name"))
        }.attr("name")
        val action = safeResolve(baseUrl, form.attr("action").ifBlank { baseUrl }) ?: return null
        val encodedName = java.net.URLEncoder.encode(inputName, Charsets.UTF_8.name())
        val separator = if (action.contains("?")) "&" else "?"
        return "$action$separator$encodedName={searchTerms}"
    }

    private fun isSearchFieldName(name: String): Boolean =
        name.equals("q", true) || listOf("search", "query", "keyword", "term").any { name.contains(it, true) }

    internal fun sanitizedCatalogSample(document: Document, config: DiscoverySourceConfig): String {
        val sanitized = document.clone()
        val listingRoot = novelPingLayout(document.baseUri())?.let { sanitized.selectFirst(it.scopeSelector) } ?: sanitized
        val cards = runCatching { listingRoot.select(config.htmlBookSelector).take(8) }.getOrDefault(emptyList())
        val sample = if (cards.isNotEmpty())
            Jsoup.parseBodyFragment(cards.joinToString("\n") { it.outerHtml() }, document.baseUri())
        else sanitized
        sample.select("script, style, noscript, svg, iframe, object, embed, form, nav, footer, header, aside, video, audio, link, meta").remove()
        sample.select("*").forEach { element ->
            val allowed = setOf("class", "id", "role", "itemprop", "itemtype", "typeof", "property", "rel", "href", "src", "srcset", "title")
            element.attributes().asList().map { it.key }.forEach { key ->
                val safeDataAttribute = key.startsWith("data-") || key.startsWith("aria-")
                if (key !in allowed && !safeDataAttribute) {
                    element.removeAttr(key)
                } else if (key == "srcset" || key == "data-srcset") {
                    val sanitizedSet = sanitizeSrcset(element.attr(key))
                    if (sanitizedSet.isBlank()) element.removeAttr(key) else element.attr(key, sanitizedSet)
                } else if (key in setOf("href", "src", "data-src", "data-lazy-src", "data-original", "data-image", "data-cover", "data-lazy")) {
                    val raw = element.attr(key)
                    val path = if (raw.startsWith("data:", true) || raw.startsWith("blob:", true)) null
                        else runCatching { URI(raw).rawPath }.getOrNull()
                    if (path.isNullOrBlank()) element.removeAttr(key)
                    else element.attr(key, path.take(240))
                } else if (element.attr(key).length > 240) {
                    element.attr(key, element.attr(key).take(240))
                }
            }
        }
        return sample.body()?.html()?.take(12_000).orEmpty()
    }

    private fun nextPageLink(document: Document): Element? =
        document.selectFirst(".pagination li.next:not(.last) a[href]")
            ?: document.selectFirst("li.next:not(.last) a[href]")
            ?: document.selectFirst("a[rel~=next][href]")

    private suspend fun fetchDocument(url: String): DiscoveryFetchedDocument = withContext(Dispatchers.IO) {
        val safeUrl = validateHttpUrl(url)
        val response = Jsoup.connect(safeUrl)
            .userAgent(USER_AGENT)
            .timeout(15_000)
            .maxBodySize(MAX_CATALOG_BYTES + 1)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .execute()
        val finalUrl = validateHttpUrl(response.url().toExternalForm())
        when (response.statusCode()) {
            401, 403 -> error("This source requires sign-in or blocks catalog access. Authentication is not supported.")
            429 -> error("This source is rate-limiting requests. Wait before refreshing.")
            in 200..299 -> Unit
            else -> error("The source returned HTTP ${response.statusCode()}.")
        }
        require(response.bodyAsBytes().size <= MAX_CATALOG_BYTES) { "The source page exceeds the size limit." }
        val contentType = response.contentType().orEmpty()
        val parser = if (contentType.contains("xml", true) || contentType.contains("atom", true))
            Parser.xmlParser() else Parser.htmlParser()
        DiscoveryFetchedDocument(Jsoup.parse(response.body(), finalUrl, parser), finalUrl, contentType)
    }

    private suspend fun fetchHtmlDocument(url: String, fallbackContext: Context?): DiscoveryFetchedDocument {
        try {
            return fetchDocument(url)
        } catch (failure: Exception) {
            if (failure is CancellationException || !failure.isTlsFailure() || fallbackContext == null) throw failure
            try {
                val safeUrl = validateHttpUrl(url)
                val rendered = RenderedNovelPage(fallbackContext, MAX_CATALOG_BYTES).load(safeUrl) { document ->
                    requirePublicCatalogDocument(document)
                    document.body()?.text()?.isNotBlank() == true
                }
                val finalUrl = validateRenderedDocument(rendered)
                require(sameOrigin(safeUrl, finalUrl)) { "The source redirected to another website." }
                return DiscoveryFetchedDocument(rendered, finalUrl, "text/html")
            } catch (fallbackFailure: Exception) {
                if (fallbackFailure is CancellationException) throw fallbackFailure
                throw IllegalStateException(
                    "Java could not verify this site’s HTTPS certificate, and the secure browser fallback failed: " +
                        fallbackFailure.message.orEmpty(),
                    fallbackFailure,
                )
            }
        }
    }

    private fun Throwable.isTlsFailure(): Boolean {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this
        while (current != null && visited.add(current)) {
            if (current is SSLException || current is CertificateException || current is CertPathValidatorException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun validateHttpUrl(raw: String): String {
        require(raw.length <= 4_096) { "The source URL is too long." }
        val uri = runCatching { URI(raw.trim()).normalize() }
            .getOrElse { throw IllegalArgumentException("Enter a valid source URL.") }
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Only HTTP(S) source URLs are supported."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Source URLs must have a host and cannot contain credentials."
        }
        return uri.toString()
    }

    private fun safeResolve(baseUrl: String, value: String): String? {
        if (value.isBlank() || value.trim().startsWith("#")) return null
        return runCatching { validateHttpUrl(URI(baseUrl).resolve(value.trim()).normalize().toString()) }.getOrNull()
    }

    private fun safeResolveTemplate(baseUrl: String, value: String): String? = runCatching {
        val placeholder = "__SEARCH_TERMS__"
        URI(baseUrl).resolve(value.replace("{searchTerms}", placeholder).replace("{query}", placeholder))
            .toString().replace(placeholder, "{searchTerms}")
    }.getOrNull()

    internal fun expandSearchTemplate(template: String, query: String): String {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        return validateHttpUrl(template.replace("{searchTerms}", encoded).replace("{query}", encoded))
    }

    private fun selectFirst(root: Element, selector: String): Element? {
        if (selector.isBlank()) return null
        return try {
            root.selectFirst(selector)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid CSS selector '$selector': ${failure.message}")
        }
    }

    private fun localName(element: Element?): String =
        element?.tagName()?.substringAfterLast(':')?.lowercase().orEmpty()

    private fun localElements(root: Element, name: String): List<Element> =
        root.getAllElements().filter { localName(it) == name.lowercase() }

    private fun isStandardEbooksHost(host: String): Boolean =
        host.equals("standardebooks.org", true) || host.endsWith(".standardebooks.org", true)

    internal suspend fun streamEpub(url: String, destination: File) = withContext(Dispatchers.IO) {
        val safeUrl = validateHttpUrl(url)
        val connection = URL(safeUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            validateHttpUrl(connection.url.toExternalForm())
            when (status) {
                401, 403 -> error("This source requires sign-in or blocks EPUB downloads.")
                429 -> error("This source is rate-limiting downloads. Wait before trying again.")
                in 200..299 -> Unit
                else -> error("The EPUB download returned HTTP $status.")
            }
            require(connection.contentLengthLong <= MAX_EPUB_BYTES) { "This EPUB exceeds the 80 MiB download limit." }
            destination.outputStream().buffered().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_EPUB_BYTES) { "This EPUB exceeds the 80 MiB download limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(destination.isFile && destination.length() > 0L) { "The EPUB download is empty." }
            validateEpub(destination)
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    internal fun validateEpub(file: File) {
        ZipFile(file).use { zip ->
            val mime = zip.getEntry("mimetype") ?: error("The download is not a valid EPUB file.")
            val mimeBytes = ByteArray(65)
            val mimeSize = zip.getInputStream(mime).use { input ->
                var total = 0
                while (total < mimeBytes.size) {
                    val count = input.read(mimeBytes, total, mimeBytes.size - total)
                    if (count < 0) break
                    total += count
                }
                total
            }
            val mimeType = if (mimeSize in 1..64) String(mimeBytes, 0, mimeSize, Charsets.US_ASCII) else ""
            require(mimeType == "application/epub+zip" && zip.getEntry("META-INF/container.xml") != null) {
                "The downloaded file is not a valid EPUB. This site may require a browser or sign-in."
            }
        }
    }
}
