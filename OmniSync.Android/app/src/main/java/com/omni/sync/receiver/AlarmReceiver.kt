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
import com.omni.sync.utils.AlarmStore
import com.google.gson.Gson
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
                val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                val gson = Gson()
                val savedAlarm = AlarmStore.load(prefs, gson).firstOrNull { it.id == alarmId }
                // A one-off override must never become tomorrow's recurring alarm.
                val alarmData = savedAlarm?.copy(nextTriggerOverride = null) ?: AlarmData(
                    id = alarmId,
                    enabled = true,
                    hour = intent.getIntExtra("HOUR", 0),
                    minute = intent.getIntExtra("MINUTE", 0),
                    isAM = intent.getBooleanExtra("IS_AM", true),
                    soundId = intent.getStringExtra("SOUND_ID") ?: "gentle",
                    repeatDaily = true
                )
                if (savedAlarm?.nextTriggerOverride != null) {
                    val updated = AlarmStore.load(prefs, gson).map { alarm ->
                        if (alarm.id == alarmId) alarm.copy(nextTriggerOverride = null) else alarm
                    }
                    AlarmStore.save(prefs, gson, updated)
                }
                val config = GradualConfig(
                    initialVolume = intent.getIntExtra("CURRENT_VOLUME", 5),
                    alarmDuration = intent.getIntExtra("ALARM_DURATION", 3),
                    snoozeDuration = intent.getIntExtra("SNOOZE_DURATION", 10),
                    volumeIncrement = intent.getIntExtra("VOLUME_INCREMENT", 5),
                    maxRepetitions = intent.getIntExtra("MAX_REPETITIONS", 6)
                )
                
                AlarmScheduler.scheduleAlarm(context, alarmId, alarmData, config, forceNextDay = true)
                Log.d("AlarmReceiver", "Rescheduled alarm $alarmId for tomorrow.")
            }

            if (!repeatDaily && !isSnooze) {
                val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                val gson = Gson()
                val alarms = AlarmStore.load(prefs, gson)
                if (alarms.any { it.id == alarmId && it.nextTriggerOverride != null }) {
                    AlarmStore.save(prefs, gson, alarms.map { if (it.id == alarmId) it.copy(nextTriggerOverride = null) else it })
                }
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
