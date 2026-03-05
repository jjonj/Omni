package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.sync.ui.components.ReaderSettingsOverlay
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
    val isSettingsVisible = remember { mutableStateOf(false) }
    val readerTheme by booksViewModel.readerTheme.collectAsState()

    val webInterface = remember(base64Data.value, bookPath) {
        object {
            @android.webkit.JavascriptInterface
            fun getEpubBase64(): String = base64Data.value ?: ""
            @android.webkit.JavascriptInterface
            fun saveLocation(cfi: String, scrollY: Int) { 
                booksViewModel.saveProgress(bookPath, "${cfi}|${scrollY}") 
            }
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

    // React to theme changes
    LaunchedEffect(readerTheme, hasLoaded.value) {
        if (hasLoaded.value) {
            val js = """
                window.applyTheme("${readerTheme.backgroundColor}", "${readerTheme.textColor}", ${readerTheme.fontSize});
            """.trimIndent()
            webViewRef.value?.evaluateJavascript(js, null)
        }
    }

    LaunchedEffect(base64Data.value, webViewRef.value, loadError.value) {
        val wv = webViewRef.value
        if (wv != null && !hasLoaded.value) {
            if (loadError.value != null) {
                wv.loadDataWithBaseURL(null, "<html><body style='background:#111;color:#eee;'>${loadError.value}</body></html>", "text/html", "utf-8", null)
                hasLoaded.value = true
            } else if (base64Data.value != null) {
                wv.loadDataWithBaseURL("file:///android_asset/", htmlBulletproof, "text/html", "utf-8", null)
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
                },
                actions = {
                    IconButton(onClick = { isSettingsVisible.value = !isSettingsVisible.value }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
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
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
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

            if (isSettingsVisible.value) {
                Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                    ReaderSettingsOverlay(
                        theme = readerTheme,
                        onThemeChange = { booksViewModel.updateTheme(it) },
                        onClose = { isSettingsVisible.value = false }
                    )
                }
            }
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

      <script src="jszip.min.js"></script>
      <script src="epub.min.js"></script>
      
      <script>
        (function() {
          let rendition = null;
          let book = null;

          window.nextPage = () => rendition && rendition.next();
          window.prevPage = () => rendition && rendition.prev();

          window.applyTheme = (bg, text, size) => {
              if (!rendition) return;
              document.body.style.background = bg;
              document.getElementById("viewer").style.background = bg;
              rendition.themes.default({
              "body": { 
                "background": bg + " !important", 
                "color": text + " !important", 
                "padding": "20px !important", 
                "font-size": size + "px !important"
              }
              });
              };

              let currentCfi = "";
              window.onscroll = () => {
              if (currentCfi) {
              window.AndroidApp.saveLocation(currentCfi, window.scrollY);
              }
              };

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

              // Initial theme
              // We'll call a helper that gets values from settings via Android interface
              // For now we'll just wait for the LaunchedEffect from Kotlin to trigger applyTheme
              // or we could add another interface method for sync theme fetch.

              rendition.on("relocated", (loc) => {
                  currentCfi = loc.start.cfi;
                  window.AndroidApp.saveLocation(currentCfi, window.scrollY);
              });

              const savedRaw = window.AndroidApp.getSavedLocation();
              if (savedRaw) { 
                  const parts = savedRaw.split('|');
                  const savedCfi = parts[0];
                  const savedScroll = parts.length > 1 ? parseInt(parts[1]) : 0;

                  await rendition.display(savedCfi); 
                  if (savedScroll > 0) {
                      setTimeout(() => window.scrollTo(0, savedScroll), 100);
                  }
              } else { 
                  await rendition.display(); 
              }

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
