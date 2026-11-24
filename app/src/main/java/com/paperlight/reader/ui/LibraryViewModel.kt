package com.paperlight.reader.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.DiscoverySourceEntity
import com.paperlight.reader.data.BackupProgress
import com.paperlight.reader.data.BackupProgressStage
import com.paperlight.reader.data.PdfEpubMode
import com.paperlight.reader.data.WebNovelJobEntity
import com.paperlight.reader.data.WebNovelJobState
import java.io.File
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import com.paperlight.reader.webnovel.WebNovelExportService
import com.paperlight.reader.webnovel.WebNovelChapterManifestCodec
import com.paperlight.reader.webnovel.WebNovelRecoveryWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: BookRepository, private val context: Context) : ViewModel() {
    val books = repository.books.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val libraries = repository.libraries.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val webNovelJobs = repository.webNovelJobs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val discoverySources = repository.discoverySources.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _libraryBusy = MutableStateFlow(true)
    val libraryBusy = _libraryBusy.asStateFlow()
    private val _backupRecoveryBlocked = MutableStateFlow(true)
    val backupRecoveryBlocked = _backupRecoveryBlocked.asStateFlow()
    private val _backupProgress = MutableStateFlow<BackupProgress?>(null)
    val backupProgress = _backupProgress.asStateFlow()
    private var backupJob: Job? = null

    init {
        viewModelScope.launch {
            WebNovelExportService.downloadResult.collect { message ->
                if (message != null && WebNovelExportService.consumeResult(message)) {
                    _message.value = message
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.recoverInterruptedBackupRestore()
                repository.ensureDefaultLibrary()
                repository.reconcileWebNovelJobs(WebNovelExportService.isServiceActive())
                WebNovelRecoveryWorker.scheduleAutomaticJobs(context, repository)
                repository.refreshMetadata()
                _backupRecoveryBlocked.value = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _backupRecoveryBlocked.value = true
                _message.value = failure.message ?: "Could not initialize the library."
            } finally {
                _libraryBusy.value = false
            }
        }
    }

    fun clearMessage(shown: String) {
        _message.compareAndSet(shown, null)
    }

    fun saveDiscoverySource(source: DiscoverySourceEntity, onComplete: (Boolean) -> Unit = {}) {
        if (!recoveryAllowsLibraryAction()) { onComplete(false); return }
        viewModelScope.launch {
            try {
                repository.saveDiscoverySource(source)
                _message.value = "Saved ${source.name}."
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not save this source."
                onComplete(false)
            }
        }
    }

    fun saveDiscoveryScrapeOptions(
        id: String,
        expectedBaseUrl: String,
        pageUrl: String,
        options: WebNovelScrapeOptions,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!recoveryAllowsLibraryAction()) { onComplete(false); return }
        viewModelScope.launch {
            try {
                repository.updateDiscoveryScrapeOptions(id, expectedBaseUrl, pageUrl, options)
                _message.value = "Saved scraper settings for this website."
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not save scraper settings for this website."
                onComplete(false)
            }
        }
    }

    fun setDiscoverySourceEnabled(id: String, enabled: Boolean, onComplete: (Boolean) -> Unit = {}) {
        if (!recoveryAllowsLibraryAction()) { onComplete(false); return }
        viewModelScope.launch {
            try {
                onComplete(repository.setDiscoverySourceEnabled(id, enabled))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not update this source."
                onComplete(false)
            }
        }
    }

    fun deleteDiscoverySource(id: String, onComplete: (Boolean) -> Unit = {}) {
        if (!recoveryAllowsLibraryAction()) { onComplete(false); return }
        viewModelScope.launch {
            try {
                val deleted = repository.deleteDiscoverySource(id)
                if (!deleted) _message.value = "This source was already removed."
                onComplete(deleted)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not remove this source."
                onComplete(false)
            }
        }
    }

    fun cacheDiscoverySourceResults(source: DiscoverySourceEntity, key: String, json: String,
        onComplete: (Boolean) -> Unit = {}) {
        if (!recoveryAllowsLibraryAction()) { onComplete(false); return }
        viewModelScope.launch {
            try {
                onComplete(repository.cacheDiscoverySourceResults(source, key, json))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not cache search results."
                onComplete(false)
            }
        }
    }

    private fun recoveryAllowsLibraryAction(): Boolean {
        if (_backupRecoveryBlocked.value) {
            _message.value = "Finish backup recovery before using the library."
            return false
        }
        return true
    }

    fun retryBackupRecovery() {
        if (!_backupRecoveryBlocked.value || _libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.recoverInterruptedBackupRestore()
                repository.ensureDefaultLibrary()
                _backupRecoveryBlocked.value = false
                _message.value = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Backup recovery could not finish."
            } finally {
                _libraryBusy.value = false
            }
        }
    }

    fun importBooks(uris: List<Uri>, libraryId: String) {
        if (uris.isEmpty() || !recoveryAllowsLibraryAction()) return
        viewModelScope.launch {
            var imported = 0
            var lastFileName = ""
            var firstError: String? = null
            for (uri in uris) {
                repository.import(uri, libraryId).fold(
                    onSuccess = { imported++; lastFileName = it.fileName },
                    onFailure = {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        if (firstError == null) firstError = it.message ?: "Import failed"
                    },
                )
            }
            val failed = uris.size - imported
            _message.value = when {
                imported == 0 -> firstError ?: "Import failed"
                failed > 0 -> "Imported $imported of ${uris.size} books. $firstError"
                imported == 1 -> "Imported $lastFileName"
                else -> "Imported $imported books"
            }
        }
    }

    fun importDownloadedEpub(file: File, title: String, author: String, libraryId: String) {
        if (!recoveryAllowsLibraryAction()) {
            file.delete()
            return
        }
        viewModelScope.launch {
            try {
                repository.importGeneratedEpub(file, title, author, libraryId).fold(
                    onSuccess = { _message.value = "Imported ${it.fileName}" },
                    onFailure = { _message.value = it.message ?: "Could not import the downloaded EPUB." },
                )
            } finally {
                file.delete()
            }
        }
    }

    fun updateProgress(bookId: String, progress: Float) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch { repository.updateProgress(bookId, progress) }
    }

    fun exportBook(bookId: String, destination: Uri) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch {
            try {
                _message.value = "Exported ${repository.exportBook(bookId, destination)}."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not export this book."
            }
        }
    }

    fun remove(book: BookEntity) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch { repository.remove(book) }
    }

    fun removeBooks(ids: List<String>) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch {
            runCatching { repository.removeBooks(ids) }
                .onFailure { _message.value = it.message ?: "Could not remove the selected books." }
        }
    }

    fun moveBooks(ids: List<String>, libraryId: String, onComplete: (Boolean) -> Unit) {
        if (!recoveryAllowsLibraryAction()) {
            onComplete(false)
            return
        }
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.moveBooks(ids, libraryId)
                _message.value = "Moved ${ids.size} book${if (ids.size == 1) "" else "s"}."
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not move the selected books."
                onComplete(false)
            } finally { _libraryBusy.value = false }
        }
    }

    fun saveLibrary(id: String?, name: String, onComplete: (String?, String?) -> Unit) {
        if (!recoveryAllowsLibraryAction()) {
            onComplete(null, "Finish backup recovery before using the library.")
            return
        }
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.saveLibrary(id, name).fold(
                    onSuccess = { onComplete(it.id, null) },
                    onFailure = { onComplete(null, it.message ?: "Could not save this library.") }
                )
            } finally { _libraryBusy.value = false }
        }
    }

    fun deleteLibrary(id: String, onComplete: (Boolean) -> Unit) {
        if (!recoveryAllowsLibraryAction()) {
            onComplete(false)
            return
        }
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.deleteLibrary(id)
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not delete this library."
                onComplete(false)
            } finally { _libraryBusy.value = false }
        }
    }

    fun backup(
        uri: Uri,
        restore: Boolean,
        includeApiKey: Boolean = false,
        password: String? = null,
        replaceExistingSettings: Boolean = false,
        replaceExistingGroqKey: Boolean = false,
    ) {
        if (!recoveryAllowsLibraryAction()) return
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        _backupProgress.value = BackupProgress(if (restore) BackupProgressStage.Decrypting else BackupProgressStage.Preparing)
        backupJob = viewModelScope.launch {
            try {
                if (restore) {
                    val count = repository.restoreBackup(uri, password, replaceExistingSettings, replaceExistingGroqKey) {
                        _backupProgress.value = it
                    }
                    _message.value = "Backup restore complete; added $count book${if (count == 1) "" else "s"}."
                } else {
                    repository.exportBackup(uri, includeApiKey, password) { _backupProgress.value = it }
                    _message.value = "Backup saved."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                val recoveryPending = restore && repository.backupRestorePending()
                if (recoveryPending) _backupRecoveryBlocked.value = true
                _message.value = if (recoveryPending) "Restore canceled; library recovery is required before further changes." else "Backup operation canceled."
                throw cancelled
            } catch (failure: Exception) {
                if (restore && repository.backupRestorePending()) _backupRecoveryBlocked.value = true
                _message.value = failure.message ?: "Backup operation failed."
            } finally {
                _backupProgress.value = null
                _libraryBusy.value = false
                backupJob = null
            }
        }
    }

    fun cancelBackup() {
        backupJob?.cancel()
    }

    val downloadProgress = WebNovelExportService.downloadProgress

    fun cancelDownload() {
        try {
            WebNovelExportService.cancel(context)
        } catch (failure: Exception) {
            _message.value = failure.message ?: "Could not cancel the export."
        }
    }

    fun pauseWebNovelJob(id: String) {
        viewModelScope.launch {
            try {
                val job = repository.requestPauseWebNovelJob(id)
                WebNovelRecoveryWorker.cancel(context, id)
                if (job?.status == WebNovelJobState.PAUSE_REQUESTED && job.runnerType != "WORKER") WebNovelExportService.pause(context, id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not pause this conversion."
            }
        }
    }

    fun cancelWebNovelJob(id: String) {
        viewModelScope.launch {
            try {
                val job = repository.requestCancelWebNovelJob(id)
                WebNovelRecoveryWorker.cancel(context, id)
                if (job?.status == WebNovelJobState.CANCEL_REQUESTED && job.runnerType != "WORKER") WebNovelExportService.cancel(context, id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not cancel this conversion."
            }
        }
    }

    fun resumeWebNovelJob(id: String) {
        viewModelScope.launch {
            try {
                val current = repository.webNovelJobById(id)
                if (current?.status == WebNovelJobState.WAITING_RATE_LIMIT) {
                    val until = current.waitUntil ?: 0L
                    _message.value = when {
                        current.runnerToken != null -> "This conversion is still waiting in the foreground service."
                        until > System.currentTimeMillis() -> "Retry after ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(until))}."
                        else -> null
                    }
                    if (current.runnerToken != null || until > System.currentTimeMillis()) return@launch
                }
                val job = repository.resumeWebNovelJob(id)
                if (job == null) _message.value = "This conversion is not ready to resume yet."
                else WebNovelExportService.start(context, job.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not resume this conversion."
            }
        }
    }

    fun resetInvalidWebNovelChapterManifest(id: String) {
        viewModelScope.launch {
            try {
                val job = repository.resetInvalidWebNovelChapterManifest(id)
                if (job == null) _message.value = "This conversion no longer exists."
                else WebNovelExportService.start(context, job.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not reset the saved chapter list."
            }
        }
    }

    fun hasInvalidSavedChapterManifest(job: WebNovelJobEntity): Boolean =
        job.status == WebNovelJobState.FAILED &&
            job.error?.startsWith(WebNovelChapterManifestCodec.INVALID_MANIFEST_ERROR_PREFIX) == true

    fun canResumeWebNovelJob(job: WebNovelJobEntity): Boolean =
        job.status in setOf(WebNovelJobState.QUEUED, WebNovelJobState.PAUSED, WebNovelJobState.INTERRUPTED, WebNovelJobState.FAILED) ||
            job.status == WebNovelJobState.WAITING_RATE_LIMIT

    private var conversionJob: kotlinx.coroutines.Job? = null
    private val _conversionProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val conversionProgress = _conversionProgress.asStateFlow()

    fun cancelConversion() { conversionJob?.cancel() }

    fun convertPdf(source: File, fallbackTitle: String, mode: PdfEpubMode, libraryId: String) {
        if (!recoveryAllowsLibraryAction()) return
        if (conversionJob?.isActive == true) return
        _conversionProgress.value = 0 to 0
        conversionJob = viewModelScope.launch {
            try {
                repository.convertPdfToEpub(source, fallbackTitle, mode, libraryId) { completed, total ->
                    _conversionProgress.value = completed to total
                }.fold(
                    onSuccess = { _message.value = "Created ${it.title}.epub" },
                    onFailure = { _message.value = it.message ?: "Could not convert this PDF." },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _message.value = "Conversion cancelled."
                throw cancelled
            } finally {
                _conversionProgress.value = null
            }
        }
    }

    fun createEpubFromUrl(
        url: String,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        libraryId: String = "default"
    ) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch {
            try {
                val job = repository.enqueueWebNovelConversion(url, options, libraryId)
                when {
                    job.status == WebNovelJobState.QUEUED || job.status == WebNovelJobState.RUNNING ->
                        WebNovelExportService.start(context, job.id)
                    job.status == WebNovelJobState.COMPLETED -> _message.value = "This website conversion is already in your library."
                    else -> _message.value = "This conversion is ${job.status.lowercase().replace('_', ' ')}. Open Website conversions to resume or cancel it."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not queue the background export."
            }
        }
    }

    companion object {
        fun factory(repository: BookRepository, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(repository, context.applicationContext) as T
                }
            }
    }
}
