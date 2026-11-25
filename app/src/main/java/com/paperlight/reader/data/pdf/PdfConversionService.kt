package com.paperlight.reader.data.pdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi
import com.paperlight.reader.MainActivity
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.PdfConversionJobEntity
import com.paperlight.reader.data.PdfConversionJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PdfConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queueProcessor: Job? = null
    private var currentJobId: String? = null
    private var currentRunnerToken: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
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
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                scope.launch {
                    jobId?.let { repository().pausePdfConversionJob(it, signalService = false) }
                    if (queueProcessor?.isActive != true) stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                scope.launch {
                    jobId?.let { repository().cancelPdfConversionJob(it, signalService = false) }
                    if (queueProcessor?.isActive != true) stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
        }
        try {
            ensureForeground()
        } catch (failure: Exception) {
            lastError.value = failure.message ?: "Could not start the PDF conversion service."
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        lastError.value = null
        if (queueProcessor?.isActive != true) {
            val redeliveredJobId = intent?.getStringExtra(EXTRA_JOB_ID)
            queueProcessor = scope.launch { withTimeout(SERVICE_MAX_MILLIS - SERVICE_TIMEOUT_MARGIN_MILLIS) { processQueue(redeliveredJobId) } }
        }
        return START_REDELIVER_INTENT
    }

    private fun ensureForeground() {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "PDF conversions", NotificationManager.IMPORTANCE_LOW),
        )
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification(null))
            foregroundStarted = true
            wakeLock = checkNotNull(getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VibeReader:PdfConversion")
                .apply { acquire(SERVICE_MAX_MILLIS) }
        }
    }

    private suspend fun processQueue(redeliveredJobId: String?) {
        val repository = repository()
        try {
            repository.reconcilePdfConversionJobs()
            var redelivery = redeliveredJobId
            while (currentCoroutineContext().isActive) {
                val job = redelivery?.let { repository.claimRedeliveredPdfConversionJob(it) }
                    ?: repository.claimNextPdfConversionJob()
                redelivery = null
                if (job == null) {
                    delay(150)
                    val next = repository.claimNextPdfConversionJob() ?: break
                    executeClaimedJob(repository, next)
                } else {
                    executeClaimedJob(repository, job)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lastError.value = failure.message ?: "Could not continue the PDF conversion queue."
        } finally {
            withContext(NonCancellable) {
                currentRunnerToken?.let { token -> runCatching { repository.forgetPdfConversionRunnerToken(token) } }
                runCatching { PdfConversionRecoveryWorker.scheduleAutomaticJobs(applicationContext, repository) }
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

    private suspend fun executeClaimedJob(repository: BookRepository, job: PdfConversionJobEntity) {
        val token = requireNotNull(job.runnerToken) { "The claimed PDF job has no runner token." }
        currentJobId = job.id
        currentRunnerToken = token
        activeJobId.value = job.id
        progress.value = ConversionProgress(job.completedPages, job.totalPages, job.stage, job.id, job.status)
        notifyProgress(progress.value!!)
        PdfConversionRunner(applicationContext, repository).runClaimed(job, token) { stage, completed, total ->
            val update = ConversionProgress(completed, total, stage, job.id, PdfConversionJobState.RUNNING)
            progress.value = update
            notifyProgress(update)
        }
        currentRunnerToken = null
        currentJobId = null
        activeJobId.value = null
    }

    private fun notifyProgress(update: ConversionProgress?) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(update))
    }

    private fun notification(update: ConversionProgress?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(update?.let { "PDF to EPUB" } ?: "PDF conversion")
            .setContentText(update?.let { progressText(it) } ?: "Preparing PDF conversion…")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(update?.status == PdfConversionJobState.RUNNING)
            .setProgress(update?.total?.coerceAtLeast(0) ?: 0, update?.completed?.coerceAtLeast(0) ?: 0,
                update?.total?.let { it <= 0 } ?: true)
        update?.let { current ->
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", controlIntent(current.jobId, pause = true))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", controlIntent(current.jobId, pause = false))
        }
        return builder.build()
    }

    private fun progressText(progress: ConversionProgress): String = if (progress.total > 0) {
        "${progress.stage}: ${progress.completed}/${progress.total} pages"
    } else progress.stage

    private fun controlIntent(jobId: String, pause: Boolean): PendingIntent {
        val action = if (pause) ACTION_PAUSE else ACTION_CANCEL
        val intent = Intent(this, PdfConversionService::class.java).setAction(action).putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            this, (jobId + action).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun repository() = BookRepository(this, BookDatabase.getInstance(this).bookDao())

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        serviceActive = false
        queueProcessor?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        queueProcessor?.cancel(CancellationException("PDF conversion service reached its Android time limit."))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    data class ConversionProgress(
        val completed: Int,
        val total: Int,
        val stage: String,
        val jobId: String,
        val status: String,
    )

    companion object {
        const val EXTRA_JOB_ID = "pdfJobId"
        const val ACTION_PAUSE = "com.paperlight.reader.pdf.PAUSE"
        const val ACTION_CANCEL = "com.paperlight.reader.pdf.CANCEL"
        private const val CHANNEL = "pdf-conversion"
        private const val NOTIFICATION_ID = 5102
        private const val SERVICE_MAX_MILLIS = 5 * 60 * 60 * 1_000L
        private const val SERVICE_TIMEOUT_MARGIN_MILLIS = 30 * 60 * 1_000L

        @Volatile var serviceActive = false
            private set
        val activeJobId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val progress = kotlinx.coroutines.flow.MutableStateFlow<ConversionProgress?>(null)
        val lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        fun signal(context: Context, jobId: String, pause: Boolean) {
            val intent = Intent(context, PdfConversionService::class.java)
                .setAction(if (pause) ACTION_PAUSE else ACTION_CANCEL)
                .putExtra(EXTRA_JOB_ID, jobId)
            runCatching { context.startService(intent) }
        }
    }
}
