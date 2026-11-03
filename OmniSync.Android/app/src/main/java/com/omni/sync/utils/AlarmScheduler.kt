package com.omni.sync.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.omni.sync.receiver.AlarmReceiver
import com.omni.sync.ui.screen.AlarmData
import com.omni.sync.ui.screen.GradualConfig
import java.util.Calendar

object AlarmScheduler {
    fun restoreEnabledAlarms(context: Context) {
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val config = prefs.getString("config", null)?.let { gson.fromJson(it, GradualConfig::class.java) } ?: GradualConfig()
        AlarmStore.load(prefs, gson).filter { it.enabled }.forEach { alarm ->
            val trigger = alarm.resolvedForNextTrigger()
            if (trigger.scheduledDate == null || WakeAlarmActions.nextTrigger(alarm, System.currentTimeMillis()) > System.currentTimeMillis()) {
                scheduleAlarm(context, alarm.id, alarm, config)
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

        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            (context.applicationContext as? com.omni.sync.OmniSyncApplication)?.mainViewModel?.addLog(
                "[Wake alarm $alarmId] Scheduled ${calendar.time}; daily=${data.repeatDaily}, wake=${data.isWakeAlarm}, nextOverride=${data.nextTriggerOverride != null}", com.omni.sync.ui.screen.LogType.INFO)
        } catch (e: SecurityException) {
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
        
        // Also cancel potential snooze
        cancelSnooze(context, alarmId)
        
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
    }
}
