package com.omni.sync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.omni.sync.service.AlarmService
import com.omni.sync.utils.AlarmScheduler
import com.omni.sync.ui.screen.AlarmData
import com.omni.sync.ui.screen.GradualConfig
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm Received! ${intent.action}")
        if (intent.action == "com.omni.sync.ALARM_TRIGGER") {
            
            // 1. Reschedule for tomorrow if it's the Main Alarm (not snooze) and Repeat is ON
            val isSnooze = intent.getIntExtra("CURRENT_REPETITION", 0) > 0
            val repeatDaily = intent.getBooleanExtra("REPEAT_DAILY", false)
            val alarmId = intent.getIntExtra("ALARM_ID", 0)

            if (repeatDaily && !isSnooze) {
                val hour = intent.getIntExtra("HOUR", 0)
                val minute = intent.getIntExtra("MINUTE", 0)
                val isAM = intent.getBooleanExtra("IS_AM", true)
                val soundId = intent.getStringExtra("SOUND_ID") ?: "gentle"
                
                val alarmData = AlarmData(enabled = true, hour = hour, minute = minute, isAM = isAM, soundId = soundId, repeatDaily = true)
                val config = GradualConfig(
                    initialVolume = intent.getIntExtra("CURRENT_VOLUME", 5),
                    alarmDuration = intent.getIntExtra("ALARM_DURATION", 3),
                    snoozeDuration = intent.getIntExtra("SNOOZE_DURATION", 10),
                    volumeIncrement = intent.getIntExtra("VOLUME_INCREMENT", 5),
                    maxRepetitions = intent.getIntExtra("MAX_REPETITIONS", 6)
                )
                
                AlarmScheduler.scheduleAlarm(context, alarmId, alarmData, config)
                Log.d("AlarmReceiver", "Rescheduled alarm $alarmId for tomorrow.")
            }

            // 2. Start the Service to play sound
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                action = "START_ALARM"
                putExtras(intent) // Pass all config extras
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}