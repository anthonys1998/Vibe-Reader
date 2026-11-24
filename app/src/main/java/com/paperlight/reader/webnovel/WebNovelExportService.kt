package com.paperlight.reader.webnovel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.paperlight.reader.MainActivity
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.WebNovelJobEntity
import com.paperlight.reader.data.WebNovelJobState
import com.paperlight.reader.data.WebNovelScrapeOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NovelDownloadProgress(
    val completed: Int,
    val total: Int,
    val label: String,
    val jobId: String? = null,
    val status: String = WebNovelJobState.RUNNING,
    val waitUntil: Long? = null,
)

class WebNovelExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queueProcessor: Job? = null
    private var activeConversion: Job? = null
    private var currentJobId: String? = null
    private var currentRunnerToken: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var quotaStopped = false
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        serviceActive = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_PAUSE -> {
                controlJob(intent.getStringExtra(EXTRA_JOB_ID), pause = true)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                controlJob(intent.getStringExtra(EXTRA_JOB_ID), pause = false)
                return START_NOT_STICKY
            }
        }

        try {
            ensureForeground(intent?.getStringExtra(EXTRA_JOB_ID))
        } catch (failure: Exception) {
            result.value = failure.message ?: "Could not start the background export."
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        result.value = null
        if (queueProcessor?.isActive != true) {
            val redeliveredJobId = intent?.getStringExtra(EXTRA_JOB_ID)
            queueProcessor = scope.launch { processQueue(redeliveredJobId) }
        }
        return START_REDELIVER_INTENT
    }

    private fun ensureForeground(jobId: String?) {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Web EPUB exports", NotificationManager.IMPORTANCE_LOW))
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification(NovelDownloadProgress(0, 0, "Starting website EPUB queue…", jobId)))
            foregroundStarted = true
            val power = checkNotNull(getSystemService(PowerManager::class.java))
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VibeReader:WebEpubExport").apply {
                acquire(6 * 60 * 60 * 1_000L)
            }
        }
    }

    private suspend fun processQueue(redeliveredJobId: String?) {
        try {
            var redelivery = redeliveredJobId
            val repository = repository()
            repository.reconcileWebNovelJobs(serviceActive = false)
            while (!quotaStopped && currentCoroutineContext().isActive) {
                val next = redelivery?.let { repository.claimRedeliveredWebNovelJob(it) }
                    ?: repository.claimNextWebNovelJob()
                redelivery = null
                if (next == null) {
                    // Let a start command delivered with the latest enqueue reach this loop before stopping.
                    delay(150)
                    val queued = repository.claimNextWebNovelJob() ?: break
                    executeClaimedJob(repository, queued)
                } else {
                    executeClaimedJob(repository, next)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result.value = failure.message ?: "Could not continue the website EPUB queue."
        } finally {
            withContext(NonCancellable) {
                currentRunnerToken?.let { token -> runCatching { repository().forgetWebNovelRunnerToken(token) } }
                runCatching { WebNovelRecoveryWorker.scheduleAutomaticJobs(applicationContext, repository()) }
            }
            queueProcessor = null
            currentJobId = null
            currentRunnerToken = null
            activeJobId.value = null
            progress.value = null
            releaseWakeLock()
            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelfResult(latestStartId)
        }
    }

    private suspend fun executeClaimedJob(repository: BookRepository, job: WebNovelJobEntity) {
        val token = requireNotNull(job.runnerToken) { "The claimed conversion has no runner token." }
        currentJobId = job.id
        currentRunnerToken = token
        activeJobId.value = job.id
        var completed = job.completed
        var total = job.total
        var label = job.label
        progress.value = NovelDownloadProgress(completed, total, label, job.id, job.status, job.waitUntil)
        notifyProgress(progress.value!!)
        activeConversion = scope.launch {
            try {
                val options = WebNovelScrapeOptionsJson.decode(job.optionsJson)
                val generated = repository.createEpubFromUrl(
                    url = job.sourceUrl,
                    options = options,
                    libraryId = job.libraryId,
                    webNovelJob = job.id to token,
                    onProgress = { nextCompleted, nextTotal, nextLabel ->
                        completed = nextCompleted
                        total = nextTotal
                        label = nextLabel
                        if (!repository.updateWebNovelJobProgress(job.id, token, WebNovelJobState.RUNNING,
                                completed, total, label)) throw CancellationException("This conversion is no longer running.")
                        publishProgress(NovelDownloadProgress(completed, total, label, job.id, WebNovelJobState.RUNNING))
                    },
                    onRateLimitWait = { host, waitUntil ->
                        val state = if (waitUntil == null) WebNovelJobState.RUNNING else WebNovelJobState.WAITING_RATE_LIMIT
                        val statusLabel = if (waitUntil == null) label else "Waiting for $host rate limit"
                        if (!repository.updateWebNovelJobProgress(job.id, token, state, completed, total,
                                statusLabel, waitUntil)) throw CancellationException("This conversion is no longer running.")
                        publishProgress(NovelDownloadProgress(completed, total, statusLabel, job.id, state, waitUntil))
                    },
                    onManifestReady = { title ->
                        if (!repository.updateWebNovelJobTitle(job.id, token, title)) {
                            throw CancellationException("This conversion is no longer running.")
                        }
                    },
                )
                generated.fold(
                    onSuccess = { result.value = "Created ${it.title}.epub" },
                    onFailure = { failure ->
                        if (!repository.failWebNovelJob(job.id, token, failure.message ?: "Could not create this EPUB.")) {
                            throw CancellationException("This conversion was stopped before import.")
                        }
                        result.value = failure.message ?: "Could not create the EPUB."
                    },
                )
            } catch (deferred: RateLimitDeferredException) {
                if (repository.deferWebNovelJobForRateLimit(job.id, token, deferred.host, deferred.waitUntil)) {
                    publishProgress(NovelDownloadProgress(completed, total, "Waiting for ${deferred.host} rate limit",
                        job.id, WebNovelJobState.WAITING_RATE_LIMIT, deferred.waitUntil))
                    WebNovelRecoveryWorker.schedule(this@WebNovelExportService, repository.webNovelJobById(job.id))
                } else withContext(NonCancellable) {
                    runCatching { repository.settleWebNovelJobRequest(job.id, token) }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val settled = runCatching { repository.settleWebNovelJobRequest(job.id, token) }.getOrDefault(false)
                    if (!settled) runCatching { repository.interruptWebNovelJob(job.id, token) }
                }
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    runCatching { repository.failWebNovelJob(job.id, token, failure.message ?: "Could not create this EPUB.") }
                }
                result.value = failure.message ?: "Could not create the EPUB."
            } finally {
                withContext(NonCancellable) {
                    runCatching { repository.forgetWebNovelRunnerToken(token) }
                }
                activeConversion = null
                currentJobId = null
                currentRunnerToken = null
                activeJobId.value = null
                progress.value = null
            }
        }
        activeConversion?.join()
    }

    private fun controlJob(id: String?, pause: Boolean) {
        if (id.isNullOrBlank()) return
        scope.launch {
            val repository = repository()
            val requested = if (pause) repository.requestPauseWebNovelJob(id) else repository.requestCancelWebNovelJob(id)
            val current = requested ?: repository.webNovelJobById(id)
            val wantsPause = current?.status == WebNovelJobState.PAUSE_REQUESTED
            val wantsCancel = current?.status == WebNovelJobState.CANCEL_REQUESTED
            if (currentJobId == id && ((pause && wantsPause) || (!pause && wantsCancel))) {
                activeConversion?.cancel(CancellationException(if (pause) "Conversion paused" else "Conversion cancelled"))
            }
            val alreadySettled = (pause && current?.status == WebNovelJobState.PAUSED) ||
                (!pause && current?.status == WebNovelJobState.CANCELLED)
            if (requested == null && !wantsPause && !wantsCancel && !alreadySettled) {
                result.value = "This conversion is no longer active."
            }
            if (queueProcessor?.isActive != true) stopSelfResult(latestStartId)
        }
    }

    private fun publishProgress(update: NovelDownloadProgress) {
        progress.value = update
        notifyProgress(update)
    }

    private fun notifyProgress(update: NovelDownloadProgress) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(update))
    }

    private fun notification(update: NovelDownloadProgress): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Creating web EPUB")
            .setContentText(if (update.total > 0) "${update.completed}/${update.total} · ${update.label}" else update.label)
            .setProgress(update.total, update.completed, update.total == 0)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        update.jobId?.let { id ->
            val pause = PendingIntent.getService(this, id.hashCode(), controlIntent(ACTION_PAUSE, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getService(this, id.hashCode() xor 0x25A, controlIntent(ACTION_CANCEL, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Pause", pause).build())
            builder.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        return builder.build()
    }

    private fun controlIntent(action: String, id: String) =
        Intent(this, WebNovelExportService::class.java).setAction(action).putExtra(EXTRA_JOB_ID, id)

    override fun onTimeout(startId: Int, fgsType: Int) {
        quotaStopped = true
        result.value = "Android stopped this conversion at its background time limit. Resume it later to continue from saved chapters."
        activeConversion?.cancel(CancellationException("Android foreground-service time limit reached"))
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceActive = false
        scope.cancel()
        releaseWakeLock()
        progress.value = null
        activeJobId.value = null
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun repository() = BookRepository(applicationContext, BookDatabase.getInstance(applicationContext).bookDao())

    companion object {
        private const val CHANNEL = "web_epub_export"
        private const val NOTIFICATION_ID = 710
        private const val EXTRA_JOB_ID = "jobId"
        private const val ACTION_PROCESS_QUEUE = "com.paperlight.reader.PROCESS_WEB_EXPORT_QUEUE"
        private const val ACTION_PAUSE = "com.paperlight.reader.PAUSE_WEB_EXPORT"
        private const val ACTION_CANCEL = "com.paperlight.reader.CANCEL_WEB_EXPORT"
        private val progress = MutableStateFlow<NovelDownloadProgress?>(null)
        val downloadProgress = progress.asStateFlow()
        private val result = MutableStateFlow<String?>(null)
        val downloadResult = result.asStateFlow()
        private val activeJobId = MutableStateFlow<String?>(null)
        val currentJobId = activeJobId.asStateFlow()
        @Volatile private var serviceActive = false

        fun isServiceActive(): Boolean = serviceActive

        fun consumeResult(message: String): Boolean = result.compareAndSet(message, null)

        fun start(context: Context, jobId: String) {
            context.startForegroundService(Intent(context, WebNovelExportService::class.java).apply {
                action = ACTION_PROCESS_QUEUE
                putExtra(EXTRA_JOB_ID, jobId)
            })
        }

        fun pause(context: Context, jobId: String) {
            context.startService(Intent(context, WebNovelExportService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_JOB_ID, jobId)
            })
        }

        fun cancel(context: Context, jobId: String) {
            context.startService(Intent(context, WebNovelExportService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            })
        }

        fun cancel(context: Context) {
            currentJobId.value?.let { cancel(context, it) }
        }
    }
}
