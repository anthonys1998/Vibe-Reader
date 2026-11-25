package com.paperlight.reader.discovery

import com.paperlight.reader.data.DiscoverySourceEntity
import com.paperlight.reader.data.DiscoverySourceKind
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoveryBookDetailsTest {
    @Test fun extractsNovelPingSynopsisAndGenresFromStructuredAndVisibleDetails() {
        val html = """
            <html><head>
              <meta itemprop="description" content="Short metadata summary…">
              <meta itemprop="genre" content="https://novelping.com/novelping-genres/fan-fiction">
              <meta itemprop="genre" content="https://novelping.com/novelping-genres/system">
              <meta property="og:image" content="https://images.novelping.com/novel/example-cover.jpg?v=1">
            </head><body>
              <div class="book-meta">
                <a class="btn meta-chip" href="https://novelping.com/novelping-genres/fan-fiction">Fan-fiction</a>
                <a class="btn meta-chip" href="https://novelping.com/novelping-genres/system">System</a>
              </div>
              <div class="novel-description-block">
                <div id="novel-description-content" itemprop="description">
                  <p>Asher wakes as a failing instructor at a magic academy.</p>
                  <p>The Master Teacher System reveals the students’ hidden potential.</p>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val details = DiscoveryBookDetailsClient.parse(Jsoup.parse(html))

        assertEquals(
            "Asher wakes as a failing instructor at a magic academy. The Master Teacher System reveals the students’ hidden potential.",
            details.synopsis,
        )
        assertEquals(listOf("Fan fiction", "System"), details.genres)
        assertEquals("https://images.novelping.com/novel/example-cover.jpg?v=1", details.coverUrl)
    }

    @Test fun fallsBackToCommonSynopsisAndGenreMetadata() {
        val html = """
            <html><head>
              <meta name="description" content="A quiet librarian finds a hidden world.">
              <meta property="book:tag" content="Fantasy">
            </head><body>
              <section class="genres"><a href="/genres/fantasy">Fantasy</a><a href="/genres/mystery">Mystery</a></section>
            </body></html>
        """.trimIndent()

        val details = DiscoveryBookDetailsClient.parse(Jsoup.parse(html))

        assertEquals("A quiet librarian finds a hidden world.", details.synopsis)
        assertEquals(listOf("Fantasy", "Mystery"), details.genres)
    }

    @Test fun cleansStandardEbooksDownloadBoilerplateFromItsSynopsisMetadata() {
        val html = """
            <html><head>
              <meta name="description" content="Free epub ebook download of the Standard Ebooks edition of Pride and Prejudice: A Regency-era novel of manners in which five women adjust to a new neighbor.">
              <meta property="og:image" content="/ebooks/jane-austen/pride-and-prejudice/downloads/cover.jpg">
            </head>
            <body><main>No genres listed.</main></body></html>
        """.trimIndent()

        val pageUrl = "https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice"
        val details = DiscoveryBookDetailsClient.parse(Jsoup.parse(html, pageUrl), DiscoverySourceKind.STANDARD_EBOOKS, pageUrl)

        assertEquals(
            "A Regency-era novel of manners in which five women adjust to a new neighbor.",
            details.synopsis,
        )
        assertTrue(details.genres.isEmpty())
        assertEquals("https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/cover.jpg", details.coverUrl)
    }

    @Test fun resolvesLazyPictureSourceSetsAndRejectsNonHttpCoverUrls() {
        val html = """
            <html><body>
              <picture>
                <source data-srcset="data:image/avif;base64,AAAA 1x, /covers/book@2x.avif 2x">
                <img class="novel-cover" src="data:image/gif;base64,AA">
              </picture>
            </body></html>
        """.trimIndent()
        val pageUrl = "https://books.example/novels/example"

        val details = DiscoveryBookDetailsClient.parse(Jsoup.parse(html, pageUrl), pageUrl = pageUrl)
        val dataOnly = DiscoveryBookDetailsClient.parse(
            Jsoup.parse("<img class='cover' src='javascript:alert(1)'>", pageUrl), pageUrl = pageUrl)

        assertEquals("https://books.example/covers/book@2x.avif", details.coverUrl)
        assertEquals(null, dataOnly.coverUrl)
    }

    @Test fun lazyCoverSourceTakesPriorityOverAnHttpPlaceholder() {
        val html = """
            <html><body><img class="book-cover" src="/images/no-cover.png" data-src="/covers/actual-book.jpg"></body></html>
        """.trimIndent()
        val pageUrl = "https://books.example/novels/example"

        val details = DiscoveryBookDetailsClient.parse(Jsoup.parse(html, pageUrl), pageUrl = pageUrl)

        assertEquals("https://books.example/covers/actual-book.jpg", details.coverUrl)
    }

    @Test fun synopsisAndGenresAreBoundedAndDeduplicated() {
        val html = buildString {
            append("<html><body><div class='synopsis'>")
            append("description ")
            append("a".repeat(12_050))
            append("</div><div class='genres'>")
            repeat(28) { index ->
                append("<a href='/genre/genre-$index'>Genre $index</a>")
            }
            append("<a href='/genre/genre-0'>Genre 0</a></div></body></html>")
        }

        val details = DiscoveryBookDetailsClient.parse(Jsoup.parse(html))

        assertEquals(12_000, details.synopsis?.length)
        assertEquals(24, details.genres.size)
        assertEquals(details.genres.size, details.genres.map { it.lowercase() }.distinct().size)
    }

    @Test fun publicPageFetchFailuresPropagateInsteadOfLookingLikeMissingMetadata() {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}/catalog"
        server.close()
        val source = DiscoverySourceEntity(
            id = "details-failure",
            name = "Details failure",
            kind = DiscoverySourceKind.HTML,
            baseUrl = baseUrl,
            configJson = DiscoverySourceConfigCodec.encode(DiscoverySourceConfig()),
            cacheKey = "",
            cacheJson = "",
            cacheUpdatedAt = 0L,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val book = DiscoveryBook("failure", "Book", "Author", "$baseUrl/book-details")

        assertThrows(Exception::class.java) {
            runBlocking { DiscoveryBookDetailsClient.load(source, book) }
        }
    }

    @Test fun skipsOpdsUnsupportedAndDownloadOnlyEntriesWithoutOpeningTheirUrls() = runBlocking {
        val server = ServerSocket(0).apply { soTimeout = 250 }
        val requestCount = AtomicInteger()
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        requestCount.incrementAndGet()
                        val requestLine = InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)
                            .buffered().readLine().orEmpty()
                        requestedPaths += requestLine.split(' ').getOrNull(1).orEmpty()
                        val output = socket.getOutputStream()
                        val body = ("<html><body><div class='synopsis'>Catalog detail synopsis</div>" +
                            "<div class='genres'><a href='/genres/fantasy'>Fantasy</a></div></body></html>")
                            .toByteArray(StandardCharsets.UTF_8)
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(StandardCharsets.US_ASCII))
                        output.write(body)
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep waiting until the test closes the server.
                } catch (failure: Exception) {
                    if (!server.isClosed) throw failure
                }
            }
        }.apply { isDaemon = true; start() }
        val baseUrl = "http://127.0.0.1:${server.localPort}/catalog"
        fun source(kind: String) = DiscoverySourceEntity(
            id = "details-test",
            name = "Details test",
            kind = kind,
            baseUrl = baseUrl,
            configJson = DiscoverySourceConfigCodec.encode(DiscoverySourceConfig()),
            cacheKey = "",
            cacheJson = "",
            cacheUpdatedAt = 0L,
            createdAt = 1L,
            updatedAt = 1L,
        )

        try {
            val opdsBook = DiscoveryBook("opds", "Book", "Author", "$baseUrl/feed.atom")
            val directEpub = DiscoveryBook(
                id = "epub",
                title = "Book",
                author = "Author",
                detailsUrl = "$baseUrl/books/book.epub",
                epubUrl = "$baseUrl/books/book.epub",
            )
            val unsupportedBook = DiscoveryBook("unsupported", "Book", "Author", "$baseUrl/details")
            val opdsAlternate = DiscoveryBook(
                id = "opds-alternate",
                title = "Book",
                author = "Author",
                detailsUrl = "$baseUrl/books/book-details",
                epubUrl = "$baseUrl/download/book.epub",
            )

            assertEquals(DiscoveryBookDetails(null, emptyList()),
                DiscoveryBookDetailsClient.load(source(DiscoverySourceKind.OPDS), opdsBook))
            assertEquals(DiscoveryBookDetails(null, emptyList()),
                DiscoveryBookDetailsClient.load(source(DiscoverySourceKind.HTML), directEpub))
            assertEquals(DiscoveryBookDetails(null, emptyList()),
                DiscoveryBookDetailsClient.load(source("UNSUPPORTED"), unsupportedBook))
            assertEquals(
                DiscoveryBookDetails("Catalog detail synopsis", listOf("Fantasy")),
                DiscoveryBookDetailsClient.load(source(DiscoverySourceKind.OPDS), opdsAlternate),
            )

            assertEquals(1, requestCount.get())
            assertEquals(listOf("/catalog/books/book-details"), requestedPaths)
            assertTrue(server.isBound)
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }
}
