package com.paperlight.reader.webnovel

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.WebNovelJobEntity
import com.paperlight.reader.data.WebNovelJobState
import com.paperlight.reader.data.WebNovelScrapeOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Runs due durable conversions in short, checkpointed WorkManager slices without starting a service. */
class WebNovelRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val repository = try {
            BookRepository(applicationContext, BookDatabase.getInstance(applicationContext).bookDao())
        } catch (_: Exception) {
            return Result.retry()
        }
        val job = try {
            repository.claimAutomaticWebNovelJob(jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
        if (job == null) {
            try {
                val current = repository.webNovelJobById(jobId) ?: return Result.success()
                if (current.autoRun && current.status in AUTOMATIC_LEASE_STATES) return Result.retry()
                if (current.autoRun && current.status == WebNovelJobState.WAITING_RATE_LIMIT &&
                    (current.waitUntil ?: 0L) > System.currentTimeMillis()) {
                    schedule(applicationContext, current)
                    return Result.success()
                }
                if (current.autoRun && current.status in AUTOMATIC_STATES) {
                    val postponed = repository.postponeAutomaticWebNovelJob(jobId) ?: return Result.success()
                    schedule(applicationContext, postponed)
                    return Result.success()
                }
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
                execute(repository, job, token)
                true
            } ?: false
            if (!completedWithinSlice) {
                try {
                    val settled = repository.settleWebNovelJobRequest(job.id, token)
                    if (!settled && repository.continueWebNovelJobAfterWorkerSlice(job.id, token) &&
                        !scheduleNextGeneration(repository, job.id)) return Result.retry()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return Result.retry()
                }
            }
        } catch (_: WebNovelWorkerSliceYieldException) {
            try {
                if (repository.continueWebNovelJobAfterWorkerSlice(job.id, token)) {
                    if (!scheduleNextGeneration(repository, job.id)) return Result.retry()
                } else repository.settleWebNovelJobRequest(job.id, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Result.retry()
            }
        } catch (deferred: RateLimitDeferredException) {
            try {
                if (repository.deferWebNovelJobForRateLimit(job.id, token, deferred.host, deferred.waitUntil)) {
                    if (!scheduleNextGeneration(repository, job.id)) return Result.retry()
                } else repository.settleWebNovelJobRequest(job.id, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Result.retry()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val settled = runCatching { repository.settleWebNovelJobRequest(job.id, token) }.getOrDefault(false)
                if (!settled && runCatching { repository.continueWebNovelJobAfterWorkerSlice(job.id, token) }.getOrDefault(false)) {
                    runCatching { schedule(applicationContext, repository.webNovelJobById(job.id)) }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            runCatching { repository.failWebNovelJob(job.id, token, failure.message ?: "Could not continue this website EPUB conversion.") }
                .onFailure { return Result.retry() }
        } finally {
            withContext(NonCancellable) {
                runCatching { repository.forgetWebNovelRunnerToken(token) }
                runCatching { WebNovelRecoveryWorker.scheduleAutomaticJobs(applicationContext, repository) }
            }
        }
        return Result.success()
    }

    private suspend fun execute(repository: BookRepository, job: WebNovelJobEntity, token: String) {
        var completedCount = job.completed
        var totalCount = job.total
        var currentLabel = job.label
        val startedAt = SystemClock.elapsedRealtime()
        val generated = repository.createEpubFromUrl(
            url = job.sourceUrl,
            options = WebNovelScrapeOptionsJson.decode(job.optionsJson),
            libraryId = job.libraryId,
            webNovelJob = job.id to token,
            onProgress = { nextCompleted, nextTotal, nextLabel ->
                completedCount = nextCompleted
                totalCount = nextTotal
                currentLabel = nextLabel
                if (!repository.updateWebNovelJobProgress(job.id, token, WebNovelJobState.RUNNING,
                        completedCount, totalCount, currentLabel)) throw CancellationException("This conversion is no longer running.")
            },
            onRateLimitWait = { host, waitUntil ->
                val state = if (waitUntil == null) WebNovelJobState.RUNNING else WebNovelJobState.WAITING_RATE_LIMIT
                if (waitUntil != null) currentLabel = "Waiting for $host rate limit"
                if (!repository.updateWebNovelJobProgress(job.id, token, state, completedCount, totalCount,
                        currentLabel, waitUntil)) throw CancellationException("This conversion is no longer running.")
            },
            onManifestReady = { title ->
                if (!repository.updateWebNovelJobTitle(job.id, token, title)) throw CancellationException("This conversion is no longer running.")
            },
            shouldYieldAtChapterBoundary = {
                SystemClock.elapsedRealtime() - startedAt >= SOFT_SLICE_MILLIS
            },
        )
        generated.fold(
            onSuccess = { },
            onFailure = { failure ->
                if (!repository.failWebNovelJob(job.id, token, failure.message ?: "Could not create this EPUB."))
                    throw CancellationException("This conversion was stopped before import.")
            },
        )
    }

    private suspend fun scheduleNextGeneration(repository: BookRepository, jobId: String): Boolean = try {
        schedule(applicationContext, repository.webNovelJobById(jobId))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val KEY_JOB_ID = "jobId"
        private const val SOFT_SLICE_MILLIS = 3 * 60 * 1_000L
        private const val HARD_LIMIT_MILLIS = 9 * 60 * 1_000L
        private val AUTOMATIC_STATES = setOf(WebNovelJobState.QUEUED, WebNovelJobState.WAITING_RATE_LIMIT, WebNovelJobState.INTERRUPTED)

        fun schedule(context: Context, job: WebNovelJobEntity?) {
            job ?: return
            if (!job.autoRun || job.status !in AUTOMATIC_STATES) return
            val delay = ((job.waitUntil ?: System.currentTimeMillis()) - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<WebNovelRecoveryWorker>()
                .setInputData(Data.Builder().putString(KEY_JOB_ID, job.id).build())
                .addTag(tag(job.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(job), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(tag(jobId))
        }

        suspend fun scheduleAutomaticJobs(context: Context, repository: BookRepository) {
            repository.automaticWebNovelJobs().forEach { schedule(context, it) }
        }

        private fun uniqueName(job: WebNovelJobEntity) = "web-novel-recovery-${job.id}-${job.updatedAt}-${job.waitUntil ?: 0L}"
        private fun tag(jobId: String) = "web-novel-recovery-$jobId"
        private val AUTOMATIC_LEASE_STATES = setOf(WebNovelJobState.RUNNING,
            WebNovelJobState.PAUSE_REQUESTED, WebNovelJobState.CANCEL_REQUESTED)
    }
}
