package com.paperlight.reader.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfReflowEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun reflowsAcrossPageBoundaryWithoutDuplicateWordsAndWritesWorkingNav() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        PDFBoxResourceLoader.init(context)
        val input = createPdf(pageCount = 4) { document, pages ->
            drawLine(document, pages[0], "Chapter One", 746f, size = 20f)
            drawLine(document, pages[0], "This paragraph continues across", 710f)
            drawLine(document, pages[0], "the page boundary and", 694f)
            drawLine(document, pages[1], "finishes cleanly.", 710f)
            drawLine(document, pages[1], "Additional digital content keeps this page text rich.", 694f)
            drawLine(document, pages[2], "Third page contains enough ordinary text to stay on the text path.", 710f)
            drawLine(document, pages[3], "Fourth page contains enough ordinary text to stay on the text path.", 710f)
            pages.forEachIndexed { index, page ->
                drawLine(document, page, "RUNNING HEADER ${index + 1}", 780f, size = 8f)
                drawLine(document, page, "Page ${index + 1}", 12f, size = 8f)
            }
        }
        val output = File(temporaryFolder.root, "reflow.epub")
        val checkpoints = File(temporaryFolder.root, "checkpoint")

        val result = PdfReflowEngine(context).convert(input, output, "Fallback title", "Text", checkpoints, { null }, { _, _, _ -> })

        assertTrue(result is PdfConversionResult.Completed)
        assertTrue((result as PdfConversionResult.Completed).fallbackPageNumbers.isEmpty())
        ZipFile(output).use { zip ->
            assertEquals("mimetype", zip.entries().nextElement().name)
            val chapter = zip.getInputStream(zip.getEntry("EPUB/chapter-0001.xhtml")).bufferedReader().use { it.readText() }
            val textOnly = chapter.replace(Regex("<[^>]+>"), "")
            assertTrue(textOnly.contains("This paragraph continues across the page boundary and finishes cleanly."))
            assertEquals(1, "This paragraph continues across".toRegex().findAll(textOnly).count())
            assertTrue(chapter.indexOf("pdf-page-1") < chapter.indexOf("pdf-page-2"))
            assertTrue(chapter.indexOf("pdf-page-2") < chapter.indexOf("finishes cleanly."))
            assertTrue(!chapter.contains("RUNNING HEADER"))
            assertTrue(!chapter.contains("Page 1"))
            val nav = zip.getInputStream(zip.getEntry("EPUB/nav.xhtml")).bufferedReader().use { it.readText() }
            assertTrue(nav.contains("href=\"chapter-0001.xhtml\""))
            assertTrue(nav.contains("Chapter One"))
            assertTrue(zip.getEntry("EPUB/book.opf") != null)
        }
    }

    @Test
    fun resolvesDirectAndGoToBookmarksIntoChapterNavigation() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        PDFBoxResourceLoader.init(context)
        val input = createPdf { document, pages ->
            drawLine(document, pages[0], "Direct destination content with enough words to avoid OCR.", 700f)
            drawLine(document, pages[1], "GoTo destination content with enough words to avoid OCR.", 700f)
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val direct = PDOutlineItem().apply {
                title = "Direct bookmark"
                destination = PDPageFitDestination().apply { page = pages[0] }
            }
            val goTo = PDOutlineItem().apply {
                title = "GoTo bookmark"
                action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { page = pages[1] } }
            }
            outline.addLast(direct)
            outline.addLast(goTo)
        }
        val output = File(temporaryFolder.root, "outline.epub")
        val result = PdfReflowEngine(context).convert(
            input, output, "Outline fixture", "Text", File(temporaryFolder.root, "outline-checkpoint"), { null }, { _, _, _ -> },
        )
        assertTrue(result is PdfConversionResult.Completed)

        ZipFile(output).use { zip ->
            val nav = zip.getInputStream(zip.getEntry("EPUB/nav.xhtml")).bufferedReader().use { it.readText() }
            assertTrue(nav.contains("Direct bookmark"))
            assertTrue(nav.contains("GoTo bookmark"))
            assertTrue(nav.contains("chapter-0001.xhtml#pdf-page-1"))
            assertTrue(nav.contains("chapter-0002.xhtml#pdf-page-2"))
        }
    }

    @Test
    fun extractsHorizontalColumnGapsFromGeneratedTwoColumnPdf() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        PDFBoxResourceLoader.init(context)
        val input = createPdf(pageCount = 1) { document, pages ->
            repeat(8) { row ->
                val y = 730f - row * 18f
                drawLine(document, pages[0], "Left column line ${row + 1} has readable text.", y, x = 45f)
                drawLine(document, pages[0], "Right column line ${row + 1} has separate text.", y, x = 335f)
            }
        }
        PDDocument.load(input).use { document ->
            val lines = PdfReflowEngine(context).extractPage(document, 1)
            assertTrue("Extracted line geometry should expose the column gutter", PdfReflowRules.looksMultiColumn(lines, 612f))
        }
    }

    private fun createPdf(
        pageCount: Int = 2,
        fill: (PDDocument, List<PDPage>) -> Unit,
    ): File {
        val file = File(temporaryFolder.root, "fixture-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            val pages = List(pageCount) { PDPage(PDRectangle.LETTER).also(document::addPage) }
            document.documentInformation.title = "Fixture title"
            document.documentInformation.author = "Fixture author"
            fill(document, pages)
            document.save(file)
        }
        return file
    }

    private fun drawLine(document: PDDocument, page: PDPage, text: String, y: Float, x: Float = 50f, size: Float = 11f) {
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, size)
            stream.newLineAtOffset(x, y)
            stream.showText(text)
            stream.endText()
        }
    }
}
