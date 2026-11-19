package com.omni.sync.data.repository

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * True on-device diagnostic history, retained across process death and link outages.
 * This is deliberately NOT a logcat dump: only predefined event names, states,
 * operation IDs and classified errors can leave the phone (never paths, prompts,
 * file contents, auth credentials or exception messages).
 */
data class AndroidDiagnosticEvent(
    val eventId: String,
    val timestampMs: Long,
    val category: String,
    val status: String,
    val operationId: String?,
    val errorKind: String?,
    val count: Int?,
    val source: String? = null,
    val eventName: String? = null,
    val scope: String? = null,
    val exceptionType: String? = null
)

class AndroidDiagnostics(context: Context) {
    companion object {
        const val MAX_EVENTS = 300
        const val MAX_MINUTES = 1440
        private const val RETENTION_MS = 24L * 60 * 60 * 1000
        private val categories = setOf("app", "connection", "files.list", "sessions.create", "diagnostics")
        private val scopes = setOf("root", "wartribes", "obsidian", "taskboard", "drive", "other")
        private val exceptionTypes = setOf("TimeoutException", "SocketTimeoutException", "ConnectException",
            "IllegalStateException", "IllegalArgumentException", "HttpException", "IOException",
            "CancellationException", "UnknownHostException", "JsonSyntaxException",
            "CompletionException", "RuntimeException", "Exception")
        fun pathScope(path: String): String = when {
            path.isBlank() || path == "/" -> "root"
            path.contains("wartribes", ignoreCase = true) -> "wartribes"
            path.contains("obsidian", ignoreCase = true) -> "obsidian"
            path.contains("taskboard", ignoreCase = true) -> "taskboard"
            path.matches(Regex("(?i)^[a-z]:[/\\\\]?$")) -> "drive"
            else -> "other"
        }
        private val sources = setOf("dashboard", "SignalRClient", "FilesViewModel", "GenericAgentClient", "CrashReportingTree")
        private val eventNames = setOf("startup", "auth", "connect", "connect_failed",
            "connection_closed", "reconnect_scheduled", "directory_list", "directory_result",
            "session_create", "session_result", "dashboard_error", "dashboard_file",
            "dashboard_connection", "dashboard_session", "dashboard_other",
            "dashboard_file_cached", "dashboard_file_loaded", "dashboard_file_failed",
            "dashboard_connected", "dashboard_disconnected", "dashboard_reconnect",
            "dashboard_session_failed", "dashboard_session_created",
            "timber_exception", "diagnostic_upload", "cache_fallback")
        private val statuses = setOf(
            "attempt", "ok", "failed", "offline", "timeout", "authenticated",
            "auth_failed", "closed", "reconnecting", "reconnected", "rejected", "unknown"
        )
        fun errorKind(error: Throwable): String {
            val name = error.javaClass.simpleName.lowercase()
            val message = error.message.orEmpty().lowercase()
            return when {
                "timeout" in name || "timed out" in message -> "Timeout"
                "auth" in name || "unauthoriz" in message -> "Unauthorized"
                "connection" in name || "socket" in name ||
                    "connect" in message || "network" in message -> "Connection"
                "cancellation" in name -> "Cancelled"
                "http" in name -> "Http"
                else -> "Other"
            }
        }
    }

    private val gson = Gson()
    private val disk = AtomicFile(File(context.filesDir, "android-diagnostics-v1.json"))
    private val events = ArrayDeque<AndroidDiagnosticEvent>()
    private val guard = Any()
    val deviceId: String = context.getSharedPreferences("android-diagnostic-identity", Context.MODE_PRIVATE)
        .let { prefs ->
            prefs.getString("deviceId", null)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).commit() }
        }

    init {
        synchronized(guard) {
            try {
                val saved = disk.openRead().bufferedReader(Charsets.UTF_8).use {
                    gson.fromJson(it, Array<AndroidDiagnosticEvent>::class.java)
                } ?: emptyArray()
                saved.takeLast(MAX_EVENTS).forEach { event ->
                    if (event.category in categories && event.status in statuses) events.addLast(event)
                }
                prune(System.currentTimeMillis())
            } catch (_: Exception) {
                // Broken old history must not prevent the phone from connecting.
                events.clear()
            }
        }
    }

    fun record(
        category: String, status: String, operationId: String? = null,
        error: Throwable? = null, count: Int? = null,
        source: String? = null, eventName: String? = null,
        scope: String? = null
    ): String {
        require(category in categories && status in statuses)
        require(source == null || source in sources)
        require(eventName == null || eventName in eventNames)
        require(scope == null || scope in scopes)
        val exceptionType = error?.javaClass?.simpleName?.takeIf { it in exceptionTypes }
        val id = operationId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        val entry = AndroidDiagnosticEvent(
            UUID.randomUUID().toString(), System.currentTimeMillis(), category, status, id,
            error?.let(::errorKind), count?.coerceIn(0, 1_000_000), source, eventName,
            scope, exceptionType
        )
        synchronized(guard) {
            events.addLast(entry)
            prune(entry.timestampMs)
            persist()
        }
        return entry.eventId
    }

    /** Capture existing UI/app log activity without persisting raw messages or exceptions. */
    fun dashboard(message: String, level: String) {
        val normalized = message.lowercase()
        val event = when {
            "directory" in normalized && "cache" in normalized -> "dashboard_file_cached"
            "loaded" in normalized && "entries" in normalized -> "dashboard_file_loaded"
            "directory" in normalized && ("error" in normalized || "failed" in normalized) -> "dashboard_file_failed"
            "created" in normalized && "session" in normalized -> "dashboard_session_created"
            "session" in normalized && ("failed" in normalized || "uncertain" in normalized) -> "dashboard_session_failed"
            "reconnect" in normalized -> "dashboard_reconnect"
            "connection closed" in normalized || "disconnected" in normalized -> "dashboard_disconnected"
            "connected" in normalized && "hub" in normalized -> "dashboard_connected"
            "directory" in normalized || "file" in normalized || "folder" in normalized -> "dashboard_file"
            "connect" in normalized || "hub" in normalized || "signalr" in normalized -> "dashboard_connection"
            "session" in normalized || "agent" in normalized || "ai" in normalized -> "dashboard_session"
            "startup" in normalized -> "startup"
            level == "ERROR" || level == "WARNING" -> "dashboard_error"
            else -> "dashboard_other"
        }
        val status = when {
            "not connected" in normalized || "unavailable" in normalized -> "offline"
            "timeout" in normalized || "timed out" in normalized -> "timeout"
            level == "ERROR" -> "failed"
            level == "WARNING" -> "unknown"
            "failed" in normalized || "error" in normalized -> "failed"
            else -> "ok"
        }
        // Existing dashboard text can contain paths, URLs and even command output.
        // Record the safe app-side classification, NEVER the original text.
        record("app", status, source = "dashboard", eventName = event)
    }

    fun snapshot(minutes: Int): List<AndroidDiagnosticEvent> = synchronized(guard) {
        val cutoff = System.currentTimeMillis() - minutes.coerceIn(1, MAX_MINUTES) * 60_000L
        events.filter { it.timestampMs >= cutoff }.takeLast(MAX_EVENTS)
    }

    private fun prune(now: Long) {
        while (events.isNotEmpty() &&
            (events.size > MAX_EVENTS || events.first().timestampMs < now - RETENTION_MS)
        ) events.removeFirst()
    }

    private fun persist() {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = disk.startWrite()
            stream.write(gson.toJson(events.toList()).toByteArray(Charsets.UTF_8))
            disk.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { disk.failWrite(it) }
            Log.w("AndroidDiagnostics", "Could not persist bounded diagnostic history")
        }
    }
}
