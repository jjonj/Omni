package com.omni.sync.logic.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AssistantCommandRouterTest {
    @Test
    fun resolvesImmediateWakePc() {
        val command = AssistantCommandRouter.resolve(
            "Turn on my PC",
            nowMillis = 1_000L
        )

        assertNotNull(command)
        assertEquals("wake.pc", command!!.id)
        assertNull(command.runAtMillis)
    }

    @Test
    fun resolvesDelayedWakePcWithGenericTimingSuffix() {
        val now = 10_000L

        val command = AssistantCommandRouter.resolve(
            "Turn on my PC in one hour",
            nowMillis = now
        )

        assertNotNull(command)
        assertEquals("wake.pc", command!!.id)
        assertEquals(now + 3_600_000L, command.runAtMillis)
    }

    @Test
    fun parsesHalfHourSuffixIndependentlyOfCommand() {
        val now = 5_000L

        val parsed = AssistantCommandRouter.parseModifiers(
            "do something in half an hour",
            nowMillis = now
        )

        assertEquals("do something", parsed.commandText)
        assertEquals(now + 1_800_000L, parsed.runAtMillis)
        assertEquals("in half an hour", parsed.modifierText)
    }

    @Test
    fun leavesUnrecognizedCommandForDesktopOrJarvis() {
        val command = AssistantCommandRouter.resolve(
            "play the Tetris movie",
            nowMillis = 1_000L
        )

        assertNull(command)
    }
}
