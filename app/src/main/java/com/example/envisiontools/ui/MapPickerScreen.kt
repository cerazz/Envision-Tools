package com.example.envisiontools.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.envisiontools.viewmodel.EnvisionViewModel

private const val TAG = "MapPicker"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    viewModel: EnvisionViewModel,
    initialLat: Double = 43.2686,
    initialLon: Double = 5.3955,
    onBack: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pick Location") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        AndroidView(
            factory = { ctx ->
                Log.d(TAG, "WebView factory: initialLat=$initialLat initialLon=$initialLon")
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // Allow file:// pages to load https:// resources (needed for tile images)
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    Log.d(TAG, "WebView settings applied (JS enabled, domStorage, allowUniversalAccess)")

                    // Forward every JS console.log / console.error to Logcat
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            val level = when (msg.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                                else -> "LOG"
                            }
                            Log.d(TAG, "JS[$level] ${msg.sourceId()}:${msg.lineNumber()} – ${msg.message()}")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            Log.d(TAG, "onPageStarted: $url")
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "onPageFinished: $url")
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            Log.e(TAG, "onReceivedError: url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            Log.e(TAG, "onReceivedHttpError: url=${request?.url} status=${response?.statusCode}")
                        }
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            Log.e(TAG, "onReceivedSslError: ${error?.primaryError} url=${error?.url}")
                            // Do NOT call handler.proceed() in production — cancel to stay secure
                            handler?.cancel()
                        }
                    }

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onLocationSelected(lat: Double, lon: Double) {
                                Log.d(TAG, "onLocationSelected: lat=$lat lon=$lon")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    viewModel.setPickedLocation(lat, lon)
                                    onBack()
                                }
                            }
                        },
                        "Android"
                    )

                    // Read the HTML from assets, inject initial coordinates as JS globals,
                    // then load via loadDataWithBaseURL with an https:// origin so that
                    // the Leaflet CDN and OSM tile requests are not blocked.
                    val html = try {
                        ctx.assets.open("map_picker.html").bufferedReader().readText().also {
                            Log.d(TAG, "Loaded map_picker.html from assets (${it.length} chars)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read map_picker.html from assets", e)
                        "<html><body>Asset load error: ${e.message}</body></html>"
                    }
                    val coordScript = "<script>var INITIAL_LAT=$initialLat;var INITIAL_LON=$initialLon;</script>"
                    val htmlWithCoords = html.replace("<script>", "$coordScript<script>", ignoreCase = false)
                        .let { if (it == html) html.replace("</head>", "$coordScript</head>") else it }
                    Log.d(TAG, "Coord injection check – html changed: ${html != htmlWithCoords}")

                    Log.d(TAG, "Calling loadDataWithBaseURL with base https://envisiontools.app/")
                    loadDataWithBaseURL(
                        "https://envisiontools.app/",
                        htmlWithCoords,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
