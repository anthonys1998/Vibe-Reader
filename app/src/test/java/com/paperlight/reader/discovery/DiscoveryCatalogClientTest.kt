package com.paperlight.reader.discovery

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.jsoup.Jsoup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoveryCatalogClientTest {
    @Test fun standardEbooksParserReadsMetadataCoverAndNextPage() {
        val html = """
            <html><body>
              <ol class="ebooks-list">
                <li typeof="schema:Book" about="/ebooks/jane-austen/pride-and-prejudice">
                  <p><a property="schema:url" href="/ebooks/jane-austen/pride-and-prejudice">
                    <span property="schema:name">Pride and Prejudice</span></a></p>
                  <p class="author"><span property="schema:name">Jane Austen</span></p>
                  <img property="schema:image" src="/images/pride.jpg">
                </li>
              </ol>
              <a rel="next" href="/ebooks?page=2">Next</a>
            </body></html>
        """.trimIndent()

        val page = DiscoveryCatalogClient.parseStandardHtml(html, "https://standardebooks.org/ebooks")
        assertEquals(1, page.books.size)
        assertEquals("Pride and Prejudice", page.books.single().title)
        assertEquals("Jane Austen", page.books.single().author)
        assertEquals("https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice",
            page.books.single().detailsUrl)
        assertEquals("https://standardebooks.org/images/pride.jpg", page.books.single().coverUrl)
        assertEquals("https://standardebooks.org/ebooks?page=2", page.nextUrl)
    }

    @Test fun namespacedOpdsParserReturnsAcquisitionsCollectionsAndNext() {
        val xml = """
            <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
              <atom:link rel="next" href="page2.xml"/>
              <atom:entry>
                <atom:title>Sample Book</atom:title>
                <atom:author><atom:name>Example Author</atom:name></atom:author>
                <atom:id>urn:example:book:1</atom:id>
                <atom:link rel="http://opds-spec.org/image/thumbnail" href="../covers/1.jpg"/>
                <atom:link rel="http://opds-spec.org/acquisition/open-access"
                  type="application/epub+zip" href="../downloads/1.epub"/>
              </atom:entry>
              <atom:entry>
                <atom:title>Fiction</atom:title>
                <atom:link rel="subsection" type="application/atom+xml" href="fiction.xml"/>
              </atom:entry>
            </atom:feed>
        """.trimIndent()

        val page = DiscoveryCatalogClient.parseOpdsXml(xml, "https://catalog.example.org/feeds/root.xml")
        assertEquals(1, page.books.size)
        assertEquals("Sample Book", page.books.single().title)
        assertEquals("Example Author", page.books.single().author)
        assertEquals("https://catalog.example.org/downloads/1.epub", page.books.single().epubUrl)
        assertEquals("https://catalog.example.org/covers/1.jpg", page.books.single().coverUrl)
        assertEquals("Fiction", page.collections.single().title)
        assertEquals("https://catalog.example.org/feeds/fiction.xml", page.collections.single().url)
        assertEquals("https://catalog.example.org/feeds/page2.xml", page.nextUrl)
    }

    @Test fun customHtmlParserUsesBookCardsAndNeverPromotesNavigationLinks() {
        val html = """
            <html><body>
              <nav><a href="/random">Menu link must not become a book</a></nav>
              <article class="result">
                <h2><a href="/books/one">Visible title</a></h2>
                <span class="author">A Writer</span>
                <img class="cover" src="/images/cover.jpg">
                <a class="epub" href="/downloads/one.epub">EPUB</a>
              </article>
            </body></html>
        """.trimIndent()
        val config = DiscoverySourceConfig(htmlBookSelector = "article.result", htmlTitleSelector = "h2 a",
            htmlAuthorSelector = ".author", htmlDetailLinkSelector = "h2 a", htmlCoverSelector = "img.cover",
            htmlEpubLinkSelector = "a.epub")

        val page = DiscoveryCatalogClient.parseHtmlList(html, "https://books.example.org/list", config)
        assertEquals(1, page.books.size)
        assertEquals("Visible title", page.books.single().title)
        assertEquals("A Writer", page.books.single().author)
        assertEquals("https://books.example.org/books/one", page.books.single().detailsUrl)
        assertEquals("https://books.example.org/images/cover.jpg", page.books.single().coverUrl)
        assertEquals("https://books.example.org/downloads/one.epub", page.books.single().epubUrl)
        try {
            DiscoveryCatalogClient.parseHtmlList("<nav><a href='/random'>Menu</a></nav>",
                "https://books.example.org/list", config)
            fail("A navigation-only page must not be treated as a book listing.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun automaticHtmlDetectionBuildsWorkingSelectorsForBothHeadingLinkLayoutsAndLazyCovers() {
        val nestedLink = """
            <html><head><title>Cloudflare Catalog</title></head><body><nav><a href="/login">Sign in</a></nav>
            <article class="book-card">
              <header><h2><a class="title" href="/books/one">Cloudflare Story</a></h2></header>
              <p class="author">Writer One</p>
              <img class="cover" src="data:image/gif;base64,AA" data-src="/covers/one.jpg">
            </article>
            <article class="book-card">
              <header><h2><a class="title" href="/books/two">Second Story</a></h2></header>
              <p class="author">Writer Two</p>
              <img class="cover" src="data:image/gif;base64,AA" data-src="/covers/two.jpg">
            </article>
            <footer><form><input type="password"><div data-sitekey="newsletter"></div><iframe src="/captcha"></iframe></form></footer>
            </body></html>
        """.trimIndent()
        val url = "https://catalog.example.org/books"
        val detected = DiscoveryCatalogClient.detectHtmlSource(Jsoup.parse(nestedLink, url), url)
        assertEquals(listOf("Cloudflare Story", "Second Story"), detected.previewTitles)
        assertEquals(true, detected.config.excludeNonCatalogRegions)
        val parsed = DiscoveryCatalogClient.parseHtmlList(nestedLink, url, detected.config)
        assertEquals("https://catalog.example.org/covers/one.jpg", parsed.books.first().coverUrl)
        assertEquals("Writer One", parsed.books.first().author)
        assertTrue(detected.config.htmlDetailLinkSelector.contains("a"))

        val headingInsideLink = """
            <article class="catalog-item"><a class="detail" href="/books/three"><h3>Third Good Book</h3></a></article>
            <article class="catalog-item"><a class="detail" href="/books/four"><h3>Fourth Good Book</h3></a></article>
        """.trimIndent()
        val other = DiscoveryCatalogClient.detectHtmlSource(Jsoup.parse(headingInsideLink, url), url)
        assertEquals(listOf("Third Good Book", "Fourth Good Book"), other.previewTitles)
        assertEquals("https://catalog.example.org/books/three",
            DiscoveryCatalogClient.parseHtmlList(headingInsideLink, url, other.config).books.first().detailsUrl)
    }

    @Test fun automaticHtmlDetectionSkipsNavigationPaginationAndCommentCards() {
        val html = """
            <nav role="navigation"><div class="book"><a href="/menu/one">Menu Book One</a></div>
              <div class="book"><a href="/menu/two">Menu Book Two</a></div></nav>
            <div role="pagination"><article class="book"><a href="/page/one">Page One Book</a></article>
              <article class="book"><a href="/page/two">Page Two Book</a></article></div>
            <div class="comments"><article class="book"><a href="/comment/one">Comment One Book</a></article>
              <article class="book"><a href="/comment/two">Comment Two Book</a></article></div>
        """.trimIndent()
        val url = "https://catalog.example.org/"
        val detected = DiscoveryCatalogClient.detectHtmlSource(Jsoup.parse(html, url), url)
        assertTrue(detected.previewTitles.isEmpty())
        assertEquals(DiscoverySourceConfig().htmlBookSelector, detected.config.htmlBookSelector)
    }

    @Test fun inferredSelectorFiltersCommentCardsEvenWhenTheyShareTheBookClass() {
        val html = """
            <main><article class="book"><h2><a href="/books/one">First Real Book</a></h2></article>
              <article class="book"><h2><a href="/books/two">Second Real Book</a></h2></article></main>
            <section class="comments"><article class="book"><h2><a href="/comments/one">Comment Link One</a></h2></article>
              <article class="book"><h2><a href="/comments/two">Comment Link Two</a></h2></article></section>
        """.trimIndent()
        val url = "https://catalog.example.org/"
        val detected = DiscoveryCatalogClient.detectHtmlSource(Jsoup.parse(html, url), url)
        assertEquals(listOf("First Real Book", "Second Real Book"), detected.previewTitles)
        assertEquals(listOf("https://catalog.example.org/books/one", "https://catalog.example.org/books/two"),
            DiscoveryCatalogClient.parseHtmlList(html, url, detected.config).books.map { it.detailsUrl })
    }

    @Test fun manualSelectorsStillAllowBooksInsideAsideRegionsByDefault() {
        val html = """
            <aside class="reading-list"><article class="sidebar-book">
              <h2>Saved Book</h2><a href="/books/saved">Details</a>
            </article></aside>
        """.trimIndent()
        val config = DiscoverySourceConfig(htmlBookSelector = "aside article.sidebar-book",
            htmlTitleSelector = "h2", htmlDetailLinkSelector = "a[href]")
        assertNull(config.excludeNonCatalogRegions)
        val page = DiscoveryCatalogClient.parseHtmlList(html, "https://catalog.example.org/", config)
        assertEquals("Saved Book", page.books.single().title)
        assertEquals("https://catalog.example.org/books/saved", page.books.single().detailsUrl)
        assertEquals(1, DiscoveryCatalogClient.parseHtmlList(html, "https://catalog.example.org/",
            config.copy(excludeNonCatalogRegions = false)).books.size)
        try {
            DiscoveryCatalogClient.parseHtmlList(html, "https://catalog.example.org/",
                config.copy(excludeNonCatalogRegions = true))
            fail("The inferred region filter should skip aside cards.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun automaticHtmlDetectionFindsSameOriginModesAndSearchAndPreservesSavedModeSettings() {
        val html = """
            <html><head><link rel="search" href="/find?q={searchTerms}"></head><body>
              <nav><a href="/latest">Latest</a><a href="/popular">Popular</a>
                <a href="https://elsewhere.example/popular">Popular external</a></nav>
              <article class="catalog-item"><h2><a href="/books/a">Alpha Book</a></h2></article>
              <article class="catalog-item"><h2><a href="/books/b">Beta Book</a></h2></article>
            </body></html>
        """.trimIndent()
        val url = "https://catalog.example.org/list"
        val detected = DiscoveryCatalogClient.detectHtmlSource(Jsoup.parse(html, url), url)
        assertEquals("https://catalog.example.org/find?q={searchTerms}", detected.config.searchUrlTemplate)
        assertEquals("https://catalog.example.org/latest", detected.config.htmlLatestUrl)
        assertEquals("https://catalog.example.org/popular", detected.config.htmlPopularUrl)
        assertEquals("https://catalog.example.org/relative",
            DiscoveryCatalogClient.configuredModeUrl(url, "/relative", "Latest"))
        try {
            DiscoveryCatalogClient.configuredModeUrl(url, "https://elsewhere.example/popular", "Popular")
            fail("Mode URLs must stay on the source origin.")
        } catch (_: IllegalArgumentException) {
        }

        val saved = DiscoverySourceConfig(htmlLatestUrl = "https://catalog.example.org/custom-new",
            htmlPopularUrl = "https://catalog.example.org/custom-top",
            searchUrlTemplate = "https://catalog.example.org/custom-search?q={searchTerms}",
            excludeNonCatalogRegions = false)
        val page = DiscoveryCatalogClient.parseHtmlWithAutoDetection(Jsoup.parse(html, url), url, saved, "")
        assertEquals(saved.searchUrlTemplate, page.detectedConfig?.searchUrlTemplate)
        assertEquals(saved.htmlLatestUrl, page.detectedConfig?.htmlLatestUrl)
        assertEquals(saved.htmlPopularUrl, page.detectedConfig?.htmlPopularUrl)
        assertEquals(false, page.detectedConfig?.excludeNonCatalogRegions)
        assertEquals(2, page.books.size)
    }

    @Test fun sourceConfigCodecReadsPreviousJsonDefaultsAndRoundTripsNewCapabilities() {
        val previous = DiscoverySourceConfigCodec.decode("""{"searchUrlTemplate":"/find?q={searchTerms}","htmlBookSelector":"article.old"}""")
        assertEquals("article.old", previous.htmlBookSelector)
        assertEquals("", previous.htmlLatestUrl)
        assertEquals("", previous.htmlPopularUrl)
        assertFalse(previous.renderJavaScript)
        assertNull(previous.excludeNonCatalogRegions)
        val updated = previous.copy(htmlLatestUrl = "https://catalog.example.org/latest",
            htmlPopularUrl = "https://catalog.example.org/popular", renderJavaScript = true,
            excludeNonCatalogRegions = true)
        assertEquals(updated, DiscoverySourceConfigCodec.decode(DiscoverySourceConfigCodec.encode(updated)))
    }

    @Test fun renderedHtmlValidationRejectsOversizeAndUnsafeFinalUrls() {
        val safe = Jsoup.parse("<html><body>public</body></html>", "https://catalog.example.org/list")
        assertEquals("https://catalog.example.org/list", DiscoveryCatalogClient.validateRenderedDocument(safe))
        val large = Jsoup.parse("<html><body>" + "a".repeat(1_000_100) + "</body></html>",
            "https://catalog.example.org/list")
        try {
            DiscoveryCatalogClient.validateRenderedDocument(large)
            fail("Rendered pages are limited to 1 MiB.")
        } catch (_: IllegalArgumentException) {
        }
        val unsafe = Jsoup.parse("<html/>", "file:///tmp/list")
        try {
            DiscoveryCatalogClient.validateRenderedDocument(unsafe)
            fail("Rendered final URLs must be HTTP(S).")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun automaticHtmlDetectionRejectsKnownBrowserChallengePage() {
        val challenge = Jsoup.parse("<html><head><title>Just a moment</title></head><body>Checking your browser</body></html>",
            "https://catalog.example.org/")
        try {
            DiscoveryCatalogClient.detectHtmlSource(challenge, "https://catalog.example.org/")
            fail("Challenge interstitials are not public catalog listings.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun cacheRoundTripsFullUrlsAndRefusesOversizedPayload() {
        val longUrl = "https://catalog.example.org/books/" + "a".repeat(2300)
        val page = DiscoveryPage(listOf(DiscoveryBook("stable-id", "Book", "Author", longUrl,
            epubUrl = "https://downloads.example.org/book.epub")))
        val json = DiscoveryCatalogCache.encode(page)
        assertNotNull(json)
        assertEquals(page, DiscoveryCatalogCache.decode(requireNotNull(json)))
        assertNull(DiscoveryCatalogCache.encode(page, maxBytes = 16))
    }

    @Test fun oversizedMimetypeZipEntryIsRejectedWithBoundedRead() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "bad-epub-" + UUID.randomUUID() + ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write(("application/epub+zip" + "x".repeat(100)).toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("<container/>".toByteArray())
                zip.closeEntry()
            }
            try {
                DiscoveryCatalogClient.validateEpub(file)
                fail("Oversized mimetype entries must be rejected.")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            file.delete()
        }
    }

    @Test fun standardEbooksDownloadUrlSelectsTheDirectFileResponseAndPreservesOtherQueryData() {
        assertEquals(
            "https://standardebooks.org/ebooks/example/book/downloads/book.epub?lang=en&tag=first&tag=second&source=download#contents",
            DiscoveryCatalogClient.standardEpubDownloadUrl(
                "https://standardebooks.org/ebooks/example/book/downloads/book.epub?lang=en&source=preview&tag=first&source=old&tag=second#contents"))
        assertEquals(
            "https://standardebooks.org/ebooks/example/book/downloads/book.epub?source=download",
            DiscoveryCatalogClient.standardEpubDownloadUrl(
                "https://standardebooks.org/ebooks/example/book/downloads/book.epub"))
    }

    @Test fun epubStreamingAcceptsBinaryZipBytesFromAnHttpResponse() {
        val payload = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("<container/>".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            bytes.toByteArray()
        }
        val server = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/epub+zip\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    output.write(payload)
                    output.flush()
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }.apply { isDaemon = true; start() }
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "downloaded-epub-" + UUID.randomUUID() + ".epub")
        try {
            runBlocking {
                DiscoveryCatalogClient.streamEpub("http://127.0.0.1:${server.localPort}/book.epub", file)
            }
            serverThread.join(5_000)
            assertFalse(serverThread.isAlive)
            assertNull(serverFailure.get())
            assertArrayEquals(payload, file.readBytes())
        } finally {
            server.close()
            serverThread.join(5_000)
            file.delete()
        }
    }
}
