package com.omni.sync.utils

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.MainActivity
import com.omni.sync.OmniSyncApplication
import com.omni.sync.R
import com.omni.sync.data.repository.ReminderData
import com.omni.sync.receiver.ReminderReceiver
import com.omni.sync.service.AlarmService
import com.omni.sync.ui.screen.LogType
import java.time.OffsetDateTime
import java.time.ZonedDateTime

object ReminderDelivery {
    private val gson = Gson()
    data class PendingAction(val id: String, val method: String, val dueAt: String? = null, val token: String = java.util.UUID.randomUUID().toString())
    private fun prefs(context: Context) = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
    fun log(context: Context, message: String, error: Boolean = false) {
        (context.applicationContext as? OmniSyncApplication)?.mainViewModel?.addLog("[Reminders] $message", if (error) LogType.ERROR else LogType.INFO)
    }
    @Synchronized
    fun load(context: Context): List<ReminderData> = runCatching {
        gson.fromJson<List<ReminderData>>(prefs(context).getString("items", "[]"), object : TypeToken<List<ReminderData>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())
    @Synchronized
    fun pending(context: Context): List<PendingAction> = runCatching {
        gson.fromJson<List<PendingAction>>(prefs(context).getString("actions", "[]"), object : TypeToken<List<PendingAction>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())
    @Synchronized
    fun acknowledge(context: Context, token: String) {
        prefs(context).edit().putString("actions", gson.toJson(pending(context).filterNot { it.token == token })).commit()
        log(context, "Hub acknowledged action $token")
    }
    @Synchronized
    fun merge(context: Context, incoming: List<ReminderData>): List<ReminderData> {
        val old = load(context).associateBy { it.id }
        val queued = pending(context).map { it.id }.toSet()
        val merged = old.toMutableMap()
        incoming.forEach { item ->
            val previous = old[item.id]
            if (item.id !in queued && (previous == null || item.revision >= previous.revision)) merged[item.id] = item
        }
        val result = merged.values.toList()
        prefs(context).edit().putString("items", gson.toJson(result)).commit()
        result.forEach { item ->
            val previous = old[item.id]
            if (item.isTerminal || (previous != null && fireTime(previous) != fireTime(item))) stopPresentation(context, item.id)
            schedule(context, item)
        }
        log(context, "Persisted ${result.size} items from Hub; ${queued.size} awaiting action sync")
        return result
    }
    fun fireTime(item: ReminderData): Long? = runCatching { OffsetDateTime.parse(item.nextFireAt ?: item.dueAt).toInstant().toEpochMilli() }.getOrNull()
    private fun alarmIntent(context: Context, id: String) = Intent(context, ReminderReceiver::class.java).apply {
        action = "com.omni.sync.REMINDER_TRIGGER"
        data = Uri.parse("omni://reminder/$id")
        putExtra("REMINDER_ID", id)
    }
    private fun pi(context: Context, id: String, volume: Int = 5, repetition: Int = 0) = PendingIntent.getBroadcast(context, 0,
        alarmIntent(context, id).putExtra("CURRENT_VOLUME", volume).putExtra("CURRENT_REPETITION", repetition),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun nextTaskWindow(context: Context, now: Long): Long {
        val times = prefs(context).getString("task_times", "11:00,18:00")!!.split(',').mapNotNull { runCatching { java.time.LocalTime.parse(it.trim()) }.getOrNull() }
        val current = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        return (if (times.isEmpty()) listOf(java.time.LocalTime.of(11, 0)) else times).flatMap { time ->
            listOf(current.toLocalDate(), current.toLocalDate().plusDays(1)).map { date -> ZonedDateTime.of(date, time, current.zone).toInstant().toEpochMilli() }
        }.filter { it > now }.minOrNull()!!
    }
    @Synchronized
    fun schedule(context: Context, item: ReminderData, restore: Boolean = false) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (item.isTerminal) { manager.cancel(pi(context, item.id)); return }
        val fireAt = fireTime(item) ?: run { log(context, "Invalid due time for ${item.id}: ${item.dueAt}", true); return }
        val prefs = prefs(context)
        val scheduledKey = "scheduled_${item.id}"
        if (!restore && prefs.getLong(scheduledKey, Long.MIN_VALUE) == fireAt) return
        val shown = prefs.getLong("shown_${item.id}", Long.MIN_VALUE) == fireAt
        if (shown && item.kind != 0) return
        val at = if (shown) nextTaskWindow(context, System.currentTimeMillis()) else maxOf(fireAt, System.currentTimeMillis() + 1000)
        if (setAlarm(context, item, at)) prefs.edit().putLong(scheduledKey, fireAt).commit()
    }
    fun setAlarm(context: Context, item: ReminderData, at: Long, volume: Int = 5, repetition: Int = 0): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                log(context, "Exact alarm permission missing for ${item.id}; using an inexact wakeup", true)
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context, item.id, volume, repetition))
            } else if (item.kind == 0) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context, item.id, volume, repetition))
            } else {
                val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("omni://reminder/open/${item.id}")).putExtra("OPEN_SCREEN", "ALARM"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(context, item.id, volume, repetition))
            }
            log(context, "Scheduled ${item.id} kind=${item.kind} revision=${item.revision} at=${java.util.Date(at)} volume=$volume repetition=$repetition")
            return true
        } catch (e: Exception) { log(context, "Schedule failed ${item.id}: ${e.message}", true); return false }
    }
    @Synchronized
    fun trigger(context: Context, intent: Intent) {
        val id = intent.getStringExtra("REMINDER_ID") ?: return
        val item = load(context).find { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val fireAt = fireTime(item) ?: return
        if (item.isTerminal || fireAt > now + 1000) { log(context, "Ignoring cancelled or rescheduled trigger $id"); return }
        log(context, "Receiver fired $id title=${item.title} kind=${item.kind} revision=${item.revision}")
        prefs(context).edit().putLong("shown_$id", fireAt).commit()
        if (item.kind == 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("OmniTasks", "Omni tasks", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("omni://reminder/open/$id")).putExtra("OPEN_SCREEN", "ALARM"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, "OmniTasks").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.title).setContentText("Overdue task").setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open)
                .setOnlyAlertOnce(false).setAutoCancel(false)
                .addAction(0, "Done", actionIntent(context, id, "CompleteReminder"))
                .addAction(0, "Tomorrow", actionIntent(context, id, "Tomorrow"))
                .addAction(0, "Open", open).build()
            try {
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) log(context, "Notifications disabled: grant notification permission for task delivery", true)
                nm.cancel(id, 0)
                nm.notify(id, 0, notification)
                log(context, "Posted fresh task notification $id; channel importance=${nm.getNotificationChannel("OmniTasks").importance}")
            } catch (e: SecurityException) { log(context, "Notification blocked $id: ${e.message}", true) }
            setAlarm(context, item, nextTaskWindow(context, now))
        } else {
            val alarmPrefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
            val config = alarmPrefs.getString("config", null)?.let { gson.fromJson(it, com.omni.sync.ui.screen.GradualConfig::class.java) }
                ?: com.omni.sync.ui.screen.GradualConfig()
            val service = Intent(context, AlarmService::class.java).apply {
                putExtra("REMINDER_ID", id)
                putExtra("ALARM_ID", requestId(context, id))
                putExtra("DISMISS_TEXT", item.title)
                putExtra("CURRENT_VOLUME", if (item.kind == 2) 100 else intent.getIntExtra("CURRENT_VOLUME", config.initialVolume))
                putExtra("ALARM_DURATION", config.alarmDuration)
                putExtra("MAX_REPETITIONS", config.maxRepetitions)
                putExtra("VOLUME_INCREMENT", config.volumeIncrement)
                putExtra("SNOOZE_DURATION", config.snoozeDuration)
                putExtra("CURRENT_REPETITION", intent.getIntExtra("CURRENT_REPETITION", 0))
                putExtra("REMINDER_KIND", item.kind)
            }
            try { androidx.core.content.ContextCompat.startForegroundService(context, service) }
            catch (e: Exception) { log(context, "Alarm service start failed $id: ${e.message}", true); setAlarm(context, item, now + 60_000) }
        }
    }
    @Synchronized
    fun requestId(context: Context, id: String): Int {
        val prefs = prefs(context)
        val key = "request_$id"
        if (prefs.contains(key)) return prefs.getInt(key, -1)
        val next = prefs.getInt("next_request", -1)
        prefs.edit().putInt(key, next).putInt("next_request", next - 1).commit()
        return next
    }
    private fun actionIntent(context: Context, id: String, method: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction("com.omni.sync.REMINDER_ACTION").setData(Uri.parse("omni://reminder/action/$id/$method"))
            .putExtra("REMINDER_ID", id).putExtra("METHOD", method), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    @Synchronized
    fun act(context: Context, id: String, method: String, dueAt: String? = null) {
        val item = load(context).find { it.id == id } ?: return
        val action = PendingAction(id, method, dueAt)
        val changed = when (method) {
            "CompleteReminder" -> item.copy(state = 3, nextFireAt = null)
            "DismissReminder" -> item.copy(state = 4, nextFireAt = null)
            else -> item.copy(state = 2, nextFireAt = dueAt)
        }
        prefs(context).edit().putString("items", gson.toJson(load(context).map { if (it.id == id) changed else it }))
            .putString("actions", gson.toJson(pending(context) + action)).remove("scheduled_$id").commit()
        stopPresentation(context, id)
        schedule(context, changed)
        log(context, "Applied $method locally to $id; queued ${action.token} for Hub")
    }
    fun stopPresentation(context: Context, id: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id, 0)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(context, id))
        if (AlarmService.lastTriggeredAlarmId == requestId(context, id) && (AlarmService.isRinging.value || AlarmService.isSnoozing.value)) {
            context.startService(Intent(context, AlarmService::class.java).setAction("com.omni.sync.REMINDER_STOP"))
        }
    }
    fun restore(context: Context) {
        log(context, "Restoring persisted reminder schedules")
        load(context).forEach { item ->
            if (!item.isTerminal && item.kind != 0 && fireTime(item)?.let { it <= System.currentTimeMillis() } == true) {
                setAlarm(context, item, System.currentTimeMillis() + 1000)
            } else schedule(context, item, restore = true)
        }
    }
}
