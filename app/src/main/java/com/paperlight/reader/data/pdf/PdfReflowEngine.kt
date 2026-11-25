package com.paperlight.reader.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Independent offline PDF extraction, OCR, reflow and EPUB packaging pipeline. */
internal class PdfReflowEngine(private val context: Context) {
    companion object {
        private const val MAX_SOURCE_BYTES = 256L * 1024L * 1024L
        private const val MAX_EPUB_BYTES = 512L * 1024L * 1024L
        private const val MAX_OCR_WIDTH = 2200
        private const val MAX_OCR_HEIGHT = 3000
        private const val MAX_IMAGE_WIDTH = 1400
        private const val MAX_IMAGE_HEIGHT = 2100
        private const val MIN_TEXT_CHARS = 35
        private const val CONTAINER_XML = "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"EPUB/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
    }

    suspend fun convert(
        inputSnapshot: File,
        output: File,
        fallbackTitle: String,
        mode: String,
        checkpointDirectory: File,
        shouldStop: suspend () -> PdfConversionStop?,
        onCheckpointProgress: suspend (PdfConversionStage, Int, Int) -> Unit,
    ): PdfConversionResult = withContext(Dispatchers.IO) {
        require(inputSnapshot.isFile && inputSnapshot.length() > 0L) { "The PDF snapshot is missing or empty." }
        require(inputSnapshot.length() <= MAX_SOURCE_BYTES) { "This PDF is larger than the supported 256 MiB snapshot limit." }
        require(mode == "Text" || mode == "Images") { "Unsupported PDF conversion mode." }
        val inputHash = sha256Pdf(inputSnapshot)
        currentCoroutineContext().ensureActive()
        onCheckpointProgress(PdfConversionStage.SnapshotValidation, 0, 0)
        PDFBoxResourceLoader.init(context.applicationContext)
        val pdf = try {
            PDDocument.load(inputSnapshot, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir))
        } catch (_: InvalidPasswordException) {
            error("Password-protected PDFs cannot be converted.")
        }
        pdf.use { document ->
            require(!document.isEncrypted) { "Encrypted PDFs cannot be converted." }
            val totalPages = document.numberOfPages
            require(totalPages > 0) { "This PDF has no pages." }
            val checkpoint = PdfEpubCheckpointStore(checkpointDirectory, inputHash, mode, totalPages)
            val title = document.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: fallbackTitle
            val author = document.documentInformation.author?.trim().orEmpty()
            val language = document.documentCatalog.language?.trim()?.takeIf {
                it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
            } ?: "und"
            val outlines = extractOutlines(document)
            var rendererHandle: PdfRendererHandle? = null
            var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
            fun renderer(): PdfRendererHandle = rendererHandle ?: PdfRendererHandle(inputSnapshot).also { rendererHandle = it }
            try {
                for (number in 1..totalPages) {
                        currentCoroutineContext().ensureActive()
                        if (!checkpoint.hasPage(number)) {
                            requestedStop(shouldStop)?.let { return@withContext stopResult(it, inputHash) }
                            val page = document.getPage(number - 1)
                            val width = page.mediaBox.width.coerceAtLeast(1f)
                            val height = page.mediaBox.height.coerceAtLeast(1f)
                            var lines = if (mode == "Text") extractPage(document, number) else emptyList()
                            var usedOcr = false
                            if (mode == "Text" && lines.sumOf { it.text.length } < MIN_TEXT_CHARS) {
                                onCheckpointProgress(PdfConversionStage.OCR, number - 1, totalPages)
                                val recognized = try {
                                    val activeRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
                                    recognizePage(renderer(), number, width, height, activeRecognizer)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                if (recognized.isNotEmpty()) {
                                    lines = recognized
                                    usedOcr = true
                                }
                            }
                            val multiColumn = mode == "Text" && PdfReflowRules.looksMultiColumn(lines, width)
                            val hasRasterImages = mode == "Text" && !usedOcr && lines.sumOf { it.text.length } >= MIN_TEXT_CHARS && hasRasterImage(page)
                            val needsImage = mode == "Images" || lines.isEmpty() || multiColumn || hasRasterImages
                            currentCoroutineContext().ensureActive()
                            val tempImage = if (needsImage) renderPageToTemp(renderer(), number, checkpointDirectory) else null
                            try {
                                currentCoroutineContext().ensureActive()
                                checkpoint.commitPage(
                                    PdfPageRecord(
                                        number = number,
                                        width = width,
                                        height = height,
                                        lines = lines,
                                        usedOcr = usedOcr,
                                        imageFallback = needsImage,
                                    ),
                                    tempImage,
                                )
                            } finally {
                                tempImage?.delete()
                            }
                        }
                        val record = checkpoint.readPage(number)
                        onCheckpointProgress(if (record.usedOcr) PdfConversionStage.OCR else PdfConversionStage.Extracting, number, totalPages)
                        requestedStop(shouldStop)?.let { return@withContext stopResult(it, inputHash) }
                }
            } finally {
                recognizer?.close()
                rendererHandle?.close()
            }

            val repeatedEdges = PdfReflowRules.repeatedEdgeKeys((1..totalPages).asSequence().map(checkpoint::readPage))
            val pageHeadings = findPageHeadings(checkpoint, totalPages, repeatedEdges, mode)
            val fallbackPages = (1..totalPages).filter { checkpoint.readPage(it).imageFallback }
            writeDiagnostics(checkpointDirectory, fallbackPages, repeatedEdges.size)
            onCheckpointProgress(PdfConversionStage.Reflow, totalPages, totalPages)
            requestedStop(shouldStop)?.let { return@withContext stopResult(it, inputHash) }
            onCheckpointProgress(PdfConversionStage.Packaging, 0, totalPages)
            requestedStop(shouldStop)?.let { return@withContext stopResult(it, inputHash) }
            val completed = packageEpub(
                checkpoint = checkpoint,
                totalPages = totalPages,
                title = title,
                author = author,
                language = language,
                mode = mode,
                outlines = outlines,
                pageHeadings = pageHeadings,
                repeatedEdges = repeatedEdges,
                output = output,
                shouldStop = shouldStop,
            )
            when (completed) {
                PdfConversionStop.Pause -> PdfConversionResult.Paused(inputHash)
                PdfConversionStop.Cancel -> PdfConversionResult.Cancelled(inputHash)
                PdfConversionStop.Continue -> {
                    onCheckpointProgress(PdfConversionStage.Complete, totalPages, totalPages)
                    PdfConversionResult.Completed(title, author, inputHash, fallbackPages)
                }
            }
        }
    }

    internal fun extractPage(document: PDDocument, number: Int): List<PdfTextLine> {
        val lines = mutableListOf<PdfTextLine>()
        var totalChars = 0
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                val clean = text.trim().take(PdfEpubCheckpointStore.MAX_LINE_CHARS).replace(Regex("\\s+"), " ")
                if (clean.isEmpty()) return
                require(lines.size < PdfEpubCheckpointStore.MAX_LINES_PER_PAGE) { "PDF page $number contains too many text lines." }
                totalChars += clean.length
                require(totalChars <= 1_500_000) { "PDF page $number contains too much text." }
                val positions = textPositions.filter { !it.unicode.isNullOrBlank() }
                val x = positions.minOfOrNull { it.xDirAdj } ?: return
                val y = positions.minOfOrNull { it.yDirAdj } ?: return
                val sortedPositions = positions.sortedBy { it.xDirAdj }
                val maxHorizontalGap = sortedPositions.zipWithNext().maxOfOrNull { (left, right) ->
                    (right.xDirAdj - (left.xDirAdj + left.widthDirAdj)).coerceAtLeast(0f)
                } ?: 0f
                lines += PdfTextLine(
                    clean.take(PdfEpubCheckpointStore.MAX_LINE_CHARS),
                    x,
                    y,
                    positions.maxOfOrNull { it.xDirAdj + it.widthDirAdj }?.minus(x)?.coerceAtLeast(0f) ?: 0f,
                    positions.map { it.fontSizeInPt }.average().toFloat().takeIf { it.isFinite() } ?: 0f,
                    maxHorizontalGap,
                )
            }
        }.apply {
            setSortByPosition(true)
            startPage = number
            endPage = number
        }
        stripper.writeText(document, java.io.Writer.nullWriter())
        return lines.sortedWith(compareBy<PdfTextLine> { it.y }.thenBy { it.x })
    }

    private suspend fun recognizePage(
        renderer: PdfRendererHandle,
        number: Int,
        pageWidth: Float,
        pageHeight: Float,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): List<PdfTextLine> {
        currentCoroutineContext().ensureActive()
        val page = renderer.openPage(number)
        return page.use {
            val scale = minOf(MAX_OCR_WIDTH.toFloat() / page.width, MAX_OCR_HEIGHT.toFloat() / page.height)
            val bitmapWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
            val bitmapHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
            require(bitmapWidth.toLong() * bitmapHeight <= MAX_OCR_WIDTH.toLong() * MAX_OCR_HEIGHT) { "PDF OCR bitmap exceeded its size limit." }
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                val xRatio = pageWidth / bitmapWidth.toFloat()
                val yRatio = pageHeight / bitmapHeight.toFloat()
                result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val text = line.text.trim().take(PdfEpubCheckpointStore.MAX_LINE_CHARS)
                    if (text.isBlank()) return@mapNotNull null
                    PdfTextLine(text, box.left * xRatio, box.top * yRatio, box.width() * xRatio, box.height() * yRatio)
                }.sortedWith(compareBy<PdfTextLine> { it.y }.thenBy { it.x })
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun findPageHeadings(
        checkpoint: PdfEpubCheckpointStore,
        totalPages: Int,
        repeatedEdges: Set<String>,
        mode: String,
    ): Map<Int, Heading> {
        if (mode == "Images") return emptyMap()
        val headings = mutableMapOf<Int, Heading>()
        for (number in 1..totalPages) {
            val page = checkpoint.readPage(number)
            if (page.imageFallback) continue
            val lines = page.lines.filterNot { PdfReflowRules.isRepeatedEdge(it, page, repeatedEdges) }
            if (lines.isEmpty()) continue
            val median = lines.map { it.fontSize }.filter { it > 0f }.sorted().let { it.getOrNull(it.size / 2) ?: 10f }
            val heading = lines.firstOrNull { line ->
                line.y / page.height <= 0.30f && line.text.length <= 100 && (
                    line.text.matches(Regex("(?i)^(chapter|part|book|prologue|epilogue|appendix)\\b.*")) ||
                        (line.fontSize >= median * 1.35f && line.text.length <= 80)
                    )
            } ?: continue
            headings[number] = Heading(heading.text, heading)
        }
        return headings
    }

    private suspend fun packageEpub(
        checkpoint: PdfEpubCheckpointStore,
        totalPages: Int,
        title: String,
        author: String,
        language: String,
        mode: String,
        outlines: List<PdfOutlineEntry>,
        pageHeadings: Map<Int, Heading>,
        repeatedEdges: Set<String>,
        output: File,
        shouldStop: suspend () -> PdfConversionStop?,
    ): PdfConversionStop {
        val parent = output.absoluteFile.parentFile ?: error("Could not create the EPUB output directory.")
        if (!parent.exists() && !parent.mkdirs()) error("Could not create the EPUB output directory.")
        if (output.exists() && !output.delete()) error("Could not replace the previous EPUB output.")
        val temporary = File(parent, output.name + ".${UUID.randomUUID()}.tmp")
        val sections = mutableListOf<EpubSection>()
        val imagePages = mutableListOf<Int>()
        val outlineByPage = outlines.filter { it.pageNumber in 1..totalPages }.groupBy { it.pageNumber }
        var stop = PdfConversionStop.Continue
        try {
            BoundedOutputStream(FileOutputStream(temporary), MAX_EPUB_BYTES).use { bounded ->
                ZipOutputStream(BufferedOutputStream(bounded)).use { zip ->
                    writeMimetype(zip)
                    zip.writeXml("META-INF/container.xml", CONTAINER_XML)
                    // Images are emitted before content so each chapter can be streamed as a single zip entry.
                    for (pageNumber in 1..totalPages) {
                        currentCoroutineContext().ensureActive()
                        val record = checkpoint.readPage(pageNumber)
                        if (record.imageFallback && record.imageFile != null) {
                            zip.putNextEntry(ZipEntry("EPUB/page-$pageNumber.jpg"))
                            record.imageFile.inputStream().use { it.copyTo(zip, 32 * 1024) }
                            zip.closeEntry()
                            imagePages += pageNumber
                        }
                        requestedStop(shouldStop)?.let { stop = it; return@use }
                    }
                    var activeSection: EpubSection? = null
                    var pendingParagraph: StringBuilder? = null
                    val pendingPageAnchors = mutableListOf<Pair<Int, Int>>()
                    var pendingX = 0f
                    var pendingLastLine = ""
                    var pageAnchorWritten = false
                    fun writePageAnchor(pageNumber: Int) {
                        zip.write("<span id=\"pdf-page-$pageNumber\"></span>".toByteArray(Charsets.UTF_8))
                    }
                    fun flushParagraph() {
                        val pending = pendingParagraph ?: return
                        if (pending.isNotEmpty()) {
                            zip.write("<p>".toByteArray(Charsets.UTF_8))
                            var offset = 0
                            pendingPageAnchors.sortedBy { it.first }.forEach { (position, page) ->
                                val safePosition = position.coerceIn(offset, pending.length)
                                zip.write(xml(pending.substring(offset, safePosition)).toByteArray(Charsets.UTF_8))
                                writePageAnchor(page)
                                offset = safePosition
                            }
                            zip.write(xml(pending.substring(offset).trim()).toByteArray(Charsets.UTF_8))
                            zip.write("</p>".toByteArray(Charsets.UTF_8))
                        }
                        pendingPageAnchors.clear()
                        pendingParagraph = null
                    }
                    fun closeSection() {
                        flushParagraph()
                        if (activeSection != null) {
                            zip.write("</body></html>".toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                    fun startSection(sectionTitle: String) {
                        closeSection()
                        val section = EpubSection(sections.size + 1, sectionTitle)
                        sections += section
                        activeSection = section
                        val html = "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"${xml(language)}\"><head><title>${xml(sectionTitle)}</title><meta charset=\"UTF-8\"/></head><body><h1>${xml(sectionTitle)}</h1>"
                        zip.putNextEntry(ZipEntry(section.path))
                        zip.write(html.toByteArray(Charsets.UTF_8))
                    }
                    for (pageNumber in 1..totalPages) {
                        currentCoroutineContext().ensureActive()
                        pageAnchorWritten = false
                        val record = checkpoint.readPage(pageNumber)
                        val explicitTitle = outlineByPage[pageNumber]?.firstOrNull()?.title?.takeIf(String::isNotBlank)
                        val heading = pageHeadings[pageNumber]
                        val startsSection = mode == "Images" || explicitTitle != null || heading != null
                        if (activeSection == null || startsSection) {
                            val sectionTitle = explicitTitle ?: heading?.text ?: if (mode == "Images") "Page $pageNumber" else title.ifBlank { "Document" }
                            startSection(sectionTitle)
                            activeSection?.startPage = pageNumber
                        }
                        if (record.imageFallback) {
                            flushParagraph()
                            writePageAnchor(pageNumber)
                            pageAnchorWritten = true
                            if (record.imageFile != null) {
                                zip.write("<p><img src=\"page-$pageNumber.jpg\" alt=\"PDF page $pageNumber (image fallback)\" style=\"max-width:100%;height:auto;\"/></p>".toByteArray(Charsets.UTF_8))
                            } else {
                                zip.write("<p>Page $pageNumber could not be reflowed.</p>".toByteArray(Charsets.UTF_8))
                            }
                        } else {
                            val lines = record.lines.filterNot { PdfReflowRules.isRepeatedEdge(it, record, repeatedEdges) }
                                .filterNot { heading != null && it == heading.line }
                            val paragraphs = PdfReflowRules.paragraphs(lines)
                            if (paragraphs.isEmpty()) {
                                flushParagraph()
                                writePageAnchor(pageNumber)
                                pageAnchorWritten = true
                            }
                            paragraphs.forEachIndexed { index, paragraph ->
                                val pending = pendingParagraph
                                if (pending != null && index == 0 && PdfReflowRules.canContinue(pendingLastLine, paragraph.firstLine, pendingX, paragraph.startX)) {
                                    pending.append(PdfReflowRules.joinSuffix(pendingLastLine, paragraph.firstLine))
                                    pendingPageAnchors += pending.length to pageNumber
                                    pageAnchorWritten = true
                                    pending.append(paragraph.text)
                                    pendingLastLine = paragraph.lastLine
                                } else {
                                    flushParagraph()
                                    if (!pageAnchorWritten) {
                                        writePageAnchor(pageNumber)
                                        pageAnchorWritten = true
                                    }
                                    pendingParagraph = StringBuilder(paragraph.text)
                                    pendingX = paragraph.startX
                                    pendingLastLine = paragraph.lastLine
                                }
                            }
                        }
                        requestedStop(shouldStop)?.let { stop = it; return@use }
                    }
                    closeSection()
                    if (sections.isEmpty()) error("No readable content could be extracted from this PDF.")
                    zip.writeXml("EPUB/nav.xhtml", buildNavigation(sections, outlines, language))
                    val chapterItems = sections.joinToString("") { "<item id=\"chapter${it.number}\" href=\"${it.fileName}\" media-type=\"application/xhtml+xml\"/>" }
                    val imageItems = imagePages.mapIndexed { index, page -> "<item id=\"image${index + 1}\" href=\"page-$page.jpg\" media-type=\"image/jpeg\"/>" }.joinToString("")
                    val items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" + chapterItems + imageItems
                    val spine = sections.joinToString("") { "<itemref idref=\"chapter${it.number}\"/>" }
                    val creator = if (author.isBlank()) "" else "<dc:creator>${xml(author)}</dc:creator>"
                    val modified = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                    zip.writeXml(
                        "EPUB/book.opf",
                        "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"book-id\">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${xml(title)}</dc:title><dc:language>${xml(language)}</dc:language>$creator<meta property=\"dcterms:modified\">$modified</meta></metadata><manifest>$items</manifest><spine>$spine</spine></package>",
                    )
                }
            }
            if (stop != PdfConversionStop.Continue) return stop
            validateEpub(temporary, sections.size, imagePages.size)
            if (output.exists() && !output.delete()) error("Could not replace the previous EPUB output.")
            if (!temporary.renameTo(output)) error("Could not finish the EPUB output file.")
            return PdfConversionStop.Continue
        } finally {
            temporary.delete()
        }
    }

    private fun extractOutlines(document: PDDocument): List<PdfOutlineEntry> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        val entries = mutableListOf<PdfOutlineEntry>()
        val visited = Collections.newSetFromMap(IdentityHashMap<PDOutlineItem, Boolean>())
        var visitedCount = 0
        fun visit(item: PDOutlineItem?, level: Int) {
            if (level > 32) return
            var current = item
            while (current != null && visitedCount < 10_000 && visited.add(current)) {
                visitedCount++
                val page = try {
                    current.findDestinationPage(document)?.let { document.pages.indexOf(it) + 1 }
                } catch (_: Exception) {
                    null
                }
                if (page != null && page > 0) {
                    current.title?.trim()?.takeIf(String::isNotEmpty)?.let { entries += PdfOutlineEntry(page, it, level) }
                }
                current.firstChild?.let { visit(it, level + 1) }
                current = current.nextSibling
            }
        }
        visit(root.firstChild, 0)
        return entries
    }

    private fun buildNavigation(sections: List<EpubSection>, outlines: List<PdfOutlineEntry>, language: String): String {
        val byPage = sections.mapNotNull { section ->
            val page = section.startPage ?: return@mapNotNull null
            page to section
        }.toMap()
        val navItems = outlines.mapNotNull { outline ->
            val section = byPage[outline.pageNumber] ?: sections.firstOrNull { it.startPage != null && it.startPage!! <= outline.pageNumber }
            section?.let { "<li><a href=\"${it.fileName}#pdf-page-${outline.pageNumber}\">${xml(outline.title)}</a></li>" }
        }.distinct().joinToString("")
        val seenFiles = outlines.mapNotNull { outline -> byPage[outline.pageNumber]?.fileName }.toSet()
        val inferred = sections.filterNot { it.fileName in seenFiles }.joinToString("") { "<li><a href=\"${it.fileName}\">${xml(it.title)}</a></li>" }
        val toc = (navItems + inferred).ifBlank { sections.joinToString("") { "<li><a href=\"${it.fileName}\">${xml(it.title)}</a></li>" } }
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"${xml(language)}\"><head><title>Contents</title></head><body><nav epub:type=\"toc\"><h1>Contents</h1><ol>$toc</ol></nav></body></html>"
    }

    private fun writeDiagnostics(directory: File, fallbackPages: List<Int>, repeatedCount: Int) {
        val target = File(directory, "diagnostics.txt")
        val temporary = File(directory, "diagnostics.txt.tmp")
        val body = buildString {
            appendLine("pdf-checkpoint-v2")
            appendLine("fallback-pages=${fallbackPages.joinToString(",")}")
            appendLine("repeated-edge-keys-removed=$repeatedCount")
            appendLine("embedded-raster-figures=not-extracted; mixed pages use page-image fallback")
        }
        FileOutputStream(temporary).use { stream ->
            stream.write(body.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (target.exists() && !target.delete()) error("Could not update PDF checkpoint diagnostics.")
        if (!temporary.renameTo(target)) error("Could not commit PDF checkpoint diagnostics.")
    }

    private fun validateEpub(file: File, expectedChapters: Int, expectedImages: Int) {
        ZipFile(file).use { zip ->
            require(zip.getEntry("mimetype") != null && zip.getEntry("META-INF/container.xml") != null &&
                zip.getEntry("EPUB/book.opf") != null && zip.getEntry("EPUB/nav.xhtml") != null) {
                "The generated EPUB is missing required package files."
            }
            require(zip.entries().asSequence().count { it.name.matches(Regex("EPUB/chapter-[0-9]{4,}\\.xhtml")) } == expectedChapters) {
                "The generated EPUB has an incomplete chapter list."
            }
            require(zip.entries().asSequence().count { it.name.matches(Regex("EPUB/page-[0-9]+\\.jpg")) } == expectedImages) {
                "The generated EPUB has an incomplete image list."
            }
        }
    }

    private fun renderPageToTemp(renderer: PdfRendererHandle, number: Int, directory: File): File {
        val page = renderer.openPage(number)
        page.use {
            val scale = minOf(MAX_IMAGE_WIDTH.toFloat() / page.width, MAX_IMAGE_HEIGHT.toFloat() / page.height)
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            require(width.toLong() * height <= MAX_IMAGE_WIDTH.toLong() * MAX_IMAGE_HEIGHT) { "PDF page image exceeded its size limit." }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val temp = File(directory, "page-${number.toString().padStart(6, '0')}.jpg.tmp")
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                FileOutputStream(temp).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) { "Could not encode PDF page $number." }
                    stream.flush()
                    stream.fd.sync()
                }
                return temp
            } catch (failure: Throwable) {
                temp.delete()
                throw failure
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun requestedStop(callback: suspend () -> PdfConversionStop?): PdfConversionStop? {
        currentCoroutineContext().ensureActive()
        return callback()?.takeUnless { it == PdfConversionStop.Continue }
    }

    private fun stopResult(stop: PdfConversionStop, hash: String): PdfConversionResult = when (stop) {
        PdfConversionStop.Pause -> PdfConversionResult.Paused(hash)
        PdfConversionStop.Cancel -> PdfConversionResult.Cancelled(hash)
        PdfConversionStop.Continue -> error("Continue is not a stop request.")
    }

    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray()
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(bytes) }.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun ZipOutputStream.writeXml(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray())
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun xml(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (char >= ' ' && char != '\uFFFE' && char != '\uFFFF') append(char)
            }
        }
    }

    private data class Heading(val text: String, val line: PdfTextLine)
    private data class EpubSection(val number: Int, val title: String, var startPage: Int? = null) {
        val fileName: String get() = "chapter-${number.toString().padStart(4, '0')}.xhtml"
        val path: String get() = "EPUB/$fileName"
    }

    private class PdfRendererHandle(source: File) : AutoCloseable {
        private val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try {
            PdfRenderer(descriptor)
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }

        fun openPage(number: Int): PdfRenderer.Page = renderer.openPage(number - 1)
        override fun close() {
            renderer.close()
            descriptor.close()
        }
    }

    private class BoundedOutputStream(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        private var written = 0L
        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            written++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            out.write(buffer, offset, length)
            written += length
        }
        private fun ensureCapacity(amount: Int) {
            if (amount < 0 || written + amount > limit) error("Generated EPUB exceeds the supported 512 MiB output limit.")
        }
    }

    private fun hasRasterImage(page: PDPage): Boolean = page.resources?.xObjectNames?.any { name ->
        try {
            page.resources?.getXObject(name) is PDImageXObject
        } catch (_: Exception) {
            false
        }
    } == true
}
