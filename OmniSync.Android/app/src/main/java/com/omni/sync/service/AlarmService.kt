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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var volumeJob: Job? = null
    private var timeoutJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "OmniAlarmChannel"
        const val ACTION_DISMISS = "com.omni.sync.ALARM_DISMISS"
        
        // Global state for UI to observe
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
        // Suppressing deprecation because for an alarm service ensuring the device wakes up 
        // to show a full-screen notification, this flag is still often required in conjunction with fullScreenIntent.
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OmniSync::AlarmServiceWakeLock"
        )
        wakeLock?.acquire(20 * 60 * 1000L) // 20 mins max safety
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            stopSelf()
            return START_NOT_STICKY
        }

        _isRinging.value = true

        val soundId = intent?.getStringExtra("SOUND_ID") ?: "gentle"
        val initialVolume = intent?.getIntExtra("INITIAL_VOLUME", 5) ?: 5
        val maxVolume = 100
        val rampUpDurationSec = intent?.getIntExtra("ALARM_DURATION", 3) ?: 3
        
        startForeground(999, createNotification())
        playAlarm(soundId, initialVolume, maxVolume, rampUpDurationSec)
        
        // Auto-stop after duration + buffer
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(rampUpDurationSec * 1000L + 10000) // Buffer 10s
            Log.i("AlarmService", "Alarm timeout reached. Stopping.")
            stopSelf()
        }

        return START_STICKY
    }

    private fun playAlarm(soundId: String, initialVol: Int, maxVol: Int, durationSec: Int) {
        try {
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
                isLooping = true
                prepare()
            }

            mediaPlayer?.start()
            startVolumeRamp(initialVol, maxVol, durationSec)

        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to play alarm", e)
            stopSelf()
        }
    }

    private fun startVolumeRamp(startVol: Int, maxVol: Int, durationSec: Int) {
        volumeJob?.cancel()
        volumeJob = serviceScope.launch {
            val steps = 20
            val totalMillis = durationSec * 1000L
            val stepTime = totalMillis / steps
            val volStep = (maxVol - startVol) / steps.toFloat()
            
            var currentVol = startVol.toFloat()
            
            for (i in 0..steps) {
                if (mediaPlayer == null) break
                val logVol = 1f - (kotlin.math.ln(100f - currentVol) / kotlin.math.ln(100f))
                try {
                    val safeVol = logVol.coerceIn(0f, 1f)
                    mediaPlayer?.setVolume(safeVol, safeVol)
                } catch (e: Exception) { break }
                
                currentVol = (currentVol + volStep).coerceAtMost(99f)
                delay(stepTime)
            }
        }
    }

    private fun createNotification(): Notification {
        val dismissIntent = Intent(this, AlarmService::class.java).apply { action = ACTION_DISMISS }
        val pendingDismiss = PendingIntent.getService(this, 0, dismissIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFullScreen = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alarm Ringing")
            .setContentText("Tap to Dismiss")
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
        volumeJob?.cancel()
        timeoutJob?.cancel()
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("AlarmService", "Error cleaning up media player", e)
        }
        mediaPlayer = null
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Error releasing wake lock", e)
        }
        super.onDestroy()
    }
}