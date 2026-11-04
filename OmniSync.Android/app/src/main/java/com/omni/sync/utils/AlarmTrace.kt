package com.omni.sync.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.omni.sync.ui.screen.AlarmData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reboot-persistent alarm journal readable through Phone Commands: alarm-log. */
object AlarmTrace {
    private const val LIMIT = 2000000L
    @Volatile private var output: File? = null
    private fun timeStamp(v: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(v))
    fun time(v: Long?): String = if (v == null) "none" else timeStamp(v) + " (" + v + ")"
    fun describe(a: AlarmData): String {
        val n = a.resolvedForNextTrigger()
        fun clock(x: AlarmData) = "%02d:%02d".format(Locale.US, x.hour % 12 + if (x.isAM) 0 else 12, x.minute)
        return "id=" + a.id + " enabled=" + a.enabled + " wake=" + a.isWakeAlarm +
            " daily=" + a.repeatDaily + " target=" + clock(a) + " next=" + clock(n) +
            " latest=" + a.latestWakeMinutes + " auto=" + a.autoStillAwake +
            " date=" + time(n.scheduledDate) + " override=" + (a.nextTriggerOverride != null)
    }
    @Synchronized fun init(ctx: Context, reason: String) {
        output = File(ctx.applicationContext.filesDir, "alarm_history.log")
        record("INIT", reason + " uptime=" + SystemClock.elapsedRealtime() +
            " zone=" + java.util.TimeZone.getDefault().id)
    }
    @Synchronized fun record(event: String, info: String) {
        val line = timeStamp(System.currentTimeMillis()) + " uptime=" + SystemClock.elapsedRealtime() +
            " " + event + " " + info + "\n"
        Log.i("AlarmTrace", line.trim())
        try {
            val dest = output ?: return
            if (dest.length() >= LIMIT) {
                val old = File(dest.parentFile, "alarm_history.previous.log")
                old.delete()
                dest.renameTo(old)
            }
            dest.appendText(line)
        } catch (e: Exception) { Log.e("AlarmTrace", "Write failed", e) }
    }
    @Synchronized fun export(ctx: Context): String =
        listOf(File(ctx.filesDir, "alarm_history.previous.log"), File(ctx.filesDir, "alarm_history.log"))
            .filter { it.exists() }.flatMap { it.readLines() }.takeLast(180)
            .joinToString("\n").ifBlank { "No alarm journal yet. Install updated build first." }
}
