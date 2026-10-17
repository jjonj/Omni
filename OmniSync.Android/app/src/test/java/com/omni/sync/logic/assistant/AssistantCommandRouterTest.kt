package com.omni.sync.logic.assistant

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantCommandRouterTest {
    @Test
    fun sharedModifierVectorsStayInSync() {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("assistant/modifier_vectors.json")
        ) { "Shared modifier vectors were not copied into test resources." }

        val vectors = stream.reader().use { reader ->
            JsonParser.parseReader(reader).asJsonArray
        }
        val now = 10_000L

        for (vector in vectors) {
            val item = vector.asJsonObject
            val text = item.get("text").asString
            val expectedCommand = item.get("command").asString
            val delayNode = item.get("delaySeconds")
            val parsed = AssistantCommandRouter.parseModifiers(text, now)

            assertEquals(text, expectedCommand, parsed.commandText)
            if (delayNode == null || delayNode.isJsonNull) {
                assertNull(text, parsed.runAtMillis)
            } else {
                assertEquals(
                    text,
                    now + (delayNode.asDouble * 1_000.0).toLong(),
                    parsed.runAtMillis
                )
            }
        }
    }

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
    fun resolvesNaturalDelayedWakePcPhrases() {
        val now = 10_000L
        val cases = mapOf(
            "Turn on my PC in an hour" to 3_600_000L,
            "Wake my computer in twenty minutes" to 1_200_000L,
            "Boot my PC in 1 hour and 30 minutes" to 5_400_000L,
            "Start my computer in an hour and a half" to 5_400_000L
        )

        for ((text, delay) in cases) {
            val command = AssistantCommandRouter.resolve(text, nowMillis = now)
            assertNotNull(text, command)
            assertEquals(text, "wake.pc", command!!.id)
            assertEquals(text, now + delay, command.runAtMillis)
        }
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
