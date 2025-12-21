package com.omni.sync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omni.sync.R
import android.app.PendingIntent
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.MainActivity
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.model.NotificationAction

import android.widget.RemoteViews

class ForegroundService : Service() {

    private val CHANNEL_ID = "OmniSyncForegroundServiceChannel"
    private var statusMessage: String? = null

    companion object {
        const val ACTION_TRIGGER_NOTIFICATION_ACTION = "com.omni.sync.TRIGGER_ACTION"
        const val EXTRA_ACTION_ID = "extra_action_id"
        const val ACTION_REFRESH_NOTIFICATION = "com.omni.sync.REFRESH_NOTIFICATION"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_NOTIFICATION_ACTION -> {
                val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
                if (actionId != null) {
                    handleNotificationAction(actionId)
                }
            }
            ACTION_REFRESH_NOTIFICATION -> {
                statusMessage = intent.getStringExtra(EXTRA_STATUS_MESSAGE)
                updateNotification()
            }
            AlarmService.ACTION_DISMISS -> {
                statusMessage = null
                AlarmService.stopAlarm(this)
                updateNotification()
            }
        }

        val notification = createNotification()
        startForeground(1, notification)

        return START_STICKY
    }

    private fun handleNotificationAction(actionId: String) {
        val actions = getSavedActions()
        val action = actions.find { it.id == actionId } ?: return
        
        val app = application as OmniSyncApplication
        val signalRClient = app.signalRClient
        val mainViewModel = app.mainViewModel

        if (action.isWol && action.macAddress != null) {
            mainViewModel.sendWakeOnLan(action.macAddress)
        } else if (action.command.startsWith("NAV:")) {
            val screenName = action.command.substring(4)
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_SCREEN", screenName)
            }
            startActivity(intent)
        } else if (action.command.startsWith("NAV_FILE:")) {
            val path = action.command.substring(9)
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_SCREEN", "FILES")
                putExtra("FILE_PATH", path)
            }
            startActivity(intent)
        } else if (action.command.startsWith("ALARM:")) {
            val minutesStr = action.command.substring(6)
            val minutes = minutesStr.toIntOrNull() ?: 0
            if (minutes > 0) {
                // We need AlarmScheduler here. 
                // Since ForegroundService is a service, we can call it.
                // We need the data from SharedPreferences though for gradual config.
                val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                val configJson = prefs.getString("config", null)
                val gson = Gson()
                val config = if (configJson != null) gson.fromJson(configJson, com.omni.sync.ui.screen.GradualConfig::class.java) else com.omni.sync.ui.screen.GradualConfig()
                
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.MINUTE, minutes)
                
                val alarmData = com.omni.sync.ui.screen.AlarmData(
                    enabled = true,
                    hour = cal.get(java.util.Calendar.HOUR_OF_DAY).let { if (it == 0 || it == 12) 12 else it % 12 },
                    minute = cal.get(java.util.Calendar.MINUTE),
                    isAM = cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM,
                    repeatDaily = false
                )
                com.omni.sync.utils.AlarmScheduler.scheduleAlarm(this, 1, alarmData, config)
                // Update prefs so UI reflects it
                prefs.edit().putString("alarm1", gson.toJson(alarmData)).apply()
                mainViewModel.addLog("Quick Alarm set for $minutes minutes from now", com.omni.sync.ui.screen.LogType.SUCCESS)
            }
        } else if (action.command.startsWith("BOOKMARK:")) {
            val url = action.command.substring(9)
            mainViewModel.addLog("Notification: Opening bookmark on PC...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.sendBrowserCommand("Navigate", url, true)
        } else if (action.command.startsWith("BROWSER:")) {
            val cmd = action.command.substring(8)
            mainViewModel.addLog("Notification: Browser $cmd...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.sendBrowserCommand(cmd, "", false)
        } else {
            mainViewModel.addLog("Notification: Triggering ".plus(action.label).plus("..."), com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.executeCommand(action.command)
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getSavedActions(): List<NotificationAction> {
        val prefs = getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("notification_actions", null)
        if (json == null) {
            // Default actions
            return listOf(
                NotificationAction("1", "Shutdown", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
                NotificationAction("2", "Sleep", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
                NotificationAction("3", "TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
                NotificationAction("4", "WOL", "", isWol = true, macAddress = "10FFE0379DAC")
            )
        }
        val type = object : TypeToken<List<NotificationAction>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val actions = getSavedActions()
        
        val customLayout = RemoteViews(packageName, R.layout.notification_layout)
        
        if (statusMessage != null) {
            customLayout.setViewVisibility(R.id.notification_status, android.view.View.VISIBLE)
            customLayout.setTextViewText(R.id.notification_status, statusMessage)
            
            customLayout.setViewVisibility(R.id.btn_dismiss_alarm, android.view.View.VISIBLE)
            val dismissIntent = Intent(this, ForegroundService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
            }
            val pendingDismiss = PendingIntent.getService(this, 999, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            customLayout.setOnClickPendingIntent(R.id.btn_dismiss_alarm, pendingDismiss)
        } else {
            customLayout.setViewVisibility(R.id.notification_status, android.view.View.GONE)
            customLayout.setViewVisibility(R.id.btn_dismiss_alarm, android.view.View.GONE)
        }

        // Hide all buttons initially
        val btnIds = listOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6)
        btnIds.forEach { customLayout.setViewVisibility(it, android.view.View.GONE) }

        actions.take(6).forEachIndexed { index, action ->
            val btnId = btnIds[index]
            customLayout.setViewVisibility(btnId, android.view.View.VISIBLE)
            customLayout.setTextViewText(btnId, action.label)
            
            val triggerIntent = Intent(this, ForegroundService::class.java).apply {
                this.action = ACTION_TRIGGER_NOTIFICATION_ACTION
                putExtra(EXTRA_ACTION_ID, action.id)
            }
            val triggerPendingIntent = PendingIntent.getService(this, action.id.hashCode(), triggerIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            customLayout.setOnClickPendingIntent(btnId, triggerPendingIntent)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCustomContentView(customLayout)
            .setCustomBigContentView(customLayout)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        return builder.build()
    }
}
