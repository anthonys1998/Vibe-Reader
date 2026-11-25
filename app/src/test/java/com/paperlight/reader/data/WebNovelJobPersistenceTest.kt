package com.paperlight.reader.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.paperlight.reader.discovery.DiscoverySourceConfigCodec
import com.paperlight.reader.discovery.DiscoverySourcePurpose
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import com.paperlight.reader.webnovel.WebNovelChapterManifest
import com.paperlight.reader.webnovel.WebNovelChapterManifestCodec
import com.paperlight.reader.webnovel.WebNovelChapterManifestStore
import com.paperlight.reader.webnovel.WebNovelChapterLink
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.InvalidSavedChapterManifestException
import com.paperlight.reader.webnovel.ChapterManifestTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNovelJobPersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: BookDatabase

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = BookDatabase.seedDiscoverySources(db)
            }).allowMainThreadQueries().build()
        runBlocking { database.bookDao().insertLibrary(LibraryEntity("default", "My Library", 0L)) }
    }

    @After fun closeDatabase() { database.close() }

    @Test fun bookExportCopiesOriginalBytesAndFilename() = runBlocking {
        val source = java.io.File(context.cacheDir, "export-source-${java.util.UUID.randomUUID()}.epub")
        val destination = java.io.File(context.cacheDir, "export-destination-${java.util.UUID.randomUUID()}.epub")
        val bytes = byteArrayOf(0, 1, 2, 13, 10, -1, 42)
        source.writeBytes(bytes)
        val book = BookEntity("export-book", "Export title", fileName = "Original name.epub",
            localPath = source.absolutePath, format = "EPUB", importedAt = 1L, libraryId = "default")
        try {
            database.bookDao().insertBook(book)
            val exportedName = BookRepository(context, database.bookDao()).exportBook(book.id, android.net.Uri.fromFile(destination))
            assertEquals(book.fileName, exportedName)
            assertArrayEquals(bytes, destination.readBytes())
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test fun missingBookOrSourceDoesNotTruncateSelectedDestination() = runBlocking {
        val missingSource = java.io.File(context.cacheDir, "missing-export-${java.util.UUID.randomUUID()}.epub")
        val destination = java.io.File(context.cacheDir, "existing-destination-${java.util.UUID.randomUUID()}.epub")
        val originalBytes = byteArrayOf(9, 8, 7, 0, -1)
        destination.writeBytes(originalBytes)
        val dao = database.bookDao()
        dao.insertBook(BookEntity("missing-source", "Missing source", fileName = "missing.epub",
            localPath = missingSource.absolutePath, format = "EPUB", importedAt = 1L, libraryId = "default"))
        val repository = BookRepository(context, dao)
        try {
            for (id in listOf("missing-source", "deleted-book")) {
                try {
                    repository.exportBook(id, android.net.Uri.fromFile(destination))
                    fail("Expected export of $id to fail")
                } catch (_: IllegalArgumentException) {
                } catch (_: IllegalStateException) {
                }
                assertArrayEquals("Failed export must leave the chosen document intact", originalBytes, destination.readBytes())
            }
        } finally {
            destination.delete()
        }
    }

    @Test fun queueClaimsAreExclusiveAndStaleLeaseCannotPublish() = runBlocking {
        val dao = database.bookDao()
        dao.insertWebNovelJob(job("job-1"))
        dao.insertWebNovelJob(job("job-2"))
        val claim = requireNotNull(dao.claimNextWebNovelJob("runner-a", 10L))
        assertEquals("runner-a", claim.runnerToken)
        assertNull(dao.claimNextWebNovelJob("runner-b", 11L))

        assertEquals(0, dao.updateWebNovelJobProgress("job-1", "old-runner", WebNovelJobState.RUNNING,
            1, 3, "stale", null, 12L))
        assertEquals(0, dao.updateWebNovelJobTitle("job-1", "old-runner", "Stale title", 12L))
        assertEquals(1, dao.updateWebNovelJobTitle("job-1", "runner-a", "Snapshot title", 13L))
        assertEquals("Snapshot title", dao.webNovelJobById("job-1")?.bookTitle)
        val book = BookEntity("webnovel-result", "Title", fileName = "Title.epub", localPath = "/tmp/title.epub",
            format = "EPUB", importedAt = 20L, libraryId = "default")
        assertFalse(dao.insertGeneratedWebNovelBookAndFinish(book, "job-1", "old-runner"))
        assertNull(dao.bookById(book.id))

        assertTrue(dao.insertGeneratedWebNovelBookAndFinish(book, "job-1", "runner-a"))
        assertEquals(WebNovelJobState.COMPLETED, dao.webNovelJobById("job-1")?.status)
        assertEquals(book.id, dao.webNovelJobById("job-1")?.resultBookId)
        assertEquals(book.id, dao.bookById(book.id)?.id)
        val secondClaim = requireNotNull(dao.claimNextWebNovelJob("runner-b", 21L))
        assertEquals("job-2", secondClaim.id)
        assertEquals(WebNovelJobState.CANCEL_REQUESTED, dao.requestCancelWebNovelJob("job-2", 22L)?.status)
        dao.reconcileStaleWebNovelJobs(23L)
        assertEquals(WebNovelJobState.CANCELLED, dao.webNovelJobById("job-2")?.status)
    }

    @Test fun requestedPauseSettlesToUserResumableStateAfterProcessDeath() = runBlocking {
        val dao = database.bookDao()
        dao.insertWebNovelJob(job("job-pause"))
        dao.claimNextWebNovelJob("runner", 1L)
        assertEquals(WebNovelJobState.PAUSE_REQUESTED,
            dao.requestPauseWebNovelJob("job-pause", 2L)?.status)
        dao.reconcileStaleWebNovelJobs(3L)
        assertEquals(WebNovelJobState.PAUSED, dao.webNovelJobById("job-pause")?.status)
        assertEquals(WebNovelJobState.QUEUED, dao.resumeWebNovelJob("job-pause", 4L)?.status)
    }

    @Test fun automaticWorkerAndForegroundServiceShareOneLeaseAndPausedJobsStayPaused() = runBlocking {
        val repository = BookRepository(context, database.bookDao())
        val queued = repository.enqueueWebNovelConversion("https://example.org/automatic", WebNovelScrapeOptions(), "default")
        assertTrue(queued.autoRun)

        val foreground = requireNotNull(repository.claimNextWebNovelJob())
        assertEquals("FOREGROUND", foreground.runnerType)
        assertNull(repository.claimAutomaticWebNovelJob(queued.id))
        assertEquals(foreground.runnerToken, database.bookDao().webNovelJobById(queued.id)?.runnerToken)

        assertTrue(repository.deferWebNovelJobForRateLimit(queued.id, requireNotNull(foreground.runnerToken),
            "example.org", System.currentTimeMillis() - 1L))
        val worker = requireNotNull(repository.claimAutomaticWebNovelJob(queued.id))
        assertEquals("WORKER", worker.runnerType)
        assertEquals(WebNovelJobState.RUNNING, worker.status)

        val pause = requireNotNull(repository.requestPauseWebNovelJob(queued.id))
        assertEquals(WebNovelJobState.PAUSE_REQUESTED, pause.status)
        assertFalse(pause.autoRun)
        assertFalse(repository.continueWebNovelJobAfterWorkerSlice(queued.id, requireNotNull(worker.runnerToken)))
        assertTrue(repository.settleWebNovelJobRequest(queued.id, requireNotNull(worker.runnerToken)))
        assertEquals(WebNovelJobState.PAUSED, database.bookDao().webNovelJobById(queued.id)?.status)
        assertNull(repository.claimAutomaticWebNovelJob(queued.id))

        val cancelQueued = repository.enqueueWebNovelConversion("https://example.org/cancel", WebNovelScrapeOptions(), "default")
        val cancelRunner = requireNotNull(repository.claimAutomaticWebNovelJob(cancelQueued.id))
        assertEquals(WebNovelJobState.CANCEL_REQUESTED, repository.requestCancelWebNovelJob(cancelQueued.id)?.status)
        assertTrue(repository.settleWebNovelJobRequest(cancelQueued.id, requireNotNull(cancelRunner.runnerToken)))
        val cancelled = requireNotNull(database.bookDao().webNovelJobById(cancelQueued.id))
        assertEquals(WebNovelJobState.CANCELLED, cancelled.status)
        assertFalse(cancelled.autoRun)
        assertNull(repository.claimAutomaticWebNovelJob(cancelQueued.id))
    }

    @Test fun workerContinuationKeepsQueuedForegroundJobsFromBeingStranded() = runBlocking<Unit> {
        val repository = BookRepository(context, database.bookDao())
        val first = repository.enqueueWebNovelConversion("https://example.org/first", WebNovelScrapeOptions(), "default")
        val second = repository.enqueueWebNovelConversion("https://example.org/second", WebNovelScrapeOptions(), "default")
        val worker = requireNotNull(repository.claimAutomaticWebNovelJob(first.id))

        assertNull(repository.claimNextWebNovelJob())
        assertEquals(WebNovelJobState.QUEUED, database.bookDao().webNovelJobById(second.id)?.status)
        assertTrue(repository.continueWebNovelJobAfterWorkerSlice(first.id, requireNotNull(worker.runnerToken)))
        assertEquals(WebNovelJobState.WAITING_RATE_LIMIT, database.bookDao().webNovelJobById(first.id)?.status)

        val queuedClaim = requireNotNull(repository.claimAutomaticWebNovelJob(second.id))
        assertEquals(second.id, queuedClaim.id)
        assertEquals("WORKER", queuedClaim.runnerType)
        repository.interruptWebNovelJob(second.id, requireNotNull(queuedClaim.runnerToken))
    }

    @Test fun orphanWorkerLeaseRecoversButLiveForegroundLeaseIsNeverStolen() = runBlocking<Unit> {
        val dao = database.bookDao()
        val repository = BookRepository(context, dao)
        val live = repository.enqueueWebNovelConversion("https://example.org/live", WebNovelScrapeOptions(), "default")
        val claimed = requireNotNull(repository.claimNextWebNovelJob())
        repository.reconcileWebNovelJobs(serviceActive = false)
        assertEquals(claimed.runnerToken, dao.webNovelJobById(live.id)?.runnerToken)

        val orphan = job("orphan-worker").copy(status = WebNovelJobState.RUNNING, runnerToken = "orphan-token",
            runnerType = "WORKER", autoRun = true, waitUntil = null)
        dao.insertWebNovelJob(orphan)
        repository.reconcileWebNovelJobs(serviceActive = false)
        val recovered = requireNotNull(dao.webNovelJobById(orphan.id))
        assertEquals(WebNovelJobState.WAITING_RATE_LIMIT, recovered.status)
        assertNull(recovered.runnerToken)
        assertTrue(recovered.waitUntil!! >= System.currentTimeMillis() - 1_000L)
        assertTrue(recovered.autoRun)
        repository.interruptWebNovelJob(live.id, requireNotNull(claimed.runnerToken))
    }

    @Test fun scrapeOptionsSnapshotRoundTrips() {
        val options = WebNovelScrapeOptions(
            chapterLinkSelector = "a.chapter", chapterContentSelector = ".story", chapterTitleSelector = "h1.title",
            removeSelector = ".ad", firstChapter = 3, lastChapter = 9, requestDelayMillis = 700L, renderInBrowser = true,
        )
        assertEquals(options, WebNovelScrapeOptionsJson.decode(WebNovelScrapeOptionsJson.encode(options)))
    }

    @Test fun manifestStoreReusesFrozenOrderAndOnlyReindexesAfterExplicitReset() = runBlocking {
        val options = WebNovelScrapeOptions()
        val sourceUrl = "https://example.org/book"
        val checkpointKey = WebNovelEpubUtility.checkpointKey(sourceUrl, options)
        val directory = java.io.File(context.cacheDir, "manifest-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val store = WebNovelChapterManifestStore(directory)
        val original = WebNovelChapterManifest(
            sourceUrl = sourceUrl, tocUrl = "https://www.example.org/toc", checkpointKey = checkpointKey,
            title = "Original title", coverImageUrl = "https://images.example.org/cover.jpg",
            chapters = listOf(
                WebNovelChapterLink("https://www.example.org/chapter-a", "Chapter A"),
                WebNovelChapterLink("https://www.example.org/chapter-b", "Chapter B"),
            ),
        )
        val changed = original.copy(title = "Changed title", chapters = original.chapters.reversed())
        var indexCalls = 0
        try {
            assertEquals(original, store.loadOrCreate(sourceUrl, checkpointKey) { indexCalls++; original })
            assertEquals(original, store.loadOrCreate(sourceUrl, checkpointKey) { indexCalls++; changed })
            assertEquals("A retry must not read a changed TOC", 1, indexCalls)

            java.io.File(directory, WebNovelChapterManifestStore.FILE_NAME).writeText("{corrupt")
            var corruptManifestFailed = false
            try {
                store.loadOrCreate(sourceUrl, checkpointKey) { indexCalls++; changed }
            } catch (_: InvalidSavedChapterManifestException) {
                corruptManifestFailed = true
            }
            assertTrue(corruptManifestFailed)
            assertEquals("A corrupt manifest must not trigger a silent TOC refresh", 1, indexCalls)

            assertTrue(store.clear())
            assertEquals(changed, store.loadOrCreate(sourceUrl, checkpointKey) { indexCalls++; changed })
            assertEquals(2, indexCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun manifestValidatesRedirectedOriginAndRejectsSchemeMismatch() {
        val sourceUrl = "https://example.org/book"
        val key = WebNovelEpubUtility.checkpointKey(sourceUrl, WebNovelScrapeOptions())
        val manifest = WebNovelChapterManifest(
            sourceUrl = sourceUrl, tocUrl = "https://www.example.org/toc", checkpointKey = key,
            title = "Redirected book", coverImageUrl = null,
            chapters = listOf(WebNovelChapterLink("https://www.example.org/chapter-1", "Chapter 1")),
        )
        assertEquals(manifest, WebNovelChapterManifestCodec.decode(
            WebNovelChapterManifestCodec.encode(manifest), sourceUrl, key))
        var rejectedSchemeMismatch = false
        try {
            WebNovelChapterManifestCodec.encode(manifest.copy(
                chapters = listOf(WebNovelChapterLink("http://www.example.org:443/chapter-1", "Chapter 1"))))
        } catch (_: IllegalArgumentException) {
            rejectedSchemeMismatch = true
        }
        assertTrue(rejectedSchemeMismatch)
    }

    @Test fun manifestByteLimitRejectsLargeUnicodeBeforeSerialization() {
        val sourceUrl = "https://example.org/book"
        val options = WebNovelScrapeOptions()
        val manifest = WebNovelChapterManifest(
            sourceUrl = sourceUrl, tocUrl = sourceUrl, checkpointKey = WebNovelEpubUtility.checkpointKey(sourceUrl, options),
            title = "Large book", coverImageUrl = null,
            chapters = (0 until WebNovelChapterManifestCodec.MAX_CHAPTERS).map { index ->
                WebNovelChapterLink("https://example.org/chapter-$index", "😀".repeat(80))
            },
        )
        var rejectedAsTooLarge = false
        try {
            WebNovelChapterManifestCodec.encode(manifest)
        } catch (_: ChapterManifestTooLargeException) {
            rejectedAsTooLarge = true
        }
        assertTrue(rejectedAsTooLarge)
    }

    @Test fun confirmedManifestResetPreservesUrlCheckedChapterCacheAndUsesCanonicalKey() = runBlocking {
        val options = WebNovelScrapeOptions()
        val sourceUrl = "https://example.org/old/../book "
        val key = WebNovelEpubUtility.checkpointKey(sourceUrl, options)
        assertEquals(key, WebNovelEpubUtility.checkpointKey("https://example.org/book", options))
        val directory = java.io.File(context.filesDir, "webnovel-checkpoints/$key").apply { mkdirs() }
        val manifestFile = java.io.File(directory, WebNovelChapterManifestStore.FILE_NAME).apply { writeText("corrupt") }
        val cachedChapter = java.io.File(directory, "chapter-00000.xhtml").apply { writeText("saved page") }
        val cachedUrl = java.io.File(directory, "chapter-00000.url").apply { writeText("https://example.org/chapter-1") }
        val failed = job("failed-reset").copy(
            sourceUrl = sourceUrl, optionsJson = WebNovelScrapeOptionsJson.encode(options), checkpointKey = key,
            status = WebNovelJobState.FAILED,
            completed = 4, total = 7,
            error = "${WebNovelChapterManifestCodec.INVALID_MANIFEST_ERROR_PREFIX} broken JSON",
        )
        database.bookDao().insertWebNovelJob(failed)
        val unrelated = job("unrelated-reset")
        database.bookDao().insertWebNovelJob(unrelated)
        try {
            val reset = requireNotNull(BookRepository(context, database.bookDao()).resetInvalidWebNovelChapterManifest(failed.id))
            assertEquals(WebNovelJobState.QUEUED, reset.status)
            assertEquals(0, reset.completed)
            assertEquals(0, reset.total)
            assertTrue(reset.autoRun)
            assertEquals(WebNovelJobState.QUEUED, database.bookDao().webNovelJobById(unrelated.id)?.status)
            assertFalse(manifestFile.exists())
            assertTrue(cachedChapter.isFile)
            assertTrue(cachedUrl.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun manifestResetRefusesWhileAnotherJobCouldUseSharedCheckpoint() = runBlocking {
        val options = WebNovelScrapeOptions()
        val sourceUrl = "https://example.org/book"
        val key = WebNovelEpubUtility.checkpointKey(sourceUrl, options)
        val directory = java.io.File(context.filesDir, "webnovel-checkpoints/$key").apply { mkdirs() }
        val manifestFile = java.io.File(directory, WebNovelChapterManifestStore.FILE_NAME).apply { writeText("corrupt") }
        database.bookDao().insertWebNovelJob(job("failed-reset").copy(
            sourceUrl = sourceUrl, optionsJson = WebNovelScrapeOptionsJson.encode(options), checkpointKey = key,
            status = WebNovelJobState.FAILED,
            error = "${WebNovelChapterManifestCodec.INVALID_MANIFEST_ERROR_PREFIX} broken JSON",
        ))
        database.bookDao().insertWebNovelJob(job("other-queued").copy(checkpointKey = key))
        try {
            var resetRefused = false
            try {
                BookRepository(context, database.bookDao()).resetInvalidWebNovelChapterManifest("failed-reset")
            } catch (_: IllegalStateException) {
                resetRefused = true
            }
            assertTrue(resetRefused)
            assertTrue(manifestFile.isFile)
            assertEquals(WebNovelJobState.FAILED, database.bookDao().webNovelJobById("failed-reset")?.status)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun versionFourDatabaseMigratesAndPreservesBooks() {
        val name = "v4-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE books (id TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, fileName TEXT NOT NULL, localPath TEXT NOT NULL, format TEXT NOT NULL, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER, progress REAL NOT NULL, status TEXT NOT NULL, chapterPage INTEGER NOT NULL DEFAULT 0, chapterPageCount INTEGER NOT NULL DEFAULT 0, libraryId TEXT NOT NULL DEFAULT 'default', PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE libraries (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("INSERT INTO libraries VALUES ('default', 'My Library', 0)")
        old.execSQL("INSERT INTO books VALUES ('existing', 'Existing', '', 'existing.epub', '/books/existing.epub', 'EPUB', 1, NULL, 0, 'IMPORTED', 0, 0, 'default')")
        old.version = 4
        old.close()

        val migrated = Room.databaseBuilder(context, BookDatabase::class.java, name)
            .addMigrations(BookDatabase.addWebNovelJobs, BookDatabase.addWebNovelJobTitle, BookDatabase.addDiscoverySources,
                BookDatabase.addBackupRecoveryAndAutomaticJobs, BookDatabase.removeGutenbergAndSeedNovelPing,
                BookDatabase.addPdfConversionJobs).build()
        try {
            runBlocking {
                assertEquals("Existing", migrated.bookDao().bookById("existing")?.title)
                assertNotNull(migrated.bookDao().findLibrary("default"))
                assertTrue(migrated.bookDao().observeWebNovelJobs().first().isEmpty())
                assertEquals(setOf(DiscoverySourceIds.STANDARD_EBOOKS, DiscoverySourceIds.NOVELPING),
                    migrated.bookDao().allDiscoverySources().map { it.id }.toSet())
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionFiveDatabaseAddsNullableJobTitleWithoutChangingJobState() = runBlocking {
        val name = "v5-title-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE books (id TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, fileName TEXT NOT NULL, localPath TEXT NOT NULL, format TEXT NOT NULL, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER, progress REAL NOT NULL, status TEXT NOT NULL, chapterPage INTEGER NOT NULL DEFAULT 0, chapterPageCount INTEGER NOT NULL DEFAULT 0, libraryId TEXT NOT NULL DEFAULT 'default', PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE libraries (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE web_novel_jobs (id TEXT NOT NULL, sourceUrl TEXT NOT NULL, optionsJson TEXT NOT NULL, libraryId TEXT NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, label TEXT NOT NULL, error TEXT, waitUntil INTEGER, runnerToken TEXT, checkpointKey TEXT NOT NULL, resultBookId TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("CREATE INDEX index_web_novel_jobs_createdAt ON web_novel_jobs (createdAt)")
        old.execSQL("INSERT INTO libraries VALUES ('default', 'My Library', 0)")
        old.execSQL("INSERT INTO books VALUES ('book-id', 'Saved title', '', 'saved.epub', '/books/saved.epub', 'EPUB', 1, NULL, 0, 'IMPORTED', 0, 0, 'default')")
        old.execSQL("INSERT INTO web_novel_jobs VALUES ('legacy-job', 'https://example.org/book', '{}', 'default', 'PAUSED', 2, 4, 'Paused', NULL, NULL, NULL, 'key', 'book-id', 1, 2)")
        old.version = 5
        old.close()

        val migrated = Room.databaseBuilder(context, BookDatabase::class.java, name)
            .addMigrations(BookDatabase.addWebNovelJobTitle, BookDatabase.addDiscoverySources,
                BookDatabase.addBackupRecoveryAndAutomaticJobs, BookDatabase.removeGutenbergAndSeedNovelPing,
                BookDatabase.addPdfConversionJobs).build()
        try {
            val legacy = requireNotNull(migrated.bookDao().webNovelJobById("legacy-job"))
            assertEquals(WebNovelJobState.PAUSED, legacy.status)
            assertEquals(2, legacy.completed)
            assertEquals("Saved title", legacy.bookTitle)
            assertTrue(migrated.bookDao().allDiscoverySources().any { it.id == DiscoverySourceIds.STANDARD_EBOOKS })
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSixDatabaseAddsSourcesWithoutChangingBooksOrJobs() = runBlocking {
        val name = "v6-discovery-sources-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE books (id TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, fileName TEXT NOT NULL, localPath TEXT NOT NULL, format TEXT NOT NULL, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER, progress REAL NOT NULL, status TEXT NOT NULL, chapterPage INTEGER NOT NULL DEFAULT 0, chapterPageCount INTEGER NOT NULL DEFAULT 0, libraryId TEXT NOT NULL DEFAULT 'default', PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE libraries (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE web_novel_jobs (id TEXT NOT NULL, sourceUrl TEXT NOT NULL, optionsJson TEXT NOT NULL, libraryId TEXT NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, label TEXT NOT NULL, error TEXT, waitUntil INTEGER, runnerToken TEXT, checkpointKey TEXT NOT NULL, resultBookId TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, bookTitle TEXT, PRIMARY KEY(id))")
        old.execSQL("CREATE INDEX index_web_novel_jobs_createdAt ON web_novel_jobs (createdAt)")
        old.execSQL("INSERT INTO libraries VALUES ('default', 'My Library', 0)")
        old.execSQL("INSERT INTO books VALUES ('saved-book', 'Saved', '', 'saved.epub', '/books/saved.epub', 'EPUB', 1, NULL, 0, 'IMPORTED', 0, 0, 'default')")
        old.execSQL("INSERT INTO web_novel_jobs VALUES ('saved-job', 'https://example.org/book', '{}', 'default', 'PAUSED', 3, 8, 'Paused', NULL, NULL, NULL, 'key', 'webnovel-result', 1, 2, 'Novel')")
        old.version = 6
        old.close()
        val migrated = Room.databaseBuilder(context, BookDatabase::class.java, name)
            .addMigrations(BookDatabase.addDiscoverySources, BookDatabase.addBackupRecoveryAndAutomaticJobs,
                BookDatabase.removeGutenbergAndSeedNovelPing, BookDatabase.addPdfConversionJobs).build()
        try {
            assertEquals("Saved", migrated.bookDao().bookById("saved-book")?.title)
            assertEquals(WebNovelJobState.PAUSED, migrated.bookDao().webNovelJobById("saved-job")?.status)
            assertEquals(setOf(DiscoverySourceIds.STANDARD_EBOOKS, DiscoverySourceIds.NOVELPING),
                migrated.bookDao().allDiscoverySources().map { it.id }.toSet())
            val novelPing = requireNotNull(migrated.bookDao().discoverySourceById(DiscoverySourceIds.NOVELPING))
            assertEquals(DiscoverySourceKind.HTML, novelPing.kind)
            assertEquals(DiscoverySourcePurpose.CONVERT_WEBSITE,
                DiscoverySourceConfigCodec.decode(novelPing.configJson).purpose)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSevenAddsRestoreMarkersAndAutomaticIntentWithoutRequeuingPausedOrCancelledJobs() = runBlocking {
        val name = "v7-recovery-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE books (id TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, fileName TEXT NOT NULL, localPath TEXT NOT NULL, format TEXT NOT NULL, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER, progress REAL NOT NULL, status TEXT NOT NULL, chapterPage INTEGER NOT NULL DEFAULT 0, chapterPageCount INTEGER NOT NULL DEFAULT 0, libraryId TEXT NOT NULL DEFAULT 'default', PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE libraries (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("CREATE TABLE web_novel_jobs (id TEXT NOT NULL, sourceUrl TEXT NOT NULL, optionsJson TEXT NOT NULL, libraryId TEXT NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, label TEXT NOT NULL, error TEXT, waitUntil INTEGER, runnerToken TEXT, checkpointKey TEXT NOT NULL, resultBookId TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, bookTitle TEXT, PRIMARY KEY(id))")
        old.execSQL("CREATE INDEX index_web_novel_jobs_createdAt ON web_novel_jobs (createdAt)")
        old.execSQL("CREATE TABLE discovery_sources (id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, baseUrl TEXT NOT NULL, enabled INTEGER NOT NULL, configJson TEXT NOT NULL, cacheKey TEXT, cacheJson TEXT, cacheUpdatedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        old.execSQL("INSERT INTO libraries VALUES ('default', 'My Library', 0)")
        old.execSQL("INSERT INTO discovery_sources VALUES ('standard-ebooks', 'Standard Ebooks', 'STANDARD_EBOOKS', 'https://standardebooks.org/ebooks', 0, '{\"kept\":true}', 'standard-cache', '{\"saved\":true}', 7, 0, 8)")
        old.execSQL("INSERT INTO discovery_sources VALUES ('project-gutenberg', 'Project Gutenberg', 'GUTENBERG', 'https://www.gutenberg.org/ebooks/search.opds/', 0, '{}', NULL, NULL, NULL, 1, 1)")
        old.execSQL("INSERT INTO discovery_sources VALUES ('custom-novelping', 'My NovelPing', 'HTML', 'https://novelping.com/sort/popular', 0, '{\"purpose\":\"CONVERT_WEBSITE\"}', 'novel-cache', '{\"books\":[]}', 9, 2, 10)")
        old.execSQL("INSERT INTO discovery_sources VALUES ('custom-catalog', 'My Catalog', 'OPDS', 'https://books.example/catalog', 1, '{}', NULL, NULL, NULL, 3, 4)")
        fun insertJob(id: String, status: String) {
            old.execSQL("INSERT INTO web_novel_jobs (id, sourceUrl, optionsJson, libraryId, status, completed, total, label, checkpointKey, resultBookId, createdAt, updatedAt) VALUES ('$id', 'https://example.org/$id', '{}', 'default', '$status', 0, 0, '$status', 'key-$id', 'result-$id', 1, 2)")
        }
        insertJob("queued", WebNovelJobState.QUEUED)
        insertJob("waiting", WebNovelJobState.WAITING_RATE_LIMIT)
        insertJob("paused", WebNovelJobState.PAUSED)
        insertJob("cancelled", WebNovelJobState.CANCELLED)
        old.version = 7
        old.close()

        val migrated = Room.databaseBuilder(context, BookDatabase::class.java, name)
            .addMigrations(BookDatabase.addBackupRecoveryAndAutomaticJobs, BookDatabase.removeGutenbergAndSeedNovelPing,
                BookDatabase.addPdfConversionJobs).build()
        try {
            val dao = migrated.bookDao()
            assertTrue(requireNotNull(dao.webNovelJobById("queued")).autoRun)
            assertTrue(requireNotNull(dao.webNovelJobById("waiting")).autoRun)
            assertFalse(requireNotNull(dao.webNovelJobById("paused")).autoRun)
            assertFalse(requireNotNull(dao.webNovelJobById("cancelled")).autoRun)
            assertNull(dao.webNovelJobById("waiting")?.runnerType)
            assertFalse(dao.hasRestoreTransaction("restore-marker"))
            val preservedSources = dao.allDiscoverySources().associateBy { it.id }
            assertEquals(setOf(DiscoverySourceIds.STANDARD_EBOOKS, "custom-novelping", "custom-catalog"), preservedSources.keys)
            assertFalse(requireNotNull(preservedSources[DiscoverySourceIds.STANDARD_EBOOKS]).enabled)
            assertEquals("{\"kept\":true}", preservedSources[DiscoverySourceIds.STANDARD_EBOOKS]?.configJson)
            assertEquals("novel-cache", preservedSources["custom-novelping"]?.cacheKey)
            assertFalse(requireNotNull(preservedSources["custom-novelping"]).enabled)
            dao.insertRestoreTransaction(RestoreTransactionEntity("restore-marker", 5L))
            assertTrue(dao.hasRestoreTransaction("restore-marker"))
            assertEquals(1, dao.deleteRestoreTransaction("restore-marker"))
            assertFalse(dao.hasRestoreTransaction("restore-marker"))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun customSourceCacheIsBoundedConditionalAndDeletionPersists() = runBlocking {
        val dao = database.bookDao()
        val repository = BookRepository(context, dao)
        val source = DiscoverySourceEntity("custom-one", "Example catalog", DiscoverySourceKind.OPDS,
            "https://example.org/catalog", configJson = "{}", createdAt = 10L, updatedAt = 10L)
        repository.saveDiscoverySource(source)
        assertTrue(repository.cacheDiscoverySourceResults(source, "key", "{\"books\":[]}"))
        val cached = requireNotNull(dao.discoverySourceById(source.id))
        assertEquals("key", cached.cacheKey)
        assertFalse(repository.cacheDiscoverySourceResults(source, "oversized-utf8",
            "€".repeat(BookRepository.MAX_DISCOVERY_CACHE_BYTES / 3 + 1)))
        repository.saveDiscoverySource(source.copy(baseUrl = "https://example.org/edited", updatedAt = 11L))
        assertFalse(repository.cacheDiscoverySourceResults(source, "stale-config", "{}"))
        val enabledSnapshot = requireNotNull(dao.discoverySourceById(source.id))
        assertTrue(repository.setDiscoverySourceEnabled(source.id, false))
        assertTrue(repository.setDiscoverySourceEnabled(source.id, true))
        assertFalse(repository.cacheDiscoverySourceResults(enabledSnapshot, "stale-after-toggle", "{}"))
        assertTrue(repository.deleteDiscoverySource(source.id))
        repository.ensureDefaultLibrary()
        assertNull(dao.discoverySourceById(source.id))
        assertEquals(setOf(DiscoverySourceIds.STANDARD_EBOOKS, DiscoverySourceIds.NOVELPING),
            dao.allDiscoverySources().map { it.id }.toSet())
    }

    private fun job(id: String) = WebNovelJobEntity(
        id = id, sourceUrl = "https://example.org/book", optionsJson = WebNovelScrapeOptionsJson.encode(WebNovelScrapeOptions()),
        libraryId = "default", status = WebNovelJobState.QUEUED, completed = 0, total = 0, label = "Queued",
        error = null, waitUntil = null, runnerToken = null, checkpointKey = "checkpoint", resultBookId = "book-$id",
        bookTitle = null, createdAt = 1L, updatedAt = 1L,
    )
}
