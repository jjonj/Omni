package com.omni.sync.utils

import android.content.Context
import com.google.gson.Gson
import com.omni.sync.OmniSyncApplication
import com.omni.sync.ui.screen.AlarmData
import com.omni.sync.ui.screen.GradualConfig
import com.omni.sync.ui.screen.LogType
import java.util.Calendar

object WakeAlarmActions {
    private const val HOUR = 3_600_000L
    private const val MIN_SLEEP = 8 * HOUR
    private const val MAX_SLEEP = 9 * HOUR
    // How far before the target wake time the oversleep cap may pull the alarm. Bounds the damage
    // from a stray early-evening signal that is not actually the last one before bed.
    private const val MAX_EARLIER = 2 * HOUR
    // Automatic signals only matter once a capped alarm could be affected.
    private const val AUTO_WINDOW = MAX_SLEEP + MAX_EARLIER
    private var lastAutomatic = 0L

    fun nextTrigger(alarm: AlarmData, now: Long): Long {
        val data = alarm.resolvedForNextTrigger()
        return Calendar.getInstance().apply {
            timeInMillis = data.scheduledDate ?: now
            set(Calendar.HOUR_OF_DAY, data.hour % 12 + if (data.isAM) 0 else 12)
            set(Calendar.MINUTE, data.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (data.scheduledDate == null && timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            if (alarm.isWakeAlarm) {
                val latestMinutes = alarm.latestWakeMinutes ?: 720
                val latest = (clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, latestMinutes / 60)
                    set(Calendar.MINUTE, latestMinutes % 60)
                }
                if (timeInMillis > latest.timeInMillis) timeInMillis = latest.timeInMillis
                if (data.scheduledDate == null && timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
        }.timeInMillis
    }

    fun adjustedTime(alarm: AlarmData, now: Long): Long {
        val target = nextTrigger(alarm.copy(nextTriggerOverride = null), now)
        val minimumSleep = ((now + MIN_SLEEP + 59_999) / 60_000) * 60_000
        // Gone to bed early: wake after at most 9h, as close to the target as that allows.
        val maximumSleep = maxOf((now + MAX_SLEEP) / 60_000 * 60_000, target - MAX_EARLIER)
        val desired = maxOf(minOf(target, maximumSleep), minimumSleep)
        val latest = Calendar.getInstance().apply {
            timeInMillis = desired
            val minutes = alarm.latestWakeMinutes ?: 720
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return minOf(desired, latest)
    }

    private fun capNote(alarm: AlarmData, now: Long, fireAt: Long): String {
        val target = nextTrigger(alarm.copy(nextTriggerOverride = null), now)
        return when {
            fireAt < target -> "moved ${(target - fireAt) / 60_000} min earlier to cap sleep at 9h"
            fireAt > target -> "moved ${(fireAt - target) / 60_000} min later for 8h sleep (latest wake cap applied)"
            else -> "on target"
        }
    }

    @Synchronized
    fun stillAwake(context: Context, alarmId: Int? = null, automatic: Boolean = false) {
        val now = System.currentTimeMillis()
        val viewModel = (context.applicationContext as? OmniSyncApplication)?.mainViewModel
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val gson = Gson()
        val config = prefs.getString("config", null)?.let { gson.fromJson(it, GradualConfig::class.java) } ?: GradualConfig()
        val alarms = AlarmStore.load(prefs, gson)
        if (automatic && alarms.none { it.enabled && it.isWakeAlarm && it.autoStillAwake }) return
        if (automatic && (com.omni.sync.service.AlarmService.isRinging.value || com.omni.sync.service.AlarmService.isSnoozing.value)) return
        val nearWakeAlarm = alarms.any { it.enabled && it.isWakeAlarm && it.autoStillAwake && nextTrigger(it, now) - now in 1..(9 * 3_600_000L) }
        viewModel?.recordStillAwake(startIfInactive = !automatic || nearWakeAlarm,
            source = if (automatic) "phone interaction" else "Still awake button")
        // Tracking uses every activity signal; rescheduling alarms is throttled separately.
        if (automatic && now - lastAutomatic < 60_000) return
        if (automatic) lastAutomatic = now
        viewModel?.addLog("[Wake detection] ${if (automatic) "Phone activity" else "Still awake button"} received; alarmId=$alarmId", LogType.INFO)
        val updated = alarms.map { alarm ->
            if (!alarm.isWakeAlarm || (alarmId != null && alarm.id != alarmId)) return@map alarm
            val remaining = nextTrigger(alarm, now) - now
            if (!alarm.enabled || (automatic && (!alarm.autoStillAwake || remaining !in 1..AUTO_WINDOW))) {
                viewModel?.addLog("[Wake detection ${alarm.id}] Skipped: enabled=${alarm.enabled}, auto=${alarm.autoStillAwake}, minutesUntilAlarm=${remaining / 60_000}", LogType.INFO)
                return@map alarm
            }
            val fireAt = adjustedTime(alarm, now)
            if (fireAt <= now) {
                viewModel?.addLog("[Wake detection ${alarm.id}] Not adjusted: latest wake limit has passed", LogType.INFO)
                return@map alarm
            }
            val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
            val changed = alarm.copy(hour = cal.get(Calendar.HOUR_OF_DAY).let { if (it % 12 == 0) 12 else it % 12 },
                minute = cal.get(Calendar.MINUTE), isAM = cal.get(Calendar.AM_PM) == Calendar.AM, scheduledDate = fireAt)
            val result = alarm.withNextTriggerOverride(changed)
            AlarmScheduler.scheduleAlarm(context, alarm.id, result, config)
            (context.applicationContext as? OmniSyncApplication)?.mainViewModel?.addLog(
                "[Wake alarm ${alarm.id}] Still awake${if (automatic) " (phone interaction)" else ""}: next occurrence ${cal.time}; ${capNote(alarm, now, fireAt)}", LogType.INFO)
            result
        }
        if (updated != alarms) AlarmStore.save(prefs, gson, updated)
    }
}
