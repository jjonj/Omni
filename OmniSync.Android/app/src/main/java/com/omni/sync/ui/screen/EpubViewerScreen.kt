package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.sync.viewmodel.BooksViewModel
import java.io.File

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
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val base64Data = remember { mutableStateOf<String?>(null) }
    val loadError = remember { mutableStateOf<String?>(null) }
    val hasLoaded = remember { mutableStateOf(false) }

    val webInterface = remember(base64Data.value, bookPath) {
        object {
            @android.webkit.JavascriptInterface
            fun getEpubBase64(): String = base64Data.value ?: ""
            @android.webkit.JavascriptInterface
            fun saveLocation(cfi: String) { booksViewModel.saveProgress(bookPath, cfi) }
            @android.webkit.JavascriptInterface
            fun getSavedLocation(): String = booksViewModel.getCachedProgress(bookPath) ?: ""
            @android.webkit.JavascriptInterface
            fun log(msg: String) { booksViewModel.log("EPUB JS: $msg") }
        }
    }

    LaunchedEffect(bookPath) {
        hasLoaded.value = false
        base64Data.value = null
        loadError.value = null
        val localPath = booksViewModel.downloadManager.getLocalPath(bookPath)
        if (localPath == null) {
            loadError.value = "EPUB not downloaded yet."
            return@LaunchedEffect
        }
        try {
            val bytes = File(localPath).readBytes()
            base64Data.value = Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            loadError.value = "Read error: ${e.message}"
        }
    }

    LaunchedEffect(base64Data.value, webViewRef.value, loadError.value) {
        val wv = webViewRef.value
        if (wv != null && !hasLoaded.value) {
            if (loadError.value != null) {
                wv.loadDataWithBaseURL(null, "<html><body style='background:#111;color:#eee;'>${loadError.value}</body></html>", "text/html", "utf-8", null)
                hasLoaded.value = true
            } else if (base64Data.value != null) {
                wv.loadDataWithBaseURL("https://omni.local/", htmlBulletproof, "text/html", "utf-8", null)
                hasLoaded.value = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewRef.value = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        addJavascriptInterface(webInterface, "AndroidApp")
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                                if (cm != null) booksViewModel.log("JS: ${cm.message()}")
                                return true
                            }
                        }
                    }
                }
            )
        }
    }
}

private val htmlBulletproof = """
    <!doctype html>
    <html>
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
      <style>
        body { 
            margin: 0; padding: 0; 
            padding-bottom: 80px; /* Space for fixed controls */
            background: #111; color: #eee; 
            font-family: sans-serif; 
        }
        #viewer { 
            width: 100%; 
            min-height: 500px; /* Safe starting height */
            background: #111;
        }
        #controls { 
            position: fixed; bottom: 0; left: 0; right: 0; 
            height: 70px; background: #1a1a1a; 
            display: flex; align-items: center; justify-content: space-around; 
            border-top: 1px solid #333; z-index: 1000;
        }
        button { 
            padding: 12px 40px; font-weight: bold; 
            background: #333; color: #eee; 
            border: 1px solid #444; border-radius: 8px; 
            font-size: 16px;
        }
        button:active { background: #444; }
        #loading { padding: 50px; text-align: center; color: #666; }
      </style>
    </head>
    <body>
      <div id="viewer">
        <div id="loading">Loading book...</div>
      </div>
      
      <div id="controls">
        <button onclick="window.prevPage()">PREV</button>
        <button onclick="window.nextPage()">NEXT</button>
      </div>

      <script src="https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js"></script>
      <script src="https://cdn.jsdelivr.net/npm/epubjs/dist/epub.min.js"></script>
      
      <script>
        (function() {
          let rendition = null;
          let book = null;

          window.nextPage = () => rendition && rendition.next();
          window.prevPage = () => rendition && rendition.prev();

          async function init() {
              if (typeof ePub === 'undefined') {
                  setTimeout(init, 500);
                  return;
              }

              try {
                  const b64 = window.AndroidApp.getEpubBase64();
                  if (!b64) return;
                  
                  const binary = atob(b64);
                  const bytes = new Uint8Array(binary.length);
                  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                  
                  book = ePub(bytes.buffer);
                  await book.opened;

                  rendition = book.renderTo("viewer", { 
                      width: "100%", height: "auto", flow: "scrolled", manager: "default"
                  });
                  
                  rendition.themes.default({
                      "body": { 
                        "background": "#111 !important", 
                        "color": "#ccc !important", 
                        "padding": "20px !important", 
                        "font-size": "18px !important"
                      }
                  });

                  rendition.on("relocated", (loc) => {
                      window.AndroidApp.saveLocation(loc.start.cfi);
                  });

                  const saved = window.AndroidApp.getSavedLocation();
                  if (saved) { await rendition.display(saved); } 
                  else { await rendition.display(); }
                  
                  document.getElementById("loading").style.display = "none";

              } catch (e) {
                  window.AndroidApp.log("Error: " + e);
              }
          }

          setTimeout(init, 200);
        })();
      </script>
    </body>
    </html>
""".trimIndent()
