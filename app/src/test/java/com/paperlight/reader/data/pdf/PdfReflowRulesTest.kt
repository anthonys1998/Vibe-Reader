package com.paperlight.reader.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReflowRulesTest {
    @Test
    fun joinsWrappedLinesOnceAndPreservesVisibleHyphens() {
        val paragraphs = PdfReflowRules.paragraphs(
            listOf(
                line("This is a", y = 10f, width = 45f),
                line("well-", y = 22f, width = 28f),
                line("known phrase.", y = 34f, width = 72f),
            ),
        )

        assertEquals(1, paragraphs.size)
        assertEquals("This is a well-known phrase.", paragraphs.single().text)
        assertEquals("well-known", "well-" + PdfReflowRules.joinSuffix("well-", "known") + "known")
    }

    @Test
    fun splitsIndentedParagraphAndChecksCrossPageContinuationConservatively() {
        val paragraphs = PdfReflowRules.paragraphs(
            listOf(
                line("A finished sentence.", x = 40f, y = 10f, width = 130f),
                line("An indented paragraph.", x = 60f, y = 22f, width = 150f),
            ),
        )
        assertEquals(2, paragraphs.size)
        assertTrue(PdfReflowRules.canContinue("A sentence with", "a continuation", 40f, 40f))
        assertFalse(PdfReflowRules.canContinue("A sentence ends.", "A new paragraph", 40f, 40f))
        assertFalse(PdfReflowRules.canContinue("A sentence with", "Chapter 2", 40f, 40f))
    }

    @Test
    fun removesOnlyStronglyRepeatedEdgeLines() {
        val pages = (1..4).map { pageNumber ->
            PdfPageRecord(
                number = pageNumber,
                width = 612f,
                height = 792f,
                lines = listOf(
                    line("Sample Book $pageNumber", y = 15f),
                    line("Body content $pageNumber", y = 120f),
                    line("Page $pageNumber", y = 770f),
                ),
                usedOcr = false,
                imageFallback = false,
            )
        }
        val repeated = PdfReflowRules.repeatedEdgeKeys(pages.asSequence())
        assertEquals(2, repeated.size)
        assertTrue(PdfReflowRules.isRepeatedEdge(pages.first().lines.first(), pages.first(), repeated))
        assertFalse(PdfReflowRules.isRepeatedEdge(pages.first().lines[1], pages.first(), repeated))
        assertTrue(PdfReflowRules.isRepeatedEdge(pages.first().lines.last(), pages.first(), repeated))
    }

    @Test
    fun fallsBackWhenOneExtractedRowHasAColumnGutter() {
        assertTrue(PdfReflowRules.looksMultiColumn(listOf(line("left and right text", y = 10f, maxGap = 80f)), 612f))
        assertFalse(PdfReflowRules.looksMultiColumn(listOf(line("an ordinary single line", y = 10f, maxGap = 4f)), 612f))
    }

    private fun line(
        text: String,
        x: Float = 40f,
        y: Float,
        width: Float = 100f,
        maxGap: Float = 0f,
    ) = PdfTextLine(text, x, y, width, 10f, maxGap)
}
