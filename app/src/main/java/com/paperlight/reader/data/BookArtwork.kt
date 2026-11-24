package com.paperlight.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

internal object BookArtwork {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun peek(bookId: String): Bitmap? = cache.get(bookId)

    fun load(book: BookEntity): Bitmap? {
        cache.get(book.id)?.let { return it }
        val image = runCatching {
            when (book.format) {
                "EPUB" -> epubCover(File(book.localPath))
                "PDF" -> renderPdfPage(book.localPath, 0f, 320).bitmap
                "CBZ", "CBR" -> renderComicPage(book, 0f, 480).bitmap
                else -> null
            }
        }.getOrNull()
        if (image != null) cache.put(book.id, image)
        return image
    }

    fun metadata(file: File): Pair<String, String>? = runCatching {
        ZipFile(file).use { zip ->
            var opf: String? = null
            readTags(zip, "META-INF/container.xml") {
                if (name == "rootfile" && opf == null) opf = getAttributeValue(null, "full-path")
            }
            var title = ""
            val authors = mutableListOf<String>()
            readTags(zip, opf ?: return@use null) {
                if (namespace?.contains("purl.org/dc/elements") == true) {
                    when (name) {
                        "title" -> if (title.isBlank()) title = nextText().trim()
                        "creator" -> nextText().trim().takeIf { it.isNotBlank() }?.let(authors::add)
                    }
                }
            }
            title to authors.joinToString(", ")
        }
    }.getOrNull()

    private fun epubCover(file: File): Bitmap? = ZipFile(file).use { zip ->
        var packagePath: String? = null
        readTags(zip, "META-INF/container.xml") {
            if (name == "rootfile" && packagePath == null) packagePath = getAttributeValue(null, "full-path")
        }
        val opf = packagePath ?: return null
        var coverId: String? = null
        var coverHref: String? = null
        val images = linkedMapOf<String, String>()
        readTags(zip, opf) {
            when (name) {
                "meta" -> if (getAttributeValue(null, "name") == "cover") coverId = getAttributeValue(null, "content")
                "item" -> {
                    val id = getAttributeValue(null, "id")
                    val href = getAttributeValue(null, "href")
                    val type = getAttributeValue(null, "media-type")
                    if (id != null && href != null && type?.startsWith("image/") == true) {
                        images[id] = href
                        if (getAttributeValue(null, "properties")?.split(Regex("\\s+"))?.contains("cover-image") == true)
                            coverHref = href
                    }
                }
            }
            Unit
        }
        val href = coverHref ?: images[coverId] ?: images.entries.firstOrNull {
            it.key.contains("cover", ignoreCase = true) || it.value.contains("cover", ignoreCase = true)
        }?.value ?: return null
        val path = java.net.URI(null, null, "/" + opf.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }, null)
            .resolve(href).normalize().rawPath ?: return null
        val entry = zip.getEntry(Uri.decode(path).trimStart('/')) ?: return null
        if (entry.size !in 1..(24L * 1024 * 1024)) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 640) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun readTags(zip: ZipFile, path: String, onTag: XmlPullParser.() -> Unit) {
        val entry = zip.getEntry(path) ?: return
        if (entry.size !in 1..(2L * 1024 * 1024)) return
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) parser.onTag()
            }
        }
    }
}
