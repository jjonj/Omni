package com.omni.sync.logic

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

class SleepTracker(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(context.getSharedPreferences("sleep_tracker_prefs", Context.MODE_PRIVATE))

    companion object {
        private const val KEY_LAST_ACTIVE_TIME = "last_active_time"
        private const val KEY_SLEEP_START_TIME = "sleep_start_time"
    }

    @Synchronized
    fun recordActivity() {
        prefs.edit().putLong(KEY_LAST_ACTIVE_TIME, clock()).apply()
    }

    @Synchronized
    fun recordPotentialSleepStart() {
        if (getSleepStartTime() == 0L) {
            prefs.edit().putLong(KEY_SLEEP_START_TIME, clock()).apply()
        }
    }

    /** Phone activity moves the estimate forward without ending an active sleep session. */
    @Synchronized
    fun recordStillAwake(startIfInactive: Boolean = true) {
        val now = clock()
        val editor = prefs.edit().putLong(KEY_LAST_ACTIVE_TIME, now)
        if (isSleeping() || startIfInactive) editor.putLong(KEY_SLEEP_START_TIME, now)
        editor.apply()
    }

    @Synchronized
    fun resetSleep() {
        prefs.edit().putLong(KEY_SLEEP_START_TIME, 0L).putLong(KEY_LAST_ACTIVE_TIME, clock()).remove("pause_time").apply()
    }

    fun getSleepStartTime(): Long {
        return prefs.getLong(KEY_SLEEP_START_TIME, 0L)
    }

    fun getLastActiveTime(): Long {
        return prefs.getLong(KEY_LAST_ACTIVE_TIME, clock())
    }

    fun getFormattedSleepDuration(): String {
        val start = getSleepStartTime()
        if (start == 0L) return "Not sleeping"
        
        val durationMs = (clock() - start).coerceAtLeast(0)
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
