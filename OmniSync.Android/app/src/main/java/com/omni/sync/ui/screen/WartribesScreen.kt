package com.omni.sync.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omni.sync.data.wartribes.WartribesOfflineCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wartribes screen: Development Dashboard (WDD) and Asset Review (WAR) tabs.
 *
 * Both are the existing web UIs, reached through the authenticated Hub Gateway
 * launcher (`<hub>/<tool-id>`), because the tool servers only listen on the PC's
 * loopback. Sync model:
 * - The PC is the only source of truth (TaskBoard / WAR SQLite). The pages keep
 *   their own polling (WDD every 30s; WAR reloads after each action), and WDD's
 *   read/unread state is already shared server-side across devices.
 * - Every GET the phone sees is mirrored to [WartribesOfflineCache]; "Save for
 *   offline" additionally pulls state, task details and media in one go.
 * - Offline the pages open read-only from that cache. Writes are never queued:
 *   they fail with an explicit "offline" error instead (no merge conflicts).
 */
private enum class WartribesTab(val title: String, val toolId: String) {
    DASHBOARD("Dashboard", "wartribes-development-dashboard"),
    ASSETS("Asset Review", "wartribes-asset-review")
}

private const val PREFS = "wartribes"
private const val DEFAULT_HUB = "https://hub.rancorous.online"

/** Only the selected tool owns a live WebView. A failed renderer must never kill Omni. */
private class TabHost(val tab: WartribesTab) {
    var webView: WebView? = null
    @Volatile var toolPrefix: String? = null
    var disposed = false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WartribesScreen(parentPadding: PaddingValues = PaddingValues(), onExit: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var hubBase by remember { mutableStateOf(prefs.getString("hub_base", DEFAULT_HUB) ?: DEFAULT_HUB) }
    var selected by remember { mutableIntStateOf(prefs.getInt("tab", 0).coerceIn(0, WartribesTab.values().lastIndex)) }
    var offline by remember { mutableStateOf(false) }
    var cachedAt by remember { mutableStateOf<Long?>(null) }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    val cache = remember { WartribesOfflineCache(context.applicationContext) }
    cache.onStatus = { isOffline, savedAt ->
        mainHandler.post {
            offline = isOffline
            cachedAt = if (isOffline) savedAt ?: cachedAt else null
        }
    }

    fun launcherUrl(tab: WartribesTab) = hubBase.trimEnd('/') + "/" + tab.toolId
    fun lastToolUrl(tab: WartribesTab) = prefs.getString("last_${tab.name}", null)

    var generation by remember { mutableIntStateOf(0) }
    val current = remember(selected, hubBase, generation) { TabHost(WartribesTab.values()[selected]) }
    var pageError by remember(current) { mutableStateOf<String?>(null) }
    val opened = remember(hubBase) { mutableSetOf<WartribesTab>() }
    val atlasCacheQueued = remember(hubBase) { AtomicBoolean(false) }

    fun reportFailure(error: Throwable) {
        android.util.Log.e("Wartribes", "WebView failure", error)
        runCatching {
            (context.applicationContext as? com.omni.sync.OmniSyncApplication)?.androidDiagnostics
                ?.record("app", "failed", error = error, source = "CrashReportingTree",
                    eventName = "timber_exception", scope = "wartribes")
        }
    }

    fun createView(host: TabHost, viewContext: Context): FrameLayout {
        val container = FrameLayout(viewContext)
        val tab = host.tab
        try {
            val web = WebView(viewContext)
            host.webView = web
            container.addView(web, FrameLayout.LayoutParams(-1, -1))
            web.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true // honour each page's mobile viewport meta
                    settings.loadWithOverviewMode = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    // A WebView implementation may advertise this extension but fail to install it.
                    // Offline scripting is optional; normal navigation must still work.
                    val documentStartInstalled = runCatching {
                        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) false
                        else {
                            WebViewCompat.addDocumentStartJavaScript(this, WartribesOfflineCache.OFFLINE_FETCH_SHIM, setOf("*"))
                            true
                        }
                    }.getOrElse { reportFailure(it); false }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (request.isForMainFrame) host.toolPrefix = toolPrefixOf(request.url)
                            val path = request.url.path.orEmpty()
                            // The Atlas is an iframe; onPageFinished on the host never sees its
                            // navigation. Start one background snapshot when the reader opens.
                            if (tab == WartribesTab.DASHBOARD &&
                                (path.endsWith("/DevelopmentDashboard/atlas/magazine.html") ||
                                 path.endsWith("/DevelopmentDashboard/atlas/map.html")) &&
                                atlasCacheQueued.compareAndSet(false, true)) {
                                mainHandler.post {
                                    scope.launch {
                                        saveStatus = "Caching Atlas for offline..."
                                        val result = withContext(Dispatchers.IO) {
                                            val uri = Uri.parse(hubBase)
                                            val origin = "${uri.scheme}://${uri.encodedAuthority}"
                                            val base = "$origin/gateway/tools/${tab.toolId}/"
                                            cache.prefetchAtlasArchive(base + "dashboard/api/atlas-offline.zip", base) { count ->
                                                mainHandler.post { saveStatus = "Caching Atlas: $count files..." }
                                            }
                                        }
                                        saveStatus = result
                                        if (result.startsWith("Atlas offline save failed")) atlasCacheQueued.set(false)
                                    }
                                }
                            }
                            val hubHost = Uri.parse(hubBase).host ?: return null
                            return try {
                                cache.intercept(request, hubHost, host.toolPrefix, lastToolUrl(tab))
                            } catch (e: Exception) {
                                android.util.Log.w("Wartribes", "WebView interception failed; using native request", e)
                                null
                            }
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            if (!documentStartInstalled)
                                runCatching { view.evaluateJavascript(WartribesOfflineCache.OFFLINE_FETCH_SHIM, null) }
                                    .onFailure(::reportFailure)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // Remember the real tool page so offline starts can skip the launcher.
                            if (toolPrefixOf(Uri.parse(url)) != null && cache.isCached(url))
                                prefs.edit().putString("last_${tab.name}", url).apply()
                            runCatching { CookieManager.getInstance().flush() }.onFailure(::reportFailure)
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            // Android otherwise terminates the entire hosting app when Chromium dies.
                            reportFailure(IllegalStateException("Wartribes WebView renderer stopped"))
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                            host.webView = null
                            if (!host.disposed) pageError = "The web renderer stopped. Tap Retry to reopen this tool."
                            return true
                        }
                    }
                    val resume = if (tab in opened) lastToolUrl(tab) else null
                    opened.add(tab)
                    loadUrl(resume?.takeIf { Uri.parse(it).host == Uri.parse(hubBase).host } ?: launcherUrl(tab))
                }
        } catch (error: Exception) {
            reportFailure(error)
            container.removeAllViews()
            host.webView?.let { runCatching { it.destroy() } }
            host.webView = null
            container.post { if (!host.disposed) pageError = "Could not open WebView (${error.javaClass.simpleName})." }
        } catch (error: LinkageError) {
            reportFailure(error)
            container.removeAllViews()
            host.webView?.let { runCatching { it.destroy() } }
            host.webView = null
            container.post { if (!host.disposed) pageError = "Android WebView is unavailable (${error.javaClass.simpleName})." }
        }
        return container
    }

    DisposableEffect(Unit) {
        onDispose { cache.onStatus = null }
    }

    // WAR navigates with history.pushState, so Back walks its own views first.
    BackHandler { current.webView?.let { if (it.canGoBack()) { it.goBack(); return@BackHandler } }; onExit() }

    Column(Modifier.fillMaxSize().padding(parentPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f)) {
                WartribesTab.values().forEachIndexed { i, tab ->
                    Tab(selected = selected == i, onClick = {
                        selected = i
                        prefs.edit().putInt("tab", i).apply()
                    }, text = { Text(tab.title, maxLines = 1) })
                }
            }
            IconButton(onClick = { current.webView?.reload() ?: run { generation++ } }) { Icon(Icons.Default.Refresh, "Reload") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Save both for offline") },
                        leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                        enabled = saveStatus == null ||
                            !(saveStatus!!.startsWith("Saving") || saveStatus!!.startsWith("Caching")),
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                saveStatus = "Saving…"
                                saveStatus = withContext(Dispatchers.IO) {
                                    saveForOffline(cache, hubBase) { msg -> mainHandler.post { saveStatus = msg } }
                                }
                            }
                        }
                    )
                    DropdownMenuItem(text = { Text("Open launcher (restart tool)") }, onClick = {
                        menuOpen = false
                        val web = current.webView
                        if (web != null) web.loadUrl(launcherUrl(current.tab)) else { opened.remove(current.tab); generation++ }
                    })
                    DropdownMenuItem(text = { Text("Settings & cache") }, onClick = {
                        menuOpen = false; showSettings = true
                    })
                }
            }
        }
        StatusStrip(offline, cachedAt, saveStatus)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(current) {
                AndroidView(
                    factory = { createView(current, it) },
                    onReset = null,
                    onRelease = { container ->
                        current.disposed = true
                        container.removeAllViews()
                        current.webView?.let { runCatching { it.destroy() }.onFailure(::reportFailure) }
                        current.webView = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            pageError?.let { error ->
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { opened.remove(current.tab); generation++ }) { Text("Retry") }
                }
            }
        }
    }

    if (showSettings) {
        var draft by remember { mutableStateOf(hubBase) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Wartribes") },
            text = {
                Column {
                    Text("Hub address. The tools are opened through its Gateway (login once in the page; the session is kept).",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Offline cache: ${cache.sizeBytes() / (1024 * 1024)} MB (max ${WartribesOfflineCache.MAX_TOTAL_BYTES / (1024 * 1024)} MB)",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { cache.clear(); showSettings = false }) { Text("Clear offline cache") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = draft.trim().trimEnd('/').ifBlank { DEFAULT_HUB }
                    prefs.edit().putString("hub_base", cleaned).apply()
                    hubBase = cleaned
                    showSettings = false
                    generation++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusStrip(offline: Boolean, cachedAt: Long?, saveStatus: String?) {
    if (!offline && saveStatus == null) return
    val text = buildString {
        if (offline) {
            append("Offline · read-only")
            cachedAt?.let { append(" · cached " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
        }
        if (saveStatus != null) {
            if (isNotEmpty()) append("  ·  ")
            append(saveStatus)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(if (offline) Color(0xFF4A3412) else Color(0xFF1C2A3A))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (offline) Color(0xFFE0A030) else Color(0xFF4CAF50)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE6EDF5),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun toolPrefixOf(uri: Uri): String? =
    Regex("^/gateway/tools/([a-z0-9][a-z0-9-]{1,49})/").find(uri.path.orEmpty())?.value

/**
 * Pull everything needed to read both tools offline: WDD state, details of
 * active/recent/captured tasks and image captures; WAR state per pipeline and
 * media of attempts awaiting review plus approved images. Runs on Dispatchers.IO.
 */
private fun saveForOffline(cache: WartribesOfflineCache, hubBase: String, progress: (String) -> Unit): String {
    val origin = Uri.parse(hubBase).let { "${it.scheme}://${it.encodedAuthority}" }
    val urls = LinkedHashSet<String>()
    try {
        // Cache even the Atlas chapters and images that have not been opened yet.
        val dashboardBase = "$origin/gateway/tools/${WartribesTab.DASHBOARD.toolId}/"
        progress("Saving Atlas for offline...")
        val atlasResult = cache.prefetchAtlasArchive(dashboardBase + "dashboard/api/atlas-offline.zip", dashboardBase) {
            count -> progress("Saving Atlas: $count files...")
        }
        // WDD
        val wdd = dashboardBase + "dashboard/"
        progress("Saving dashboard state…")
        if (cache.prefetch(wdd + "api/state") < 0) return "Save failed: can't reach the dashboard (open it once to log in / start it)"
        val state = JSONObject(cache.cachedText(wdd + "api/state") ?: "{}")
        val media = state.optJSONArray("media")
        val taskIds = LinkedHashSet<String>()
        if (media != null) for (i in 0 until media.length()) {
            val m = media.getJSONObject(i)
            taskIds += m.optString("task")
            if (m.optString("mediaType") == "image") urls += wdd + "media/" + Uri.encode(m.optString("filename"))
        }
        val tasks = state.optJSONArray("tasks")
        val recentCutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(14)).toString()
        if (tasks != null) for (i in 0 until tasks.length()) {
            val t = tasks.getJSONObject(i)
            if (t.optString("column") != "done" || t.optString("modifiedAt") > recentCutoff) taskIds += t.optString("id")
        }
        taskIds.filter { it.isNotBlank() }.take(300).forEach { urls += wdd + "api/task/" + Uri.encode(it) }

        // WAR
        val war = "$origin/gateway/tools/${WartribesTab.ASSETS.toolId}/"
        progress("Saving asset review state…")
        if (cache.prefetch(war + "api/catalog") >= 0) {
            val pipelines = JSONObject(cache.cachedText(war + "api/catalog") ?: "{}").optJSONObject("pipelines")
            pipelines?.keys()?.forEach { pipeline ->
                val stateUrl = war + "api/state?pipeline=" + Uri.encode(pipeline)
                if (cache.prefetch(stateUrl) < 0) return@forEach
                val attempts = JSONObject(cache.cachedText(stateUrl) ?: "{}").optJSONArray("attempts") ?: return@forEach
                for (i in 0 until attempts.length()) {
                    val a = attempts.getJSONObject(i)
                    if (a.isNull("file_path")) continue
                    val pending = a.optString("state") == "submitted"
                    val approvedImage = a.optString("state") == "approved" && a.optString("media_type") != "video"
                    if (pending || approvedImage) urls += war + "media/" + Uri.encode(a.optString("id"))
                }
            }
        }

        var done = 0; var failed = 0; var bytes = 0L
        urls.forEach { url ->
            if (!cache.isCached(url) || url.contains("/api/")) {
                val n = cache.prefetch(url)
                if (n < 0) failed++ else bytes += n
            }
            if (++done % 10 == 0) progress("Saving $done/${urls.size}…")
        }
        return "${if (atlasResult.startsWith("Saved")) "Atlas saved. " else "$atlasResult. "}" +
            "Saved ${urls.size - failed}/${urls.size} other items (${bytes / (1024 * 1024)} MB new)"
    } catch (e: Exception) {
        return "Save failed: ${e.message}"
    }
}
