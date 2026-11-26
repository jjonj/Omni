package com.omni.sync.data.wartribes

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Read-through disk cache behind the Wartribes WebViews (WDD + WAR).
 *
 * Used from `WebViewClient.shouldInterceptRequest` (background thread):
 * - Online: GETs on the Hub host are fetched natively with the WebView's
 *   cookies; 200 responses are stored and handed to the page. Non-200
 *   responses (login redirects, launcher hops, errors) return null so the
 *   WebView repeats the request itself and keeps normal behaviour.
 * - Offline (network error): the last stored copy is served, so both UIs
 *   open as read-only snapshots of what was last viewed or prefetched.
 *
 * POSTs are never intercepted or queued; [OFFLINE_FETCH_SHIM] handles them in JS.
 * The PC stays the only source of truth: nothing here writes back.
 */
class WartribesOfflineCache(context: Context) {
    private val dir = File(context.filesDir, "wartribes-cache").apply { mkdirs() }
    private var writesSinceTrim = 0

    /** True after the last native request failed to reach the Hub. */
    @Volatile var offline = false
        private set

    /** Called (on a background thread) when [offline] flips or a cached copy is served. */
    @Volatile var onStatus: ((offline: Boolean, cachedAtMillis: Long?) -> Unit)? = null

    /**
     * @param toolPrefix `/gateway/tools/<id>/` of the page currently shown, if any. The remote
     *   Gateway's JS shim only rescopes fetch/XHR, so dynamic `<img src="/media/x">` tags would
     *   otherwise hit the Hub root; such stray root paths are rescoped here.
     * @param offlineFallbackUrl tool page to open instead when the launcher URL can't be reached.
     */
    fun intercept(request: WebResourceRequest, hubHost: String, toolPrefix: String?,
                  offlineFallbackUrl: String?): WebResourceResponse? {
        var url = request.url
        if (request.method != "GET" || url.scheme !in setOf("http", "https")) return null
        if (!url.host.equals(hubHost, ignoreCase = true)) return null
        val path = url.path.orEmpty()
        val rescoped = !request.isForMainFrame && toolPrefix != null &&
            !path.startsWith("/gateway/") && !path.startsWith("/__")
        if (rescoped) url = url.buildUpon().encodedPath(toolPrefix + url.encodedPath.orEmpty().removePrefix("/")).build()
        // Video seeking uses Range requests; let the WebView stream those natively.
        if (request.requestHeaders.keys.any { it.equals("Range", true) }) {
            if (offline) return cached(url.toString())?.let { serve(it) }
            if (!rescoped) return null
        }
        return try {
            fetch(url.toString(), request)
        } catch (e: IOException) {
            setOffline(true)
            val entry = cached(url.toString()) ?: cached(stripQuery(url))
            when {
                entry != null -> serve(entry)
                request.isForMainFrame && offlineFallbackUrl != null && isCached(offlineFallbackUrl) ->
                    redirectPage(offlineFallbackUrl)
                request.isForMainFrame -> offlinePage(url.toString())
                else -> null
            }
        }
    }

    private fun fetch(url: String, request: WebResourceRequest): WebResourceResponse? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 6_000
        conn.readTimeout = 30_000
        request.requestHeaders.forEach { (k, v) ->
            // Drop conditional/encoding headers so we always receive a full, decoded 200 body.
            // Range is only forwarded for rescoped media (the WebView streams the rest itself).
            if (k.lowercase() !in DROPPED_REQUEST_HEADERS) conn.setRequestProperty(k, v)
            else if (k.equals("Range", true)) conn.setRequestProperty(k, v)
        }
        val cookies = CookieManager.getInstance()
        cookies.getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        val code = conn.responseCode
        setOffline(false)
        conn.headerFields["Set-Cookie"]?.forEach { cookies.setCookie(url, it) }
        if (code == 206) {
            val (mime, encoding) = parseContentType(conn.contentType)
            val headers = conn.headerFields.filterKeys { it != null && it.lowercase() in setOf("content-range", "content-length", "accept-ranges") }
                .mapValues { it.value.joinToString(", ") }
            return WebResourceResponse(mime, encoding, 206, "Partial Content", headers, connectionStream(conn))
        }
        if (code != 200) {
            val reason = conn.responseMessage?.ifBlank { null } ?: "Error"
            conn.disconnect()
            // Android WebResourceResponse rejects redirects (3xx) and invalid status codes.
            // Let WebView handle navigation/auth redirects itself rather than crashing.
            if (code !in 100..299 && code !in 400..599) return null
            // Rescoped URLs differ from what the page asked for, so the WebView can't retry them.
            return if (url != request.url.toString())
                WebResourceResponse("text/plain", "utf-8", code, reason,
                    emptyMap(), ByteArrayInputStream(ByteArray(0)))
            else null
        }
        val (mime, encoding) = parseContentType(conn.contentType)
        val headers = conn.headerFields.filterKeys { it != null && it.lowercase() !in DROPPED_RESPONSE_HEADERS }
            .mapValues { it.value.joinToString(", ") }
        val streamOnly = mime == "text/event-stream" || mime.startsWith("video/") ||
            mime.startsWith("audio/") || conn.contentLengthLong > MAX_ENTRY_BYTES
        if (streamOnly) return WebResourceResponse(mime, encoding, 200, "OK", headers, connectionStream(conn))

        // Do not materialize many concurrent responses (especially chunked media) on the Java heap.
        // Spool at most the cache limit; oversized responses continue streaming without caching.
        val input = connectionStream(conn)
        val temp = try { File.createTempFile("response-", ".part", dir) } catch (e: IOException) {
            input.close(); throw e
        }
        var handedOff = false
        try {
            val complete = copyCachePrefix(input, temp)
            if (complete) {
                input.close()
                runCatching {
                    store(url, mime, encoding, temp)
                    val canonical = stripQuery(Uri.parse(url))
                    if (request.isForMainFrame && canonical != url) store(canonical, mime, encoding, temp)
                }.onFailure { android.util.Log.w("Wartribes", "Could not cache response", it) }
            }
            val prefix = object : FilterInputStream(temp.inputStream()) {
                override fun close() { try { super.close() } finally { temp.delete() } }
            }
            val body = if (complete) prefix else SequenceInputStream(prefix, input)
            val result = WebResourceResponse(mime, encoding, 200, "OK", headers, body)
            handedOff = true
            return result
        } finally {
            if (!handedOff) {
                runCatching { input.close() }
                temp.delete()
            }
        }
    }

    private fun connectionStream(conn: HttpURLConnection): InputStream =
        object : FilterInputStream(conn.inputStream) {
            override fun close() { try { super.close() } finally { conn.disconnect() } }
        }

    /** Leaves input positioned after the copied prefix if the response exceeds the cache limit. */
    private fun copyCachePrefix(input: InputStream, target: File): Boolean {
        var bytes = 0L
        val buffer = ByteArray(32 * 1024)
        target.outputStream().use { output ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return true
                output.write(buffer, 0, n)
                bytes += n
                if (bytes > MAX_ENTRY_BYTES) return false
            }
        }
    }

    /**
     * Cache the complete Atlas (including lazily loaded chapters and images) in one
     * download. The Gateway serves only the static DevelopmentDashboard tree.
     * Each ZIP member is stored under its normal page URL so WebView's existing
     * offline request interceptor can read it without rewriting the Atlas UI.
     */
    fun prefetchAtlasArchive(archiveUrl: String, dashboardBase: String,
                             progress: (Int) -> Unit): String = try {
        val conn = URL(archiveUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
            CookieManager.getInstance().getCookie(archiveUrl)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) throw IOException("Atlas archive HTTP ${conn.responseCode}")
            if (conn.contentLengthLong > MAX_TOTAL_BYTES) throw IOException("Atlas archive is too large")
            var count = 0
            var total = 0L
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    if (name != "DevelopmentDashboard/index.html" &&
                        !name.startsWith("DevelopmentDashboard/atlas/"))
                        throw IOException("Unexpected Atlas archive entry")
                    if (name.split('/').any { it == ".." || it == "." } || '\\' in name)
                        throw IOException("Invalid Atlas archive path")
                    val temp = File.createTempFile("atlas-", ".part", dir)
                    try {
                        if (!copyCachePrefix(zip, temp)) throw IOException("Oversized Atlas asset: $name")
                        val bytes = temp.length()
                        total += bytes
                        if (total > MAX_TOTAL_BYTES) throw IOException("Atlas exceeds offline cache budget")
                        val (mime, charset) = atlasMime(name)
                        store(dashboardBase.trimEnd('/') + "/" + name, mime, charset, temp)
                        count++
                        if (count % 25 == 0) progress(count)
                    } finally { temp.delete(); zip.closeEntry() }
                }
            }
            if (count < 3) throw IOException("Atlas archive was empty")
            "Saved Atlas offline: $count files (${total / (1024 * 1024)} MB)"
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        "Atlas offline save failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun atlasMime(path: String): Pair<String, String?> {
        val type = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        return type to (if (type.startsWith("text/") || type == "application/json") "utf-8" else null)
    }

    /** Download one URL into the cache (used by "Save for offline"). Returns bytes stored or -1. */
    fun prefetch(url: String): Long = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 30_000
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200 || conn.contentLengthLong > MAX_ENTRY_BYTES) -1
            else {
                val (mime, encoding) = parseContentType(conn.contentType)
                val temp = File.createTempFile("prefetch-", ".part", dir)
                try {
                    if (!conn.inputStream.use { copyCachePrefix(it, temp) }) -1
                    else {
                        store(url, mime, encoding, temp)
                        temp.length()
                    }
                } finally { temp.delete() }
            }
        } finally { conn.disconnect() }
    } catch (e: IOException) { -1 }

    /** Cached body as text, e.g. a state JSON used to plan a prefetch. */
    fun cachedText(url: String): String? = cached(url)?.let { String(it.body.readBytes()) }

    fun isCached(url: String): Boolean = bodyFile(url).exists()

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private class Entry(val body: File, val mime: String, val encoding: String?, val savedAt: Long)

    private fun cached(url: String): Entry? {
        val body = bodyFile(url)
        val meta = File(dir, key(url) + ".json")
        if (!body.exists() || !meta.exists()) return null
        return runCatching {
            val m = JSONObject(meta.readText())
            body.setLastModified(System.currentTimeMillis()) // LRU touch
            Entry(body, m.getString("mime"), m.optString("encoding").ifBlank { null }, m.getLong("savedAt"))
        }.getOrNull()
    }

    private fun serve(entry: Entry): WebResourceResponse {
        onStatus?.invoke(true, entry.savedAt)
        return WebResourceResponse(entry.mime, entry.encoding, 200, "OK",
            mapOf("Cache-Control" to "no-store"), entry.body.inputStream())
    }

    @Synchronized
    private fun store(url: String, mime: String, encoding: String?, body: File) {
        val k = key(url)
        val tmp = File(dir, "$k.tmp")
        body.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(File(dir, "$k.bin").also { it.delete() })) {
            tmp.delete()
            throw IOException("Could not replace cached body")
        }
        File(dir, "$k.json").writeText(JSONObject()
            .put("url", url).put("mime", mime).put("encoding", encoding ?: "")
            .put("savedAt", System.currentTimeMillis()).toString())
        if (++writesSinceTrim >= 40) { writesSinceTrim = 0; trim() }
    }

    // Evict least-recently-used bodies once the cache exceeds its budget.
    private fun trim() {
        val bodies = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.lastModified() } ?: return
        var total = bodies.sumOf { it.length() }
        for (f in bodies) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= f.length()
            f.delete()
            File(dir, f.name.removeSuffix(".bin") + ".json").delete()
        }
    }

    private fun setOffline(value: Boolean) {
        if (offline == value) return
        offline = value
        onStatus?.invoke(value, null)
    }

    private fun offlinePage(url: String): WebResourceResponse {
        val html = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="background:#10151f;color:#cfd8e3;font:15px system-ui;padding:24px">
            <h3>Offline</h3><p>This page has not been cached on the phone yet.</p>
            <p style="color:#7f93a9;word-break:break-all">${android.text.Html.escapeHtml(url)}</p>
            <p>Open it once while connected (or use <b>Save for offline</b>) to read it without a connection.</p></body>"""
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }

    private fun redirectPage(target: String): WebResourceResponse {
        val html = "<!doctype html><script>location.replace(${JSONObject.quote(target)})</script>"
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }

    private fun bodyFile(url: String) = File(dir, key(url) + ".bin")

    private fun key(url: String): String = MessageDigest.getInstance("SHA-1")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun stripQuery(uri: Uri): String = uri.buildUpon().clearQuery().fragment(null).build().toString()

    private fun parseContentType(raw: String?): Pair<String, String?> {
        val parts = (raw ?: "application/octet-stream").split(";").map { it.trim() }
        val charset = parts.drop(1).firstOrNull { it.startsWith("charset=", true) }?.substringAfter("=")?.trim('"')
        return parts[0].lowercase() to charset
    }

    companion object {
        const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 600L * 1024 * 1024
        private val DROPPED_REQUEST_HEADERS = setOf("if-none-match", "if-modified-since", "accept-encoding", "range")
        private val DROPPED_RESPONSE_HEADERS = setOf("content-length", "content-encoding", "transfer-encoding",
            "set-cookie", "content-type", "etag", "last-modified", "cache-control")

        /**
         * Injected at document start. Remembers responses of read-only WAR actions
         * (catalog, detail, guidance reads, undo history, concept list) in localStorage
         * and replays them when offline, so WAR can still boot and open details.
         * Any other POST that fails offline gets a clear 503 JSON error instead of a raw
         * network failure; nothing is queued for later.
         */
        val OFFLINE_FETCH_SHIM = """
(() => {
  if (window.__omniWartribesShim) return; window.__omniWartribesShim = 1;
  const READ_ACTIONS = new Set(['catalog','detail','guidance','undo_history','help','concept']);
  const READ_KEYS = new Set(['action','pipeline','sampleId','limit','topic','step','operation','undoSession']);
  const realFetch = window.fetch.bind(window);
  function readOnlyKey(url, body) {
    if (!/\/api\/action(\?|$)/.test(url) || typeof body !== 'string') return null;
    try {
      const o = JSON.parse(body);
      if (!READ_ACTIONS.has(o.action) || !Object.keys(o).every(k => READ_KEYS.has(k))) return null;
      if (o.action === 'concept' && o.operation !== 'list') return null;
      delete o.undoSession;
      return 'omniwt:' + JSON.stringify(Object.keys(o).sort().map(k => [k, o[k]]));
    } catch (e) { return null; }
  }
  window.fetch = async function (input, init) {
    const method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD') return realFetch(input, init);
    const url = typeof input === 'string' ? input : (input && input.url) || String(input);
    const key = readOnlyKey(url, init && init.body);
    try {
      const r = await realFetch(input, init);
      if (key && r.ok) r.clone().text().then(t => { try { localStorage.setItem(key, t); } catch (e) {} });
      return r;
    } catch (e) {
      const saved = key && localStorage.getItem(key);
      if (saved) return new Response(saved, {status: 200, headers: {'Content-Type': 'application/json'}});
      return new Response(JSON.stringify({error: 'Offline: this is a read-only cached view. Reconnect to the Hub to make changes.'}),
        {status: 503, headers: {'Content-Type': 'application/json'}});
    }
  };
})();
""".trimIndent()
    }
}
