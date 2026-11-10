package com.omni.sync.logic.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.receiver.AssistantCommandReceiver
import java.util.UUID

data class ScheduledAssistantRun(
    val runId: String,
    val commandId: String,
    val runAtMillis: Long,
    val createdAtMillis: Long,
    val origin: String
)

object AssistantScheduleStore {
    private const val PREFS = "assistant_schedule_store"
    private const val KEY_RUNS = "runs"
    private const val WAKE_MAX_DELAY_MILLIS = 15 * 60 * 1000L
    private val gson = Gson()
    private val runType = object : TypeToken<MutableList<ScheduledAssistantRun>>() {}.type

    @Synchronized
    fun add(
        context: Context,
        commandId: String,
        runAtMillis: Long,
        origin: String,
        runId: String = UUID.randomUUID().toString()
    ): ScheduledAssistantRun {
        val run = ScheduledAssistantRun(
            runId = runId,
            commandId = commandId,
            runAtMillis = runAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            origin = origin
        )
        val runs = load(context)
        runs.removeAll { it.runId == run.runId }
        runs.add(run)
        persist(context, runs)
        setAlarm(context, run)
        return run
    }

    @Synchronized
    fun take(context: Context, runId: String): ScheduledAssistantRun? {
        val runs = load(context)
        val run = runs.firstOrNull { it.runId == runId } ?: return null
        runs.removeAll { it.runId == runId }
        persist(context, runs)
        return run
    }

    @Synchronized
    fun cancel(context: Context, runId: String): Boolean {
        val runs = load(context)
        val run = runs.firstOrNull { it.runId == runId } ?: return false
        cancelAlarm(context, run)
        runs.removeAll { it.runId == runId }
        persist(context, runs)
        return true
    }
    @Synchronized
    fun restore(context: Context) {
        val now = System.currentTimeMillis()
        val runs = load(context)
        val toRestore = mutableListOf<ScheduledAssistantRun>()

        for (run in runs) {
            if (run.runAtMillis > now) {
                toRestore += run
                continue
            }

            val lateness = now - run.runAtMillis
            if (lateness <= maxDelayMillis(run.commandId)) {
                // Persist before scheduling an already-due alarm; AlarmManager
                // will deliver a past-due timestamp immediately.
                toRestore += run
            } else {
                AssistantFeedback.notify(
                    context,
                    "Jarvis command missed",
                    "Skipped ${run.commandId}; it was ${lateness / 60_000} minutes late.",
                    run.runId
                )
            }
        }

        persist(context, toRestore)
        toRestore.forEach { run -> setAlarm(context, run) }
    }

    fun maxDelayMillis(commandId: String): Long = when (commandId) {
        "wake.pc" -> WAKE_MAX_DELAY_MILLIS
        else -> 10 * 60 * 1000L
    }

    private fun load(context: Context): MutableList<ScheduledAssistantRun> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RUNS, null)
            ?: return mutableListOf()
        return try {
            gson.fromJson<MutableList<ScheduledAssistantRun>>(json, runType)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persist(
        context: Context,
        runs: List<ScheduledAssistantRun>
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RUNS, gson.toJson(runs))
            .commit()
    }

    private fun setAlarm(context: Context, run: ScheduledAssistantRun) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            run.runId.hashCode(),
            executionIntent(context, run),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (run.commandId == "wake.pc") {
            // Alarm-clock alarms are exact even when SCHEDULE_EXACT_ALARM is denied.
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(run.runAtMillis, pendingIntent), pendingIntent
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    run.runAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    run.runAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                run.runAtMillis,
                pendingIntent
            )
        }
    }
    private fun cancelAlarm(context: Context, run: ScheduledAssistantRun) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            run.runId.hashCode(),
            executionIntent(context, run),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun executionIntent(
        context: Context,
        run: ScheduledAssistantRun
    ) = Intent(context, AssistantCommandReceiver::class.java).apply {
        action = AssistantCommandReceiver.ACTION_EXECUTE
        data = Uri.parse("omni://assistant/run/${run.runId}")
        putExtra(AssistantCommandReceiver.EXTRA_RUN_ID, run.runId)
        putExtra(AssistantCommandReceiver.EXTRA_COMMAND_ID, run.commandId)
    }
}
