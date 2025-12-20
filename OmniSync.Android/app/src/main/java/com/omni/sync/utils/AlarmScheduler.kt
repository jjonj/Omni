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

    fun scheduleAlarm(context: Context, alarmId: Int, data: AlarmData, config: GradualConfig) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e("AlarmScheduler", "Permission SCHEDULE_EXACT_ALARM not granted. Alarm will not fire.")
                // In a real app, you would prompt the user here.
                return
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.omni.sync.ALARM_TRIGGER"
            putExtra("ALARM_ID", alarmId)
            putExtra("SOUND_ID", data.soundId)
            putExtra("INITIAL_VOLUME", config.initialVolume)
            putExtra("ALARM_DURATION", config.alarmDuration)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Convert 12h (1-12) to 24h (0-23) manually for safety
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
            
            // If the time is in the past, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        Log.i("AlarmScheduler", "Scheduling Alarm $alarmId for: ${calendar.time}")

        try {
            // using setAlarmClock ensures the alarm icon appears in status bar and is more robust against doze mode
            val alarmClockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (e: SecurityException) {
            Log.e("AlarmScheduler", "Security Exception scheduling alarm", e)
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
        Log.i("AlarmScheduler", "Cancelled Alarm $alarmId")
    }
}