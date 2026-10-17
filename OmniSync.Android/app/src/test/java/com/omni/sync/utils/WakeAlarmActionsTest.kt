package com.omni.sync.utils

import com.google.gson.Gson
import com.omni.sync.ui.screen.AlarmData
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.util.TimeZone

class WakeAlarmActionsTest {
    private fun utc(test: () -> Unit) {
        val original = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); test() } finally { TimeZone.setDefault(original) }
    }
    private fun instant(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun lateSleepGetsEightHoursInsteadOfTheOriginalEarlyTarget() = utc {
        val now = instant("2026-09-29T02:00:00Z")
        assertEquals(instant("2026-09-29T10:00:00Z"), WakeAlarmActions.adjustedTime(AlarmData(hour = 8, minute = 30, isWakeAlarm = true), now))
    }
    @Test fun nearbyTargetIsPreservedWithinTheSleepWindow() = utc {
        val now = instant("2026-09-29T00:15:00Z")
        assertEquals(instant("2026-09-29T08:30:00Z"), WakeAlarmActions.adjustedTime(AlarmData(hour = 8, minute = 30), now))
    }
    @Test fun minimumSleepRoundsUpRatherThanLosingSeconds() = utc {
        val now = instant("2026-09-29T02:00:30Z")
        assertEquals(instant("2026-09-29T10:01:00Z"), WakeAlarmActions.adjustedTime(AlarmData(hour = 8, isWakeAlarm = true), now))
    }
    @Test fun latestWakeTimeCapsTheAdjustment() = utc {
        val now = instant("2026-09-29T05:00:00Z")
        assertEquals(instant("2026-09-29T12:00:00Z"), WakeAlarmActions.adjustedTime(AlarmData(hour = 9), now))
    }
    @Test fun earlyBedtimePreservesTheIdealDailyTarget() = utc {
        val now = instant("2026-09-28T22:00:00Z")
        assertEquals(instant("2026-09-29T09:00:00Z"), WakeAlarmActions.adjustedTime(AlarmData(hour = 9), now))
    }
    @Test fun oneOccurrenceOverridePreservesTheDailyTarget() = utc {
        val base = AlarmData(id = 1, enabled = true, repeatDaily = true, hour = 8, minute = 30, isWakeAlarm = true)
        val changed = base.withNextTriggerOverride(base.copy(hour = 10, minute = 0, scheduledDate = instant("2026-09-29T10:00:00Z")))
        assertEquals(8, changed.hour)
        assertEquals(30, changed.minute)
        assertEquals(10, changed.resolvedForNextTrigger().hour)
        assertTrue(changed.resolvedForNextTrigger().repeatDaily)
        assertEquals(8, changed.copy(nextTriggerOverride = null).resolvedForNextTrigger().hour)
    }
    @Test fun staleOccurrenceCannotTurnOffWakeFeatures() {
        val alarm = AlarmData(enabled = true, isWakeAlarm = true, autoStillAwake = true, latestWakeMinutes = 660,
            nextTriggerOverride = AlarmData(isWakeAlarm = false, autoStillAwake = false, latestWakeMinutes = 720))
        val next = alarm.resolvedForNextTrigger()
        assertTrue(next.isWakeAlarm)
        assertTrue(next.autoStillAwake)
        assertEquals(660, next.latestWakeMinutes)
    }
    @Test fun scheduledOverrideNeverFiresAfterTheLatestLimit() = utc {
        val now = instant("2026-09-29T02:00:00Z")
        val alarm = AlarmData(hour = 9, isWakeAlarm = true, latestWakeMinutes = 660,
            nextTriggerOverride = AlarmData(hour = 1, isAM = false, scheduledDate = instant("2026-09-29T13:00:00Z")))
        assertEquals(instant("2026-09-29T11:00:00Z"), WakeAlarmActions.nextTrigger(alarm, now))
    }
    @Test fun stillAwakeAdjustmentsNeverAccumulateIntoTheTarget() = utc {
        val base = AlarmData(hour = 8, minute = 30, enabled = true, repeatDaily = true, isWakeAlarm = true)
        val changed = base.withNextTriggerOverride(base.copy(hour = 10, minute = 0))
        assertEquals(instant("2026-09-29T11:00:00Z"), WakeAlarmActions.adjustedTime(changed, instant("2026-09-29T03:00:00Z")))
        assertEquals(8, changed.hour)
        assertEquals(30, changed.minute)
    }
    @Test fun legacyAlarmHasNoAutomaticAdjustmentAndUsesNoonAsTheLimit() {
        val legacy = Gson().fromJson("""{"id":1,"hour":8,"minute":30,"isAM":true}""", AlarmData::class.java)
        assertFalse(legacy.isWakeAlarm)
        assertFalse(legacy.autoStillAwake)
        assertEquals(720, legacy.latestWakeMinutes ?: 720)
    }
    @Test fun timezoneOffsetsRepresentTheSameDueInstant() {
        val a = com.omni.sync.data.repository.ReminderData(dueAt = "2026-09-29T10:00:00+02:00")
        val b = com.omni.sync.data.repository.ReminderData(dueAt = "2026-09-29T08:00:00Z")
        assertEquals(ReminderDelivery.fireTime(a), ReminderDelivery.fireTime(b))
    }
}
