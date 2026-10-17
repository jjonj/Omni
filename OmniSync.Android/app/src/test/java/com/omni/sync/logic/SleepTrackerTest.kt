package com.omni.sync.logic

import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class SleepTrackerTest {
    private val data = mutableMapOf<String, Long>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private var now = 1_000_000L
    private val tracker: SleepTracker

    init {
        every { prefs.getLong(any(), any()) } answers { data[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } answers { data[firstArg()] = secondArg(); editor }
        every { editor.remove(any()) } answers { data.remove(firstArg<String>()); editor }
        tracker = SleepTracker(prefs) { now }
    }

    @Test fun repeatedStartDoesNotRestartTheSession() {
        tracker.recordPotentialSleepStart()
        now += 3_600_000
        tracker.recordPotentialSleepStart()
        assertEquals("1h 0m", tracker.getFormattedSleepDuration())
    }

    @Test fun stillAwakeUsesTheLastSignalAndKeepsTrackingActive() {
        tracker.recordPotentialSleepStart()
        now += 3_600_000
        tracker.recordStillAwake()
        now += 30 * 60_000
        assertTrue(tracker.isSleeping())
        assertEquals("30m", tracker.getFormattedSleepDuration())
    }

    @Test fun backgroundActivityDoesNotChangeEstimatedSleep() {
        tracker.recordPotentialSleepStart()
        now += 3_600_000
        tracker.recordActivity()
        assertEquals("1h 0m", tracker.getFormattedSleepDuration())
    }

    @Test fun sessionAndEstimateSurviveTrackerRecreation() {
        tracker.recordPotentialSleepStart()
        now += 3_600_000
        tracker.recordStillAwake()
        now += 2 * 3_600_000
        val restored = SleepTracker(prefs) { now }
        assertTrue(restored.isSleeping())
        assertEquals("2h 0m", restored.getFormattedSleepDuration())
    }

    @Test fun automaticDaytimeActivityDoesNotStartASession() {
        tracker.recordStillAwake(startIfInactive = false)
        assertFalse(tracker.isSleeping())
    }

    @Test fun explicitWakeEndsTheSessionAndFurtherActivityDoesNotRestartIt() {
        tracker.recordPotentialSleepStart()
        now += 3_600_000
        tracker.resetSleep()
        tracker.resetSleep()
        tracker.recordStillAwake(startIfInactive = false)
        assertFalse(tracker.isSleeping())
        assertEquals("Not sleeping", tracker.getFormattedSleepDuration())
    }

    @Test fun clockMovingBackwardsDoesNotShowNegativeSleep() {
        tracker.recordPotentialSleepStart()
        now -= 60_000
        assertEquals("0m", tracker.getFormattedSleepDuration())
    }
}
