package com.omni.sync.data.repository

import android.content.Context
import com.omni.sync.viewmodel.MainViewModel
import io.mockk.mockk
import io.mockk.every
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

class SignalRClientReconnectionTest {
    private val context = mockk<Context>(relaxed = true)
    private val mainViewModel = mockk<MainViewModel>(relaxed = true)
    private lateinit var signalRClient: SignalRClient

    @Before
    fun setup() {
        val clipboardManager = mockk<android.content.ClipboardManager>(relaxed = true)
        every { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) } returns clipboardManager
        signalRClient = SignalRClient(context, mainViewModel)
    }

    @Test
    fun `getRetryDelay should return correct intervals`() {
        // Intervals: 0, 10, 30, 60, 120, 1800 repeating
        
        assertEquals(0L, signalRClient.getRetryDelay(0))
        assertEquals(10000L, signalRClient.getRetryDelay(1))
        assertEquals(30000L, signalRClient.getRetryDelay(2))
        assertEquals(60000L, signalRClient.getRetryDelay(3))
        assertEquals(120000L, signalRClient.getRetryDelay(4))
        assertEquals(1800000L, signalRClient.getRetryDelay(5))
        assertEquals(1800000L, signalRClient.getRetryDelay(6))
    }
}