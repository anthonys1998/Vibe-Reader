package com.paperlight.reader.data.pdf

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.PdfConversionJobEntity
import com.paperlight.reader.data.PdfConversionJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Continues a user-started PDF job from its last committed page checkpoint. */
class PdfConversionRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val repository = try {
            BookRepository(applicationContext, BookDatabase.getInstance(applicationContext).bookDao())
        } catch (_: Exception) {
            return Result.retry()
        }
        val job = try {
            repository.claimAutomaticPdfConversionJob(jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
        if (job == null) {
            try {
                val current = repository.pdfConversionJobById(jobId) ?: return Result.success()
                if (current.autoRun && current.status in AUTOMATIC_STATES) return Result.retry()
                return Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Result.retry()
            }
        }
        val token = requireNotNull(job.runnerToken)
        try {
            val completedWithinSlice = withTimeoutOrNull(HARD_LIMIT_MILLIS) {
                PdfConversionRunner(applicationContext, repository).runClaimed(
                    job = job,
                    token = token,
                    workerSliceMillis = SOFT_SLICE_MILLIS,
                )
                true
            } ?: false
            if (!completedWithinSlice && repository.continuePdfConversionAfterWorkerSlice(job.id, token)) {
                scheduleNext(repository, job.id)
            }
        } catch (_: PdfWorkerSliceYieldException) {
            try {
                if (repository.continuePdfConversionAfterWorkerSlice(job.id, token)) scheduleNext(repository, job.id)
                else repository.settlePdfConversionJobRequest(job.id, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Result.retry()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val settled = runCatching { repository.settlePdfConversionJobRequest(job.id, token) }.getOrDefault(false)
                if (!settled && runCatching { repository.continuePdfConversionAfterWorkerSlice(job.id, token) }.getOrDefault(false)) {
                    runCatching { schedule(applicationContext, repository.pdfConversionJobById(job.id)) }
                }
            }
            throw cancelled
        } catch (_: Exception) {
            runCatching { repository.failPdfConversionJob(job.id, token, "Could not continue this PDF conversion.") }
                .onFailure { return Result.retry() }
        } finally {
            withContext(NonCancellable) {
                runCatching { repository.forgetPdfConversionRunnerToken(token) }
                runCatching { scheduleAutomaticJobs(applicationContext, repository) }
            }
        }
        return Result.success()
    }

    private suspend fun scheduleNext(repository: BookRepository, jobId: String) {
        schedule(applicationContext, repository.pdfConversionJobById(jobId), DEFAULT_SLICE_DELAY_MILLIS)
    }

    companion object {
        private const val KEY_JOB_ID = "jobId"
        private const val SOFT_SLICE_MILLIS = 3 * 60 * 1_000L
        private const val HARD_LIMIT_MILLIS = 9 * 60 * 1_000L
        private const val DEFAULT_SLICE_DELAY_MILLIS = 15_000L
        private val AUTOMATIC_STATES = setOf(PdfConversionJobState.QUEUED, PdfConversionJobState.INTERRUPTED,
            PdfConversionJobState.RUNNING, PdfConversionJobState.PAUSE_REQUESTED, PdfConversionJobState.CANCEL_REQUESTED)

        fun schedule(context: Context, job: PdfConversionJobEntity?, delayMillis: Long = 0L) {
            job ?: return
            if (!job.autoRun || job.status !in AUTOMATIC_STATES) return
            val request = OneTimeWorkRequestBuilder<PdfConversionRecoveryWorker>()
                .setInputData(Data.Builder().putString(KEY_JOB_ID, job.id).build())
                .addTag(tag(job.id))
                .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(job), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(tag(jobId))
        }

        suspend fun scheduleAutomaticJobs(context: Context, repository: BookRepository) {
            repository.automaticPdfConversionJobs().forEach { schedule(context, it, DEFAULT_SLICE_DELAY_MILLIS) }
        }

        private fun uniqueName(job: PdfConversionJobEntity) = "pdf-recovery-${job.id}-${job.updatedAt}"
        private fun tag(jobId: String) = "pdf-recovery-$jobId"
    }
}
