package com.paperlight.reader.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.discovery.DiscoveryCatalogClient
import com.paperlight.reader.discovery.DiscoverySourceConfig
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GroqApiKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("groqApiKey", Context.MODE_PRIVATE)

    fun hasKey(): Boolean = getKey() != null

    fun getKey(): String? {
        return runCatching {
            val encoded = preferences.getString(KEY_VALUE, null) ?: return@runCatching null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, packed, 0, GCM_IV_BYTES))
            String(cipher.doFinal(packed, GCM_IV_BYTES, packed.size - GCM_IV_BYTES), Charsets.UTF_8).takeIf(String::isNotBlank)
        }.getOrElse {
            preferences.edit().remove(KEY_VALUE).apply()
            null
        }
    }

    fun saveKey(key: String) {
        preferences.edit().putString(KEY_VALUE, prepareEncryptedValue(key)).apply()
    }

    fun hasStoredValue(): Boolean = preferences.contains(KEY_VALUE)

    /** Produces a device-local Keystore ciphertext without changing the stored key. */
    fun prepareEncryptedValue(key: String): String {
        val clean = key.trim()
        require(clean.length in 20..500) { "Enter a valid Groq API key." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun restoreEncryptedValue(encoded: String) {
        validateEncryptedValue(encoded)
        check(preferences.edit().putString(KEY_VALUE, encoded).commit()) { "Could not restore the Groq API key." }
    }

    fun validateEncryptedValue(encoded: String) {
        val decoded = decryptValue(encoded)
        require(decoded.length in 20..500) { "The Groq API key in this restore is invalid." }
    }

    fun clear() {
        preferences.edit().remove(KEY_VALUE).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun decryptValue(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, packed, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(packed, GCM_IV_BYTES, packed.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val KEY_ALIAS = "vibe-reader-groq-api-key"
        const val KEY_VALUE = "encrypted"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

data class GroqScrapeSuggestion(
    val chapterLinkSelector: String,
    val chapterContentSelector: String,
    val chapterTitleSelector: String,
    val removeSelector: String,
    val bookTitle: String,
)

class GroqAiClient(private val context: Context) {
    private val keyStore = GroqApiKeyStore(context)

    suspend fun suggestDiscoveryConfig(
        url: String,
        currentConfig: DiscoverySourceConfig,
        renderedContext: Context? = null,
    ): DiscoverySourceConfig = withContext(Dispatchers.IO) {
        val sample = DiscoveryCatalogClient.htmlSampleForAi(
            url, currentConfig.renderJavaScript, renderedContext, currentConfig)
        val prompt = """Configure CSS selectors for the repeated book cards in this public HTML catalog. Return ONLY a JSON object with string fields htmlBookSelector, htmlTitleSelector, htmlAuthorSelector, htmlDetailLinkSelector, htmlCoverSelector, and htmlEpubLinkSelector. htmlBookSelector, htmlTitleSelector, and htmlDetailLinkSelector are required. Suggest every optional field that is supported by the sample. If an optional field is absent, return an empty string; do not invent a selector. Use CSS selectors only, and make title/author/details/cover/download selectors relative to the book card. For covers, inspect picture/img/source markup and lazy image attributes such as src, data-src, data-lazy-src, data-original, srcset, and data-srcset; choose the book-cover image rather than a logo or avatar. Do not return JavaScript, URLs to fetch, or explanations.
HTML SAMPLE:
${sample.html}"""
        applyDiscoveryConfigSuggestion(parseJson(request(prompt)), sample.finalUrl, sample.html, currentConfig)
    }

    suspend fun suggestScrapeConfiguration(
        url: String,
        sampleChapterUrl: String = "",
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
    ): GroqScrapeSuggestion = withContext(Dispatchers.IO) {
        val samples = WebNovelEpubUtility(context).inspectForAi(url, sampleChapterUrl, options)
        val prompt = """You configure a user-requested web-novel EPUB scraper. Read the sanitized HTML samples and return ONLY a JSON object with string fields: chapterLinkSelector, chapterContentSelector, chapterTitleSelector, removeSelector, bookTitle. Use valid CSS selectors only. Select chapter links on the table-of-contents page, the main readable chapter content, its title, and removable clutter. Do not return JavaScript, URLs to fetch, or explanations. If uncertain, use conservative selectors. bookTitle should be a short publication title.
TOC HTML:
${samples.tocHtml}
CHAPTER HTML:
${samples.chapterHtml}"""
        val json = parseJson(request(prompt))
        val suggestion = GroqScrapeSuggestion(
            chapterLinkSelector = json.optString("chapterLinkSelector").trim().take(300),
            chapterContentSelector = json.optString("chapterContentSelector").trim().take(500),
            chapterTitleSelector = json.optString("chapterTitleSelector", "h1, h2").trim().take(300),
            removeSelector = json.optString("removeSelector", "script, style, nav, header, footer, aside").trim().take(500),
            bookTitle = json.optString("bookTitle").trim().take(160),
        )
        require(suggestion.chapterLinkSelector.isNotBlank() && suggestion.chapterContentSelector.isNotBlank()) {
            "Groq did not return usable selectors. Try a different URL or enter the selectors manually."
        }
        require(suggestion.chapterLinkSelector.length <= 300 && suggestion.chapterContentSelector.length <= 500 &&
            suggestion.chapterTitleSelector.length <= 300 && suggestion.removeSelector.length <= 500) {
            "Groq returned selectors that are too long. Try again or enter them manually."
        }
        val toc = Jsoup.parse(samples.tocHtml, url)
        val chapter = Jsoup.parse(samples.chapterHtml, url)
        val linkMatches = runCatching { toc.select(suggestion.chapterLinkSelector) }
            .getOrElse { throw IllegalArgumentException("The suggested chapter-link selector is invalid. Review it or enter it manually.") }
        require(linkMatches.any { it.normalName() == "a" || it.selectFirst("a[href]") != null }) {
            "The suggested chapter-link selector did not match the table of contents. Review the selectors or enter them manually."
        }
        val contentMatches = runCatching { chapter.select(suggestion.chapterContentSelector) }
            .getOrElse { throw IllegalArgumentException("The suggested content selector is invalid. Review it or enter it manually.") }
        require(contentMatches.isNotEmpty()) {
            "The suggested content selector did not match the sample chapter. Review the selectors or enter them manually."
        }
        if (suggestion.chapterTitleSelector.isNotBlank()) {
            runCatching { chapter.select(suggestion.chapterTitleSelector) }.getOrElse {
                throw IllegalArgumentException("The suggested title selector is invalid. Review it or enter it manually.")
            }
        }
        if (suggestion.removeSelector.isNotBlank()) {
            runCatching { chapter.select(suggestion.removeSelector) }.getOrElse {
                throw IllegalArgumentException("The suggested cleanup selector is invalid. Review it or enter it manually.")
            }
        }
        suggestion
    }

    suspend fun summarizeNotes(annotations: List<ReaderAnnotation>): String {
        val notes = annotations.asSequence().filter { it.note.isNotBlank() }.take(100)
            .mapIndexed { index, note -> "${index + 1}. ${note.note.take(1_500)}" }.toList()
        require(notes.isNotEmpty()) { "Add written notes before asking for a summary." }
        val input = notes.joinToString("\n").take(MAX_NOTES_CHARS)
        return request(
            "Summarize these reader-written notes into a concise, organized overview. Preserve uncertainty and do not add facts that are not in the notes. Do not reproduce book passages.\n\nReader notes:\n$input"
        ).trim().take(MAX_SUMMARY_CHARS).ifBlank { "Groq returned an empty summary. Try again." }
    }

    suspend fun checkConnection() {
        request("Reply with the single word OK.")
    }

    private val prefs = context.getSharedPreferences("groqModels", Context.MODE_PRIVATE)

    fun selectedModel(): String? = prefs.getString("model", null)

    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val data = call("models").getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it) }
            .filter { it.optBoolean("active", true) }
            .map { it.getString("id") }
            .filterNot { id -> listOf("whisper", "orpheus", "guard", "tts", "embed").any { id.contains(it, true) } }
            .distinct().sorted()
    }

    private suspend fun request(prompt: String): String = withContext(Dispatchers.IO) {
        val model = prefs.getString("model", null) ?: error("Choose a model in Groq AI settings.")
        val messages = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", messages)
        // The selector prompt asks for JSON. Some chat models do not support JSON mode.
        val response = call("chat/completions", body)
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .getString("content").trim().ifBlank { error("Groq returned an empty answer.") }
    }

    private fun call(path: String, body: JSONObject? = null): JSONObject {
        val key = keyStore.getKey() ?: error("Save a Groq API key first.")
        val connection = URL("https://api.groq.com/openai/v1/$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            if (body != null) {
                val limits = listOf("requests" to "Daily requests", "tokens" to "Tokens per minute").mapNotNull { (field, label) ->
                    val left = connection.getHeaderField("x-ratelimit-remaining-$field") ?: return@mapNotNull null
                    val total = connection.getHeaderField("x-ratelimit-limit-$field") ?: return@mapNotNull null
                    "$label: $left / $total remaining"
                }
                if (limits.isNotEmpty()) {
                    val stamp = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
                    prefs.edit().putString("limits:" + body.getString("model"), "Last response · $stamp\n" + limits.joinToString("\n")).apply()
                }
            }
            if (status !in 200..299) {
                val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault("")
                error("Groq HTTP $status. " + detail.replace(key, "[redacted]").take(450))
            }
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJson(raw: String): JSONObject {
        require(raw.length <= MAX_SELECTOR_RESPONSE_CHARS) { "Groq's selector response exceeds the size limit." }
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Groq returned an invalid selector configuration. Try again." }
        return JSONObject(cleaned.substring(start, end + 1))
    }

    private companion object {
        const val MAX_NOTES_CHARS = 24_000
        const val MAX_SUMMARY_CHARS = 10_000
        const val MAX_SELECTOR_RESPONSE_CHARS = 32_000
    }
}

internal fun applyDiscoveryConfigSuggestion(
    json: JSONObject,
    finalUrl: String,
    sampleHtml: String,
    currentConfig: DiscoverySourceConfig,
): DiscoverySourceConfig {
    fun selector(field: String): String {
        val value = if (json.has(field) && !json.isNull(field)) json.optString(field).trim() else ""
        require(value.length <= 1_000) { "The suggested $field selector exceeds the size limit." }
        return value
    }
    fun optionalSelector(field: String, fallback: String): String = selector(field).ifBlank { fallback }
    fun optionalCoverSelector(): String {
        val suggested = selector("htmlCoverSelector")
        if (suggested.isNotBlank()) return suggested
        val defaultSelector = DiscoverySourceConfig().htmlCoverSelector
        if (currentConfig.htmlCoverSelector.isNotBlank() && currentConfig.htmlCoverSelector != defaultSelector) {
            return currentConfig.htmlCoverSelector
        }
        return inferCoverSelector(sampleHtml, finalUrl, selector("htmlBookSelector"))
            .orEmpty()
    }
    val candidate = currentConfig.copy(
        htmlBookSelector = selector("htmlBookSelector"),
        htmlTitleSelector = selector("htmlTitleSelector"),
        htmlAuthorSelector = optionalSelector("htmlAuthorSelector", currentConfig.htmlAuthorSelector),
        htmlDetailLinkSelector = selector("htmlDetailLinkSelector"),
        htmlCoverSelector = optionalCoverSelector(),
        htmlEpubLinkSelector = optionalSelector("htmlEpubLinkSelector", currentConfig.htmlEpubLinkSelector),
    )
    DiscoveryCatalogClient.validateAiDiscoveryConfig(finalUrl, sampleHtml, candidate)
    return candidate
}

private fun inferCoverSelector(sampleHtml: String, finalUrl: String, bookSelector: String): String? {
    val document = Jsoup.parse(sampleHtml, finalUrl)
    val card = runCatching { document.selectFirst(bookSelector) }.getOrNull() ?: return null
    fun hasImage(element: Element): Boolean = DiscoveryCatalogClient.imageUrl(element, finalUrl) != null

    val semanticImages = runCatching {
        card.select("img[itemprop=image], img[class*=cover], img[id*=cover], .cover img, .book-cover img, .novel-cover img")
    }.getOrNull().orEmpty().filter(::hasImage)
    if (semanticImages.size == 1) return relativeSelector(card, semanticImages.single())
    if (semanticImages.size > 1) return null

    val usablePictures = card.select("picture").filter { picture ->
        picture.select("source, img").any(::hasImage)
    }
    if (usablePictures.size == 1) {
        val picture = usablePictures.single()
        val sources = picture.select("source").filter(::hasImage)
        if (sources.isNotEmpty()) return relativeSelector(card, sources.first())
        val images = picture.select("img").filter(::hasImage)
        if (images.size == 1) return relativeSelector(card, images.single())
        return null
    }
    if (usablePictures.size > 1) return null

    val standaloneImages = card.select("img").filter { image ->
        image.parents().none { it.normalName().equals("picture", true) } && hasImage(image)
    }
    return standaloneImages.singleOrNull()?.let { relativeSelector(card, it) }
}

private fun relativeSelector(card: Element, target: Element): String? {
    val path = mutableListOf<String>()
    var element: Element? = target
    while (true) {
        val current = element ?: break
        if (current === card) break
        val tag = current.normalName().takeIf(String::isNotBlank) ?: return null
        val classes = current.classNames().filter { CSS_IDENTIFIER.matches(it) }.take(2)
        var part = tag + classes.joinToString("") { ".$it" }
        val siblings = current.parent()?.children()?.filter { it.normalName() == tag }.orEmpty()
        if (siblings.size > 1) part += ":nth-of-type(${siblings.indexOf(current) + 1})"
        path += part
        element = current.parent()
    }
    if (element !== card || path.isEmpty()) return null
    return path.asReversed().joinToString(" > ")
}

private val CSS_IDENTIFIER = Regex("-?[_A-Za-z][-_A-Za-z0-9]*")
