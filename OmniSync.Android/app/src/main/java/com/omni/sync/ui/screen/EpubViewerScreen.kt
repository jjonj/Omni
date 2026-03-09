package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val focusManager = LocalFocusManager.current

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val base64Data = remember { mutableStateOf<String?>(null) }
    val loadError = remember { mutableStateOf<String?>(null) }
    val hasLoaded = remember { mutableStateOf(false) }
    val isSettingsVisible = remember { mutableStateOf(false) }
    val readerTheme by booksViewModel.readerTheme.collectAsState()
    
    // Non-persistent scroll speed
    var scrollSpeed by remember { mutableFloatStateOf(0f) }

    // Dynamic Orientation and Fullscreen
    DisposableEffect(isLandscape) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.hide(WindowInsetsCompat.Type.ime())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                insetsController.hide(WindowInsetsCompat.Type.ime())
            }
        }

        onDispose {
            val window = activity?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Force sensor orientation and clear focus to hide keyboard
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

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

    // React to theme changes and scroll speed
    LaunchedEffect(readerTheme, scrollSpeed, hasLoaded.value) {
        if (hasLoaded.value) {
            val js = """
                window.applyTheme("${readerTheme.backgroundColor}", "${readerTheme.textColor}", ${readerTheme.fontSize});
                window.setScrollSpeed($scrollSpeed);
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
            if (!isLandscape || isSettingsVisible.value) {
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
        },
        bottomBar = {
            if (!isLandscape || isSettingsVisible.value) {
                Surface(tonalElevation = 4.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { webViewRef.value?.evaluateJavascript("window.prevPage()", null) }) {
                            Icon(Icons.Default.ChevronLeft, "Prev")
                        }
                        IconButton(onClick = { webViewRef.value?.evaluateJavascript("window.nextPage()", null) }) {
                            Icon(Icons.Default.ChevronRight, "Next")
                        }
                        
                        Spacer(Modifier.width(8.dp))
                        Text("Auto-scroll", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = scrollSpeed,
                            onValueChange = { scrollSpeed = it },
                            valueRange = 0f..2.5f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(if (isLandscape && !isSettingsVisible.value) PaddingValues(0.dp) else paddingValues)) {
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
                        // Try to prevent keyboard showing up by making it not focusable unless needed
                        isFocusable = false
                        isFocusableInTouchMode = false
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
            background: #111; color: #eee; 
            font-family: sans-serif; 
            overflow-x: hidden;
        }
        #viewer { 
            width: 100%; 
            min-height: 500px; /* Safe starting height */
            background: #111;
        }
        #loading { padding: 50px; text-align: center; color: #666; }
        
        /* Ensure no input fields can request focus and bring up keyboard */
        * { -webkit-tap-highlight-color: transparent; outline: none; }
        input, textarea, [contenteditable] { display: none !important; }
      </style>
    </head>
    <body>
      <div id="viewer">
        <div id="loading">Loading book...</div>
      </div>
      
      <script src="jszip.min.js"></script>
      <script src="epub.min.js"></script>
      
      <script>
        (function() {
          let rendition = null;
          let book = null;
          let scrollSpeed = 0;

          window.nextPage = () => rendition && rendition.next();
          window.prevPage = () => rendition && rendition.prev();

          window.setScrollSpeed = (speed) => {
              scrollSpeed = speed;
          };

          function scrollLoop() {
              if (scrollSpeed > 0) {
                  window.scrollBy(0, scrollSpeed);
              }
              requestAnimationFrame(scrollLoop);
          }
          requestAnimationFrame(scrollLoop);

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
