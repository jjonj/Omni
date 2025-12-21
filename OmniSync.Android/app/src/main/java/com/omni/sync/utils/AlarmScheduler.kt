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

    // Request Codes:
    // Main Alarm 1: 1
    // Main Alarm 2: 2
    // Snooze Alarm 1: 1001
    // Snooze Alarm 2: 1002

    fun scheduleAlarm(context: Context, alarmId: Int, data: AlarmData, config: GradualConfig) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e("AlarmScheduler", "Permission SCHEDULE_EXACT_ALARM not granted.")
                return
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.omni.sync.ALARM_TRIGGER"
            putExtra("ALARM_ID", alarmId)
            putExtra("SOUND_ID", data.soundId)
            putExtra("HOUR", data.hour)
            putExtra("MINUTE", data.minute)
            putExtra("IS_AM", data.isAM)
            
            // Initial Config
            putExtra("CURRENT_VOLUME", config.initialVolume)
            putExtra("ALARM_DURATION", config.alarmDuration)
            putExtra("MAX_REPETITIONS", config.maxRepetitions)
            putExtra("VOLUME_INCREMENT", config.volumeIncrement)
            putExtra("SNOOZE_DURATION", config.snoozeDuration)
            putExtra("REPEAT_DAILY", data.repeatDaily)
            putExtra("CURRENT_REPETITION", 0)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId, // ID 1 or 2
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Convert 12h (1-12) to 24h (0-23)
        val hour24 = if (data.isAM) {
            if (data.hour == 12) 0 else data.hour
        } else {
            if (data.hour == 12) 12 else data.hour + 12
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, data.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
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
        repeatDaily: Boolean
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