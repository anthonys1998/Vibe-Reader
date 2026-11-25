package com.paperlight.reader.data.pdf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfEpubCheckpointStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun committedPageAndImageSurviveStoreRecreation() {
        val directory = temporaryFolder.newFolder("checkpoints")
        val store = store(directory)
        val image = File(directory, "render.tmp").apply { writeText("page image bytes") }
        store.commitPage(
            PdfPageRecord(1, 612f, 792f, listOf(PdfTextLine("First page", 40f, 80f, 50f, 10f)), false, true),
            image,
        )

        val resumed = store(directory)
        val page = resumed.readPage(1)
        assertEquals("First page", page.lines.single().text)
        assertTrue(page.imageFallback)
        assertEquals("page image bytes", page.imageFile?.readText())
        assertFalse(resumed.hasPage(2))
    }

    @Test
    fun rejectsInputOrModeMismatchInsteadOfReusingOtherArtifacts() {
        val directory = temporaryFolder.newFolder("identity")
        store(directory).commitPage(PdfPageRecord(1, 612f, 792f, emptyList(), false, false))

        assertFails { store(directory, hash = "b".repeat(64)) }
        assertFails { PdfEpubCheckpointStore(directory, "a".repeat(64), "Images", 1) }
    }

    @Test
    fun detectsDamagedRasterArtifactOnResume() {
        val directory = temporaryFolder.newFolder("damaged")
        val store = store(directory)
        val image = File(directory, "render.tmp").apply { writeText("original image") }
        store.commitPage(PdfPageRecord(1, 612f, 792f, emptyList(), false, true), image)
        store.imageFile(1).writeText("damaged image")

        assertFails { store(directory).readPage(1) }
    }

    private fun store(directory: File, hash: String = "a".repeat(64)) =
        PdfEpubCheckpointStore(directory, hash, "Text", 2)

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected checkpoint validation to fail")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed identity and artifact validation.
        } catch (_: IllegalStateException) {
            // `error` is used for corrupt checkpoint data.
        }
    }
}
