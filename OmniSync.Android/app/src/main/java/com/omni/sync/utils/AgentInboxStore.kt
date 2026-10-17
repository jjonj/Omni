package com.omni.sync.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.omni.sync.MainActivity
import com.omni.sync.R
import com.omni.sync.data.repository.AgentSessionSummaryData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Per-session notification levels, mirroring the mockup's Everything / Needs you + finished / Needs you only / Muted. */
enum class AgentNotifyLevel(val label: String, val hint: String) {
    ALL("Everything", "Every state change, including when it starts working"),
    DEFAULT("Needs you + finished", "Questions, errors, usage limits and finished turns"),
    NEEDS("Needs you only", "Questions, errors and usage limits"),
    MUTED("Muted", "Still listed in the inbox, never notifies")
}

/**
 * Phone-side presentation state for the agent inbox: archive, "seen" markers (which turn idle sessions into
 * "Finished" until opened) and notification levels. Provider sessions themselves are never changed by this.
 */
data class AgentLocalState(
    val archived: Map<String, Long> = emptyMap(),
    val seen: Map<String, Long> = emptyMap(),
    val notify: Map<String, String> = emptyMap(),
    val baselined: Boolean = false
)

object AgentInboxStore {
    private const val PREFS = "agent_inbox"
    private const val KEY = "state"
    private val gson = Gson()
    private val _state = MutableStateFlow(AgentLocalState())
    val state: StateFlow<AgentLocalState> = _state
    private var loaded = false

    /** Session currently open in the detail screen; it never notifies. */
    @Volatile var viewingSessionId: String? = null

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val json = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _state.value = json?.let { runCatching { gson.fromJson(it, AgentLocalState::class.java) }.getOrNull() } ?: AgentLocalState()
    }

    fun notifyLevel(id: String): AgentNotifyLevel =
        _state.value.notify[id]?.let { runCatching { AgentNotifyLevel.valueOf(it) }.getOrNull() } ?: AgentNotifyLevel.DEFAULT

    fun isArchived(id: String, modifiedMillis: Long): Boolean {
        val at = _state.value.archived[id] ?: return false
        return modifiedMillis <= at // new activity after archiving brings it back
    }

    fun isUnseen(id: String, modifiedMillis: Long): Boolean = modifiedMillis > (_state.value.seen[id] ?: 0L)

    fun archive(context: Context, id: String) = update(context) { it.copy(archived = it.archived + (id to System.currentTimeMillis())) }
    fun unarchive(context: Context, id: String) = update(context) { it.copy(archived = it.archived - id) }
    fun setNotify(context: Context, id: String, level: AgentNotifyLevel) = update(context) { it.copy(notify = it.notify + (id to level.name)) }

    fun markSeen(context: Context, id: String, modifiedMillis: Long) {
        if ((_state.value.seen[id] ?: 0L) >= modifiedMillis) return
        update(context) { it.copy(seen = it.seen + (id to modifiedMillis)) }
    }

    /** On first run everything already on the PC counts as seen, so the inbox doesn't open with a wall of "Finished". */
    fun ensureBaseline(context: Context, sessions: List<AgentSessionSummaryData>) {
        if (_state.value.baselined || sessions.isEmpty()) return
        update(context) { it.copy(baselined = true, seen = it.seen + sessions.associate { s -> s.id to isoMillis(s.modified) }) }
    }

    @Synchronized
    private fun update(context: Context, change: (AgentLocalState) -> AgentLocalState) {
        init(context)
        val next = change(_state.value)
        _state.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(next)).apply()
    }

    fun isoMillis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}

/** Posts notifications for meaningful agent state transitions (never for low-level tool activity). */
object AgentAttentionNotifier {
    private const val CHANNEL = "agent_attention"
    private val lastStates = mutableMapOf<String, String>()
    private var primed = false
    private val NEEDS = setOf("question", "permission", "error")

    @Synchronized
    fun onSessions(context: Context, sessions: List<AgentSessionSummaryData>) {
        AgentInboxStore.init(context)
        AgentInboxStore.ensureBaseline(context, sessions)
        if (!primed) {
            sessions.forEach { lastStates[it.id] = it.state }
            primed = true
            return
        }
        sessions.forEach { s ->
            val previous = lastStates.put(s.id, s.state)
            if (previous == s.state) return@forEach
            val kind = when {
                s.state in NEEDS -> "needs"
                s.state == "limited" -> "needs"
                previous in setOf("working", "starting") && s.state == "idle" -> "finished"
                s.state == "working" && previous != null -> "working"
                else -> null
            } ?: return@forEach
            if (!allows(AgentInboxStore.notifyLevel(s.id), kind) || AgentInboxStore.viewingSessionId == s.id) return@forEach
            post(context, s, kind)
        }
    }

    private fun allows(level: AgentNotifyLevel, kind: String) = when (level) {
        AgentNotifyLevel.ALL -> true
        AgentNotifyLevel.DEFAULT -> kind == "needs" || kind == "finished"
        AgentNotifyLevel.NEEDS -> kind == "needs"
        AgentNotifyLevel.MUTED -> false
    }

    private fun post(context: Context, s: AgentSessionSummaryData, kind: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent attention", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Coding agents that need you or finished a turn"
            })
        }
        val headline = when (s.state) {
            "question" -> "Question"
            "permission" -> "Permission needed"
            "error" -> "Turn failed"
            "limited" -> "Usage limited"
            "working" -> "Started working"
            else -> "Finished"
        }
        val body = s.question?.text ?: s.error ?: s.activity.ifBlank { s.title }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_SCREEN", "AGENT_DETAIL")
            putExtra("AGENT_SESSION_ID", s.id)
        }
        val pending = PendingIntent.getActivity(context, s.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${projectName(s.cwd).ifBlank { s.provider }} · $headline")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (kind == "needs") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify("agent:${s.id}".hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission not granted; the inbox still shows the state.
        }
    }

    fun projectName(cwd: String): String = cwd.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
}
