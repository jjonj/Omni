package com.omni.sync.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.omni.sync.ui.screen.AlarmData
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class AlarmStatisticsTest {
    private fun preferences(): SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { data[firstArg()] as? String ?: secondArg() }
        every { prefs.getLong(any(), any()) } answers { data[firstArg()] as? Long ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { data[firstArg()] as? Int ?: secondArg() }
        every { prefs.contains(any()) } answers { data.containsKey(firstArg()) }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { data[firstArg()] = secondArg<String?>(); editor }
        every { editor.putInt(any(), any()) } answers { data[firstArg()] = secondArg<Int>(); editor }
        every { editor.putLong(any(), any()) } answers { data[firstArg()] = secondArg<Long>(); editor }
        every { editor.remove(any()) } answers { data.remove(firstArg<String>()); editor }
        return prefs
    }

    @Test fun repeatedRingsAreSavedOnceOnDismissal() {
        val context = mockk<Context>()
        val alarms = preferences()
        val statistics = preferences()
        every { context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE) } returns alarms
        every { context.getSharedPreferences("alarm_statistics", Context.MODE_PRIVATE) } returns statistics
        AlarmStore.save(alarms, Gson(), listOf(AlarmData(id = 1), AlarmData(id = 2)))
        AlarmStatistics.ring(context, 1, newOccurrence = true)
        val first = statistics.getLong("first", 0)
        AlarmStatistics.ring(context, 1, newOccurrence = false)
        AlarmStatistics.ring(context, 1, newOccurrence = false)
        AlarmStatistics.dismiss(context, 1)
        AlarmStatistics.dismiss(context, 1)
        val history = AlarmStatistics.load(context)
        assertEquals(1, history.size)
        assertEquals(3, history.single().rings)
        assertEquals(first, history.single().firstRing)
        assertTrue(history.single().dismissedAt >= first)
    }

    @Test fun otherAlarmsDoNotModifyAlarmOneStatistics() {
        val context = mockk<Context>()
        val alarms = preferences()
        val statistics = preferences()
        every { context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE) } returns alarms
        every { context.getSharedPreferences("alarm_statistics", Context.MODE_PRIVATE) } returns statistics
        AlarmStore.save(alarms, Gson(), listOf(AlarmData(id = 1), AlarmData(id = 2)))
        AlarmStatistics.ring(context, 2, newOccurrence = true)
        AlarmStatistics.dismiss(context, 2)
        assertTrue(AlarmStatistics.load(context).isEmpty())
    }
}
