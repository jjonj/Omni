package com.omni.sync.logic.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanSenderTest {
    @Test fun extractsHostWithoutChangingWolPort() {
        listOf("203.0.113.10", "203.0.113.10:5000", "http://203.0.113.10:5000/signalrhub",
            " HTTPS://203.0.113.10:9443/signalrhub?x=1 ", "203.0.113.10:5000/signalrhub").forEach {
            assertEquals(it, "203.0.113.10", WakeOnLanSender.remoteHost(it))
        }
        assertEquals("home.example", WakeOnLanSender.remoteHost("https://home.example/hub"))
    }

    @Test fun rejectsInvalidWanAddresses() {
        listOf("", "http://", "not a host", "ftp://home.example", "http://user@home.example").forEach {
            assertNull(it, WakeOnLanSender.remoteHost(it))
        }
    }
}