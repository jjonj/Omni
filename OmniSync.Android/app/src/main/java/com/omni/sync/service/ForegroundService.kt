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

    companion object {
        const val ACTION_TRIGGER_NOTIFICATION_ACTION = "com.omni.sync.TRIGGER_ACTION"
        const val EXTRA_ACTION_ID = "extra_action_id"
        const val ACTION_REFRESH_NOTIFICATION = "com.omni.sync.REFRESH_NOTIFICATION"
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
