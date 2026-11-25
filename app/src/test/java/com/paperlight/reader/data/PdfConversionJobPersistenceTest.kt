package com.paperlight.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfConversionJobPersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: BookDatabase

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking { database.bookDao().insertLibrary(LibraryEntity("default", "My Library", 0L)) }
    }

    @After fun closeDatabase() = database.close()

    @Test fun claimsAreExclusiveAndStaleRunnerCannotPublish() = runBlocking {
        val dao = database.bookDao()
        dao.insertPdfConversionJob(job("pdf-a"))
        dao.insertPdfConversionJob(job("pdf-b"))

        val claim = requireNotNull(dao.claimNextPdfConversionJob("runner-a", "FOREGROUND", 10L))
        assertEquals("pdf-a", claim.id)
        assertNull(dao.claimNextPdfConversionJob("runner-b", "WORKER", 11L))
        assertEquals(0, dao.updatePdfConversionJobProgress("pdf-a", "stale-runner", "Extracting", 2, 5, 12L))
        assertEquals(1, dao.updatePdfConversionJobProgress("pdf-a", "runner-a", "Extracting", 2, 5, 13L))

        assertEquals(PdfConversionJobState.PAUSE_REQUESTED,
            dao.requestPausePdfConversionJob("pdf-a", 14L)?.status)
        assertEquals(0, dao.deletePdfConversionJobIfUnclaimed("pdf-a"))
        assertEquals(1, dao.settlePdfConversionJobRequest("pdf-a", "runner-a", 15L))
        val paused = requireNotNull(dao.pdfConversionJobById("pdf-a"))
        assertEquals(PdfConversionJobState.PAUSED, paused.status)
        assertFalse(paused.autoRun)

        val queued = requireNotNull(dao.resumePdfConversionJob("pdf-a", 16L))
        assertEquals(PdfConversionJobState.QUEUED, queued.status)
        assertTrue(queued.autoRun)
        val resumed = requireNotNull(dao.claimNextPdfConversionJob("runner-c", "FOREGROUND", 17L))
        assertEquals("pdf-a", resumed.id)
        assertEquals(PdfConversionJobState.CANCEL_REQUESTED,
            dao.requestCancelPdfConversionJob("pdf-a", 18L)?.status)
        assertEquals(1, dao.settlePdfConversionJobRequest("pdf-a", "runner-c", 19L))
        assertEquals(PdfConversionJobState.CANCELLED, dao.pdfConversionJobById("pdf-a")?.status)
        assertEquals(PdfConversionJobState.QUEUED,
            dao.resumePdfConversionJob("pdf-a", 20L, retry = true)?.status)
    }

    @Test fun generatedBookAndJobFinishCommitTogetherWithStableResultId() = runBlocking {
        val dao = database.bookDao()
        val queued = job("pdf-import")
        dao.insertPdfConversionJob(queued)
        val claimed = requireNotNull(dao.claimNextPdfConversionJob("runner-import", "FOREGROUND", 1L))
        val generatedBook = BookEntity(
            id = claimed.resultBookId,
            title = "Converted title",
            fileName = "Converted title.epub",
            localPath = "/private/books/${claimed.resultBookId}.epub",
            format = "EPUB",
            importedAt = 2L,
            libraryId = "default",
        )

        assertTrue(dao.insertGeneratedPdfBookAndFinish(generatedBook, claimed.id, "runner-import"))
        assertEquals(generatedBook.id, dao.bookById(generatedBook.id)?.id)
        val completed = requireNotNull(dao.pdfConversionJobById(claimed.id))
        assertEquals(PdfConversionJobState.COMPLETED, completed.status)
        assertEquals(generatedBook.id, completed.resultBookId)
        assertNull(completed.runnerToken)
        assertFalse(completed.autoRun)
        assertFalse(dao.insertGeneratedPdfBookAndFinish(generatedBook, claimed.id, "runner-import"))
    }

    @Test fun repositoryValidatesEpubAndImportsOnlyOnceForTheClaimedJob() = runBlocking {
        val dao = database.bookDao()
        val job = job("pdf-validated-import")
        dao.insertPdfConversionJob(job)
        val claimed = requireNotNull(dao.claimNextPdfConversionJob("runner-valid", "FOREGROUND", 2L))
        val output = File(context.cacheDir, "${job.id}-validated.epub")
        val repository = BookRepository(context, dao)
        try {
            createValidEpub(output)
            val imported = repository.importPdfConversionEpub(output, "Validated", "Author", claimed.id,
                "runner-valid", job.inputHash, listOf(4, 7)).getOrThrow()
            assertEquals(job.resultBookId, imported.id)
            assertEquals(PdfConversionJobState.COMPLETED, dao.pdfConversionJobById(job.id)?.status)
            assertTrue(requireNotNull(dao.pdfConversionJobById(job.id)).warning!!.contains("4, 7"))
            assertEquals(1, dao.allBooks().count { it.id == job.resultBookId })

            createValidEpub(output)
            assertTrue(repository.importPdfConversionEpub(output, "Validated", "Author", claimed.id,
                "runner-valid", job.inputHash, listOf(4, 7)).isFailure)
            assertEquals(1, dao.allBooks().count { it.id == job.resultBookId })
        } finally {
            output.delete()
            File(context.filesDir, "books/${job.resultBookId}.epub").delete()
        }
    }

    @Test fun v9MigrationAddsPdfTableAndPreservesExistingWebsiteJob() = runBlocking {
        val name = "pdf-v9-migration-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE books (id TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, fileName TEXT NOT NULL, localPath TEXT NOT NULL, format TEXT NOT NULL, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER, progress REAL NOT NULL, status TEXT NOT NULL, chapterPage INTEGER NOT NULL DEFAULT 0, chapterPageCount INTEGER NOT NULL DEFAULT 0, libraryId TEXT NOT NULL DEFAULT 'default', PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE libraries (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("INSERT INTO libraries VALUES ('default', 'My Library', 0)")
        old.execSQL("CREATE TABLE web_novel_jobs (id TEXT NOT NULL, sourceUrl TEXT NOT NULL, optionsJson TEXT NOT NULL, libraryId TEXT NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, label TEXT NOT NULL, error TEXT, waitUntil INTEGER, runnerToken TEXT, checkpointKey TEXT NOT NULL, resultBookId TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, bookTitle TEXT, runnerType TEXT, autoRun INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))")
        old.execSQL("CREATE INDEX index_web_novel_jobs_createdAt ON web_novel_jobs (createdAt)")
        old.execSQL("INSERT INTO web_novel_jobs (id, sourceUrl, optionsJson, libraryId, status, completed, total, label, checkpointKey, resultBookId, createdAt, updatedAt) VALUES ('web-keep', 'https://example.org/book', '{}', 'default', 'PAUSED', 2, 4, 'Paused', 'checkpoint', 'result', 1, 2)")
        old.execSQL("CREATE TABLE discovery_sources (id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, baseUrl TEXT NOT NULL, enabled INTEGER NOT NULL, configJson TEXT NOT NULL, cacheKey TEXT, cacheJson TEXT, cacheUpdatedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE backup_restore_transactions (id TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.version = 9
        old.close()

        val migrated = Room.databaseBuilder(context, BookDatabase::class.java, name)
            .addMigrations(BookDatabase.addPdfConversionJobs).build()
        try {
            val dao = migrated.bookDao()
            assertEquals("web-keep", dao.webNovelJobById("web-keep")?.id)
            assertTrue(dao.allPdfConversionJobs().isEmpty())
            assertNotNull(dao.observePdfConversionJobs())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun job(id: String) = PdfConversionJobEntity(
        id = id,
        sourceName = "$id.pdf",
        sourceSize = 100L,
        inputHash = "a".repeat(64),
        inputSnapshotPath = "/private/$id.pdf",
        mode = PdfEpubMode.Text.name,
        optionsJson = "{}",
        libraryId = "default",
        status = PdfConversionJobState.QUEUED,
        completedPages = 0,
        totalPages = 0,
        stage = "Queued",
        error = null,
        warning = null,
        runnerToken = null,
        runnerType = null,
        checkpointKey = "b".repeat(64),
        resultBookId = "pdf-$id",
        createdAt = 1L,
        updatedAt = 1L,
        autoRun = true,
    )

    private fun createValidEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"EPUB/package.opf\"/></rootfiles></container>")
            add("EPUB/package.opf", "<package><metadata/><manifest/><spine/></package>")
            add("EPUB/text/chapter.xhtml", "<html><body>Read me</body></html>")
        }
    }
}
