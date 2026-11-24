package com.paperlight.reader.webnovel

import android.content.Context
import androidx.room.Room
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNovelRecoveryExecutionTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun persistedCooldownDefersBeforeRenderedTocAndChapterLoads() = runBlocking {
        val preferences = context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE)
        val options = WebNovelScrapeOptions(renderInBrowser = true)
        val tocUrl = "https://rendered-toc-cooldown.example/novel"
        val tocHost = "rendered-toc-cooldown.example"
        val tocUntil = System.currentTimeMillis() + 3_600_000L
        val tocDestination = File(context.cacheDir, "rendered-toc-${System.nanoTime()}.epub")
        val chapterUrl = "https://rendered-chapter-cooldown.example/novel"
        val chapterHost = "rendered-chapter-cooldown.example"
        val chapterUntil = tocUntil + 1L
        val chapterKey = WebNovelEpubUtility.checkpointKey(chapterUrl, options)
        val chapterDirectory = File(context.filesDir, "webnovel-checkpoints/$chapterKey")
            .apply { deleteRecursively(); mkdirs() }
        val chapterDestination = File(context.cacheDir, "rendered-chapter-${System.nanoTime()}.epub")
        File(chapterDirectory, WebNovelChapterManifestStore.FILE_NAME).writeText(
            WebNovelChapterManifestCodec.encode(WebNovelChapterManifest(
                chapterUrl, chapterUrl, chapterKey, "Novel",
                listOf(WebNovelChapterLink("https://$chapterHost/chapter", "Chapter")), null,
            )),
        )
        preferences.edit().putLong(tocHost, tocUntil).putLong(chapterHost, chapterUntil).commit()
        try {
            val tocFailure = runCatching {
                WebNovelEpubUtility(context).createEpub(tocUrl, tocDestination, options)
            }.exceptionOrNull()
            assertTrue(tocFailure is RateLimitDeferredException)
            assertEquals(tocUntil, (tocFailure as RateLimitDeferredException).waitUntil)

            val chapterFailure = runCatching {
                WebNovelEpubUtility(context).createEpub(chapterUrl, chapterDestination, options)
            }.exceptionOrNull()
            assertTrue(chapterFailure is RateLimitDeferredException)
            assertEquals(chapterUntil, (chapterFailure as RateLimitDeferredException).waitUntil)
        } finally {
            tocDestination.delete()
            chapterDestination.delete()
            chapterDirectory.deleteRecursively()
            preferences.edit().remove(tocHost).remove(chapterHost).commit()
        }
    }

    @Test fun optionalCoverIsSkippedDuringPersistedHostCooldown() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val coverRequests = AtomicInteger()
        server.createContext("/") { exchange ->
            coverRequests.incrementAndGet()
            val bytes = "image bytes".toByteArray()
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/novel"
        val coverUrl = "http://127.0.0.1:${server.address.port}/cover.png"
        val options = WebNovelScrapeOptions()
        val key = WebNovelEpubUtility.checkpointKey(base, options)
        val directory = File(context.filesDir, "webnovel-checkpoints/$key").apply { deleteRecursively(); mkdirs() }
        val chapterUrl = "http://127.0.0.1:${server.address.port}/chapter"
        val manifest = WebNovelChapterManifest(base, base, key, "Novel",
            listOf(WebNovelChapterLink(chapterUrl, "Chapter")), coverUrl)
        File(directory, WebNovelChapterManifestStore.FILE_NAME).writeText(WebNovelChapterManifestCodec.encode(manifest))
        File(directory, "chapter-00000.url").writeText(chapterUrl)
        File(directory, "chapter-00000.xhtml").writeText(
            "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Chapter</h1><p>Cached chapter prose.</p></body></html>"
        )
        val preferences = context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE)
        preferences.edit().putLong("127.0.0.1", System.currentTimeMillis() + 3_600_000L).commit()
        val destination = File(context.cacheDir, "cover-cooldown-${System.nanoTime()}.epub")
        try {
            val result = WebNovelEpubUtility(context).createEpub(base, destination, options)
            assertEquals(1, result.chapterCount)
            assertEquals(0, coverRequests.get())
        } finally {
            destination.delete()
            directory.deleteRecursively()
            preferences.edit().remove("127.0.0.1").commit()
            server.stop(0)
        }
    }

    @Test fun deferredRetryAfterIsCommittedBeforeTheWorkerIsScheduled() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Retry-After", "3600")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        val host = "127.0.0.1"
        val preferences = context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE)
        preferences.edit().remove(host).commit()
        val destination = File(context.cacheDir, "cooldown-test-${System.nanoTime()}.epub")
        try {
            val failure = runCatching {
                WebNovelEpubUtility(context).createEpub("http://$host:${server.address.port}/novel", destination)
            }.exceptionOrNull()
            assertTrue(failure is RateLimitDeferredException)
            assertTrue(preferences.getLong(host, 0L) > System.currentTimeMillis())
        } finally {
            destination.delete()
            preferences.edit().remove(host).commit()
            server.stop(0)
        }
    }

    @Test fun workerYieldAfterCachedPrefixDownloadsAndSavesAnUncachedChapter() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/novel" -> "<html><head><title>Novel</title></head><body><h1>Novel</h1>" +
                    "<a href='/one'>Chapter One</a><a href='/two'>Chapter Two</a><a href='/three'>Chapter Three</a></body></html>"
                else -> "<html><body><h1>Chapter</h1><div class='chapter-content'><p>Recovered chapter prose.</p></div></body></html>"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/novel"
        val options = WebNovelScrapeOptions(requestDelayMillis = 0L)
        val key = WebNovelEpubUtility.checkpointKey(base, options)
        val directory = File(context.filesDir, "webnovel-checkpoints/$key").apply { deleteRecursively(); mkdirs() }
        val chapterUrls = listOf("one", "two", "three").map { "http://127.0.0.1:${server.address.port}/$it" }
        val manifest = WebNovelChapterManifest(base, base, key, "Novel",
            chapterUrls.mapIndexed { index, url -> WebNovelChapterLink(url, "Chapter ${index + 1}") }, null)
        File(directory, WebNovelChapterManifestStore.FILE_NAME).writeText(WebNovelChapterManifestCodec.encode(manifest))
        File(directory, "chapter-00000.url").writeText(chapterUrls[0])
        File(directory, "chapter-00000.xhtml").writeText(
            "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Chapter One</h1><p>Cached chapter.</p></body></html>"
        )
        val database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            var yieldChecks = 0
            val failure = runCatching {
                BookRepository(context, database.bookDao()).createEpubFromUrl(base, options,
                    onProgress = { _, _, _ -> }, shouldYieldAtChapterBoundary = { yieldChecks++; true })
            }.exceptionOrNull()
            assertTrue(failure is WebNovelWorkerSliceYieldException)
            assertEquals(1, yieldChecks)
            assertEquals(chapterUrls[1], File(directory, "chapter-00001.url").readText())
            assertTrue(File(directory, "chapter-00001.xhtml").isFile)
            assertTrue(!File(directory, "chapter-00002.xhtml").exists())
        } finally {
            directory.deleteRecursively()
            database.close()
            server.stop(0)
        }
    }
}
