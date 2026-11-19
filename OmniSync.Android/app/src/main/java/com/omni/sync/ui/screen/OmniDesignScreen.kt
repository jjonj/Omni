package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val LOCAL_EDITOR = "https://appassets.androidplatform.net/assets/omnidesign/index.html"
private const val DEFAULT_SYNC_ENDPOINT = "https://hub.rancorous.online/gateway/tools/omnidesign/"
private const val PERMANENT_EDITOR_URL = "https://hub.rancorous.online/omnidesign/"

/**
 * Hosts the actual shared OmniDesign editor from the APK, never the remote website.
 * Its IndexedDB contains native editable diagrams independently of network availability.
 * Only optional revisioned PC API calls cross the native bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OmniDesignScreen(onBack: () -> Unit, diagramId: String? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnidesign", android.content.Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("sync_endpoint", DEFAULT_SYNC_ENDPOINT) ?: DEFAULT_SYNC_ENDPOINT) }
    var showEndpoint by remember { mutableStateOf(false) }
    var editingEndpoint by remember { mutableStateOf(endpoint) }
    val bridge = remember {
        OmniDesignApiBridge(
            endpoint = { prefs.getString("sync_endpoint", DEFAULT_SYNC_ENDPOINT) ?: DEFAULT_SYNC_ENDPOINT }
        )
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false) // Pinch/pan are handled by the shared canvas.
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): android.webkit.WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            webChromeClient = WebChromeClient()
            bridge.attach(this)
            addJavascriptInterface(bridge, "OmniDesignNative")
            // URI navigation is performed by LaunchedEffect, including new deep links.
        }
    }
    var leavePending by remember { mutableStateOf(false) }
    var pendingLinkedNavigation by remember { mutableStateOf<String?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    bridge.onFlushed = {
        val destination = pendingLinkedNavigation
        if (destination != null) {
            pendingLinkedNavigation = null
            webView.loadUrl(destination)
        } else if (leavePending) {
            leavePending = false
            onBack()
        }
    }
    LaunchedEffect(diagramId) {
        val linked = diagramId?.takeIf {
            Regex("^dgm_[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(it)
        }
        val destination = LOCAL_EDITOR + (linked?.let { "?diagram=$it" } ?: "")
        if (webView.url.isNullOrBlank()) {
            webView.loadUrl(destination)
        } else if (webView.url != destination) {
            // A second VIEW intent may arrive while drawing; flush the existing
            // native graph to IndexedDB before replacing this WebView page.
            pendingLinkedNavigation = destination
            webView.evaluateJavascript("if (window.omniDesignBeforeExit) { window.omniDesignBeforeExit(); true } else false") { response ->
                if (response == "false" && pendingLinkedNavigation == destination) {
                    pendingLinkedNavigation = null
                    webView.loadUrl(destination)
                }
            }
        }
    }
    val requestExit: () -> Unit = {
        if (!leavePending) {
            leavePending = true
            webView.evaluateJavascript("if (window.omniDesignBeforeExit) { window.omniDesignBeforeExit(); true } else false") { result ->
                if (result == "false" && leavePending) { leavePending = false; onBack() }
            }
            handler.postDelayed({
                if (leavePending) { leavePending = false; onBack() }
            }, 2500)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            // A new screen reopens the same IndexedDB origin and cached document IDs.
            bridge.close()
            webView.removeJavascriptInterface("OmniDesignNative")
            webView.destroy()
        }
    }
    BackHandler { requestExit() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = requestExit) { Text("‹ Back") }
            TextButton(onClick = {
                editingEndpoint = endpoint
                showEndpoint = true
            }) { Text("Hub sync address") }
        }
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
    if (showEndpoint) {
        AlertDialog(
            onDismissRequest = { showEndpoint = false },
            title = { Text("OmniDesign sync endpoint") },
            text = {
                Column {
                    Text("The editor always runs offline from this phone. Configure a reachable, private OmniDesign service URL for optional sync.")
                    OutlinedTextField(
                        value = editingEndpoint,
                        onValueChange = { editingEndpoint = it },
                        label = { Text("Service / Gateway URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val value = editingEndpoint.trim().trimEnd('/') + "/"
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        prefs.edit().putString("sync_endpoint", value).apply()
                        endpoint = value
                        showEndpoint = false
                        webView.evaluateJavascript("window.dispatchEvent(new Event('online'))", null)
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEndpoint = false }) { Text("Cancel") } }
        )
    }
}

/** Simple JSON bridge: network work stays off the UI thread and never owns local documents. */
internal class OmniDesignApiBridge(private val endpoint: () -> String) {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var webView: WebView? = null
    var onFlushed: (() -> Unit)? = null
    @JavascriptInterface fun flushed() { webView?.post { onFlushed?.invoke() } }

    /** Copy the stable owner-login editor URL, never a changing LAN IP or appassets origin. */
    @JavascriptInterface fun copyLink(diagramId: String): String {
        if (!Regex("^dgm_[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(diagramId)) return ""
        val url = PERMANENT_EDITOR_URL + "?diagram=" + diagramId
        webView?.post {
            val manager = webView?.context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText("OmniDesign diagram", url))
        }
        return url
    }
    fun attach(view: WebView) { webView = view }
    fun close() { webView = null; worker.shutdownNow() }

    @JavascriptInterface
    fun request(id: String, method: String, route: String, body: String, revision: String, key: String) {
        if (id.length > 80) return
        worker.execute {
            val result = JSONObject()
            try {
                // No arbitrary native network bridge: OmniDesign REST paths only.
                require(Regex("^api/(health|v1/(diagrams|links/resolve)(?:[/?][a-zA-Z0-9_/?=&.%-]*)?)$").matches(route))
                require(method in listOf("GET", "POST", "PUT"))
                val base = endpoint().trimEnd('/') + "/"
                val url = URL(URL(base), route)
                require(url.protocol == "https" || url.protocol == "http")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 8000
                    readTimeout = 12000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    CookieManager.getInstance().getCookie(url.toString())?.let { setRequestProperty("Cookie", it) }
                    if (body.isNotEmpty()) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    if (revision.isNotEmpty()) setRequestProperty("If-Match", revision)
                    if (key.isNotEmpty()) setRequestProperty("Idempotency-Key", key)
                }
                try {
                    if (body.isNotEmpty()) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    result.put("ok", code in 200..299)
                    result.put("status", code)
                    result.put("body", payload)
                    result.put("etag", connection.getHeaderField("ETag") ?: "")
                    if (code in 300..399) result.put("error", "Hub requires login/redirect; local changes kept")
                } finally { connection.disconnect() }
            } catch (error: Exception) {
                result.put("ok", false)
                result.put("status", 0)
                result.put("error", error.message ?: "Hub unreachable")
            }
            webView?.post {
                webView?.evaluateJavascript(
                    "window.omniDesignNativeReply?.(${JSONObject.quote(id)},${JSONObject.quote(result.toString())})",
                    null
                )
            }
        }
    }
}
