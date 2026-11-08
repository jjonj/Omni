package com.omni.sync.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.omni.sync.receiver.AlarmReceiver
import com.omni.sync.receiver.MediaPauseScheduler
import com.omni.sync.ui.screen.AlarmData
import com.omni.sync.ui.screen.GradualConfig
import java.util.Calendar

object AlarmScheduler {
    // Companion alarms have their own PendingIntent and never appear as editable alarm rows.
    private const val BEDTIME_ID_BASE = 1_000_000
    fun bedtimeAlarmId(wakeAlarmId: Int): Int = BEDTIME_ID_BASE + wakeAlarmId
    fun isBedtimeAlarmId(id: Int): Boolean = id in BEDTIME_ID_BASE until 2 * BEDTIME_ID_BASE
    fun wakeIdForBedtime(id: Int): Int = id - BEDTIME_ID_BASE

    /** Always calculate from the recurring *target*, never from a one-off wake choice or its cap. */
    fun nextBedtimeTrigger(wake: AlarmData, now: Long = System.currentTimeMillis()): Long {
        val targetDay = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, wake.hour % 12 + if (wake.isAM) 0 else 12)
            set(Calendar.MINUTE, wake.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (true) {
            val bedtime = (targetDay.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, -10) }
            if (bedtime.timeInMillis > now) return bedtime.timeInMillis
            targetDay.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    fun scheduleBedtimeAlarm(context: Context, wake: AlarmData, config: GradualConfig) {
        if (!wake.enabled || !wake.isWakeAlarm || !wake.bedtimeAlarmEnabled) {
            cancelBedtimeAlarm(context, wake.id)
            return
        }
        val at = nextBedtimeTrigger(wake)
        val local = Calendar.getInstance().apply { timeInMillis = at }
        val hour24 = local.get(Calendar.HOUR_OF_DAY)
        val reminder = AlarmData(
            id = bedtimeAlarmId(wake.id), enabled = true,
            hour = if (hour24 % 12 == 0) 12 else hour24 % 12,
            minute = local.get(Calendar.MINUTE), isAM = hour24 < 12,
            repeatDaily = true, useGradual = true, soundId = "gentle",
            scheduledDate = at, dismissText = "Time for bed"
        )
        AlarmTrace.record("BEDTIME_SCHEDULE", "wake=" + wake.id + " at=" + AlarmTrace.time(at))
        scheduleAlarm(context, reminder.id, reminder, config)
    }

    fun cancelBedtimeAlarm(context: Context, wakeAlarmId: Int) {
        // Also cancel any pending gradual snooze when the companion is disabled.
        cancelAlarm(context, bedtimeAlarmId(wakeAlarmId))
    }

    fun restoreEnabledAlarms(context: Context) {
        AlarmTrace.init(context, "restoreEnabledAlarms")
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val config = prefs.getString("config", null)?.let { gson.fromJson(it, GradualConfig::class.java) } ?: GradualConfig()
        val all = AlarmStore.load(prefs, gson)
        AlarmTrace.record("RESTORE_READ", all.joinToString(" | ") { AlarmTrace.describe(it) })
        all.filter { it.enabled }.forEach { alarm ->
            val trigger = alarm.resolvedForNextTrigger()
            if (trigger.scheduledDate == null || WakeAlarmActions.nextTrigger(alarm, System.currentTimeMillis()) > System.currentTimeMillis()) {
                scheduleAlarm(context, alarm.id, alarm, config)
            } else {
                AlarmTrace.record("RESTORE_SKIPPED", AlarmTrace.describe(alarm))
                if (alarm.isWakeAlarm) scheduleBedtimeAlarm(context, alarm, config)
            }
        }
    }

    // Request Codes:
    // Main Alarm 1: 1
    // Main Alarm 2: 2
    // Snooze Alarm 1: 1001
    // Snooze Alarm 2: 1002

    fun scheduleAlarm(
        context: Context,
        alarmId: Int,
        data: AlarmData,
        config: GradualConfig,
        forceNextDay: Boolean = false
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerData = data.resolvedForNextTrigger()
        val effectiveConfig = triggerData.gradualOverride ?: config

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e("AlarmScheduler", "Permission SCHEDULE_EXACT_ALARM not granted.")
                AlarmTrace.record("PERMISSION_DENIED", AlarmTrace.describe(data))
                (context.applicationContext as? com.omni.sync.OmniSyncApplication)?.mainViewModel?.addLog(
                    "[Wake alarm $alarmId] Scheduling failed: exact alarm permission missing", com.omni.sync.ui.screen.LogType.ERROR)
                return
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.omni.sync.ALARM_TRIGGER"
            putExtra("ALARM_ID", alarmId)
            putExtra("SOUND_ID", triggerData.soundId)
            putExtra("HOUR", triggerData.hour)
            putExtra("MINUTE", triggerData.minute)
            putExtra("IS_AM", triggerData.isAM)
            
            // Initial Config
            putExtra("CURRENT_VOLUME", effectiveConfig.initialVolume)
            putExtra("ALARM_DURATION", effectiveConfig.alarmDuration)
            putExtra("MAX_REPETITIONS", effectiveConfig.maxRepetitions)
            putExtra("VOLUME_INCREMENT", effectiveConfig.volumeIncrement)
            putExtra("SNOOZE_DURATION", effectiveConfig.snoozeDuration)
            putExtra("REPEAT_DAILY", data.repeatDaily)
            putExtra("CURRENT_REPETITION", 0)
            putExtra("MACRO_ON_TRIGGER", triggerData.macroOnTrigger)
            putExtra("MACRO_ON_DISMISS", triggerData.macroOnDismiss)
            putExtra("QUICK_ACTION_ON_TRIGGER_ID", triggerData.quickActionOnTriggerId)
            putExtra("QUICK_ACTION_ON_DISMISS_ID", triggerData.quickActionOnDismissId)
            putExtra("DISMISS_ACTION_DELAY_SECONDS", triggerData.dismissActionDelaySeconds)
            putExtra("DISMISS_TEXT", triggerData.dismissText)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Convert 12h (1-12) to 24h (0-23)
        val hour24 = if (triggerData.isAM) {
            if (triggerData.hour == 12) 0 else triggerData.hour
        } else {
            if (triggerData.hour == 12) 12 else triggerData.hour + 12
        }

        val calendar = Calendar.getInstance().apply {
            if (triggerData.scheduledDate != null) {
                timeInMillis = triggerData.scheduledDate!!
                // Ensure we use the specified hour/minute even if the timestamp had different ones
                set(Calendar.HOUR_OF_DAY, hour24)
                set(Calendar.MINUTE, triggerData.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            } else {
                set(Calendar.HOUR_OF_DAY, hour24)
                set(Calendar.MINUTE, triggerData.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (forceNextDay || timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        if (data.isWakeAlarm) {
            val now = System.currentTimeMillis()
            calendar.timeInMillis = WakeAlarmActions.nextTrigger(data, now)
            if (forceNextDay) {
                val today = Calendar.getInstance()
                if (calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        
        // If repeating daily, we might want to setRepeating. 
        // But for exact timing and the custom snooze logic, it's often better 
        // to reschedule the next day when this one fires (or is dismissed).
        // However, standard AlarmClock usage implies we schedule the specific instance.
        // We will stick to setAlarmClock for the immediate next occurrence.
        // The repetition logic for *tomorrow* should be handled:
        // 1. If repeatDaily is true, upon firing, we check if we need to schedule tomorrow. 
        //    Actually, simplest is: if repeatDaily, scheduleRepeating? 
        //    No, exact alarms don't support repeating efficiently with Doze.
        //    Strategy: Schedule this one. When it fires, if (repeatDaily) -> Schedule next day immediately.
        
        // We actually need to ensure the receiver knows to schedule the next day.
        // Since we are simplifying, we will rely on the User Re-enabling or a simplistic "Schedule Next" logic
        // inside the Receiver if we wanted robust repeating. 
        // For this specific request, we focus on the Gradual Logic.
        // **Implicitly**, if repeatDaily is TRUE, we should ideally schedule the next one.
        // I will add a method to re-schedule for tomorrow if needed, but for now let's get the Trigger working.

        Log.i("AlarmScheduler", "Scheduling Alarm $alarmId for: ${calendar.time}")

        AlarmTrace.record("SCHEDULE_ATTEMPT", "alarm=" + alarmId + " at=" +
            AlarmTrace.time(calendar.timeInMillis) + " nextDay=" + forceNextDay + " " + AlarmTrace.describe(data))
        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            AlarmTrace.record("SCHEDULE_SUCCESS", "alarm=" + alarmId + " at=" + AlarmTrace.time(calendar.timeInMillis))
            if (data.isWakeAlarm) {
                scheduleBedtimeAlarm(context, data, config)
                MediaPauseScheduler.scheduleWakePauses(context, alarmId, calendar.timeInMillis)
            } else if (!isBedtimeAlarmId(alarmId)) {
                MediaPauseScheduler.cancelWakePauses(context, alarmId)
            }
            (context.applicationContext as? com.omni.sync.OmniSyncApplication)?.mainViewModel?.addLog(
                "[Wake alarm $alarmId] Scheduled ${calendar.time}; daily=${data.repeatDaily}, wake=${data.isWakeAlarm}, nextOverride=${data.nextTriggerOverride != null}", com.omni.sync.ui.screen.LogType.INFO)
        } catch (e: SecurityException) {
            AlarmTrace.record("SCHEDULE_ERROR", "alarm=" + alarmId + " cause=" + e)
            Log.e("AlarmScheduler", "Security Exception", e)
        }
    }

    fun scheduleSnooze(
        context: Context, 
        alarmId: Int, 
        soundId: String, 
        hour: Int,
        minute: Int,
        isAM: Boolean,
        volume: Int, 
        durationSec: Int, 
        snoozeMin: Int,
        volIncrement: Int,
        maxReps: Int,
        repetition: Int,
                        repeatDaily: Boolean,
                        macroOnTrigger: String?,
                        macroOnDismiss: String?,
                        quickActionOnTriggerId: String?,
                        quickActionOnDismissId: String?,
                        dismissActionDelaySeconds: Int,
                        dismissText: String?
            ) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.omni.sync.ALARM_TRIGGER"
                    putExtra("ALARM_ID", alarmId)
                    putExtra("SOUND_ID", soundId)
                    putExtra("HOUR", hour)
                    putExtra("MINUTE", minute)
                    putExtra("IS_AM", isAM)
                    
                    // Updated Step Config
                    putExtra("CURRENT_VOLUME", volume)
                    putExtra("ALARM_DURATION", durationSec)
                    putExtra("MAX_REPETITIONS", maxReps)
                    putExtra("VOLUME_INCREMENT", volIncrement)
                    putExtra("SNOOZE_DURATION", snoozeMin)
                    putExtra("REPEAT_DAILY", repeatDaily)
                    putExtra("CURRENT_REPETITION", repetition)
                    putExtra("MACRO_ON_TRIGGER", macroOnTrigger)
                    putExtra("MACRO_ON_DISMISS", macroOnDismiss)
                    putExtra("QUICK_ACTION_ON_TRIGGER_ID", quickActionOnTriggerId)
                    putExtra("QUICK_ACTION_ON_DISMISS_ID", quickActionOnDismissId)
                    putExtra("DISMISS_ACTION_DELAY_SECONDS", dismissActionDelaySeconds)
                    putExtra("DISMISS_TEXT", dismissText)
                }
                val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId + 1000, // Offset ID for Snooze
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + (snoozeMin * 60 * 1000L)
        
        Log.i("AlarmScheduler", "Scheduling Snooze for Alarm $alarmId at $triggerTime (in $snoozeMin min)")
        AlarmTrace.record("SNOOZE_SCHEDULE", "alarm=" + alarmId +
            " at=" + AlarmTrace.time(triggerTime) + " repetition=" + repetition)

        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (e: SecurityException) {
            Log.e("AlarmScheduler", "Security Exception snooze", e)
        }
    }

    fun cancelAlarm(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.omni.sync.ALARM_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        AlarmTrace.record("CANCEL_MAIN", "id=" + alarmId)
        
        // Also cancel potential snooze, companion and pre-wake media pauses.
        cancelSnooze(context, alarmId)
        if (!isBedtimeAlarmId(alarmId)) {
            MediaPauseScheduler.cancelWakePauses(context, alarmId)
            cancelBedtimeAlarm(context, alarmId)
        }
        
        Log.i("AlarmScheduler", "Cancelled Alarm $alarmId")
    }
    
    fun cancelSnooze(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.omni.sync.ALARM_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId + 1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        AlarmTrace.record("CANCEL_SNOOZE", "id=" + alarmId)
    }
}
