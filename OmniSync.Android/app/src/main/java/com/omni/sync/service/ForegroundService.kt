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
import com.omni.sync.MainActivity
import com.omni.sync.OmniSyncApplication

class ForegroundService : Service() {

    private val CHANNEL_ID = "OmniSyncForegroundServiceChannel"

    companion object {
        const val ACTION_SHUTDOWN = "com.omni.sync.ACTION_SHUTDOWN"
        const val ACTION_SLEEP = "com.omni.sync.ACTION_SLEEP"
        const val ACTION_TV = "com.omni.sync.ACTION_TV"
        const val ACTION_WOL = "com.omni.sync.ACTION_WOL"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            handleAction(action)
        }

        val notification = createNotification()
        startForeground(1, notification)

        return START_STICKY
    }

    private fun handleAction(action: String) {
        val app = application as OmniSyncApplication
        val signalRClient = app.signalRClient
        val mainViewModel = app.mainViewModel

        when (action) {
            ACTION_SHUTDOWN -> {
                mainViewModel.addLog("Notification: Triggering Shutdown...", com.omni.sync.ui.screen.LogType.WARNING)
                signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\shutdown.bat")
            }
            ACTION_SLEEP -> {
                mainViewModel.addLog("Notification: Triggering Sleep...", com.omni.sync.ui.screen.LogType.INFO)
                signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\sleep.bat")
            }
            ACTION_TV -> {
                mainViewModel.addLog("Notification: Toggling TV...", com.omni.sync.ui.screen.LogType.INFO)
                signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat")
            }
            ACTION_WOL -> {
                mainViewModel.sendWakeOnLan("10FFE0379DAC", "10.0.0.255", 9)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound on every update
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val shutdownIntent = Intent(this, ForegroundService::class.java).apply { action = ACTION_SHUTDOWN }
        val shutdownPendingIntent = PendingIntent.getService(this, 1, shutdownIntent, PendingIntent.FLAG_IMMUTABLE)

        val sleepIntent = Intent(this, ForegroundService::class.java).apply { action = ACTION_SLEEP }
        val sleepPendingIntent = PendingIntent.getService(this, 2, sleepIntent, PendingIntent.FLAG_IMMUTABLE)

        val tvIntent = Intent(this, ForegroundService::class.java).apply { action = ACTION_TV }
        val tvPendingIntent = PendingIntent.getService(this, 3, tvIntent, PendingIntent.FLAG_IMMUTABLE)

        val wolIntent = Intent(this, ForegroundService::class.java).apply { action = ACTION_WOL }
        val wolPendingIntent = PendingIntent.getService(this, 4, wolIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniSync is Active")
            .setContentText("Quick actions available below.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.action_shutdown), shutdownPendingIntent)
            .addAction(0, getString(R.string.action_sleep), sleepPendingIntent)
            .addAction(0, getString(R.string.action_tv), tvPendingIntent)
            .addAction(0, getString(R.string.action_wol), wolPendingIntent)
            .setOngoing(true)
            .build()
    }
}
