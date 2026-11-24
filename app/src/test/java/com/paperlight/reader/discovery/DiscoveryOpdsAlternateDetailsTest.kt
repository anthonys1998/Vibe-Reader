package com.paperlight.reader.discovery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoveryOpdsAlternateDetailsTest {
    @Test fun explicitHtmlAlternateIsUsedForDetailsWhileAcquisitionRemainsTheEpub() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Book with web page</title>
                <id>urn:book:one</id>
                <link rel="alternate" type="application/xhtml+xml; charset=utf-8" href="../books/one"/>
                <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="../download/one.epub"/>
              </entry>
              <entry>
                <title>EPUB only</title>
                <id>urn:book:two</id>
                <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip" href="../download/two.epub"/>
              </entry>
            </feed>
        """.trimIndent()

        val page = DiscoveryCatalogClient.parseOpdsXml(xml, "https://catalog.example.org/feed/root.xml")

        assertEquals(2, page.books.size)
        assertEquals("https://catalog.example.org/books/one", page.books[0].detailsUrl)
        assertEquals("https://catalog.example.org/download/one.epub", page.books[0].epubUrl)
        assertEquals("https://catalog.example.org/download/two.epub", page.books[1].detailsUrl)
        assertEquals("https://catalog.example.org/download/two.epub", page.books[1].epubUrl)
    }
}
