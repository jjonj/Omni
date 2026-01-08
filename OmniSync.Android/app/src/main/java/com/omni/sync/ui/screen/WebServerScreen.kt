package com.omni.sync.ui.screen

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import com.omni.sync.ui.screen.LogType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebServerScreen(
    mainViewModel: com.omni.sync.viewmodel.MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val url = mainViewModel.getWebServerUrl()
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            mainViewModel.addLog("WebView finished loading: $url", LogType.INFO)
                        }
                        
                        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            mainViewModel.addLog("WebView error: ${error?.description}", LogType.ERROR)
                        }
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            consoleMessage?.message()?.let { 
                                mainViewModel.addLog("WebView Console: $it", LogType.INFO)
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
                    }
                    
                    loadUrl(url)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
