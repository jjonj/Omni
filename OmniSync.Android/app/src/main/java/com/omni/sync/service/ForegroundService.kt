package com.omni.sync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omni.sync.R
import android.app.PendingIntent
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.MainActivity
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.model.NotificationAction
import android.content.BroadcastReceiver
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope

class ForegroundService : Service() {

    private val CHANNEL_ID = "OmniSyncForegroundServiceChannel"
    private var statusMessage: String? = null
    private var isActionsSuppressedBySleep = true
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = application as OmniSyncApplication
            val mainViewModel = app.mainViewModel
            
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    // Connected to charger, might be going to sleep if late? 
                    // Or just recording activity.
                    mainViewModel.recordActivity()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    mainViewModel.recordActivity()
                }
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_NOTIFICATION_ACTION = "com.omni.sync.TRIGGER_ACTION"
        const val ACTION_TRIGGER_SMART_AI = "com.omni.sync.TRIGGER_SMART_AI"
        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_FROM_WIDGET = "extra_from_widget"
        const val ACTION_REFRESH_NOTIFICATION = "com.omni.sync.REFRESH_NOTIFICATION"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val ACTION_SHOW_ACTIONS = "com.omni.sync.SHOW_ACTIONS"
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("ForegroundService", "Creating foreground service process=${android.os.Process.myPid()}")
        createNotificationChannel()
        
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)

        val app = application as OmniSyncApplication
        Log.i("AndroidDeploy", "Foreground service ensuring SignalR deployment channel is connected.")
        app.signalRClient.startConnection()
        startNotificationRefresh()
    }

    private fun startNotificationRefresh() {
        val app = application as OmniSyncApplication
        refreshJob = app.mainViewModel.viewModelScope.launch {
            while (true) {
                delay(60000) // Refresh every minute
                if (app.signalRClient.connectionState.value != "Connected") {
                    Log.w(
                        "AndroidDeploy",
                        "Foreground service found deployment channel disconnected; reconnecting."
                    )
                    app.signalRClient.startConnection()
                }
                updateNotification()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        refreshJob?.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            "ForegroundService",
            "Starting foreground service action=${intent?.action ?: "default"} flags=$flags startId=$startId"
        )
        when (intent?.action) {
            ACTION_TRIGGER_NOTIFICATION_ACTION -> {
                val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
                if (actionId != null) {
                    handleNotificationAction(actionId, intent.getBooleanExtra(EXTRA_FROM_WIDGET, false))
                }
            }
            ACTION_TRIGGER_SMART_AI -> {
                handleSmartAiAction(intent.getBooleanExtra(EXTRA_FROM_WIDGET, false))
            }
            ACTION_REFRESH_NOTIFICATION -> {
                statusMessage = intent.getStringExtra(EXTRA_STATUS_MESSAGE)
                updateNotification()
            }
            ACTION_SHOW_ACTIONS -> {
                isActionsSuppressedBySleep = false
                updateNotification()
            }
            AlarmService.ACTION_DISMISS -> {
                statusMessage = null
                AlarmService.stopAlarm(this)
                updateNotification()
            }
        }

        val notification = createNotification()
        Log.i("ForegroundService", "Posting initial foreground notification id=1")
        startForeground(1, notification)

        return START_STICKY
    }

    private fun handleSmartAiAction(fromWidget: Boolean) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            if (fromWidget) showWidgetFeedback("AI Clip: clipboard is empty")
            return
        }
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                val app = application as OmniSyncApplication
                val signalRClient = app.signalRClient
                val mainViewModel = app.mainViewModel
                
                mainViewModel.addLog("Triggering Smart AI Analysis...", com.omni.sync.ui.screen.LogType.INFO)
                
                val prompt = "Summarize and analyze the following content from my clipboard:\n\n$text"
                
                if (signalRClient.connectionState.value != "Connected") {
                    if (fromWidget) showWidgetFeedback("AI Clip: Hub disconnected; not sent")
                    return
                }
                if (!signalRClient.legacyAiEnabled.value && signalRClient.selectedT3Id.value == null) {
                    if (fromWidget) showWidgetFeedback("AI Clip: choose a T3 session on the AI screen first")
                    return
                }
                signalRClient.sendAiMessage(prompt)
                
                // Show a toast for feedback
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    android.widget.Toast.makeText(this, "Sent to AI for analysis", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                // Optionally navigate to AI screen
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("OPEN_SCREEN", "AGENTS")
                }
                startActivity(intent)
            } else if (fromWidget) {
                showWidgetFeedback("AI Clip: clipboard is empty")
            }
        }
    }

    private fun handleNotificationAction(actionId: String, fromWidget: Boolean) {
        val actions = getSavedActions()
        val action = actions.find { it.id == actionId }
            ?: com.omni.sync.data.config.ConfigManager(this).getPredefinedActions().find { it.id == actionId }
        if (action == null) {
            if (fromWidget) showWidgetFeedback("Widget action unavailable; reconfigure this widget")
            return
        }
        QuickActionDispatcher.execute(this, action)
        if (fromWidget) {
            val detail = when {
                action.command == "STILL_AWAKE" -> com.omni.sync.utils.WakeAlarmActions.nextWakeAlarmSummary(this)
                action.command.startsWith("ALARM:") -> com.omni.sync.utils.WakeAlarmActions.nextAlarmSummary(this)
                else -> null
            }
            showWidgetFeedback("Received: ${action.label}" + (detail?.let { "\n$it" } ?: ""))
        }
    }

    private fun showWidgetFeedback(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Log.d("ForegroundService", "Refreshing foreground notification id=1")
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
        val configManager = com.omni.sync.data.config.ConfigManager(this)
        val config = configManager.loadConfig()
        
        if (config.notificationActions.isEmpty()) {
            // Default actions
            return configManager.getDefaultNotificationActions()
        }
        return config.notificationActions
    }

    private fun createNotification(): Notification {
        val app = application as OmniSyncApplication
        val mainViewModel = app.mainViewModel
        val isSleeping = mainViewModel.isSleeping.value
        val sleepDuration = mainViewModel.sleepDuration.value

        if (!isSleeping) {
            isActionsSuppressedBySleep = true
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val displayStatus = when {
            statusMessage != null && isSleeping -> "$statusMessage (Asleep for $sleepDuration)"
            statusMessage != null -> statusMessage
            isSleeping -> "Estimated sleep: $sleepDuration"
            else -> "Connected and ready"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(displayStatus)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (statusMessage != null) {
            val dismissIntent = Intent(this, ForegroundService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
            }
            val pendingDismiss = PendingIntent.getService(
                this,
                999,
                dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Dismiss", pendingDismiss)
        } else if (isSleeping && isActionsSuppressedBySleep) {
            val showActionsIntent = Intent(this, ForegroundService::class.java).apply {
                action = ACTION_SHOW_ACTIONS
            }
            val pendingShow = PendingIntent.getService(
                this,
                1001,
                showActionsIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Show actions", pendingShow)
        }

        if (!isSleeping || !isActionsSuppressedBySleep || statusMessage != null) {
            getSavedActions().take(6).forEach { action ->
                val triggerIntent = Intent(this, ForegroundService::class.java).apply {
                    this.action = ACTION_TRIGGER_NOTIFICATION_ACTION
                    putExtra(EXTRA_ACTION_ID, action.id)
                }
                val triggerPendingIntent = PendingIntent.getService(
                    this,
                    action.id.hashCode(),
                    triggerIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, action.label, triggerPendingIntent)
            }
        }

        return builder.build()
    }
}
