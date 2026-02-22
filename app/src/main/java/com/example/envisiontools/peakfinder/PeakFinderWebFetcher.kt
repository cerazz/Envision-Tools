package com.example.envisiontools.peakfinder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.envisiontools.ble.LandscapeLine
import com.example.envisiontools.ble.PoiEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches landscape and POI data from PeakFinder for a given lat/lon by:
 * 1. Loading the PeakFinder web UI in a hidden WebView
 * 2. Injecting JavaScript to automate the Stellarium export flow
 * 3. Intercepting the ZIP download URL
 * 4. Downloading the ZIP and parsing it via [parseStellariumZip]
 */
class PeakFinderWebFetcher(private val context: Context) {

    /**
     * Fetch landscape and POI data for the given coordinates.
     * Must be called from a coroutine; WebView operations run on the main thread,
     * ZIP download runs on the IO dispatcher.
     *
     * @return Pair of (landscape lines, POI entries); either component may be null if not found.
     * @throws Exception on network or parsing failure.
     */
    suspend fun fetch(lat: Double, lon: Double): Pair<List<LandscapeLine>?, List<PoiEntry>?> {
        val zipBytes = fetchZipBytes(lat, lon)
        return withContext(Dispatchers.Default) {
            parseStellariumZip(zipBytes)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchZipBytes(lat: Double, lon: Double): ByteArray =
        suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())

            mainHandler.post {
                val webView = WebView(context)
                val settings: WebSettings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var completed = false

                fun complete(result: Result<ByteArray>) {
                    if (completed) return
                    completed = true
                    mainHandler.post {
                        webView.stopLoading()
                        webView.destroy()
                    }
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) }
                    )
                }

                cont.invokeOnCancellation {
                    mainHandler.post {
                        if (!completed) {
                            completed = true
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // Wait for terrain to render, then inject the JS click sequence
                        mainHandler.postDelayed({
                            if (completed) return@postDelayed
                            injectStep(view, JS_HAMBURGER)
                            mainHandler.postDelayed({
                                if (completed) return@postDelayed
                                injectStep(view, JS_EXPORT_ACCORDION)
                                mainHandler.postDelayed({
                                    if (completed) return@postDelayed
                                    injectStep(view, JS_STELLARIUM_ITEM)
                                    mainHandler.postDelayed({
                                        if (completed) return@postDelayed
                                        injectStep(view, JS_SILHOUETTES_BTN)
                                        mainHandler.postDelayed({
                                            if (completed) return@postDelayed
                                            injectStep(view, JS_EXPORT_BTN)
                                        }, DELAY_EXPORT_BTN_MS)
                                    }, DELAY_SILHOUETTE_MS)
                                }, DELAY_STELLARIUM_MS)
                            }, DELAY_EXPORT_MS)
                        }, DELAY_PAGE_LOAD_MS)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        if (isPeakFinderZipUrl(url)) {
                            triggerZipDownload(url, cookieManager, ::complete)
                            return true
                        }
                        return false
                    }
                }

                webView.webChromeClient = WebChromeClient()

                webView.setDownloadListener(object : DownloadListener {
                    override fun onDownloadStart(
                        url: String,
                        userAgent: String,
                        contentDisposition: String,
                        mimetype: String,
                        contentLength: Long
                    ) {
                        triggerZipDownload(url, cookieManager, ::complete)
                    }
                })

                val pageUrl = "https://www.peakfinder.com/?lat=$lat&lng=$lon&cfg=s&lang=en"
                webView.loadUrl(pageUrl)
            }
        }

    // ---------------------------------------------------------------------------
    // JavaScript injection
    // ---------------------------------------------------------------------------

    private fun injectStep(webView: WebView, js: String) {
        webView.evaluateJavascript(js, null)
    }

    /**
     * Returns true if [url] looks like the Stellarium ZIP download served by PeakFinder.
     * Matches only URLs from the peakfinder.com domain that end in .zip or whose
     * content-disposition / path contain recognisable Stellarium export signals.
     */
    private fun isPeakFinderZipUrl(url: String): Boolean {
        val lower = url.lowercase()
        val isPeakFinderDomain = lower.contains("peakfinder.com")
        return isPeakFinderDomain && (
            lower.endsWith(".zip") ||
            lower.contains("stellarium") ||
            lower.contains("horizon")
        )
    }

    // ---------------------------------------------------------------------------
    // ZIP download (runs on IO thread via coroutine)
    // ---------------------------------------------------------------------------

    private fun triggerZipDownload(
        url: String,
        cookieManager: CookieManager,
        complete: (Result<ByteArray>) -> Unit
    ) {
        // Launch on a dedicated thread so this callback (fired on main thread) doesn't block UI
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                // Forward cookies so the server recognises our session
                val cookies = cookieManager.getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    complete(Result.failure(Exception("HTTP $responseCode downloading ZIP")))
                    return@Thread
                }
                val bytes = connection.inputStream.readBytes()
                connection.disconnect()
                complete(Result.success(bytes))
            } catch (e: Exception) {
                complete(Result.failure(e))
            }
        }.start()
    }

    // ---------------------------------------------------------------------------
    // JS snippets
    // ---------------------------------------------------------------------------

    companion object {
        // Delays between JS injection steps (milliseconds)
        private const val DELAY_PAGE_LOAD_MS  = 5_000L  // wait for terrain to render
        private const val DELAY_EXPORT_MS     = 1_000L  // wait for drawer animation
        private const val DELAY_STELLARIUM_MS = 1_000L  // wait for accordion to expand
        private const val DELAY_SILHOUETTE_MS = 1_000L  // wait for sub-menu to appear
        private const val DELAY_EXPORT_BTN_MS = 500L    // wait for format selection

        // Network timeouts (milliseconds)
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 30_000

        private val JS_HAMBURGER = """
            (function() {
                var btn = document.querySelector('button.v-app-bar__nav-icon');
                if (btn) btn.click();
            })();
        """.trimIndent()

        private val JS_EXPORT_ACCORDION = """
            (function() {
                var headers = Array.from(document.querySelectorAll('.v-list-group__header'));
                var exportHeader = headers.find(function(el) {
                    return el.innerText && el.innerText.trim() === 'Export';
                });
                if (exportHeader) exportHeader.click();
            })();
        """.trimIndent()

        private val JS_STELLARIUM_ITEM = """
            (function() {
                var items = Array.from(document.querySelectorAll('.v-list-item'));
                var item = items.find(function(el) {
                    return el.innerText && el.innerText.includes('Horizon for Stellarium');
                });
                if (item) item.click();
            })();
        """.trimIndent()

        private val JS_SILHOUETTES_BTN = """
            (function() {
                var btns = Array.from(document.querySelectorAll('button'));
                var btn = btns.find(function(el) {
                    return el.innerText && el.innerText.includes('Silhouettes');
                });
                if (btn) btn.click();
            })();
        """.trimIndent()

        private val JS_EXPORT_BTN = """
            (function() {
                var btns = Array.from(document.querySelectorAll('button.primary'));
                var btn = btns.find(function(el) {
                    return el.innerText && el.innerText.trim() === 'EXPORT';
                });
                if (btn) btn.click();
            })();
        """.trimIndent()
    }
}
