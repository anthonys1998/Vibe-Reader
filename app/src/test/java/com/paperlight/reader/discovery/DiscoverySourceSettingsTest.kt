package com.paperlight.reader.discovery

import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoverySourceSettingsTest {
    @Test fun legacySourceConfigDefaultsToDirectDownloadAndDefaultScraperOptions() {
        val config = DiscoverySourceConfigCodec.decode("""{"htmlBookSelector":"article.book"}""")

        assertEquals("article.book", config.htmlBookSelector)
        assertEquals(DiscoverySourcePurpose.DOWNLOAD_EPUB, config.purpose)
        assertEquals(WebNovelScrapeOptions(), config.scrapeOptions)
    }

    @Test fun purposeAndEveryScrapeOptionSurviveCatalogSelectorEditsAndJsonRoundTrip() {
        val options = WebNovelScrapeOptions(
            chapterLinkSelector = "ol.chapters a[href]",
            chapterContentSelector = "main .chapter",
            chapterTitleSelector = "h1.chapter-title",
            removeSelector = ".ads, nav",
            firstChapter = 4,
            lastChapter = 27,
            requestDelayMillis = 1_750L,
            renderInBrowser = true,
        )
        val original = DiscoverySourceConfig(
            searchUrlTemplate = "https://books.example/search?q={searchTerms}",
            htmlBookSelector = "article.book",
            htmlTitleSelector = "h2.title",
            htmlLatestUrl = "/latest",
            htmlPopularUrl = "/popular",
            purpose = DiscoverySourcePurpose.CONVERT_WEBSITE,
            scrapeOptions = options,
        )

        val edited = DiscoverySourceConfigCodec.decode(DiscoverySourceConfigCodec.encode(original))
            .copy(htmlTitleSelector = "h3.book-title")
        val restored = DiscoverySourceConfigCodec.decode(DiscoverySourceConfigCodec.encode(edited))

        assertEquals("h3.book-title", restored.htmlTitleSelector)
        assertEquals(original.searchUrlTemplate, restored.searchUrlTemplate)
        assertEquals(original.htmlLatestUrl, restored.htmlLatestUrl)
        assertEquals(original.htmlPopularUrl, restored.htmlPopularUrl)
        assertEquals(DiscoverySourcePurpose.CONVERT_WEBSITE, restored.purpose)
        assertEquals(options, restored.scrapeOptions)
        assertTrue(DiscoverySourcePurpose.isValid(restored.purpose))
    }

    @Test fun malformedOptionalScraperSettingsDoNotDiscardValidCatalogConfiguration() {
        val config = DiscoverySourceConfigCodec.decode("""
            {"htmlBookSelector":"article.entry","htmlTitleSelector":"h2.title","purpose":"CONVERT_WEBSITE","scrapeOptions":{"firstChapter":0}}
        """.trimIndent())

        assertEquals("article.entry", config.htmlBookSelector)
        assertEquals("h2.title", config.htmlTitleSelector)
        assertEquals(DiscoverySourcePurpose.CONVERT_WEBSITE, config.purpose)
        assertEquals(WebNovelScrapeOptions(), config.scrapeOptions)
    }
}
