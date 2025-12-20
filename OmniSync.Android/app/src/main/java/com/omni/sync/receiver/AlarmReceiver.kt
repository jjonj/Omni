package com.omni.sync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.omni.sync.service.AlarmService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm Received!")
        if (intent.action == "com.omni.sync.ALARM_TRIGGER") {
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                action = "START_ALARM"
                putExtras(intent) // Pass config extras (volume, sound, etc)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}