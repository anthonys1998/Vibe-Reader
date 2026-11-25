package com.paperlight.reader.data.pdf

import android.content.Context
import android.os.SystemClock
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.PdfConversionJobEntity
import com.paperlight.reader.data.PdfConversionJobState
import com.paperlight.reader.data.PdfEpubConverter
import com.paperlight.reader.data.PdfEpubMode
import com.paperlight.reader.data.pdf.PdfConversionResult
import com.paperlight.reader.data.pdf.PdfConversionStage
import com.paperlight.reader.data.pdf.PdfConversionStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class PdfWorkerSliceYieldException : Exception("PDF conversion yielded at a saved page checkpoint.")

/** Runs a claimed PDF job and publishes only through its persisted runner token. */
class PdfConversionRunner(private val context: Context, private val repository: BookRepository) {
    suspend fun runClaimed(
        job: PdfConversionJobEntity,
        token: String,
        workerSliceMillis: Long? = null,
        onProgress: suspend (stage: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
    ) {
        val output = repository.pdfConversionTempOutput(job)
        output.delete()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val snapshot = repository.pdfConversionInputFile(job)
            val checkpointDirectory = repository.pdfConversionCheckpointDirectory(job)
            val mode = PdfEpubMode.valueOf(job.mode)
            val result = withContext(Dispatchers.IO) {
                PdfEpubConverter(context).convertResumable(
                    inputSnapshot = snapshot,
                    destination = output,
                    fallbackTitle = job.sourceName.substringBeforeLast('.', job.sourceName),
                    mode = mode,
                    checkpointDirectory = checkpointDirectory,
                    shouldStop = {
                        val current = repository.pdfConversionJobById(job.id)
                            ?: throw CancellationException("This PDF conversion job no longer exists.")
                        if (current.runnerToken != token) throw CancellationException("This PDF conversion is no longer running.")
                        when (current.status) {
                            PdfConversionJobState.RUNNING -> null
                            PdfConversionJobState.PAUSE_REQUESTED -> PdfConversionStop.Pause
                            PdfConversionJobState.CANCEL_REQUESTED -> PdfConversionStop.Cancel
                            else -> throw CancellationException("This PDF conversion is no longer running.")
                        }
                    },
                    onCheckpointProgress = { stage, completed, total ->
                        val label = stage.label()
                        if (!repository.updatePdfConversionJobProgress(job.id, token, label, completed, total)) {
                            throw CancellationException("This PDF conversion is no longer running.")
                        }
                        onProgress(label, completed, total)
                        if (workerSliceMillis != null && stage != PdfConversionStage.Complete &&
                            SystemClock.elapsedRealtime() - startedAt >= workerSliceMillis) {
                            throw PdfWorkerSliceYieldException()
                        }
                    },
                )
            }
            when (result) {
                is PdfConversionResult.Completed -> {
                    require(result.inputSha256 == job.inputHash) { "The saved PDF snapshot failed its integrity check." }
                    val imported = repository.importPdfConversionEpub(
                        file = output,
                        title = result.title,
                        author = result.author,
                        jobId = job.id,
                        token = token,
                        inputHash = result.inputSha256,
                        fallbackPageNumbers = result.fallbackPageNumbers,
                    )
                    imported.exceptionOrNull()?.let { throw it }
                }
                is PdfConversionResult.Paused -> {
                    require(result.inputSha256 == job.inputHash) { "The saved PDF snapshot failed its integrity check." }
                    if (!repository.settlePdfConversionJobRequest(job.id, token)) {
                        repository.interruptPdfConversionJob(job.id, token)
                    }
                }
                is PdfConversionResult.Cancelled -> {
                    require(result.inputSha256 == job.inputHash) { "The saved PDF snapshot failed its integrity check." }
                    if (!repository.settlePdfConversionJobRequest(job.id, token)) {
                        repository.interruptPdfConversionJob(job.id, token)
                    }
                }
            }
        } catch (yield: PdfWorkerSliceYieldException) {
            throw yield
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val settled = runCatching { repository.settlePdfConversionJobRequest(job.id, token) }.getOrDefault(false)
                if (!settled) runCatching { repository.interruptPdfConversionJob(job.id, token) }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (!repository.failPdfConversionJob(job.id, token, failure.message ?: "Could not convert this PDF.")) {
                throw CancellationException("This PDF conversion stopped before it could report the failure.").also {
                    it.initCause(failure)
                }
            }
        } finally {
            output.delete()
        }
    }

    private fun PdfConversionStage.label(): String = when (this) {
        PdfConversionStage.SnapshotValidation -> "Checking PDF"
        PdfConversionStage.Extracting -> "Extracting text"
        PdfConversionStage.OCR -> "Reading scanned pages"
        PdfConversionStage.Reflow -> "Reflowing chapters"
        PdfConversionStage.Packaging -> "Building EPUB"
        PdfConversionStage.Complete -> "Complete"
    }
}
