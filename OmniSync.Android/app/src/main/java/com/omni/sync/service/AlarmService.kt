package com.omni.sync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omni.sync.R
import com.omni.sync.MainActivity
import com.omni.sync.OmniSyncApplication
import com.omni.sync.utils.AlarmScheduler
import com.omni.sync.worker.AlarmAutomationWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AlarmService : Service(), android.content.SharedPreferences.OnSharedPreferenceChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var timeoutJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: android.content.SharedPreferences

    // Metadata for the current running alarm to handle snooze logic
    private var currentAlarmId: Int = 0
    private var currentReminderId: String? = null
    private var currentReminderKind: Int = 0
    private var currentSoundId: String = "gentle"
    private var currentHour: Int = 0
    private var currentMinute: Int = 0
    private var currentIsAM: Boolean = true
    private var currentVolume: Int = 5
    private var currentRepetition: Int = 0
    private var maxRepetitions: Int = 0
    private var volumeIncrement: Int = 5
    private var snoozeDurationMin: Int = 10
    private var repeatDaily: Boolean = false
    private var currentDurationSec: Int = 3
    private var snoozeMessage: String? = null
    private var dismissText: String? = null
    private var macroOnTrigger: String? = null
    private var macroOnDismiss: String? = null
    private var quickActionOnTriggerId: String? = null
    private var quickActionOnDismissId: String? = null
    private var dismissActionDelaySeconds: Int = 0

    companion object {
        const val CHANNEL_ID = "OmniAlarmChannel"
        const val ACTION_DISMISS = "com.omni.sync.ALARM_DISMISS"
        const val ACTION_ALARM_DISABLED = "com.omni.sync.ALARM_DISABLED"
        
        private val _isRinging = MutableStateFlow(false)
        val isRinging: StateFlow<Boolean> = _isRinging

        private val _isSnoozing = MutableStateFlow(false)
        val isSnoozing: StateFlow<Boolean> = _isSnoozing

        var lastTriggeredAlarmId: Int = 0
        var lastTriggeredAlarmRepeating: Boolean = false

        fun init(context: Context) {
            val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
            lastTriggeredAlarmId = prefs.getInt("last_triggered_alarm_id", 0)
            lastTriggeredAlarmRepeating = prefs.getBoolean("last_triggered_alarm_repeating", false)
            _isSnoozing.value = prefs.getBoolean("is_snoozing", false)
            Log.d("AlarmService", "Initialized: lastId=$lastTriggeredAlarmId, snoozing=${_isSnoozing.value}")
        }

        fun updateState(context: Context, isRinging: Boolean? = null, isSnoozing: Boolean? = null, alarmId: Int? = null, repeating: Boolean? = null) {
            val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            isRinging?.let { 
                _isRinging.value = it
            }
            isSnoozing?.let {
                _isSnoozing.value = it
                editor.putBoolean("is_snoozing", it)
            }
            alarmId?.let {
                lastTriggeredAlarmId = it
                editor.putInt("last_triggered_alarm_id", it)
            }
            repeating?.let {
                lastTriggeredAlarmRepeating = it
                editor.putBoolean("last_triggered_alarm_repeating", it)
            }
            editor.apply()
        }

        fun stopAlarm(context: Context, alarmId: Int = 0, repeatDaily: Boolean? = null) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_DISMISS
                // If alarmId is provided, use it, otherwise fallback to last triggered
                val finalId = if (alarmId != 0) alarmId else lastTriggeredAlarmId
                val finalRepeating = repeatDaily ?: lastTriggeredAlarmRepeating
                putExtra("ALARM_ID", finalId)
                putExtra("REPEAT_DAILY", finalRepeating)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OmniSync::AlarmServiceWakeLock"
        )
        wakeLock?.acquire(20 * 60 * 1000L) 
    }

    override fun onSharedPreferenceChanged(sharedPreferences: android.content.SharedPreferences?, key: String?) {
        if (key == "config") {
            val configJson = sharedPreferences?.getString("config", null)
            if (configJson != null) {
                val gson = com.google.gson.Gson()
                val config = gson.fromJson(configJson, com.omni.sync.ui.screen.GradualConfig::class.java)
                maxRepetitions = config.maxRepetitions
                volumeIncrement = config.volumeIncrement
                snoozeDurationMin = config.snoozeDuration
                currentDurationSec = config.alarmDuration
                Log.d("AlarmService", "Gradual config updated immediately: reps=$maxRepetitions, inc=$volumeIncrement, snooze=$snoozeDurationMin")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.omni.sync.REMINDER_STOP") {
            timeoutJob?.cancel()
            stopSound()
            updateState(this, isRinging = false, isSnoozing = false)
            currentReminderId = null
            stopSelf()
            return START_NOT_STICKY
        }
        val intentAlarmId = intent?.getIntExtra("ALARM_ID", 0) ?: 0
        if (intentAlarmId != 0) {
            currentAlarmId = intentAlarmId
        }
        
        // Extract Repeat Daily early so handleDismiss knows about it
        if (intent != null && intent.hasExtra("REPEAT_DAILY")) {
            repeatDaily = intent.getBooleanExtra("REPEAT_DAILY", false)
        }

        if (intent?.action == ACTION_DISMISS) {
            if (currentReminderId == null && currentAlarmId < 0) {
                currentReminderId = com.omni.sync.utils.ReminderDelivery.load(this).firstOrNull {
                    com.omni.sync.utils.ReminderDelivery.requestId(this, it.id) == currentAlarmId
                }?.id
            }
            handleDismiss()
            return START_NOT_STICKY
        }
        currentReminderId = intent?.getStringExtra("REMINDER_ID")
        currentReminderKind = intent?.getIntExtra("REMINDER_KIND", 0) ?: 0

        // Store for global dismissal (e.g. from notification or overlay)
        updateState(this, isRinging = true, isSnoozing = false, alarmId = currentAlarmId, repeating = repeatDaily)

        // Extract Alarm Data (for starting)
        currentSoundId = intent?.getStringExtra("SOUND_ID") ?: "gentle"
        currentHour = intent?.getIntExtra("HOUR", 0) ?: 0
        currentMinute = intent?.getIntExtra("MINUTE", 0) ?: 0
        currentIsAM = intent?.getBooleanExtra("IS_AM", true) ?: true
        currentVolume = intent?.getIntExtra("CURRENT_VOLUME", 5) ?: 5
        currentDurationSec = intent?.getIntExtra("ALARM_DURATION", 3) ?: 3
        
        // Gradual/Snooze Data
        maxRepetitions = intent?.getIntExtra("MAX_REPETITIONS", 5) ?: 5
        volumeIncrement = intent?.getIntExtra("VOLUME_INCREMENT", 5) ?: 5
        snoozeDurationMin = intent?.getIntExtra("SNOOZE_DURATION", 10) ?: 10
        currentRepetition = intent?.getIntExtra("CURRENT_REPETITION", 0) ?: 0
        com.omni.sync.utils.AlarmStatistics.ring(this, currentAlarmId, currentRepetition == 0)
        repeatDaily = intent?.getBooleanExtra("REPEAT_DAILY", false) ?: false
        macroOnTrigger = intent?.getStringExtra("MACRO_ON_TRIGGER")
        macroOnDismiss = intent?.getStringExtra("MACRO_ON_DISMISS")
        quickActionOnTriggerId = intent?.getStringExtra("QUICK_ACTION_ON_TRIGGER_ID")
        quickActionOnDismissId = intent?.getStringExtra("QUICK_ACTION_ON_DISMISS_ID")
        dismissActionDelaySeconds = intent?.getIntExtra("DISMISS_ACTION_DELAY_SECONDS", 0)
            ?.coerceIn(0, 86_400) ?: 0
        dismissText = intent?.getStringExtra("DISMISS_TEXT")

        if (currentRepetition == 0) {
            val triggerMacro = macroOnTrigger?.takeIf { it.isNotBlank() }
            val triggerQuickAction = quickActionOnTriggerId?.takeIf { it.isNotBlank() }
            if (triggerMacro != null) {
                executeAlarmMacro(triggerMacro)
            } else if (triggerQuickAction != null) {
                executeQuickAction(triggerQuickAction)
            }
        }

        // Force open activity
        val alarmActivityIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            this.putExtra("OPEN_SCREEN", "ALARM")
        }
        startActivity(alarmActivityIntent)

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
        
        playAlarm(currentSoundId, currentVolume)
        if (currentReminderId != null) com.omni.sync.utils.ReminderDelivery.log(this, "Alarm service started $currentReminderId kind=$currentReminderKind volume=$currentVolume repetition=$currentRepetition")
        
        // Schedule Auto-Snooze Timeout
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            Log.d("AlarmService", "Alarm playing for $currentDurationSec seconds")
            if (currentReminderId == null || currentReminderKind != 2) {
                delay(currentDurationSec * 1000L)
                handleAutoSnooze()
            }
        }

        return START_STICKY
    }

    private fun playAlarm(soundId: String, volume: Int) {
        try {
            stopSound() // Ensure previous is stopped

            val resId = resources.getIdentifier(soundId, "raw", packageName)
            val uri = if (resId != 0) {
                Uri.parse("android.resource://$packageName/$resId")
            } else {
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true // Loop *during* the X seconds
                prepare()
                
                // Set constant volume (0.0 to 1.0)
                val volFloat = (volume / 100f).coerceIn(0f, 1f)
                setVolume(volFloat, volFloat)
            }
            mediaPlayer?.start()
            Log.d("AlarmService", "Playing alarm $soundId at volume $volume")

        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to play alarm", e)
            stopSelf()
        }
    }

    private fun handleAutoSnooze() {
        currentReminderId?.let { id ->
            stopSound()
            val item = com.omni.sync.utils.ReminderDelivery.load(this).find { it.id == id }
            if (item != null && !item.isTerminal) {
                val nextVolume = (currentVolume + volumeIncrement).coerceAtMost(100)
                com.omni.sync.utils.ReminderDelivery.setAlarm(this, item, System.currentTimeMillis() + snoozeDurationMin * 60_000L, nextVolume, currentRepetition + 1)
                com.omni.sync.utils.ReminderDelivery.log(this, "Gradual reminder $id re-alert scheduled, volume=$nextVolume")
                updateState(this, isRinging = false, isSnoozing = true)
            }
            stopSelf()
            return
        }
        Log.i("AlarmService", "Auto-snooze triggered.")
        stopSound()
        
        if (currentRepetition < maxRepetitions) {
            // Schedule Snooze
            val nextVolume = (currentVolume + volumeIncrement).coerceAtMost(100)
            val nextRepetition = currentRepetition + 1
            
            val triggerTime = System.currentTimeMillis() + (snoozeDurationMin * 60 * 1000L)
            snoozeMessage = "Snoozed until ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}"

            Log.i("AlarmService", "Scheduling snooze: Rep $nextRepetition, Vol $nextVolume in $snoozeDurationMin mins for alarm $currentAlarmId")
            
            // Update states together to prevent UI flickering
            updateState(this, isRinging = false, isSnoozing = true)

            AlarmScheduler.scheduleSnooze(
                context = this,
                alarmId = currentAlarmId,
                soundId = currentSoundId,
                hour = currentHour,
                minute = currentMinute,
                isAM = currentIsAM,
                volume = nextVolume,
                durationSec = currentDurationSec,
                snoozeMin = snoozeDurationMin,
                volIncrement = volumeIncrement,
                maxReps = maxRepetitions,
                repetition = nextRepetition,
                repeatDaily = repeatDaily,
                macroOnTrigger = macroOnTrigger,
                macroOnDismiss = macroOnDismiss,
                quickActionOnTriggerId = quickActionOnTriggerId,
                quickActionOnDismissId = quickActionOnDismissId,
                dismissActionDelaySeconds = dismissActionDelaySeconds,
                dismissText = dismissText
            )
        } else {
            // Max repetitions reached. 
            updateState(this, isRinging = false, isSnoozing = false)
            // If repeatDaily is true, the next day is already scheduled by AlarmScheduler when it first fired (or should be).
            // If repeatDaily is false, we should disable the alarm.
            if (!repeatDaily) {
                disableAlarmState()
            }
        }
        
        stopSelf()
    }

    private fun handleDismiss() {
        currentReminderId?.let { id ->
            timeoutJob?.cancel()
            stopSound()
            updateState(this, isRinging = false, isSnoozing = false)
            currentReminderId = null
            (application as OmniSyncApplication).signalRClient.dismissReminder(id)
            stopSelf()
            return
        }
        com.omni.sync.utils.AlarmStatistics.dismiss(this, currentAlarmId)
        Log.i("AlarmService", "User Dismissed Alarm $currentAlarmId.")
        val app = application as OmniSyncApplication
        // Any dismissed alarm indicates the user is awake. Snoozes and reminders
        // do not end the estimate; reminders return before reaching this branch.
        if (app.mainViewModel.sleepTracker.isSleeping()) {
            app.mainViewModel.resetSleep(source = "alarm $currentAlarmId dismissed")
        }
        stopSound()
        updateState(this, isRinging = false, isSnoozing = false)
        timeoutJob?.cancel()
        snoozeMessage = null

        val dismissMacro = macroOnDismiss?.takeIf { it.isNotBlank() }
        val dismissQuickAction = quickActionOnDismissId?.takeIf { it.isNotBlank() }
        if (dismissMacro != null || dismissQuickAction != null) {
            if (dismissActionDelaySeconds > 0) {
                AlarmAutomationWorker.enqueue(
                    context = this,
                    macroScript = dismissMacro,
                    quickActionId = dismissQuickAction.takeIf { dismissMacro == null },
                    delaySeconds = dismissActionDelaySeconds
                )
                Log.i(
                    "AlarmService",
                    "Dismiss automation scheduled in $dismissActionDelaySeconds seconds."
                )
            } else if (dismissMacro != null) {
                executeAlarmMacro(dismissMacro)
            } else if (dismissQuickAction != null) {
                executeQuickAction(dismissQuickAction)
            }
        }

        // Cancel any pending snoozes for this alarm
        if (currentAlarmId != 0) {
            AlarmScheduler.cancelSnooze(this, currentAlarmId)
        }

        // If not repeating, update UI to disabled
        if (!repeatDaily && currentAlarmId != 0) {
            disableAlarmState()
        }

        stopSelf()
    }

    private fun executeAlarmMacro(script: String) {
        val app = application as? com.omni.sync.OmniSyncApplication ?: return
        val signalRClient = app.signalRClient
        val parser = com.omni.sync.logic.macro.MacroParser()
        val executor = com.omni.sync.logic.macro.MacroExecutor(signalRClient, app.mainViewModel.appConfig.value.macros)
        
        serviceScope.launch {
            executor.execute(parser.parse(script, applicationContext), applicationContext)
        }
    }

    private fun executeQuickAction(actionId: String) {
        val configManager = com.omni.sync.data.config.ConfigManager(this)
        val action = configManager.loadConfig().notificationActions
            .ifEmpty { configManager.getDefaultNotificationActions() }
            .find { it.id == actionId }
            ?: return
        QuickActionDispatcher.execute(this, action)
    }
    
    private fun disableAlarmState() {
        Log.d("AlarmService", "disabling alarm state for ID: $currentAlarmId")
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val alarms = com.omni.sync.utils.AlarmStore.load(prefs, gson)
        val wakeAlarm = alarms.firstOrNull { it.id == currentAlarmId && it.isWakeAlarm }
        if (wakeAlarm != null) {
            com.omni.sync.utils.AlarmStore.save(prefs, gson, alarms.map {
                if (it.id == currentAlarmId) it.copy(nextTriggerOverride = null) else it
            })
            val config = prefs.getString("config", null)?.let { gson.fromJson(it, com.omni.sync.ui.screen.GradualConfig::class.java) } ?: com.omni.sync.ui.screen.GradualConfig()
            AlarmScheduler.scheduleAlarm(this, currentAlarmId, wakeAlarm.copy(nextTriggerOverride = null), config)
            (application as? com.omni.sync.OmniSyncApplication)?.mainViewModel?.addLog(
                "[Wake alarm $currentAlarmId] Automatic disable prevented; daily target retained", com.omni.sync.ui.screen.LogType.INFO)
            return
        }
        val updatedAlarms = alarms.map { alarm ->
            if (alarm.id == currentAlarmId) alarm.copy(enabled = false) else alarm
        }
        if (updatedAlarms != alarms) {
            com.omni.sync.utils.AlarmStore.save(prefs, gson, updatedAlarms)
            Log.d("AlarmService", "Updated alarm $currentAlarmId: enabled=false")
        }

        // Send broadcast to UI to turn off the switch
        val intent = Intent(ACTION_ALARM_DISABLED).apply {
            putExtra("ALARM_ID", currentAlarmId)
        }
        sendBroadcast(intent)
    }

    private fun stopSound() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("AlarmService", "Error stopping sound", e)
        }
    }

    private fun createNotification(): Notification {
        val app = application as OmniSyncApplication
        val mainViewModel = app.mainViewModel
        val isSleeping = mainViewModel.isSleeping.value
        val sleepDuration = mainViewModel.sleepDuration.value

        val dismissIntent = Intent(this, AlarmService::class.java).apply { 
            action = ACTION_DISMISS 
            putExtra("ALARM_ID", currentAlarmId)
            putExtra("REPEAT_DAILY", repeatDaily)
        }
        val pendingDismiss = PendingIntent.getService(this, currentAlarmId, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_SCREEN", "ALARM")
        }
        val pendingFullScreen = PendingIntent.getActivity(this, currentAlarmId, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        var content = if (!dismissText.isNullOrBlank()) dismissText!! else "Volume: $currentVolume% | Tap to Dismiss"
        if (isSleeping) {
            content = "$content (Estimated sleep: $sleepDuration)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alarm")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingFullScreen, true)
            .setContentIntent(pendingFullScreen)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher, "DISMISS", pendingDismiss)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarm Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        _isRinging.value = false
        // isSnoozing is persistent across service restarts until dismissed
        timeoutJob?.cancel()
        stopSound()
        
        prefs.unregisterOnSharedPreferenceChangeListener(this)

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Error releasing wake lock", e)
        }

        // Restore ForegroundService notification
        val refreshIntent = Intent(this, ForegroundService::class.java).apply {
            action = ForegroundService.ACTION_REFRESH_NOTIFICATION
            putExtra(ForegroundService.EXTRA_STATUS_MESSAGE, snoozeMessage)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(refreshIntent)
        } else {
            startService(refreshIntent)
        }

        super.onDestroy()
    }
}
