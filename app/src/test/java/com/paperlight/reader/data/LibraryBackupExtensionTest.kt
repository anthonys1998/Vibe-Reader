package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.paperlight.reader.webnovel.WebNovelChapterLink
import com.paperlight.reader.webnovel.WebNovelChapterManifest
import com.paperlight.reader.webnovel.WebNovelChapterManifestCodec
import com.paperlight.reader.webnovel.WebNovelChapterManifestStore
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryBackupExtensionTest {
    private lateinit var context: Context
    private lateinit var sourceDb: BookDatabase
    private lateinit var targetDb: BookDatabase
    private lateinit var sourceBackup: LibraryBackup
    private lateinit var targetBackup: LibraryBackup

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000_000, 500_000_000, 500_000_000)
        sourceDb = newDatabase()
        targetDb = newDatabase()
        sourceBackup = LibraryBackup(context, sourceDb.bookDao())
        targetBackup = LibraryBackup(context, targetDb.bookDao())
        File(context.filesDir, "books").deleteRecursively()
        File(context.filesDir, "webnovel-checkpoints").deleteRecursively()
        File(context.filesDir, "backup-restore-pending").deleteRecursively()
        File(context.filesDir, "backup-restore-journal.json").delete()
        File(context.filesDir, "backup-restore-journal.json.tmp").delete()
        File(context.filesDir, "reading-font").delete()
        context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    @Test fun exportRestoreRoundTripsCustomSourceJobCheckpointAndMergesCooldowns() = runBlocking {
        val (archive, job) = createSourceArchive()
        val key = job.checkpointKey
        val sourceDao = sourceDb.bookDao()
        val savedFiles = ZipFile(archive).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith("checkpoints/") }.toList()
        }
        assertEquals(listOf(
            "checkpoints/$key/chapter-00000.url",
            "checkpoints/$key/chapter-00000.xhtml",
            "checkpoints/$key/chapter-manifest.json",
        ), savedFiles)
        assertTrue(savedFiles.size <= WebNovelBackupCodec.MAX_ARCHIVE_ENTRIES)

        // The backup deadline is shorter than the current device deadline. Replacing settings may
        // update preferences, but must not shorten an already longer cooldown.
        val localLongerDeadline = System.currentTimeMillis() + 60 * 60 * 1_000L
        context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE).edit()
            .putLong("novel.example", localLongerDeadline).commit()
        File(context.filesDir, "webnovel-checkpoints").deleteRecursively()

        assertEquals(0, targetBackup.restore(Uri.fromFile(archive), replaceExistingSettings = true))

        val restoredSource = requireNotNull(targetDb.bookDao().discoverySourceById("custom-catalog"))
        assertEquals("https://catalog.example.org/opds", restoredSource.baseUrl)
        assertEquals("{\"q\":[]}", restoredSource.cacheJson)
        val restoredBuiltin = requireNotNull(targetDb.bookDao().discoverySourceById(DiscoverySourceIds.STANDARD_EBOOKS))
        assertFalse(restoredBuiltin.enabled)
        assertEquals("https://standardebooks.org/ebooks", restoredBuiltin.baseUrl)
        assertFalse(requireNotNull(targetDb.bookDao().discoverySourceById(DiscoverySourceIds.NOVELPING)).enabled)
        val restoredJob = requireNotNull(targetDb.bookDao().webNovelJobById(job.id))
        assertEquals(WebNovelJobState.PAUSED, restoredJob.status)
        assertEquals(1, restoredJob.completed)
        assertEquals(job.waitUntil, restoredJob.waitUntil)
        assertNull(restoredJob.runnerToken)
        assertNull(restoredJob.runnerType)
        assertFalse(restoredJob.autoRun)
        val checkpoint = File(context.filesDir, "webnovel-checkpoints/$key/chapter-00000.xhtml")
        assertArrayEquals("<article>chapter one</article>".toByteArray(), checkpoint.readBytes())
        assertEquals(localLongerDeadline, context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE)
            .getLong("novel.example", 0L))
        assertFalse(targetDb.bookDao().hasRestoreTransaction("unused"))
    }

    @Test fun legacyV2BackupWithoutExtensionsStillRestores() = runBlocking {
        val manifest = JSONObject().put("format", "paperlight-backup").put("version", 2)
            .put("preferences", JSONObject()).put("libraries", JSONArray()).put("books", JSONArray())
        val archive = createArchive(manifest, emptyMap())

        assertEquals(0, targetBackup.restore(Uri.fromFile(archive)))

        assertNotNull(targetDb.bookDao().findLibrary("default"))
        assertTrue(targetDb.bookDao().allWebNovelJobs().isEmpty())
        assertFalse(targetBackup.hasPendingRestore())
    }

    @Test fun corruptCheckpointPathsAndHashesFailBeforePublishingRowsOrFiles() = runBlocking {
        createSourceArchive()
        val original = File(context.cacheDir, "extension-source.zip")
        sourceBackup.export(Uri.fromFile(original))
        File(context.filesDir, "webnovel-checkpoints").deleteRecursively()
        val entries = readArchiveEntries(original)
        val originalManifest = JSONObject(String(requireNotNull(entries["manifest.json"]), Charsets.UTF_8))
        val checkpoint = originalManifest.getJSONObject("extensions").getJSONObject("webNovel")
            .getJSONArray("checkpoints").getJSONObject(0)

        val badPathManifest = JSONObject(originalManifest.toString())
        badPathManifest.getJSONObject("extensions").getJSONObject("webNovel")
            .getJSONArray("checkpoints").getJSONObject(0).put("path", "checkpoints/../escape")
        val badPath = createArchive(badPathManifest, entries.filterKeys { it != "manifest.json" })
        assertTrue(runCatching { targetBackup.restore(Uri.fromFile(badPath)) }.isFailure)

        val badHashManifest = JSONObject(originalManifest.toString())
        badHashManifest.getJSONObject("extensions").getJSONObject("webNovel")
            .getJSONArray("checkpoints").getJSONObject(0).put("sha256", "0".repeat(64))
        val badHash = createArchive(badHashManifest, entries.filterKeys { it != "manifest.json" })
        assertTrue(runCatching { targetBackup.restore(Uri.fromFile(badHash)) }.isFailure)

        assertNull(targetDb.bookDao().discoverySourceById("custom-catalog"))
        assertTrue(targetDb.bookDao().allWebNovelJobs().isEmpty())
        assertFalse(File(context.filesDir, "webnovel-checkpoints").exists())
        assertFalse(targetBackup.hasPendingRestore())
        assertNotNull(checkpoint)
    }

    @Test fun checkpointCollisionDoesNotOverwriteAndRestoresJobAsResettableFailure() = runBlocking {
        val (archive, job) = createSourceArchive()
        val key = job.checkpointKey
        File(context.filesDir, "webnovel-checkpoints/$key").apply { mkdirs() }
        val localManifest = File(context.filesDir, "webnovel-checkpoints/$key/chapter-manifest.json")
        localManifest.writeText("preserve the existing checkpoint")

        assertEquals(0, targetBackup.restore(Uri.fromFile(archive)))

        assertEquals("preserve the existing checkpoint", localManifest.readText())
        val restoredJob = requireNotNull(targetDb.bookDao().webNovelJobById(job.id))
        assertEquals(WebNovelJobState.FAILED, restoredJob.status)
        assertTrue(restoredJob.error.orEmpty().startsWith(WebNovelChapterManifestCodec.INVALID_MANIFEST_ERROR_PREFIX))
        assertFalse(restoredJob.autoRun)
    }

    @Test fun v3RecoveryUsesDatabaseMarkerForSourceJobOnlyCommitAndRollsBackOwnedCheckpoint() = runBlocking {
        val dao = targetDb.bookDao()
        val transactionId = UUID.randomUUID().toString()
        val committedJob = job("committed-job", WebNovelJobState.PAUSED, completed = 0)
        val committedSource = source()
        dao.restoreExtendedSnapshot(emptyList(), emptyList(), listOf(committedJob), listOf(committedSource),
            emptyMap(), false, transactionId, System.currentTimeMillis())
        writeV3Journal(transactionId)

        targetBackup.recoverInterruptedRestore()

        assertNotNull(dao.webNovelJobById(committedJob.id))
        assertNotNull(dao.discoverySourceById(committedSource.id))
        assertFalse(dao.hasRestoreTransaction(transactionId))
        assertFalse(targetBackup.hasPendingRestore())

        val rollbackKey = "a".repeat(64)
        val rollbackPath = File(context.filesDir, "webnovel-checkpoints/$rollbackKey/chapter-00000.xhtml")
        rollbackPath.parentFile?.mkdirs()
        val bytes = "transaction-owned".toByteArray()
        rollbackPath.writeBytes(bytes)
        val rollbackId = UUID.randomUUID().toString()
        writeV3Journal(rollbackId, owned = JSONArray().put(JSONObject()
            .put("key", rollbackKey).put("name", "chapter-00000.xhtml")
            .put("path", "checkpoints/$rollbackKey/chapter-00000.xhtml").put("size", bytes.size.toLong())
            .put("sha256", sha256(bytes))))

        targetBackup.recoverInterruptedRestore()

        assertFalse(rollbackPath.exists())
        assertFalse(targetBackup.hasPendingRestore())
    }

    @Test fun v3RecoveryKeepsStagedExtensionUntilJournalFinalizationCanRetry() = runBlocking {
        val dao = targetDb.bookDao()
        val transactionId = UUID.randomUUID().toString()
        val committedJob = job("retry-job", WebNovelJobState.PAUSED, completed = 0)
        dao.restoreExtendedSnapshot(emptyList(), emptyList(), listOf(committedJob), emptyList(), emptyMap(),
            false, transactionId, System.currentTimeMillis())
        val transactionDirectory = File(context.filesDir, "backup-restore-pending/$transactionId").apply { mkdirs() }
        val stagedExtension = File(transactionDirectory, "webnovel-extension.json").apply { writeText("staged") }
        val invalidSettings = JSONObject().put("reader", JSONObject().put("bad-setting",
            JSONObject().put("type", "unsupported").put("value", 1)))
        writeV3Journal(transactionId, settings = invalidSettings)

        assertTrue(runCatching { targetBackup.recoverInterruptedRestore() }.isFailure)
        assertTrue(targetBackup.hasPendingRestore())
        assertTrue(stagedExtension.isFile)

        val journalFile = File(context.filesDir, "backup-restore-journal.json")
        journalFile.writeText(JSONObject(journalFile.readText()).put("settings", JSONObject()).toString())
        targetBackup.recoverInterruptedRestore()

        assertFalse(targetBackup.hasPendingRestore())
        assertFalse(stagedExtension.exists())
        assertFalse(dao.hasRestoreTransaction(transactionId))
        assertNotNull(dao.webNovelJobById(committedJob.id))
    }

    private suspend fun createSourceArchive(): Pair<File, WebNovelJobEntity> {
        val dao = sourceDb.bookDao()
        val now = System.currentTimeMillis()
        val source = source()
        dao.insertDiscoverySource(source)
        dao.setDiscoverySourceEnabled(DiscoverySourceIds.STANDARD_EBOOKS, false, now)
        dao.setDiscoverySourceEnabled(DiscoverySourceIds.NOVELPING, false, now)
        val options = WebNovelScrapeOptions()
        val sourceUrl = "https://novel.example/story"
        val key = WebNovelEpubUtility.checkpointKey(sourceUrl, options)
        val chapterUrl = "https://novel.example/story/chapter-1"
        val chapterManifest = WebNovelChapterManifest(sourceUrl, sourceUrl, key, "A story",
            listOf(WebNovelChapterLink(chapterUrl, "Chapter 1"), WebNovelChapterLink("https://novel.example/story/chapter-2", "Chapter 2")), null)
        val directory = File(context.filesDir, "webnovel-checkpoints/$key").apply { mkdirs() }
        File(directory, WebNovelChapterManifestStore.FILE_NAME)
            .writeText(WebNovelChapterManifestCodec.encode(chapterManifest))
        // Create files in reverse order so the archive descriptor/file ordering must be explicit.
        File(directory, "chapter-00000.xhtml").writeText("<article>chapter one</article>")
        File(directory, "chapter-00000.url").writeText(chapterUrl)
        val job = job("job-extension", WebNovelJobState.WAITING_RATE_LIMIT, completed = 1, waitUntil = now + 30_000L,
            sourceUrl = sourceUrl, optionsJson = WebNovelScrapeOptionsJson.encode(options), checkpointKey = key)
        dao.insertWebNovelJob(job)
        context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE).edit()
            .putLong("novel.example", now + 5_000L).commit()
        val archive = File(context.cacheDir, "extension-${UUID.randomUUID()}.zip")
        sourceBackup.export(Uri.fromFile(archive))
        return archive to job
    }

    private fun source() = DiscoverySourceEntity("custom-catalog", "Custom catalog", DiscoverySourceKind.OPDS,
        "https://catalog.example.org/opds", enabled = true, configJson = "{\"searchUrlTemplate\":\"https://catalog.example.org/?q={searchTerms}\"}",
        cacheKey = "query-key", cacheJson = "{\"q\":[]}", cacheUpdatedAt = 5L, createdAt = 1L, updatedAt = 2L)

    private fun job(
        id: String,
        status: String,
        completed: Int,
        waitUntil: Long? = null,
        sourceUrl: String = "https://novel.example/story",
        optionsJson: String = WebNovelScrapeOptionsJson.encode(WebNovelScrapeOptions()),
        checkpointKey: String = WebNovelEpubUtility.checkpointKey(sourceUrl, WebNovelScrapeOptionsJson.decode(optionsJson)),
    ) = WebNovelJobEntity(
        id = id, sourceUrl = sourceUrl, optionsJson = optionsJson, libraryId = "default", status = status,
        completed = completed, total = if (completed == 0) 0 else 2, label = "Waiting", error = null,
        waitUntil = waitUntil, runnerToken = null, checkpointKey = checkpointKey, resultBookId = "result-$id",
        bookTitle = "A story", createdAt = 1L, updatedAt = 2L, runnerType = "SERVICE", autoRun = true,
    )

    private fun writeV3Journal(id: String, owned: JSONArray = JSONArray(), settings: JSONObject = JSONObject()) {
        File(context.filesDir, "backup-restore-journal.json").writeText(JSONObject()
            .put("journalVersion", 3).put("transactionId", id).put("committed", false)
            .put("bookIds", JSONArray()).put("ownedCheckpointFiles", owned)
            .put("skippedCheckpointKeys", JSONArray()).put("webNovelJobIds", JSONArray())
            .put("webNovelCooldowns", JSONArray()).put("webNovelExtensionSize", JSONObject.NULL)
            .put("webNovelExtensionSha256", JSONObject.NULL).put("libraries", JSONArray())
            .put("settings", settings).put("replaceSettings", false).put("states", JSONArray())
            .put("groqKeyEncrypted", JSONObject.NULL).put("fontIncluded", false).put("applyFont", false)
            .toString())
    }

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = BookDatabase.seedDiscoverySources(db)
        }).allowMainThreadQueries().build()

    private fun readArchiveEntries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        val result = linkedMapOf<String, ByteArray>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            result[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
        }
        result
    }

    private fun createArchive(manifest: JSONObject, entries: Map<String, ByteArray>): File {
        val file = File(context.cacheDir, "extension-test-${UUID.randomUUID()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
