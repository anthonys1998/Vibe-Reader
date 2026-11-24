package com.paperlight.reader.data

import android.content.Context
import androidx.room.Room
import com.paperlight.reader.discovery.DiscoverySourceConfig
import com.paperlight.reader.discovery.DiscoverySourceConfigCodec
import com.paperlight.reader.discovery.DiscoverySourcePurpose
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookRepositoryDiscoveryScrapeOptionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: BookDatabase

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun closeDatabase() {
        database.close()
    }

    @Test fun savesNormalizedScraperOptionsAgainstLatestSourceConfig() = runBlocking {
        val dao = database.bookDao()
        val initialConfig = DiscoverySourceConfig(
            htmlBookSelector = "article.story",
            htmlTitleSelector = "h2.title",
            htmlDetailLinkSelector = "a.details",
            purpose = DiscoverySourcePurpose.CONVERT_WEBSITE,
            scrapeOptions = WebNovelScrapeOptions(chapterLinkSelector = ".old-list a"),
        )
        val source = DiscoverySourceEntity(
            id = "custom-story-site",
            name = "Story site",
            kind = DiscoverySourceKind.HTML,
            baseUrl = "https://stories.example/catalog",
            configJson = DiscoverySourceConfigCodec.encode(initialConfig),
            cacheKey = "old-cache-key",
            cacheJson = "{\"books\":[]}",
            cacheUpdatedAt = 2L,
            createdAt = 1L,
            updatedAt = 2L,
        )
        dao.insertDiscoverySource(source)
        val latestConfig = initialConfig.copy(
            htmlBookSelector = "article.updated-story",
            purpose = DiscoverySourcePurpose.DOWNLOAD_EPUB,
        )
        dao.updateDiscoverySource(source.copy(
            configJson = DiscoverySourceConfigCodec.encode(latestConfig),
            updatedAt = 3L,
        ))
        val options = WebNovelScrapeOptions(
            chapterLinkSelector = "nav.chapter-list a[href]",
            chapterContentSelector = "main .chapter-body",
            chapterTitleSelector = "h1.chapter-title",
            removeSelector = ".advertisement, footer",
            firstChapter = 7,
            lastChapter = 19,
            requestDelayMillis = 1_200L,
            renderInBrowser = true,
        )

        BookRepository(context, dao).updateDiscoveryScrapeOptions(
            sourceId = source.id,
            expectedBaseUrl = source.baseUrl,
            pageUrl = "https://stories.example/books/example",
            options = options,
        )

        val updated = requireNotNull(dao.discoverySourceById(source.id))
        val config = DiscoverySourceConfigCodec.decode(updated.configJson)
        assertEquals(latestConfig.htmlBookSelector, config.htmlBookSelector)
        assertEquals(initialConfig.htmlTitleSelector, config.htmlTitleSelector)
        assertEquals(DiscoverySourcePurpose.DOWNLOAD_EPUB, config.purpose)
        assertEquals("nav.chapter-list a[href]", config.scrapeOptions.chapterLinkSelector)
        assertEquals("main .chapter-body", config.scrapeOptions.chapterContentSelector)
        assertEquals("h1.chapter-title", config.scrapeOptions.chapterTitleSelector)
        assertEquals(".advertisement, footer", config.scrapeOptions.removeSelector)
        assertEquals(1, config.scrapeOptions.firstChapter)
        assertNull(config.scrapeOptions.lastChapter)
        assertEquals(1_200L, config.scrapeOptions.requestDelayMillis)
        assertEquals(true, config.scrapeOptions.renderInBrowser)
        assertEquals("old-cache-key", updated.cacheKey)
        assertEquals(source.cacheJson, updated.cacheJson)
        assertEquals(2L, updated.cacheUpdatedAt)
    }

    @Test fun doesNotSaveScraperOptionsForChangedSourceOrDifferentOrigin() = runBlocking {
        val dao = database.bookDao()
        val source = DiscoverySourceEntity(
            id = "custom-source",
            name = "Custom source",
            kind = DiscoverySourceKind.HTML,
            baseUrl = "https://books.example/list",
            configJson = DiscoverySourceConfigCodec.encode(DiscoverySourceConfig()),
            createdAt = 1L,
            updatedAt = 2L,
        )
        dao.insertDiscoverySource(source)
        val repository = BookRepository(context, dao)
        val options = WebNovelScrapeOptions()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateDiscoveryScrapeOptions(
                    source.id,
                    expectedBaseUrl = "https://other.example/list",
                    pageUrl = "https://other.example/book",
                    options = options,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.updateDiscoveryScrapeOptions(
                    source.id,
                    expectedBaseUrl = source.baseUrl,
                    pageUrl = "https://other.example/book",
                    options = options,
                )
            }
        }

        assertEquals(source.configJson, dao.discoverySourceById(source.id)?.configJson)
    }
}
