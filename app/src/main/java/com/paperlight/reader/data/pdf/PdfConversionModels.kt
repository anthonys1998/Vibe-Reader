package com.paperlight.reader.data.pdf

import java.io.File

enum class PdfConversionStage {
    SnapshotValidation,
    Extracting,
    OCR,
    Reflow,
    Packaging,
    Complete,
}

enum class PdfConversionStop { Continue, Pause, Cancel }

sealed interface PdfConversionResult {
    val inputSha256: String

    data class Completed(
        val title: String,
        val author: String,
        override val inputSha256: String,
        val fallbackPageNumbers: List<Int> = emptyList(),
    ) : PdfConversionResult
    data class Paused(override val inputSha256: String) : PdfConversionResult
    data class Cancelled(override val inputSha256: String) : PdfConversionResult
}

data class PdfTextLine(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val fontSize: Float,
    val maxHorizontalGap: Float = 0f,
)

data class PdfPageRecord(
    val number: Int,
    val width: Float,
    val height: Float,
    val lines: List<PdfTextLine>,
    val usedOcr: Boolean,
    val imageFallback: Boolean,
    val imageFile: File? = null,
)

data class PdfOutlineEntry(val pageNumber: Int, val title: String, val level: Int)
