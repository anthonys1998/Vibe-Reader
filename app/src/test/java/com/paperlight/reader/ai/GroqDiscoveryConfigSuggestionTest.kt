package com.paperlight.reader.ai

import com.paperlight.reader.discovery.DiscoverySourceConfig
import com.paperlight.reader.discovery.DiscoveryCatalogClient
import com.paperlight.reader.discovery.DiscoverySourcePurpose
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroqDiscoveryConfigSuggestionTest {
    private val baseUrl = "https://books.example/catalog"
    private val sample = """
        <html><body><main><article class="book-card">
          <h2 class="name"><a class="details" href="/books/one">Example Book</a></h2>
          <span class="byline">Example Author</span>
          <img class="cover" src="/covers/one.jpg">
          <a class="download" href="/downloads/one.epub">EPUB</a>
        </article></main></body></html>
    """.trimIndent()

    @Test fun selectorSuggestionChangesCatalogFieldsAndPreservesSitePurposeScraperAndModeSettings() {
        val options = WebNovelScrapeOptions(
            chapterLinkSelector = "ol.chapter-list a",
            chapterContentSelector = "main .chapter-text",
            requestDelayMillis = 1_500L,
            renderInBrowser = true,
        )
        val current = DiscoverySourceConfig(
            searchUrlTemplate = "https://books.example/search?q={searchTerms}",
            htmlLatestUrl = "/new",
            htmlPopularUrl = "/top",
            purpose = DiscoverySourcePurpose.CONVERT_WEBSITE,
            scrapeOptions = options,
        )
        val suggestion = JSONObject("""
            {
              "htmlBookSelector":"article.book-card",
              "htmlTitleSelector":"h2.name",
              "htmlAuthorSelector":".byline",
              "htmlDetailLinkSelector":"a.details",
              "htmlCoverSelector":"img.cover",
              "htmlEpubLinkSelector":"a.download"
            }
        """.trimIndent())

        val configured = applyDiscoveryConfigSuggestion(suggestion, baseUrl, sample, current)

        assertEquals("article.book-card", configured.htmlBookSelector)
        assertEquals("h2.name", configured.htmlTitleSelector)
        assertEquals(".byline", configured.htmlAuthorSelector)
        assertEquals("a.details", configured.htmlDetailLinkSelector)
        assertEquals("img.cover", configured.htmlCoverSelector)
        assertEquals("a.download", configured.htmlEpubLinkSelector)
        assertEquals(current.searchUrlTemplate, configured.searchUrlTemplate)
        assertEquals(current.htmlLatestUrl, configured.htmlLatestUrl)
        assertEquals(current.htmlPopularUrl, configured.htmlPopularUrl)
        assertEquals(current.purpose, configured.purpose)
        assertEquals(options, configured.scrapeOptions)
    }

    @Test fun invalidSelectorSuggestionIsRejectedAgainstTheSample() {
        val invalid = JSONObject("""
            {"htmlBookSelector":"article.missing","htmlTitleSelector":"h2","htmlDetailLinkSelector":"a"}
        """.trimIndent())

        assertThrows(IllegalArgumentException::class.java) {
            applyDiscoveryConfigSuggestion(invalid, baseUrl, sample, DiscoverySourceConfig())
        }
    }

    @Test fun invalidOptionalSelectorIsRejectedEvenWhenRequiredSelectorsMatch() {
        val suggestion = JSONObject("""
            {
              "htmlBookSelector":"article.book-card",
              "htmlTitleSelector":"h2.name",
              "htmlDetailLinkSelector":"a.details",
              "htmlCoverSelector":"["
            }
        """.trimIndent())

        assertThrows(IllegalArgumentException::class.java) {
            applyDiscoveryConfigSuggestion(suggestion, baseUrl, sample, DiscoverySourceConfig())
        }
    }

    @Test fun missingOrEmptyOptionalFieldsPreserveExistingSavedSelectors() {
        val current = DiscoverySourceConfig(
            htmlAuthorSelector = ".saved-author",
            htmlCoverSelector = "picture img[data-src]",
            htmlEpubLinkSelector = "a.saved-download",
        )
        val suggestion = JSONObject("""
            {
              "htmlBookSelector":"article.book-card",
              "htmlTitleSelector":"h2.name",
              "htmlDetailLinkSelector":"a.details",
              "htmlAuthorSelector":"",
              "htmlCoverSelector":"  "
            }
        """.trimIndent())

        val configured = applyDiscoveryConfigSuggestion(suggestion, baseUrl, sample, current)

        assertEquals(current.htmlAuthorSelector, configured.htmlAuthorSelector)
        assertEquals(current.htmlCoverSelector, configured.htmlCoverSelector)
        assertEquals(current.htmlEpubLinkSelector, configured.htmlEpubLinkSelector)

        val noImageSample = """
            <article class="book-card">
              <h2 class="name"><a class="details" href="/books/one">Example Book</a></h2>
            </article>
        """.trimIndent()
        val noExistingSelectors = applyDiscoveryConfigSuggestion(
            suggestion, baseUrl, noImageSample,
            current.copy(htmlAuthorSelector = "", htmlCoverSelector = "", htmlEpubLinkSelector = ""),
        )
        assertEquals("", noExistingSelectors.htmlAuthorSelector)
        assertEquals("", noExistingSelectors.htmlCoverSelector)
        assertEquals("", noExistingSelectors.htmlEpubLinkSelector)
    }

    @Test fun suggestionCanSelectPictureSourceSetUsedByTheBookCover() {
        val html = """
            <html><body><main><article class="book-card">
              <h2 class="name"><a class="details" href="/books/one">Example Book</a></h2>
              <picture>
                <source data-srcset="data:image/avif;base64,AAAA 1x, /covers/book@2x.avif 2x">
                <img class="placeholder" src="data:image/gif;base64,AA">
              </picture>
            </article></main></body></html>
        """.trimIndent()
        val suggestion = JSONObject("""
            {
              "htmlBookSelector":"article.book-card",
              "htmlTitleSelector":"h2.name",
              "htmlDetailLinkSelector":"a.details",
              "htmlCoverSelector":"picture source"
            }
        """.trimIndent())

        val configured = applyDiscoveryConfigSuggestion(suggestion, baseUrl, html, DiscoverySourceConfig())
        val parsed = DiscoveryCatalogClient.parseHtmlList(html, baseUrl, configured)

        assertEquals("picture source", configured.htmlCoverSelector)
        assertEquals("https://books.example/covers/book@2x.avif", parsed.books.single().coverUrl)
    }

    @Test fun emptyCoverSuggestionInfersASelectorOnlyWhenTheSampleContainsAnActualCover() {
        val html = """
            <html><body><article class="book-card">
              <h2 class="name"><a class="details" href="/books/one">Example Book</a></h2>
              <picture>
                <source data-srcset="data:image/avif;base64,AAAA 1x, /covers/book@2x.avif 2x">
                <img class="placeholder" src="data:image/gif;base64,AA">
              </picture>
            </article></body></html>
        """.trimIndent()
        val suggestion = JSONObject("""
            {
              "htmlBookSelector":"article.book-card",
              "htmlTitleSelector":"h2.name",
              "htmlDetailLinkSelector":"a.details",
              "htmlCoverSelector":""
            }
        """.trimIndent())

        val configured = applyDiscoveryConfigSuggestion(suggestion, baseUrl, html,
            DiscoverySourceConfig(htmlCoverSelector = ""))
        val parsed = DiscoveryCatalogClient.parseHtmlList(html, baseUrl, configured)

        assertEquals("picture > source", configured.htmlCoverSelector)
        assertEquals("https://books.example/covers/book@2x.avif", parsed.books.single().coverUrl)
    }

    @Test fun lazyCardCoverUsesDataSourceInsteadOfAnHttpPlaceholder() {
        val html = """
            <html><body><article class="book-card">
              <h2 class="name"><a class="details" href="/books/one">Example Book</a></h2>
              <img class="cover" src="/images/no-cover.png" data-src="/covers/actual-book.jpg">
            </article></body></html>
        """.trimIndent()
        val suggestion = JSONObject("""
            {
              "htmlBookSelector":"article.book-card",
              "htmlTitleSelector":"h2.name",
              "htmlDetailLinkSelector":"a.details",
              "htmlCoverSelector":"img.cover"
            }
        """.trimIndent())

        val configured = applyDiscoveryConfigSuggestion(suggestion, baseUrl, html, DiscoverySourceConfig())
        val parsed = DiscoveryCatalogClient.parseHtmlList(html, baseUrl, configured)

        assertEquals("https://books.example/covers/actual-book.jpg", parsed.books.single().coverUrl)
    }

    @Test fun existingCrossOriginModeUrlBlocksApplyingASelectorSuggestion() {
        val current = DiscoverySourceConfig(htmlLatestUrl = "https://other.example/books")
        val suggestion = JSONObject("""
            {"htmlBookSelector":"article.book-card","htmlTitleSelector":"h2.name","htmlDetailLinkSelector":"a.details"}
        """.trimIndent())

        assertThrows(IllegalArgumentException::class.java) {
            applyDiscoveryConfigSuggestion(suggestion, baseUrl, sample, current)
        }
    }
}
