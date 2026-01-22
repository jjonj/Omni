package com.omni.sync.ui.screen

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import com.omni.sync.ui.screen.LogType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.sync.ui.components.ActionKeyButton
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.PointerInputChange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebServerScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel = viewModel()
) {
    val predefinedUrls = listOf(
        "https://www.google.com" to "Google",
        "http://10.0.0.37:5000" to "Hub API (5000)",
        "http://10.0.0.37:3333" to "Hub Web (3333)"
    )

    var currentUrl by remember { mutableStateOf(predefinedUrls[0].first) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isMoreMode by remember { mutableStateOf(false) }
    var showUrlPicker by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = isMoreMode) {
        isMoreMode = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (!isMoreMode) showUrlPicker = true }) {
                            Text(
                                text = if (isMoreMode) "Server Controls" else predefinedUrls.find { it.first == currentUrl }?.second ?: currentUrl, 
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            ) 
                            if (!isMoreMode) {
                                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                            }
                        }
                        
                        DropdownMenu(expanded = showUrlPicker, onDismissRequest = { showUrlPicker = false }) {
                            predefinedUrls.forEach { (targetUrl, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        currentUrl = targetUrl
                                        webView?.loadUrl(targetUrl)
                                        showUrlPicker = false
                                    }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (isMoreMode) {
                        IconButton(onClick = { isMoreMode = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to Web")
                        }
                    }
                },
                actions = {
                    if (!isMoreMode) {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                        IconButton(onClick = { isMoreMode = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isMoreMode) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Mini Trackpad
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    signalRClient.sendMouseMove(dragAmount.x, dragAmount.y)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TouchApp, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            Text("Mini Trackpad", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons Panel (replicated from RemoteControl)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionKeyButton(text = "Esc", modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEYPRESS", 0x1B.toUShort()) }
                        ActionKeyButton(icon = Icons.Default.KeyboardArrowUp, modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEYPRESS", 0x26.toUShort()) }
                        ActionKeyButton(text = "Enter", icon = Icons.Default.KeyboardReturn, modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEYPRESS", 0x0D.toUShort()) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionKeyButton(icon = Icons.Default.KeyboardArrowLeft, modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEYPRESS", 0x25.toUShort()) }
                        ActionKeyButton(icon = Icons.Default.KeyboardArrowDown, modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEYPRESS", 0x28.toUShort()) }
                        ActionKeyButton(icon = Icons.Default.KeyboardArrowRight, modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEYPRESS", 0x27.toUShort()) }
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Button(
                        onClick = { isMoreMode = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Back to WebView")
                    }
                }
            } else {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            setBackgroundColor(0) // Transparent to debug black screen
                            
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    mainViewModel.addLog("WebView starting: $url", LogType.INFO)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    mainViewModel.addLog("WebView finished: $url", LogType.INFO)
                                }
                                
                                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    mainViewModel.addLog("WebView error: ${error?.description} (Code: ${error?.errorCode})", LogType.ERROR)
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
                            
                            if (currentUrl.startsWith("http")) {
                                mainViewModel.addLog("WebView loading URL: $currentUrl", LogType.INFO)
                                loadUrl(currentUrl)
                            } else {
                                mainViewModel.addLog("WebView INVALID URL: $currentUrl", LogType.ERROR)
                                loadData("<html><body><h1>Hello World</h1><p>Invalid URL: $currentUrl</p></body></html>", "text/html", "UTF-8")
                            }
                        }
                    },
                    update = { 
                        // webView = it // Handled in factory
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
