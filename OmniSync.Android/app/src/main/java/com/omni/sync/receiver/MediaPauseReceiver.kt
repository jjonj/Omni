package com.omni.sync.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.google.gson.Gson
import com.omni.sync.utils.AlarmStore
import com.omni.sync.utils.WakeAlarmActions

/**
 * Manual Still Awake pauses after a fresh delay. Wake alarms independently schedule up to
 * three pauses at 7h30, 7h and 6h30 before their next trigger.
 */
object MediaPauseScheduler {
    private const val REQUEST_CODE = 11804
    private const val WAKE_ACTION = "com.omni.sync.WAKE_MEDIA_PAUSE"
    private const val HALF_HOUR = 30 * 60_000L
    private const val FIRST_OFFSET = 450 * 60_000L
    private const val PAUSE_COUNT = 3

    private fun manualPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, MediaPauseReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun wakePendingIntent(context: Context, alarmId: Int, slot: Int, wakeAt: Long): PendingIntent {
        val intent = Intent(context, MediaPauseReceiver::class.java).apply {
            action = WAKE_ACTION
            // PendingIntent identity ignores extras; data separates every wake/slot and manual timer.
            data = Uri.parse("omni://wake-media-pause/$alarmId/$slot")
            putExtra("WAKE_ALARM_ID", alarmId)
            putExtra("WAKE_AT", wakeAt)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setPauseAt(context: Context, at: Long, pending: PendingIntent) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** Restarts only the manual timer; never changes the three wake-alarm pauses. */
    fun schedule(context: Context, minutes: Int) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = manualPendingIntent(context)
        alarms.cancel(pending)
        if (minutes > 0) setPauseAt(context, System.currentTimeMillis() + minutes * 60_000L, pending)
    }

    /** Replaces this wake alarm's three slots whenever its next trigger is edited or restored. */
    fun scheduleWakePauses(context: Context, alarmId: Int, wakeAt: Long) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        for (slot in 0 until PAUSE_COUNT) {
            val pending = wakePendingIntent(context, alarmId, slot, wakeAt)
            alarms.cancel(pending)
            val fireAt = wakeAt - FIRST_OFFSET + slot * HALF_HOUR
            if (fireAt > now) setPauseAt(context, fireAt, pending)
        }
    }

    fun cancelWakePauses(context: Context, alarmId: Int) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (slot in 0 until PAUSE_COUNT) {
            alarms.cancel(wakePendingIntent(context, alarmId, slot, 0L))
        }
    }

    /** Ignore late/stale deliveries if the wake alarm was moved or turned off. */
    fun isCurrentWakePause(context: Context, intent: Intent): Boolean {
        if (intent.action != WAKE_ACTION) return true
        val id = intent.getIntExtra("WAKE_ALARM_ID", -1)
        val scheduledWake = intent.getLongExtra("WAKE_AT", -1L)
        if (id < 0 || scheduledWake <= 0) return false
        val alarm = AlarmStore.load(context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE), Gson())
            .firstOrNull { it.id == id && it.enabled && it.isWakeAlarm } ?: return false
        return WakeAlarmActions.nextTrigger(alarm, System.currentTimeMillis()) == scheduledWake
    }
}

/** Send PAUSE (never PLAY_PAUSE, which could restart already paused playback). */
class MediaPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!MediaPauseScheduler.isCurrentWakePause(context, intent)) return
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {
            Log.e("OmniMediaPause", "Scheduled media pause failed", e)
        }
    }
}
