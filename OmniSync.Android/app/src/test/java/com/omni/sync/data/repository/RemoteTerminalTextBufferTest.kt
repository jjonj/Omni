package com.omni.sync.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTerminalTextBufferTest {
    @Test
    fun preservesUtf8CharactersSplitAcrossFrames() {
        val buffer = RemoteTerminalTextBuffer(100)
        val euro = "€".toByteArray(Charsets.UTF_8)

        assertEquals("", buffer.append(euro.copyOfRange(0, 2)))
        assertEquals("€", buffer.append(euro.copyOfRange(2, 3)))
    }

    @Test
    fun boundsScrollbackByCharacterCount() {
        val buffer = RemoteTerminalTextBuffer(5)

        buffer.append("hello".toByteArray())

        assertEquals("world", buffer.append(" world".toByteArray()))
    }

    @Test
    fun clearDropsTextAndPendingUtf8Bytes() {
        val buffer = RemoteTerminalTextBuffer(100)
        val euro = "€".toByteArray(Charsets.UTF_8)
        buffer.append(euro.copyOfRange(0, 2))

        buffer.clear()

        assertEquals("x", buffer.append("x".toByteArray()))
    }
}
