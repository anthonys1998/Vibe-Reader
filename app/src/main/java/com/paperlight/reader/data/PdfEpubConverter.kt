package com.paperlight.reader.data

import android.content.Context
import com.paperlight.reader.data.pdf.PdfConversionResult
import com.paperlight.reader.data.pdf.PdfConversionStage
import com.paperlight.reader.data.pdf.PdfConversionStop
import com.paperlight.reader.data.pdf.PdfReflowEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class PdfEpubMode { Text, Images }

/** Compatibility facade for the original page-image/text converter and the durable job runner. */
internal class PdfEpubConverter(private val context: Context) {
    private val engine by lazy { PdfReflowEngine(context.applicationContext) }

    suspend fun convert(
        source: File,
        destination: File,
        fallbackTitle: String,
        mode: PdfEpubMode,
        onProgress: (Int, Int) -> Unit,
    ): Pair<String, String> {
        val checkpointDirectory = File(context.cacheDir, "pdf-epub-legacy-${UUID.randomUUID()}")
        try {
            return when (
                val result = convertResumable(
                    inputSnapshot = source,
                    destination = destination,
                    fallbackTitle = fallbackTitle,
                    mode = mode,
                    checkpointDirectory = checkpointDirectory,
                    shouldStop = { null },
                    onCheckpointProgress = { stage, completed, total ->
                        if (stage == PdfConversionStage.Extracting || stage == PdfConversionStage.OCR || stage == PdfConversionStage.Reflow) {
                            if (total > 0) onProgress(completed.coerceAtMost(total), total)
                        }
                    },
                )
            ) {
                is PdfConversionResult.Completed -> result.title to result.author
                is PdfConversionResult.Paused -> error("PDF conversion paused unexpectedly.")
                is PdfConversionResult.Cancelled -> throw CancellationException("PDF conversion was cancelled.")
            }
        } finally {
            checkpointDirectory.deleteRecursively()
        }
    }

    suspend fun convertResumable(
        inputSnapshot: File,
        destination: File,
        fallbackTitle: String,
        mode: PdfEpubMode,
        checkpointDirectory: File,
        shouldStop: suspend () -> PdfConversionStop?,
        onCheckpointProgress: suspend (PdfConversionStage, Int, Int) -> Unit,
    ): PdfConversionResult = engine.convert(
        inputSnapshot = inputSnapshot,
        output = destination,
        fallbackTitle = fallbackTitle,
        mode = mode.name,
        checkpointDirectory = checkpointDirectory,
        shouldStop = shouldStop,
        onCheckpointProgress = onCheckpointProgress,
    )
}
