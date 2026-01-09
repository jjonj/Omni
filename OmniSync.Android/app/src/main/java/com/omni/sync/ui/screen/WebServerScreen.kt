package com.omni.sync.ui.screen

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import com.omni.sync.ui.screen.LogType
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebServerScreen(
    mainViewModel: com.omni.sync.viewmodel.MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val url = mainViewModel.getWebServerUrl()
    var webView by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = url, 
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    ) 
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            mainViewModel.addLog("WebView finished: $url", LogType.INFO)
                        }
                        
                        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            mainViewModel.addLog("WebView error: ${error?.description}", LogType.ERROR)
                        }
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            consoleMessage?.message()?.let { 
                                mainViewModel.addLog("WebView JS: $it", LogType.INFO)
                            }
                            return true
                        }
                    }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowFileAccess = true
                        allowContentAccess = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                    }
                    
                    if (url.startsWith("http")) {
                        loadUrl(url)
                    } else {
                        loadData("<html><body><h1>Hello World</h1><p>Invalid URL: $url</p></body></html>", "text/html", "UTF-8")
                    }
                }
            },
            update = { 
                webView = it
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
