package com.example.envisiontools.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // Allow file:// pages to load https:// resources (needed for tile images)
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true

                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onLocationSelected(lat: Double, lon: Double) {
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
                    val html = ctx.assets.open("map_picker.html").bufferedReader().readText()
                    val coordScript = "<script>var INITIAL_LAT=$initialLat;var INITIAL_LON=$initialLon;</script>"
                    val htmlWithCoords = html.replace("<script>", "$coordScript<script>", ignoreCase = false)
                        .let { if (it == html) html.replace("</head>", "$coordScript</head>") else it }

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
