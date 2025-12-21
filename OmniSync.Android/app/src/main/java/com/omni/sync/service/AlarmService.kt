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
import com.omni.sync.utils.AlarmScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var timeoutJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    // Metadata for the current running alarm to handle snooze logic
    private var currentAlarmId: Int = 0
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

    companion object {
        const val CHANNEL_ID = "OmniAlarmChannel"
        const val ACTION_DISMISS = "com.omni.sync.ALARM_DISMISS"
        const val ACTION_ALARM_DISABLED = "com.omni.sync.ALARM_DISABLED"
        
        private val _isRinging = MutableStateFlow(false)
        val isRinging: StateFlow<Boolean> = _isRinging

        fun stopAlarm(context: Context) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_DISMISS
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OmniSync::AlarmServiceWakeLock"
        )
        wakeLock?.acquire(20 * 60 * 1000L) 
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            handleDismiss()
            return START_NOT_STICKY
        }

        // Extract Alarm Data
        currentAlarmId = intent?.getIntExtra("ALARM_ID", 0) ?: 0
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
        repeatDaily = intent?.getBooleanExtra("REPEAT_DAILY", false) ?: false

        _isRinging.value = true
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
        
        playAlarm(currentSoundId, currentVolume)
        
        // Schedule Auto-Snooze Timeout
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            Log.d("AlarmService", "Alarm playing for $currentDurationSec seconds")
            delay(currentDurationSec * 1000L)
            handleAutoSnooze()
        }

        return START_STICKY
    }

    private fun playAlarm(soundId: String, volume: Int) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }

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
        Log.i("AlarmService", "Auto-snooze triggered.")
        stopSound()

        if (currentRepetition < maxRepetitions) {
            // Schedule Snooze
            val nextVolume = (currentVolume + volumeIncrement).coerceAtMost(100)
            val nextRepetition = currentRepetition + 1
            
            val triggerTime = System.currentTimeMillis() + (snoozeDurationMin * 60 * 1000L)
            snoozeMessage = "Snoozed until ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}"

            Log.i("AlarmService", "Scheduling snooze: Rep $nextRepetition, Vol $nextVolume in $snoozeDurationMin mins")
            
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
                repeatDaily = repeatDaily
            )
        } else {
            // Max repetitions reached. 
            // If repeatDaily is true, the next day is already scheduled by AlarmScheduler when it first fired (or should be).
            // If repeatDaily is false, we should disable the alarm.
            if (!repeatDaily) {
                disableAlarmState()
            }
        }
        
        stopSelf()
    }

    private fun handleDismiss() {
        Log.i("AlarmService", "User Dismissed Alarm.")
        stopSound()
        timeoutJob?.cancel()

        // Cancel any pending snoozes for this alarm
        AlarmScheduler.cancelSnooze(this, currentAlarmId)

        // If not repeating, update UI to disabled
        if (!repeatDaily) {
            disableAlarmState()
        }

        stopSelf()
    }
    
    private fun disableAlarmState() {
        // Update SharedPreferences
        val prefs = getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val key = if (currentAlarmId == 1) "alarm1" else "alarm2"
        val json = prefs.getString(key, null)
        if (json != null) {
            val gson = com.google.gson.Gson()
            val data = gson.fromJson(json, com.omni.sync.ui.screen.AlarmData::class.java)
            data.enabled = false
            prefs.edit().putString(key, gson.toJson(data)).apply()
        }

        // Send broadcast to UI to turn off the switch
        val intent = Intent(ACTION_ALARM_DISABLED).apply {
            putExtra("ALARM_ID", currentAlarmId)
        }
        sendBroadcast(intent)
    }

    private fun stopSound() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("AlarmService", "Error stopping sound", e)
        }
    }

    private fun createNotification(): Notification {
        val dismissIntent = Intent(this, AlarmService::class.java).apply { action = ACTION_DISMISS }
        val pendingDismiss = PendingIntent.getService(this, 0, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_SCREEN", "ALARM")
        }
        val pendingFullScreen = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alarm")
            .setContentText("Volume: $currentVolume% | Tap to Dismiss")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingFullScreen, true)
            .setContentIntent(pendingFullScreen)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher, "DISMISS", pendingDismiss)
            .setDeleteIntent(pendingDismiss)
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
        timeoutJob?.cancel()
        stopSound()
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