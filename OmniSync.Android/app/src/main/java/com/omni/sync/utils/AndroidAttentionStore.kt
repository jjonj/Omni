package com.omni.sync.utils

import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Durable phone presentation cache. The authenticated desktop T3 status is authoritative. */
data class PhoneAgentAttention(
    val id: String, val sessionId: String, val message: String,
    val status: String = "pending", val nextAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

object AndroidAttentionStore {
    private const val PREFS = "direct-agent-attention-v2"
    private const val KEY = "requests"
    private val gson = Gson()
    private val type = object : TypeToken<List<PhoneAgentAttention>>() {}.type
    private val _entries = MutableStateFlow<List<PhoneAgentAttention>>(emptyList())
    val entries: StateFlow<List<PhoneAgentAttention>> = _entries
    private var loaded = false

    @Synchronized fun init(context: Context) {
        if (loaded) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        _entries.value = runCatching {
            gson.fromJson<List<PhoneAgentAttention>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
        loaded = true
    }

    @Synchronized private fun persist(context: Context, rows: List<PhoneAgentAttention>) {
        val retained = rows.filter { System.currentTimeMillis() - it.createdAt < 30L * 86_400_000 }
            .takeLast(100)
        // A confirmation cannot be represented as complete until it is saved locally.
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, gson.toJson(retained)).commit())
            throw IllegalStateException("Could not persist attention state")
        _entries.value = retained
    }

    @Synchronized fun receive(context: Context, id: String, sessionId: String, message: String): Boolean {
        init(context)
        val current = _entries.value.find { it.id == id }
        if (current != null) return false // delayed SignalR push must not resurrect a resolved request
        // A push is not proof of a verified working -> idle transition.
        // Only the T3 status reconciliation can mark this request pending/ready.
        persist(context, _entries.value + PhoneAgentAttention(id, sessionId, message, status = "waiting"))
        return true
    }

    /** Reconcile against the PC's complete list. Missing entries stay recoverable if status is uncertain. */
    private fun snoozeIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(context, id.hashCode(),
            Intent(context, AndroidAttentionSnoozeReceiver::class.java).apply {
                data = Uri.parse("omni://attention-reminder/$id")
                putExtra("requestId", id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun adjustAlarm(context: Context, current: PhoneAgentAttention) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = snoozeIntent(context, current.id)
        if (current.status == "snoozed" && current.nextAt > System.currentTimeMillis()) {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms())
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, current.nextAt, intent)
            else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, current.nextAt, intent)
        } else {
            manager.cancel(intent)
        }
    }

    @Synchronized fun reconcile(context: Context, remote: List<PhoneAgentAttention>) {
        init(context)
        val byId = _entries.value.associateBy { it.id }.toMutableMap()
        remote.forEach { row ->
            val prior = byId[row.id]
            byId[row.id] = row
            if (prior?.status != row.status || prior?.nextAt != row.nextAt)
                runCatching { adjustAlarm(context, row) }
        }
        persist(context, byId.values.sortedBy { it.createdAt })
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        remote.filter { it.status in listOf("opened", "dismissed") }.forEach {
            notifications.cancel(it.id.hashCode())
        }
    }

    @Synchronized fun applyConfirmed(context: Context, id: String, status: String, nextAt: Long = 0) {
        init(context)
        persist(context, _entries.value.map {
            if (it.id == id) it.copy(status = status, nextAt = nextAt) else it
        })
        _entries.value.firstOrNull { it.id == id }?.let {
            runCatching { adjustAlarm(context, it) }
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(id.hashCode())
    }

    fun fromStatus(value: JsonObject): PhoneAgentAttention? = runCatching {
        PhoneAgentAttention(
            id = value.get("id").asString,
            sessionId = value.get("sessionId").asString,
            message = value.get("message").asString,
            status = value.get("status").asString,
            nextAt = value.get("nextAt")?.takeUnless { it.isJsonNull }?.asLong ?: 0,
            createdAt = value.get("createdAt").asLong
        )
    }.getOrNull()
}
