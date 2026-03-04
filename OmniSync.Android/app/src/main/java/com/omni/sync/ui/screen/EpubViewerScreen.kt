package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

    val webInterface = remember(base64Data.value) {
        object {
            @android.webkit.JavascriptInterface
            fun getEpubBase64(): String = base64Data.value ?: ""
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
            booksViewModel.log("Loaded EPUB: ${bytes.size} bytes")
        } catch (e: Exception) {
            loadError.value = "Read error: ${e.message}"
        }
    }

    LaunchedEffect(base64Data.value, webViewRef.value, loadError.value) {
        val wv = webViewRef.value
        if (wv != null && !hasLoaded.value) {
            if (loadError.value != null) {
                wv.loadDataWithBaseURL(null, "<html><body>${loadError.value}</body></html>", "text/html", "utf-8", null)
                hasLoaded.value = true
            } else if (base64Data.value != null) {
                booksViewModel.log("WebView: Loading simple skeleton")
                wv.loadDataWithBaseURL("https://omni.local/", htmlSimple, "text/html", "utf-8", null)
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).border(4.dp, Color.Green)) {
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
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                if (consoleMessage != null) {
                                    booksViewModel.log("JS: ${consoleMessage.message()}")
                                }
                                return true
                            }
                        }
                    }
                }
            )
        }
    }
}

private val htmlSimple = """
    <!doctype html>
    <html>
    <head>
      <meta charset="utf-8" />
      <style>
        body { margin: 0; padding: 0; background: magenta; color: white; font-family: sans-serif; }
        .box { border: 5px solid black; padding: 10px; margin: 10px; }
        #status { background: yellow; color: black; min-height: 50px; }
        #viewer { background: white; color: black; min-height: 200px; border-color: red; }
        #controls { background: cyan; color: black; height: 60px; }
      </style>
    </head>
    <body>
      <div id="status" class="box">LOGS WILL APPEAR HERE</div>
      
      <div id="viewer" class="box">
        <h2>VIEWER BOX</h2>
        <div id="epub-content">WAITING FOR EPUB.JS...</div>
      </div>
      
      <div id="controls" class="box">
        <button onclick="window.nextPage()" style="padding: 10px;">NEXT PAGE</button>
      </div>

      <script src="https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js"></script>
      <script src="https://cdn.jsdelivr.net/npm/epubjs/dist/epub.min.js"></script>
      
      <script>
        (function() {
          const status = document.getElementById("status");
          const content = document.getElementById("epub-content");
          let rendition = null;

          const log = (msg) => {
              status.innerText += "\n> " + msg;
              console.log("EPUB JS: " + msg);
          };

          window.nextPage = () => rendition && rendition.next();

          async function init() {
              log("STARTING...");
              if (typeof ePub === 'undefined') {
                  log("ePub missing, retrying...");
                  setTimeout(init, 1000);
                  return;
              }

              try {
                  const b64 = window.AndroidApp.getEpubBase64();
                  log("B64 size: " + b64.length);
                  
                  const binary = atob(b64);
                  const bytes = new Uint8Array(binary.length);
                  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                  
                  log("Opening book...");
                  const book = ePub(bytes.buffer);
                  await book.opened;
                  log("Spine: " + book.spine.length);

                  rendition = book.renderTo("viewer", { 
                      width: "100%", height: "400px", flow: "scrolled" 
                  });
                  
                  rendition.themes.default({
                      "body": { "background": "white !important", "color": "black !important" }
                  });

                  log("Displaying...");
                  await rendition.display();
                  log("DONE - Should be visible now!");

              } catch (e) {
                  log("ERROR: " + e);
              }
          }

          setTimeout(init, 500);
        })();
      </script>
    </body>
    </html>
""".trimIndent()
