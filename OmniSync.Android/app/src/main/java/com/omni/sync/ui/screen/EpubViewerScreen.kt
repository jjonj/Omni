package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.sync.viewmodel.BooksViewModel

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubViewerScreen(
    booksViewModel: BooksViewModel,
    bookPath: String,
    bookName: String,
    baseUrl: String,
    onBack: () -> Unit
) {
    val encodedPath = Uri.encode(bookPath)
    val streamPath = "/api/stream?path=$encodedPath"
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.stopLoading()
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            factory = { context ->
                WebView(context).apply {
                    webViewRef.value = this
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowUniversalAccessFromFileURLs = true
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                booksViewModel.log("EPUB JS: ${consoleMessage.message()}", LogType.ERROR)
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                }
            },
            update = { webView ->
                val html = """
                    <!doctype html>
                    <html>
                    <head>
                      <meta charset="utf-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1" />
                      <style>
                        html, body, #viewer { width: 100%; height: 100%; margin: 0; padding: 0; background: #111; color: #eee; }
                      </style>
                    </head>
                    <body>
                      <div id="viewer">Loading EPUB...</div>
                      <script src="https://cdn.jsdelivr.net/npm/epubjs/dist/epub.min.js"></script>
                      <script>
                        const book = ePub("$streamPath");
                        const rendition = book.renderTo("viewer", { width: "100%", height: "100%", flow: "paginated" });
                        rendition.display();
                      </script>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                booksViewModel.log("EPUB viewer loading stream path: $streamPath")
            }
        )
    }
}
