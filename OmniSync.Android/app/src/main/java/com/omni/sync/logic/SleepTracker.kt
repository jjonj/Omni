package com.omni.sync.logic

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

class SleepTracker(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sleep_tracker_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_ACTIVE_TIME = "last_active_time"
        private const val KEY_SLEEP_START_TIME = "sleep_start_time"
    }

    /**
     * Call this whenever the user is "active" (app opened, UI interaction, etc.)
     */
    fun recordActivity() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_ACTIVE_TIME, now).apply()
    }

    /**
     * Call this when we suspect sleep might have started (e.g., disconnected from hub late at night)
     */
    fun recordPotentialSleepStart() {
        if (getSleepStartTime() == 0L) {
            prefs.edit().putLong(KEY_SLEEP_START_TIME, System.currentTimeMillis()).apply()
        }
    }

    fun resetSleep() {
        prefs.edit().putLong(KEY_SLEEP_START_TIME, 0L).apply()
        recordActivity()
    }

    fun getSleepStartTime(): Long {
        return prefs.getLong(KEY_SLEEP_START_TIME, 0L)
    }

    fun getLastActiveTime(): Long {
        return prefs.getLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
    }

    fun getFormattedSleepDuration(): String {
        val start = getSleepStartTime()
        if (start == 0L) return "Not sleeping"
        
        val durationMs = System.currentTimeMillis() - start
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
    
    fun isSleeping(): Boolean = getSleepStartTime() != 0L
}
