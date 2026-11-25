package com.paperlight.reader.webnovel

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class NovelPingChapterArchiveTest {
    @Test fun archiveUrlUsesTheNovelIdOnThePageOrigin() {
        val page = Jsoup.parse(
            "<div id='rating' data-novel-id='dark-survival'></div>",
            "https://novelping.com/book/dark-survival#tab-chapters-title",
        )

        assertEquals(
            "https://novelping.com/ajax/chapter-archive?novelId=dark-survival",
            NovelPingChapterArchive.urlFor(page),
        )
    }

    @Test fun archiveUrlRejectsOtherHostsAndMalformedNovelIds() {
        val otherHost = Jsoup.parse(
            "<div id='rating' data-novel-id='story'></div>", "https://example.com/book/story",
        )
        val malformedId = Jsoup.parse(
            "<div id='rating' data-novel-id='../other-host'></div>", "https://novelping.com/book/story",
        )

        assertNull(NovelPingChapterArchive.urlFor(otherHost))
        assertNull(NovelPingChapterArchive.urlFor(malformedId))
    }

    @Test fun archiveLinksKeepSiteOrderAndExcludeCrossOriginLinks() {
        val archive = Jsoup.parse(
            """
            <ul>
              <li><a href="/book/story/chapter-2">Chapter 2: Second</a></li>
              <li><a href="https://novelping.com/book/story/chapter-1">Chapter 1: First</a></li>
              <li><a href="https://example.com/book/story/chapter-3">Chapter 3: External</a></li>
            </ul>
            """.trimIndent(),
            "https://novelping.com/ajax/chapter-archive?novelId=story",
        )

        assertEquals(
            listOf(
                "https://novelping.com/book/story/chapter-2" to "Chapter 2: Second",
                "https://novelping.com/book/story/chapter-1" to "Chapter 1: First",
            ),
            NovelPingChapterArchive.extractLinks(archive, URI("https://novelping.com/book/story"), "a[href]"),
        )
    }
}
