package com.omni.sync.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

private const val TERMINAL_URL = "https://appassets.androidplatform.net/assets/terminal/terminal.html"
private const val TAG = "OmniXterm"

@Composable
fun XtermTerminalView(
    output: String,
    onInput: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { XtermRenderer() }
    renderer.onInput = onInput
    renderer.onResize = onResize

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).also { webView ->
                renderer.attach(webView)
                configureTerminalWebView(webView, renderer)
                Log.d(TAG, "Loading local terminal page: $TERMINAL_URL")
                webView.loadUrl(TERMINAL_URL)
            }
        },
        update = { renderer.render(output) }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureTerminalWebView(webView: WebView, renderer: XtermRenderer) {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(webView.context))
        .build()

    webView.setBackgroundColor(Color.BLACK)
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = false
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.blockNetworkLoads = false
    webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    webView.addJavascriptInterface(
        TerminalInputBridge(renderer::sendInput, renderer::resize, renderer::onTerminalReady),
        "OmniTerminalInput"
    )
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "JS ${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            return true
        }
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val response = assetLoader.shouldInterceptRequest(request.url)
            if (response == null) Log.w(TAG, "Unresolved WebView resource: ${request.url}")
            else Log.d(TAG, "Loaded local WebView resource: ${request.url}")
            return response
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val allowed = request.url.toString().startsWith("https://appassets.androidplatform.net/assets/terminal/")
            if (!allowed) Log.w(TAG, "Blocked WebView navigation: ${request.url}")
            return !allowed
        }

        override fun onPageFinished(view: WebView, url: String) {
            Log.d(TAG, "Terminal page finished: $url")
            renderer.onPageFinished(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            Log.e(TAG, "WebView error mainFrame=${request.isForMainFrame} url=${request.url} code=${error.errorCode} ${error.description}")
        }
    }
}

private class XtermRenderer {
    private var webView: WebView? = null
    private var isReady = false
    private var renderedOutput = ""
    private var pendingOutput = ""
    var onInput: (String) -> Unit = {}
    var onResize: (Int, Int) -> Unit = { _, _ -> }

    fun attach(webView: WebView) {
        this.webView = webView
        Log.d(TAG, "WebView attached id=${System.identityHashCode(webView)}")
    }

    fun onPageFinished(url: String) {
        if (!url.startsWith(TERMINAL_URL)) {
            Log.w(TAG, "Ignoring unexpected page completion: $url")
            return
        }
        Log.d(TAG, "xterm page loaded; awaiting renderer readiness")
    }

    fun onTerminalReady() {
        if (isReady) return
        isReady = true
        Log.d(TAG, "xterm renderer ready, pendingChars=${pendingOutput.length} pendingHash=${pendingOutput.hashCode()}")
        render(pendingOutput)
    }

    fun render(output: String) {
        pendingOutput = output
        Log.d(TAG, "Terminal render requested chars=${output.length} hash=${output.hashCode()} ready=$isReady renderedChars=${renderedOutput.length}")
        if (!isReady) {
            Log.d(TAG, "Deferring terminal render chars=${output.length}")
            return
        }

        if (!output.startsWith(renderedOutput)) {
            Log.w(TAG, "Terminal output no longer has rendered prefix; resetting xterm old=${renderedOutput.length} oldHash=${renderedOutput.hashCode()} new=${output.length} newHash=${output.hashCode()}")
            evaluate("window.omniTerminal.reset();")
            renderedOutput = ""
        }

        val textToRender = if (renderedOutput.isEmpty()) output else output.removePrefix(renderedOutput)
        if (textToRender.isNotEmpty()) {
            val encoded = Base64.encodeToString(textToRender.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            Log.d(TAG, "Writing terminal delta chars=${textToRender.length} bytes=${textToRender.toByteArray(Charsets.UTF_8).size} deltaHash=${textToRender.hashCode()}")
            evaluate("window.omniTerminal.writeBase64('$encoded');")
        }
        renderedOutput = output
    }

    fun sendInput(input: String) {
        Log.d(TAG, "Forwarding terminal input chars=${input.length}")
        onInput(input)
    }

    fun resize(columns: Int, rows: Int) {
        Log.d(TAG, "Forwarding terminal resize columns=$columns rows=$rows")
        onResize(columns, rows)
    }

    private fun evaluate(script: String) {
        val target = webView ?: return
        Log.d(TAG, "Evaluating terminal script length=${script.length} webViewId=${System.identityHashCode(target)}")
        target.post {
            target.evaluateJavascript(script) { result ->
                Log.d(TAG, "Terminal JavaScript completed result=$result")
            }
        }
    }
}

private class TerminalInputBridge(
    private val onInput: (String) -> Unit,
    private val onResize: (Int, Int) -> Unit,
    private val onReady: () -> Unit,
) {
    @JavascriptInterface
    fun ready() {
        Log.d(TAG, "JavaScript terminal renderer reported ready")
        onReady()
    }

    @JavascriptInterface
    fun sendBase64(base64: String) {
        try {
            val input = Base64.decode(base64, Base64.DEFAULT).toString(Charsets.UTF_8)
            Log.d(TAG, "JavaScript terminal input chars=${input.length} hash=${input.hashCode()}")
            onInput(input)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Terminal sent invalid input", error)
        }
    }

    @JavascriptInterface
    fun resize(columns: Int, rows: Int) {
        Log.d(TAG, "JavaScript terminal resize columns=$columns rows=$rows")
        onResize(columns, rows)
    }
}
