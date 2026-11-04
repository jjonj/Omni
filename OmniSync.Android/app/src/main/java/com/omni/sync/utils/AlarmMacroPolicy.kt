package com.omni.sync.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.omni.sync.data.config.ConfigManager
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.ui.screen.AlarmData
import com.omni.sync.ui.screen.GradualConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Alarm actions must reference an existing saved macro, never execute an orphaned script snapshot. */
object AlarmMacroPolicy {
    suspend fun isCurrent(context: Context, client: SignalRClient, script: String): Boolean =
        withContext(Dispatchers.IO) {
            // The Android alarm stores a copy of the script, not a macro ID. Deleting the
            // macro must revoke that copy, even for already scheduled and delayed alarms.
            if (ConfigManager(context).loadConfig().macros.none { it.script == script }) {
                Log.i("AlarmMacroPolicy", "Skipping alarm automation: macro removed locally.")
                return@withContext false
            }
            try {
                // The desktop catalog is authoritative. A stale Android cache must not
                // resurrect macros deleted in OmniHub (nor run when it cannot verify).
                val hubMacros = client.getMacros()
                    ?.timeout(5, TimeUnit.SECONDS)
                    ?.blockingGet()
                val exists = hubMacros?.any { it.script == script } == true
                if (!exists) Log.i("AlarmMacroPolicy", "Skipping alarm automation: macro absent from Hub.")
                exists
            } catch (error: Exception) {
                Log.w("AlarmMacroPolicy", "Skipping alarm automation: cannot verify Hub macro.", error)
                false
            }
        }

    /** Remove deleted macro associations, including next-only overrides and scheduled intents. */
    fun pruneDeleted(context: Context, scripts: Set<String>) {
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val gson = Gson()
        val alarms = AlarmStore.load(prefs, gson)
        fun clear(alarm: AlarmData): AlarmData = alarm.copy(
            macroOnTrigger = alarm.macroOnTrigger?.takeIf { it in scripts },
            macroOnDismiss = alarm.macroOnDismiss?.takeIf { it in scripts },
            nextTriggerOverride = alarm.nextTriggerOverride?.copy(
                macroOnTrigger = alarm.nextTriggerOverride?.macroOnTrigger?.takeIf { it in scripts },
                macroOnDismiss = alarm.nextTriggerOverride?.macroOnDismiss?.takeIf { it in scripts }
            )
        )
        val cleaned = alarms.map(::clear)
        if (cleaned == alarms) return
        AlarmStore.save(prefs, gson, cleaned)
        val config = prefs.getString("config", null)
            ?.let { runCatching { gson.fromJson(it, GradualConfig::class.java) }.getOrNull() }
            ?: GradualConfig()
        cleaned.filter { it.enabled }.forEach { alarm ->
            AlarmScheduler.scheduleAlarm(context, alarm.id, alarm, config)
        }
        Log.i("AlarmMacroPolicy", "Removed orphaned automation from saved alarms.")
    }
}
