package com.omni.sync.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object AlarmStatistics {
    data class Session(val firstRing: Long, val rings: Int, val dismissedAt: Long)
    private val gson = Gson()
    private fun prefs(context: Context) = context.getSharedPreferences("alarm_statistics", Context.MODE_PRIVATE)
    fun load(context: Context): List<Session> = runCatching {
        gson.fromJson<List<Session>>(prefs(context).getString("history", "[]"), object : TypeToken<List<Session>>() {}.type)
    }.getOrDefault(emptyList())

    @Synchronized
    fun ring(context: Context, alarmId: Int, newOccurrence: Boolean) {
        val alarms = AlarmStore.load(context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE), gson)
        if (alarmId != alarms.firstOrNull()?.id) return
        val prefs = prefs(context)
        val first = if (newOccurrence || !prefs.contains("first")) System.currentTimeMillis() else prefs.getLong("first", 0)
        val rings = if (newOccurrence) 1 else prefs.getInt("rings", 0) + 1
        prefs.edit().putInt("alarmId", alarmId).putLong("first", first).putInt("rings", rings).apply()
    }

    @Synchronized
    fun dismiss(context: Context, alarmId: Int) {
        val prefs = prefs(context)
        if (!prefs.contains("first") || prefs.getInt("alarmId", 0) != alarmId) return
        val session = Session(prefs.getLong("first", 0), prefs.getInt("rings", 0), System.currentTimeMillis())
        prefs.edit().putString("history", gson.toJson(load(context) + session)).remove("first").remove("rings").remove("alarmId").apply()
    }
}
