package com.paperlight.reader.webnovel

import android.content.Context
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume

internal class RenderedNovelPage(
    private val context: Context,
    private val maxSnapshotBytes: Int? = null,
) {
    init { require(maxSnapshotBytes == null || maxSnapshotBytes in 1..MAX_SNAPSHOT_CHARS) }
    suspend fun load(url: String, ready: (Document) -> Boolean): Document =
        withContext(Dispatchers.Main) {
            val browser = WebView(context.applicationContext)
            try {
                var failure: String? = null
                var sslFailure: String? = null
                var finished = false
                var mainFrameUrl = url
                browser.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    blockNetworkImage = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                browser.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme?.lowercase() !in setOf("http", "https")

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        mainFrameUrl = url
                        finished = false
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        finished = true
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && failure == null) failure = "The website returned HTTP ${response.statusCode}."
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && failure == null) {
                            failure = if (error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                                sslErrorDescription(null).also { sslFailure = sslFailure ?: it }
                            } else error.description.toString()
                        }
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        val description = sslErrorDescription(error)
                        sslFailure = sslFailure ?: description
                        if (error?.url == mainFrameUrl && failure == null) failure = description
                    }
                }
                browser.loadUrl(url)
                var lastTitle = ""
                suspend fun awaitContent(): Document {
                    while (true) {
                        failure?.let { message ->
                            val target = if (sslFailure == null) url else "the requested page"
                            val extra = sslFailure?.takeUnless { it == message }?.let { " HTTPS certificate detail: $it" }.orEmpty()
                            error("Could not load $target. $message$extra")
                        }
                        if (finished) {
                            val snapshot = browser.snapshot()
                            if (snapshot != null) {
                                if (snapshot.optBoolean("tooLarge")) error("Rendered page exceeds the size limit.")
                                val rendered = withContext(Dispatchers.Default) {
                                    val html = snapshot.getString("html")
                                    if (maxSnapshotBytes != null) require(html.toByteArray(Charsets.UTF_8).size <= maxSnapshotBytes) {
                                        "Rendered page exceeds the size limit."
                                    }
                                    val document = Jsoup.parse(html, snapshot.getString("url"))
                                    document to ready(document)
                                }
                                lastTitle = rendered.first.title().take(120)
                                if (rendered.second) return rendered.first
                            }
                        }
                        delay(350)
                    }
                }
                withTimeoutOrNull(25_000) { awaitContent() }
                    ?: run {
                        val titleSuffix = if (lastTitle.isBlank()) "" else " Page title: $lastTitle."
                        val sslSuffix = sslFailure?.let { " HTTPS certificate detail: $it" }.orEmpty()
                        error("No matching content appeared in the browser.$titleSuffix$sslSuffix")
                    }
            } finally {
                browser.stopLoading()
                browser.destroy()
            }
        }

    private suspend fun WebView.snapshot(): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            val script = "(function(){var html=document.documentElement&&document.documentElement.outerHTML;" +
                "if(!html)return null;if(html.length>" + (maxSnapshotBytes ?: MAX_SNAPSHOT_CHARS) +
                ")return {url:location.href,tooLarge:true};return {url:location.href,html:html};})()"
            evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result?.let { runCatching { JSONObject(it) }.getOrNull() })
                }
            }
        }

    private companion object {
        const val MAX_SNAPSHOT_CHARS = 8_000_000

        fun sslErrorDescription(error: SslError?): String {
            val reason = when (error?.primaryError) {
                SslError.SSL_DATE_INVALID -> "the certificate date is invalid"
                SslError.SSL_EXPIRED -> "the certificate has expired"
                SslError.SSL_IDMISMATCH -> "the certificate does not match the site hostname"
                SslError.SSL_NOTYETVALID -> "the certificate is not valid yet"
                SslError.SSL_UNTRUSTED -> "the certificate is not trusted"
                SslError.SSL_INVALID -> "the certificate is invalid"
                else -> "certificate verification failed"
            }
            return "the browser rejected the HTTPS certificate because $reason."
        }
    }
}
