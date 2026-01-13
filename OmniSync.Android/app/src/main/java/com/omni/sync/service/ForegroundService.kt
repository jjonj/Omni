package com.omni.sync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
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
import android.content.BroadcastReceiver
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope

import android.widget.RemoteViews

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
        const val ACTION_REFRESH_NOTIFICATION = "com.omni.sync.REFRESH_NOTIFICATION"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val ACTION_SHOW_ACTIONS = "com.omni.sync.SHOW_ACTIONS"
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
        
        startNotificationRefresh()
    }

    private fun startNotificationRefresh() {
        val app = application as OmniSyncApplication
        refreshJob = app.mainViewModel.viewModelScope.launch {
            while (true) {
                delay(60000) // Refresh every minute
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
        when (intent?.action) {
            ACTION_TRIGGER_NOTIFICATION_ACTION -> {
                val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
                if (actionId != null) {
                    handleNotificationAction(actionId)
                }
            }
            ACTION_TRIGGER_SMART_AI -> {
                handleSmartAiAction()
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
        startForeground(1, notification)

        return START_STICKY
    }

    private fun handleSmartAiAction() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                val app = application as OmniSyncApplication
                val signalRClient = app.signalRClient
                val mainViewModel = app.mainViewModel
                
                mainViewModel.addLog("Triggering Smart AI Analysis...", com.omni.sync.ui.screen.LogType.INFO)
                
                val prompt = "Summarize and analyze the following content from my clipboard:\n\n$text"
                
                // Ensure we are connected and maybe auto-launch session if needed
                signalRClient.sendAiMessage(prompt)
                
                // Show a toast for feedback
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    android.widget.Toast.makeText(this, "Sent to AI for analysis", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                // Optionally navigate to AI screen
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("OPEN_SCREEN", "AI_CHAT")
                }
                startActivity(intent)
            }
        }
    }

    private fun handleNotificationAction(actionId: String) {
        val actions = getSavedActions()
        val action = actions.find { it.id == actionId } ?: return
        
        val app = application as OmniSyncApplication
        val signalRClient = app.signalRClient
        val mainViewModel = app.mainViewModel

        if (action.isTellPc) {
            mainViewModel.addLog("Notification: Triggering Tell PC...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.triggerTellPc()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } else if (action.isWol && action.macAddress != null) {
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
        } else if (action.command.startsWith("MACRO:")) {
            val script = action.command.substring(6)
            mainViewModel.addLog("Notification: Running macro...", com.omni.sync.ui.screen.LogType.INFO)
            val parser = com.omni.sync.logic.macro.MacroParser()
            val executor = com.omni.sync.logic.macro.MacroExecutor(signalRClient, app.mainViewModel.appConfig.macros)
            mainViewModel.viewModelScope.launch {
                executor.execute(parser.parse(script, applicationContext), applicationContext)
            }
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
        val configManager = com.omni.sync.data.config.ConfigManager(this)
        val config = configManager.loadConfig()
        
        if (config.notificationActions.isEmpty()) {
            // Default actions
            return listOf(
                NotificationAction("1", "Shutdown", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
                NotificationAction("2", "Sleep", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
                NotificationAction("3", "TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
                NotificationAction("4", "WOL", "", isWol = true, macAddress = "10FFE0379DAC")
            )
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

        val actions = getSavedActions()
        
        val customLayout = RemoteViews(packageName, R.layout.notification_layout)
        
        // Handle click on background to open app
        customLayout.setOnClickPendingIntent(R.id.notification_root, pendingIntent)
        
        if (statusMessage != null) {
            customLayout.setViewVisibility(R.id.notification_status, android.view.View.VISIBLE)
            customLayout.setTextViewText(R.id.notification_status, statusMessage)
            
            customLayout.setViewVisibility(R.id.btn_dismiss_alarm, android.view.View.VISIBLE)
            val dismissIntent = Intent(this, ForegroundService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
            }
            val pendingDismiss = PendingIntent.getService(this, 999, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            customLayout.setOnClickPendingIntent(R.id.btn_dismiss_alarm, pendingDismiss)
            
            customLayout.setViewVisibility(R.id.btn_sleep_time, android.view.View.GONE)
        } else if (isSleeping) {
            customLayout.setViewVisibility(R.id.notification_status, android.view.View.VISIBLE)
            customLayout.setTextViewText(R.id.notification_status, "Asleep for $sleepDuration")
            customLayout.setViewVisibility(R.id.btn_dismiss_alarm, android.view.View.GONE)
            
            if (isActionsSuppressedBySleep) {
                customLayout.setViewVisibility(R.id.btn_sleep_time, android.view.View.VISIBLE)
                customLayout.setTextViewText(R.id.btn_sleep_time, "Asleep for $sleepDuration")
                
                val showActionsIntent = Intent(this, ForegroundService::class.java).apply {
                    action = ACTION_SHOW_ACTIONS
                }
                val pendingShow = PendingIntent.getService(this, 1001, showActionsIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                customLayout.setOnClickPendingIntent(R.id.btn_sleep_time, pendingShow)
            } else {
                customLayout.setViewVisibility(R.id.btn_sleep_time, android.view.View.GONE)
            }
        } else {
            customLayout.setViewVisibility(R.id.notification_status, android.view.View.GONE)
            customLayout.setViewVisibility(R.id.btn_dismiss_alarm, android.view.View.GONE)
            customLayout.setViewVisibility(R.id.btn_sleep_time, android.view.View.GONE)
        }

        // Hide all buttons initially
        val btnIds = listOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6)
        btnIds.forEach { customLayout.setViewVisibility(it, android.view.View.GONE) }

        if (!isSleeping || !isActionsSuppressedBySleep || statusMessage != null) {
            customLayout.setViewVisibility(R.id.notification_button_container, android.view.View.VISIBLE)
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
        } else {
            customLayout.setViewVisibility(R.id.notification_button_container, android.view.View.GONE)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCustomContentView(customLayout)

        return builder.build()
    }
}
