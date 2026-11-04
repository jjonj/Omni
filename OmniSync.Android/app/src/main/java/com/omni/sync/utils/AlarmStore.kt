package com.omni.sync.utils

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.ui.screen.AlarmData

object AlarmStore {
    const val ALARMS_KEY = "alarms"

    private val alarmListType = object : TypeToken<List<AlarmData>>() {}.type

    fun load(prefs: SharedPreferences, gson: Gson): List<AlarmData> {
        val stored = prefs.getString(ALARMS_KEY, null)?.let { json ->
            runCatching { gson.fromJson<List<AlarmData>>(json, alarmListType) }.getOrNull()
        }
        val source = stored ?: loadLegacyAlarms(prefs, gson)
        val normalized = normalizeIds(source)

        if (stored == null || stored != normalized) {
            AlarmTrace.record("NORMALIZE", "existingList=" + (stored != null) +
                " " + normalized.joinToString(" | ") { AlarmTrace.describe(it) })
            save(prefs, gson, normalized)
        }
        return normalized
    }

    fun save(prefs: SharedPreferences, gson: Gson, alarms: List<AlarmData>) {
        val success = prefs.edit().putString(ALARMS_KEY, gson.toJson(alarms)).commit()
        AlarmTrace.record("STORE_WRITE", "success=" + success + " " + alarms.joinToString(" | ") { AlarmTrace.describe(it) })
    }

    fun nextId(alarms: List<AlarmData>): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

    private fun loadLegacyAlarms(prefs: SharedPreferences, gson: Gson): List<AlarmData> {
        val first = prefs.getString("alarm1", null)?.let {
            runCatching { gson.fromJson(it, AlarmData::class.java) }.getOrNull()
        } ?: AlarmData(enabled = false, hour = 8, minute = 30, isAM = true)
        val second = prefs.getString("alarm2", null)?.let {
            runCatching { gson.fromJson(it, AlarmData::class.java) }.getOrNull()
        } ?: AlarmData(enabled = false, hour = 9, minute = 0, isAM = true)
        return listOf(first, second)
    }

    private fun normalizeIds(alarms: List<AlarmData>): List<AlarmData> {
        val usedIds = mutableSetOf<Int>()
        var nextId = 1
        return alarms.map { original ->
            val alarm = if (original.isWakeAlarm) original.copy(repeatDaily = true, scheduledDate = null) else original
            val id = if (alarm.id > 0 && usedIds.add(alarm.id)) {
                alarm.id
            } else {
                while (nextId in usedIds) nextId++
                nextId.also { usedIds.add(it) }
            }
            if (alarm.id == id) alarm else alarm.copy(id = id)
        }
    }
}
