package com.paperlight.reader.webnovel

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Data
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.WebNovelJobEntity
import com.paperlight.reader.data.WebNovelJobState
import com.paperlight.reader.data.WebNovelScrapeOptionsJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNovelRecoveryWorkerTest {
    @Test fun persistedCooldownSchedulesOneWorkPerRecoveryGeneration() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWork().get(5, TimeUnit.SECONDS)
        val now = System.currentTimeMillis()
        val first = job("deferred-work", now + TimeUnit.HOURS.toMillis(2), now)

        WebNovelRecoveryWorker.schedule(context, first)
        WebNovelRecoveryWorker.schedule(context, first)
        val initial = manager.getWorkInfosByTag("web-novel-recovery-${first.id}").get(5, TimeUnit.SECONDS)
        assertEquals(1, initial.size)
        assertEquals(WorkInfo.State.ENQUEUED, initial.single().state)

        val nextSlice = first.copy(updatedAt = now + 1L, waitUntil = now + TimeUnit.HOURS.toMillis(2) + 1L)
        WebNovelRecoveryWorker.schedule(context, nextSlice)
        val generations = manager.getWorkInfosByTag("web-novel-recovery-${first.id}").get(5, TimeUnit.SECONDS)
        assertEquals(2, generations.size)
        assertTrue(generations.all { it.state == WorkInfo.State.ENQUEUED })
        manager.cancelAllWork().get(5, TimeUnit.SECONDS)
    }

    @Test fun workerResumesFullyCachedDueJobAndImportsExactlyOneBook() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        BookDatabase.resetInstanceForTests()
        context.deleteDatabase("paperlight-library.db")
        val database = BookDatabase.getInstance(context)
        val dao = database.bookDao()
        val repository = BookRepository(context, dao)
        repository.ensureDefaultLibrary()
        val url = "http://127.0.0.1:9/fully-cached"
        val options = WebNovelScrapeOptions(requestDelayMillis = 0L)
        val key = WebNovelEpubUtility.checkpointKey(url, options)
        val bookId = BookRepository.webNovelBookId(url, options)
        val jobId = java.util.UUID.randomUUID().toString()
        val checkpoint = File(context.filesDir, "webnovel-checkpoints/$key").apply { deleteRecursively(); mkdirs() }
        val chapterUrl = "http://127.0.0.1:9/chapter-1"
        File(checkpoint, WebNovelChapterManifestStore.FILE_NAME).writeText(
            WebNovelChapterManifestCodec.encode(WebNovelChapterManifest(url, url, key, "Cached novel",
                listOf(WebNovelChapterLink(chapterUrl, "Chapter One")), null))
        )
        File(checkpoint, "chapter-00000.url").writeText(chapterUrl)
        File(checkpoint, "chapter-00000.xhtml").writeText(
            "<?xml version='1.0' encoding='UTF-8'?><html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Chapter One</h1><p>Saved chapter text.</p></body></html>"
        )
        val now = System.currentTimeMillis()
        dao.insertWebNovelJob(WebNovelJobEntity(
            id = jobId, sourceUrl = url, optionsJson = WebNovelScrapeOptionsJson.encode(options), libraryId = "default",
            status = WebNovelJobState.WAITING_RATE_LIMIT, completed = 1, total = 1, label = "Waiting",
            error = null, waitUntil = now - 1L, runnerToken = null, checkpointKey = key, resultBookId = bookId,
            bookTitle = "Cached novel", createdAt = now, updatedAt = now, autoRun = true,
        ))
        val worker = TestListenableWorkerBuilder<WebNovelRecoveryWorker>(context)
            .setInputData(Data.Builder().putString("jobId", jobId).build()).build()
        try {
            assertTrue(worker.doWork() is ListenableWorker.Result.Success)
            val completed = requireNotNull(dao.webNovelJobById(jobId))
            assertEquals(WebNovelJobState.COMPLETED, completed.status)
            assertFalse(completed.autoRun)
            val imported = requireNotNull(dao.bookById(bookId))
            assertEquals("Cached novel", imported.title)
            assertTrue(File(imported.localPath).isFile)
            assertEquals(1, dao.allBooks().count { it.id == bookId })
        } finally {
            checkpoint.deleteRecursively()
            dao.bookById(bookId)?.let { File(it.localPath).delete() }
            dao.deleteById(bookId)
            BookDatabase.resetInstanceForTests()
            context.deleteDatabase("paperlight-library.db")
        }
    }

    private fun job(id: String, waitUntil: Long, updatedAt: Long) = WebNovelJobEntity(
        id = id,
        sourceUrl = "https://example.org/novel",
        optionsJson = WebNovelScrapeOptionsJson.encode(WebNovelScrapeOptions()),
        libraryId = "default",
        status = WebNovelJobState.WAITING_RATE_LIMIT,
        completed = 3,
        total = 10,
        label = "Waiting for rate limit",
        error = null,
        waitUntil = waitUntil,
        runnerToken = null,
        checkpointKey = WebNovelEpubUtility.checkpointKey("https://example.org/novel", WebNovelScrapeOptions()),
        resultBookId = "webnovel-result",
        bookTitle = "Novel",
        createdAt = 1L,
        updatedAt = updatedAt,
        autoRun = true,
    )
}
