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
    val exceptionType: String? = null,
    // Present for EVERY MainViewModel.addLog entry, preserving the visible severity.
    val logLevel: String? = null
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
        private val logLevels = setOf("INFO", "SUCCESS", "WARNING", "ERROR")
        private val eventNames = setOf("startup", "auth", "connect", "connect_failed",
            "connection_closed", "reconnect_scheduled", "directory_list", "directory_result",
            "session_create", "session_result", "dashboard_error", "dashboard_file",
            "dashboard_connection", "dashboard_session", "dashboard_other",
            "dashboard_file_cached", "dashboard_file_loaded", "dashboard_file_failed",
            "dashboard_connected", "dashboard_disconnected", "dashboard_reconnect",
            "dashboard_session_failed", "dashboard_session_created",
            "dashboard_voice", "dashboard_sleep", "dashboard_alarm", "dashboard_reminder",
            "dashboard_remote", "dashboard_webview", "dashboard_book", "dashboard_clipboard",
            "dashboard_macro", "dashboard_update", "dashboard_wol", "dashboard_log_control",
            "dashboard_command", "dashboard_browser", "dashboard_settings",
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
        scope: String? = null, logLevel: String? = null, timestampMs: Long = System.currentTimeMillis()
    ): String {
        require(category in categories && status in statuses)
        require(source == null || source in sources)
        require(eventName == null || eventName in eventNames)
        require(scope == null || scope in scopes)
        require(logLevel == null || logLevel in logLevels)
        val exceptionType = error?.javaClass?.simpleName?.takeIf { it in exceptionTypes }
        val id = operationId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        val entry = AndroidDiagnosticEvent(
            UUID.randomUUID().toString(), timestampMs, category, status, id,
            error?.let(::errorKind), count?.coerceIn(0, 1_000_000), source, eventName,
            scope, exceptionType, logLevel
        )
        synchronized(guard) {
            events.addLast(entry)
            prune(entry.timestampMs)
            persist()
        }
        return entry.eventId
    }

    /** Every visible app-dashboard row passes here. Keep 1 event per row, with its
     * exact UI timestamp/severity, but classify text on-device rather than upload it.
     * This is NOT the separately selectable Hub server-log tab.
     */
    fun dashboard(message: String, level: String, timestampMs: Long) {
        val normalized = message.lowercase()
        val event = when {
            // These represent dashboard rows from every feature, even if their
            // free-form text contains filenames, URLs, voice input or credentials.
            normalized.startsWith("[voice]") || "voice recognition" in normalized -> "dashboard_voice"
            normalized.startsWith("[sleep tracker]") -> "dashboard_sleep"
            normalized.startsWith("[wake alarm") || normalized.startsWith("[wake detection") ||
                "alarm" in normalized -> "dashboard_alarm"
            normalized.startsWith("[reminders]") -> "dashboard_reminder"
            normalized.startsWith("[remote]") -> "dashboard_remote"
            normalized.startsWith("webview") -> "dashboard_webview"
            normalized.startsWith("[books]") -> "dashboard_book"
            "clipboard" in normalized -> "dashboard_clipboard"
            "macro" in normalized -> "dashboard_macro"
            "update" in normalized || "deployment" in normalized -> "dashboard_update"
            normalized.startsWith("[wol]") || "wol" in normalized -> "dashboard_wol"
            normalized == "logs cleared" || normalized.startsWith("logs copied") ||
                "fetch hub logs" in normalized || "log entries from hub" in normalized -> "dashboard_log_control"
            normalized.startsWith("[command ") || "command output" in normalized -> "dashboard_command"
            normalized.startsWith("browser") || "bookmark" in normalized -> "dashboard_browser"
            "setting" in normalized || "password" in normalized -> "dashboard_settings"
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
        // Preserve the visible row (timestamp, severity, category and order)
        // without persisting any free-form message, credentials or transcript.
        record("app", status, source = "dashboard", eventName = event,
            logLevel = level, timestampMs = timestampMs)
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
