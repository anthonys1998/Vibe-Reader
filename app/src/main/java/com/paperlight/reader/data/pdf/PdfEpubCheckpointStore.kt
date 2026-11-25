package com.paperlight.reader.data.pdf

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Versioned, app-private page checkpoints. A page record is the commit marker for its image artifact. */
internal class PdfEpubCheckpointStore(
    private val directory: File,
    private val sourceSha256: String,
    private val mode: String,
    private val totalPages: Int,
) {
    companion object {
        const val VERSION = 2
        private const val MANIFEST_MAGIC = 0x50444643 // PDFC
        private const val PAGE_MAGIC = 0x50444650 // PDFP
        const val MAX_PAGES = 10_000
        const val MAX_LINES_PER_PAGE = 20_000
        const val MAX_LINE_CHARS = 8_192
        private const val MAX_PAGE_TEXT_CHARS = 1_500_000
        private const val MAX_PAGE_RECORD_BYTES = 16L * 1024L * 1024L
        private const val MAX_IMAGE_BYTES = 16L * 1024L * 1024L
        private const val MAX_CHECKPOINT_BYTES = 512L * 1024L * 1024L
    }

    private var usedBytes = 0L
    private val verifiedImagePages = mutableSetOf<Int>()

    init {
        require(totalPages in 1..MAX_PAGES) { "This PDF exceeds the supported page limit ($MAX_PAGES)." }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid PDF snapshot hash." }
        require(mode == "Text" || mode == "Images") { "Unsupported PDF conversion mode." }
        if (!directory.exists() && !directory.mkdirs()) error("Could not create the PDF checkpoint directory.")
        require(directory.isDirectory) { "The PDF checkpoint path is not a directory." }
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach(File::delete)
        val manifest = File(directory, "manifest.bin")
        if (manifest.exists()) validateManifest(manifest) else writeManifest(manifest)
        refreshUsedBytes()
    }

    fun pageFile(number: Int): File = File(directory, "page-${number.toString().padStart(6, '0')}.bin")
    fun imageFile(number: Int): File = File(directory, "page-${number.toString().padStart(6, '0')}.jpg")
    fun hasPage(number: Int): Boolean = pageFile(number).isFile

    fun readPage(number: Int): PdfPageRecord {
        require(number in 1..totalPages)
        val file = pageFile(number)
        require(file.isFile) { "PDF page $number has not been checkpointed." }
        require(file.length() <= MAX_PAGE_RECORD_BYTES) { "PDF page $number checkpoint exceeds the supported size." }
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == PAGE_MAGIC && input.readInt() == VERSION) { "PDF page $number checkpoint has an unsupported format." }
            require(input.readInt() == number) { "PDF page checkpoint numbering is inconsistent." }
            val width = input.readFloat()
            val height = input.readFloat()
            val usedOcr = input.readBoolean()
            val imageFallback = input.readBoolean()
            val expectedImageHash = input.readUTF()
            val lineCount = input.readInt()
            require(width.isFinite() && width > 0f && height.isFinite() && height > 0f) { "PDF page $number has invalid dimensions." }
            require(lineCount in 0..MAX_LINES_PER_PAGE) { "PDF page $number checkpoint has an invalid line count." }
            val lines = ArrayList<PdfTextLine>(lineCount)
            var pageTextChars = 0L
            repeat(lineCount) {
                val text = input.readUTF()
                val x = input.readFloat()
                val y = input.readFloat()
                val lineWidth = input.readFloat()
                val fontSize = input.readFloat()
                val maxHorizontalGap = input.readFloat()
                require(text.length <= MAX_LINE_CHARS && x.isFinite() && y.isFinite() && lineWidth.isFinite() && fontSize.isFinite() && maxHorizontalGap.isFinite() && maxHorizontalGap >= 0f) {
                    "PDF page $number checkpoint contains invalid text geometry."
                }
                pageTextChars += text.length
                require(pageTextChars <= MAX_PAGE_TEXT_CHARS) { "PDF page $number checkpoint contains too much text." }
                lines += PdfTextLine(text, x, y, lineWidth, fontSize, maxHorizontalGap)
            }
            require(input.read() == -1) { "PDF page $number checkpoint has trailing data." }
            val artifact = if (expectedImageHash.isEmpty()) null else imageFile(number).also { image ->
                if (number !in verifiedImagePages) {
                    require(image.isFile && image.length() > 0L && sha256(image) == expectedImageHash) {
                        "PDF page $number image checkpoint is missing or damaged."
                    }
                    verifiedImagePages += number
                }
            }
            require(!imageFallback || artifact != null) { "PDF page $number is marked for image fallback without its image artifact." }
            return PdfPageRecord(number, width, height, lines, usedOcr, imageFallback, artifact)
        }
    }

    /** Installs a completed image first and publishes the page record last. */
    fun commitPage(record: PdfPageRecord, imageTemporaryFile: File? = null) {
        require(record.number in 1..totalPages)
        require(record.lines.size <= MAX_LINES_PER_PAGE) { "PDF page ${record.number} contains too much text." }
        require(!record.imageFallback || imageTemporaryFile != null) {
            "PDF page ${record.number} needs an image artifact for fallback."
        }
        require(record.lines.sumOf { it.text.length.toLong() } <= MAX_PAGE_TEXT_CHARS) { "PDF page ${record.number} contains too much text." }
        var expectedImageHash = ""
        val artifact = imageFile(record.number)
        if (imageTemporaryFile != null) {
            require(imageTemporaryFile.isFile && imageTemporaryFile.length() > 0L) { "Could not render PDF page ${record.number}." }
            require(imageTemporaryFile.length() <= MAX_IMAGE_BYTES) { "PDF page ${record.number} image exceeds the supported size." }
            ensureCheckpointCapacity(imageTemporaryFile.length() - if (artifact.exists()) artifact.length() else 0L)
            expectedImageHash = sha256(imageTemporaryFile)
            if (artifact.exists() && !artifact.delete()) error("Could not replace an incomplete PDF image checkpoint.")
            if (!imageTemporaryFile.renameTo(artifact)) error("Could not commit PDF page ${record.number} image checkpoint.")
            refreshUsedBytes()
        }
        val committedRecord = record.copy(imageFile = if (expectedImageHash.isEmpty()) null else artifact)
        val target = pageFile(record.number)
        val temporary = File(directory, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.writeInt(PAGE_MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(committedRecord.number)
                    output.writeFloat(committedRecord.width)
                    output.writeFloat(committedRecord.height)
                    output.writeBoolean(committedRecord.usedOcr)
                    output.writeBoolean(committedRecord.imageFallback)
                    output.writeUTF(expectedImageHash)
                    output.writeInt(committedRecord.lines.size)
                    committedRecord.lines.forEach { line ->
                        require(line.text.length <= MAX_LINE_CHARS) { "A PDF text line exceeds the supported size." }
                        output.writeUTF(line.text)
                        output.writeFloat(line.x)
                        output.writeFloat(line.y)
                        output.writeFloat(line.width)
                        output.writeFloat(line.fontSize)
                        output.writeFloat(line.maxHorizontalGap)
                    }
                    output.flush()
                    stream.fd.sync()
                }
            }
            require(temporary.length() <= MAX_PAGE_RECORD_BYTES) { "PDF page ${record.number} checkpoint exceeds the supported size." }
            ensureCheckpointCapacity(temporary.length() - if (target.exists()) target.length() else 0L)
            if (target.exists() && !target.delete()) error("Could not replace PDF page checkpoint ${record.number}.")
            if (!temporary.renameTo(target)) error("Could not commit PDF page checkpoint ${record.number}.")
            refreshUsedBytes()
        } finally {
            temporary.delete()
        }
    }

    private fun validateManifest(file: File) {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MANIFEST_MAGIC && input.readInt() == VERSION) { "PDF checkpoint format is unsupported; reset this conversion to continue." }
            require(input.readUTF() == sourceSha256 && input.readUTF() == mode && input.readInt() == totalPages) {
                "PDF checkpoint does not match this input and conversion mode; reset this conversion to continue."
            }
            require(input.read() == -1) { "PDF checkpoint manifest is damaged; reset this conversion to continue." }
        }
    }

    private fun writeManifest(file: File) {
        val temporary = File(directory, "manifest.bin.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    output.writeInt(MANIFEST_MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(sourceSha256)
                    output.writeUTF(mode)
                    output.writeInt(totalPages)
                    output.flush()
                    stream.fd.sync()
                }
            }
            if (!temporary.renameTo(file)) error("Could not commit PDF checkpoint manifest.")
        } finally {
            temporary.delete()
        }
    }

    private fun ensureCheckpointCapacity(delta: Long) {
        require(usedBytes + delta <= MAX_CHECKPOINT_BYTES) { "PDF checkpoints exceed the supported 512 MiB storage limit." }
    }

    private fun refreshUsedBytes() {
        usedBytes = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
        require(usedBytes <= MAX_CHECKPOINT_BYTES) { "PDF checkpoints exceed the supported 512 MiB storage limit." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun sha256Pdf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
