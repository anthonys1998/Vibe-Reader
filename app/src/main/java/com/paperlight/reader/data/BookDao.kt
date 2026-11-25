package com.paperlight.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, importedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET progress = :progress, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float, openedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun booksByIds(ids: List<String>): List<BookEntity>

    @Query("UPDATE books SET libraryId = :libraryId WHERE id IN (:ids)")
    suspend fun updateBookLibrary(ids: List<String>, libraryId: String)

    @Transaction
    suspend fun moveBooks(ids: List<String>, libraryId: String) {
        require(findLibrary(libraryId) != null) { "That library no longer exists." }
        ids.chunked(500).forEach { updateBookLibrary(it, libraryId) }
    }

    @Transaction
    suspend fun insertBook(book: BookEntity) {
        require(findLibrary(book.libraryId) != null) { "That library no longer exists." }
        upsert(book)
    }

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun bookById(id: String): BookEntity?

    @Query("SELECT * FROM web_novel_jobs ORDER BY createdAt DESC")
    fun observeWebNovelJobs(): Flow<List<WebNovelJobEntity>>

    @Query("SELECT * FROM web_novel_jobs ORDER BY createdAt")
    suspend fun allWebNovelJobs(): List<WebNovelJobEntity>

    @Query("SELECT * FROM discovery_sources ORDER BY createdAt, name")
    fun observeDiscoverySources(): Flow<List<DiscoverySourceEntity>>

    @Query("SELECT * FROM discovery_sources WHERE id = :id LIMIT 1")
    suspend fun discoverySourceById(id: String): DiscoverySourceEntity?

    @Query("SELECT * FROM discovery_sources")
    suspend fun allDiscoverySources(): List<DiscoverySourceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDiscoverySource(source: DiscoverySourceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscoverySourceIfMissing(source: DiscoverySourceEntity): Long

    @Update
    suspend fun updateDiscoverySource(source: DiscoverySourceEntity)

    @Query("UPDATE discovery_sources SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDiscoverySourceEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM discovery_sources WHERE id = :id")
    suspend fun deleteDiscoverySource(id: String): Int

    @Query("UPDATE discovery_sources SET cacheKey = :cacheKey, cacheJson = :cacheJson, cacheUpdatedAt = :updatedAt WHERE id = :id AND enabled = 1 AND kind = :kind AND baseUrl = :baseUrl AND configJson = :configJson AND updatedAt = :sourceUpdatedAt")
    suspend fun cacheDiscoverySourceResults(id: String, kind: String, baseUrl: String, configJson: String, sourceUpdatedAt: Long, cacheKey: String,
        cacheJson: String, updatedAt: Long): Int

    @Query("SELECT * FROM web_novel_jobs WHERE id = :id LIMIT 1")
    suspend fun webNovelJobById(id: String): WebNovelJobEntity?

    @Query("SELECT * FROM web_novel_jobs WHERE resultBookId = :bookId AND status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT', 'PAUSE_REQUESTED', 'PAUSED', 'CANCEL_REQUESTED', 'INTERRUPTED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeWebNovelJobForBook(bookId: String): WebNovelJobEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM web_novel_jobs WHERE id != :jobId AND status NOT IN ('COMPLETED', 'CANCELLED'))")
    suspend fun hasOtherNonterminalWebNovelJob(jobId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM web_novel_jobs WHERE id != :jobId AND checkpointKey = :checkpointKey AND status NOT IN ('COMPLETED', 'CANCELLED'))")
    suspend fun hasOtherNonterminalWebNovelJobForCheckpoint(jobId: String, checkpointKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWebNovelJob(job: WebNovelJobEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWebNovelJobIfMissing(job: WebNovelJobEntity): Long

    @Update
    suspend fun updateWebNovelJob(job: WebNovelJobEntity)

    @Query("SELECT * FROM web_novel_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueuedWebNovelJob(): WebNovelJobEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM web_novel_jobs WHERE runnerToken IS NOT NULL AND status IN ('RUNNING', 'WAITING_RATE_LIMIT', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED'))")
    suspend fun hasClaimedWebNovelJob(): Boolean

    @Transaction
    suspend fun claimNextWebNovelJob(token: String, now: Long): WebNovelJobEntity? {
        if (hasClaimedWebNovelJob()) return null
        val queued = nextQueuedWebNovelJob() ?: return null
        val claimed = queued.copy(status = WebNovelJobState.RUNNING, label = "Starting…", error = null,
            waitUntil = null, runnerToken = token, runnerType = "FOREGROUND", updatedAt = now)
        updateWebNovelJob(claimed)
        return claimed
    }

    @Transaction
    suspend fun claimRedeliveredWebNovelJob(id: String, token: String, now: Long): WebNovelJobEntity? {
        if (hasClaimedWebNovelJob()) return null
        val existing = webNovelJobById(id) ?: return null
        if (existing.status !in setOf(WebNovelJobState.RUNNING, WebNovelJobState.WAITING_RATE_LIMIT,
                WebNovelJobState.INTERRUPTED)) return null
        if (existing.status == WebNovelJobState.WAITING_RATE_LIMIT && existing.runnerToken == null &&
            (existing.waitUntil ?: 0L) > now) return null
        return existing.copy(status = WebNovelJobState.RUNNING, label = "Resuming…", error = null,
            waitUntil = null, runnerToken = token, runnerType = "FOREGROUND", updatedAt = now).also { updateWebNovelJob(it) }
    }

    @Transaction
    suspend fun claimAutomaticWebNovelJob(id: String, token: String, now: Long): WebNovelJobEntity? {
        if (hasClaimedWebNovelJob()) return null
        val job = webNovelJobById(id) ?: return null
        if (!job.autoRun || job.status !in setOf(WebNovelJobState.QUEUED, WebNovelJobState.INTERRUPTED,
                WebNovelJobState.WAITING_RATE_LIMIT) ||
            (job.status == WebNovelJobState.WAITING_RATE_LIMIT && (job.waitUntil ?: 0L) > now)) return null
        return job.copy(status = WebNovelJobState.RUNNING, label = "Resuming…", error = null,
            waitUntil = null, runnerToken = token, runnerType = "WORKER", updatedAt = now).also { updateWebNovelJob(it) }
    }

    @Query("UPDATE web_novel_jobs SET status = :status, completed = :completed, total = :total, label = :label, waitUntil = :waitUntil, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('RUNNING', 'WAITING_RATE_LIMIT')")
    suspend fun updateWebNovelJobProgress(id: String, token: String, status: String, completed: Int, total: Int,
        label: String, waitUntil: Long?, now: Long): Int

    @Query("UPDATE web_novel_jobs SET bookTitle = :title, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('RUNNING', 'WAITING_RATE_LIMIT')")
    suspend fun updateWebNovelJobTitle(id: String, token: String, title: String, now: Long): Int

    @Query("UPDATE web_novel_jobs SET status = :status, label = :label, error = :error, resultBookId = :resultBookId, runnerToken = NULL, runnerType = NULL, autoRun = 0, waitUntil = NULL, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('RUNNING', 'WAITING_RATE_LIMIT')")
    suspend fun finishWebNovelJob(id: String, token: String, status: String, label: String, error: String?,
        resultBookId: String, now: Long): Int

    @Transaction
    suspend fun insertGeneratedWebNovelBookAndFinish(book: BookEntity, jobId: String, token: String): Boolean {
        val job = webNovelJobById(jobId) ?: return false
        if (job.runnerToken != token || job.status !in setOf(WebNovelJobState.RUNNING, WebNovelJobState.WAITING_RATE_LIMIT)) return false
        require(findLibrary(book.libraryId) != null) { "That library no longer exists." }
        upsert(book)
        check(finishWebNovelJob(jobId, token, WebNovelJobState.COMPLETED, "Completed", null,
            book.id, System.currentTimeMillis()) > 0) { "This conversion was stopped before import." }
        return true
    }

    @Transaction
    suspend fun completeWebNovelJobWithExistingBook(jobId: String, token: String, bookId: String): Boolean {
        val job = webNovelJobById(jobId) ?: return false
        if (job.runnerToken != token || job.status !in setOf(WebNovelJobState.RUNNING, WebNovelJobState.WAITING_RATE_LIMIT)) return false
        if (bookById(bookId) == null) return false
        return finishWebNovelJob(jobId, token, WebNovelJobState.COMPLETED, "Completed", null,
            bookId, System.currentTimeMillis()) > 0
    }

    @Query("UPDATE web_novel_jobs SET status = CASE status WHEN 'PAUSE_REQUESTED' THEN 'PAUSED' WHEN 'CANCEL_REQUESTED' THEN 'CANCELLED' ELSE 'INTERRUPTED' END, label = CASE status WHEN 'PAUSE_REQUESTED' THEN 'Paused' WHEN 'CANCEL_REQUESTED' THEN 'Cancelled' ELSE 'Interrupted; resume to continue' END, runnerToken = NULL, runnerType = NULL, waitUntil = NULL, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('PAUSE_REQUESTED', 'CANCEL_REQUESTED')")
    suspend fun settleWebNovelJobRequest(id: String, token: String, now: Long): Int

    @Query("UPDATE web_novel_jobs SET status = 'INTERRUPTED', label = 'Interrupted; resume to continue', runnerToken = NULL, runnerType = NULL, waitUntil = NULL, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('RUNNING', 'WAITING_RATE_LIMIT')")
    suspend fun interruptWebNovelJob(id: String, token: String, now: Long): Int

    @Query("UPDATE web_novel_jobs SET status = 'WAITING_RATE_LIMIT', label = :label, waitUntil = :waitUntil, runnerToken = NULL, runnerType = NULL, error = NULL, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('RUNNING', 'WAITING_RATE_LIMIT')")
    suspend fun deferWebNovelJobForRateLimit(id: String, token: String, label: String, waitUntil: Long, now: Long): Int

    @Query("UPDATE web_novel_jobs SET status = 'WAITING_RATE_LIMIT', label = 'Queued for automatic recovery', waitUntil = :waitUntil, runnerToken = NULL, runnerType = NULL, error = NULL, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status = 'RUNNING' AND autoRun = 1")
    suspend fun continueWebNovelJobAfterWorkerSlice(id: String, token: String, waitUntil: Long, now: Long): Int

    @Transaction
    suspend fun requestPauseWebNovelJob(id: String, now: Long): WebNovelJobEntity? {
        val job = webNovelJobById(id) ?: return null
        val updated = when (job.status) {
            WebNovelJobState.QUEUED -> job.copy(status = WebNovelJobState.PAUSED, label = "Paused", autoRun = false, updatedAt = now)
            WebNovelJobState.RUNNING -> job.copy(
                status = WebNovelJobState.PAUSE_REQUESTED, label = "Pausing…", autoRun = false, updatedAt = now)
            WebNovelJobState.WAITING_RATE_LIMIT -> if (job.runnerToken == null) job.copy(
                status = WebNovelJobState.PAUSED, label = "Paused", autoRun = false, updatedAt = now) else job.copy(
                status = WebNovelJobState.PAUSE_REQUESTED, label = "Pausing…", autoRun = false, updatedAt = now)
            else -> return null
        }
        updateWebNovelJob(updated)
        return updated
    }

    @Transaction
    suspend fun requestCancelWebNovelJob(id: String, now: Long): WebNovelJobEntity? {
        val job = webNovelJobById(id) ?: return null
        val updated = when (job.status) {
            WebNovelJobState.QUEUED, WebNovelJobState.PAUSED, WebNovelJobState.FAILED,
            WebNovelJobState.INTERRUPTED -> job.copy(status = WebNovelJobState.CANCELLED, label = "Cancelled",
                runnerToken = null, runnerType = null, autoRun = false, waitUntil = null, updatedAt = now)
            WebNovelJobState.RUNNING -> job.copy(
                status = WebNovelJobState.CANCEL_REQUESTED, label = "Cancelling…", autoRun = false, updatedAt = now)
            WebNovelJobState.WAITING_RATE_LIMIT -> if (job.runnerToken == null) job.copy(
                status = WebNovelJobState.CANCELLED, label = "Cancelled", autoRun = false, waitUntil = null, updatedAt = now) else job.copy(
                status = WebNovelJobState.CANCEL_REQUESTED, label = "Cancelling…", autoRun = false, updatedAt = now)
            else -> return null
        }
        updateWebNovelJob(updated)
        return updated
    }

    @Transaction
    suspend fun resumeWebNovelJob(id: String, now: Long): WebNovelJobEntity? {
        val job = webNovelJobById(id) ?: return null
        if (job.status !in setOf(WebNovelJobState.QUEUED, WebNovelJobState.PAUSED, WebNovelJobState.INTERRUPTED, WebNovelJobState.FAILED) &&
            !(job.status == WebNovelJobState.WAITING_RATE_LIMIT && job.runnerToken == null &&
                (job.waitUntil ?: Long.MAX_VALUE) <= now)) return null
        return job.copy(status = WebNovelJobState.QUEUED, label = "Queued", error = null,
            waitUntil = null, runnerToken = null, runnerType = null, autoRun = true, updatedAt = now).also { updateWebNovelJob(it) }
    }

    @Query("UPDATE web_novel_jobs SET status = 'QUEUED', label = 'Queued', completed = 0, total = 0, bookTitle = NULL, error = NULL, waitUntil = NULL, runnerToken = NULL, runnerType = NULL, autoRun = 1, updatedAt = :now WHERE id = :id AND status = 'FAILED' AND error LIKE 'Saved chapter list is invalid:%'")
    suspend fun requeueFailedWebNovelJobAfterManifestReset(id: String, now: Long): Int

    @Query("UPDATE web_novel_jobs SET status = CASE status WHEN 'PAUSE_REQUESTED' THEN 'PAUSED' WHEN 'CANCEL_REQUESTED' THEN 'CANCELLED' ELSE 'INTERRUPTED' END, label = CASE status WHEN 'PAUSE_REQUESTED' THEN 'Paused' WHEN 'CANCEL_REQUESTED' THEN 'Cancelled' ELSE 'Interrupted; resume to continue' END, runnerToken = NULL, runnerType = NULL, waitUntil = NULL, updatedAt = :now WHERE runnerToken IS NOT NULL AND updatedAt <= :staleBefore AND status IN ('RUNNING', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED', 'WAITING_RATE_LIMIT')")
    suspend fun reconcileStaleWebNovelJobs(now: Long, staleBefore: Long): Int

    suspend fun reconcileStaleWebNovelJobs(now: Long): Int = reconcileStaleWebNovelJobs(now, now)

    @Query("UPDATE web_novel_jobs SET status = CASE status WHEN 'PAUSE_REQUESTED' THEN 'PAUSED' WHEN 'CANCEL_REQUESTED' THEN 'CANCELLED' WHEN 'RUNNING' THEN CASE WHEN runnerType = 'WORKER' AND autoRun = 1 THEN 'WAITING_RATE_LIMIT' ELSE 'INTERRUPTED' END WHEN 'WAITING_RATE_LIMIT' THEN CASE WHEN runnerType = 'WORKER' AND autoRun = 1 THEN 'WAITING_RATE_LIMIT' ELSE 'INTERRUPTED' END ELSE 'INTERRUPTED' END, label = CASE status WHEN 'PAUSE_REQUESTED' THEN 'Paused' WHEN 'CANCEL_REQUESTED' THEN 'Cancelled' WHEN 'RUNNING' THEN CASE WHEN runnerType = 'WORKER' AND autoRun = 1 THEN 'Queued for automatic recovery' ELSE 'Interrupted; resume to continue' END WHEN 'WAITING_RATE_LIMIT' THEN CASE WHEN runnerType = 'WORKER' AND autoRun = 1 THEN 'Queued for automatic recovery' ELSE 'Interrupted; resume to continue' END ELSE 'Interrupted; resume to continue' END, waitUntil = CASE WHEN status IN ('PAUSE_REQUESTED', 'CANCEL_REQUESTED') THEN NULL WHEN runnerType = 'WORKER' AND autoRun = 1 THEN MAX(COALESCE(waitUntil, 0), :now) ELSE NULL END, runnerToken = NULL, runnerType = NULL, updatedAt = :now WHERE id = :id AND runnerToken = :token AND status IN ('RUNNING', 'WAITING_RATE_LIMIT', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED')")
    suspend fun reconcileOrphanedWebNovelJob(id: String, token: String, now: Long): Int

    @Query("SELECT * FROM web_novel_jobs WHERE autoRun = 1 AND status IN ('QUEUED', 'WAITING_RATE_LIMIT', 'INTERRUPTED') ORDER BY COALESCE(waitUntil, 0), createdAt")
    suspend fun automaticWebNovelJobs(): List<WebNovelJobEntity>

    @Query("UPDATE web_novel_jobs SET waitUntil = MAX(COALESCE(waitUntil, 0), :wakeAt), updatedAt = :now WHERE id = :id AND autoRun = 1 AND status IN ('QUEUED', 'WAITING_RATE_LIMIT', 'INTERRUPTED')")
    suspend fun postponeAutomaticWebNovelJob(id: String, now: Long, wakeAt: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM web_novel_jobs WHERE status IN (:statuses))")
    suspend fun hasWebNovelJobsInStates(statuses: List<String>): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM web_novel_jobs WHERE runnerToken IS NOT NULL AND status IN ('RUNNING', 'WAITING_RATE_LIMIT', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED'))")
    suspend fun hasActiveWebNovelJob(): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRestoreTransaction(transaction: RestoreTransactionEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM backup_restore_transactions WHERE id = :id)")
    suspend fun hasRestoreTransaction(id: String): Boolean

    @Query("DELETE FROM backup_restore_transactions WHERE id = :id")
    suspend fun deleteRestoreTransaction(id: String): Int

    @Query("DELETE FROM backup_restore_transactions")
    suspend fun clearRestoreTransactions(): Int

    @Query("SELECT * FROM libraries")
    suspend fun allLibraries(): List<LibraryEntity>

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String)

    @Transaction
    suspend fun backupSnapshot(): Pair<List<BookEntity>, List<LibraryEntity>> = allBooks() to allLibraries()

    @Transaction
    suspend fun restoreSnapshot(libraries: List<LibraryEntity>, books: List<BookEntity>) {
        insertLibrary(LibraryEntity("default", "My Library", 0L))
        libraries.forEach { insertLibrary(it) }
        val existing = allBooks().map { it.id }.toSet()
        require(books.none { it.id in existing }) { "The library changed during restore. Please retry." }
        books.forEach { insertBook(it) }
    }

    @Transaction
    suspend fun restoreExtendedSnapshot(
        libraries: List<LibraryEntity>,
        books: List<BookEntity>,
        jobs: List<WebNovelJobEntity>,
        sources: List<DiscoverySourceEntity>,
        builtinEnabled: Map<String, Boolean>,
        replaceBuiltinEnabled: Boolean,
        transactionId: String,
        now: Long,
    ) {
        insertLibrary(LibraryEntity("default", "My Library", 0L))
        libraries.forEach { insertLibrary(it) }
        val existingBooks = allBooks().map { it.id }.toSet()
        require(books.none { it.id in existingBooks }) { "The library changed during restore. Please retry." }
        books.forEach { insertBook(it) }
        val existingSourceIds = allDiscoverySources().map { it.id }.toSet()
        sources.filterNot(DiscoverySourceIds::isRemovedGutenbergBuiltin)
            .filterNot { it.id in existingSourceIds }.forEach { insertDiscoverySourceIfMissing(it) }
        if (replaceBuiltinEnabled) {
            builtinEnabled.forEach { (id, enabled) ->
                if (id in DiscoverySourceIds.BUILT_INS) {
                    setDiscoverySourceEnabled(id, enabled, now)
                }
            }
        }
        val existingJobIds = allWebNovelJobs().map { it.id }.toSet()
        jobs.filterNot { it.id in existingJobIds }.forEach { insertWebNovelJobIfMissing(it) }
        insertRestoreTransaction(RestoreTransactionEntity(transactionId, now))
    }

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteBooks(ids: List<String>)

    @Query("UPDATE libraries SET name = :name WHERE id = :id")
    suspend fun renameLibrary(id: String, name: String)

    @Query("SELECT COUNT(*) FROM libraries WHERE name = :name COLLATE NOCASE AND id != :exceptId")
    suspend fun libraryNameCount(name: String, exceptId: String): Int

    @Query("UPDATE books SET libraryId = 'default' WHERE libraryId = :id")
    suspend fun moveLibraryBooksToDefault(id: String)

    @Query("DELETE FROM libraries WHERE id = :id")
    suspend fun deleteLibraryRow(id: String)

    @Transaction
    suspend fun deleteLibrary(id: String) {
        require(id != "default") { "The default library cannot be deleted." }
        moveLibraryBooksToDefault(id)
        deleteLibraryRow(id)
    }

    @Query("SELECT * FROM libraries ORDER BY createdAt, name COLLATE NOCASE")
    fun observeLibraries(): Flow<List<LibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibrary(library: LibraryEntity)

    @Query("SELECT * FROM libraries WHERE id = :id LIMIT 1")
    suspend fun findLibrary(id: String): LibraryEntity?
}
