package com.omni.sync.data.repository

import android.content.Context
import com.omni.sync.data.config.AppConfig
import com.omni.sync.viewmodel.MainViewModel
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SignalRClientReconnectionTest {
    private val context = mockk<Context>(relaxed = true)
    private val mainViewModel = mockk<MainViewModel>(relaxed = true)
    private lateinit var signalRClient: SignalRClient

    @Before
    fun setup() {
        val clipboardManager = mockk<android.content.ClipboardManager>(relaxed = true)
        every { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { mainViewModel.appConfig } returns MutableStateFlow(AppConfig())
        signalRClient = SignalRClient(context, mainViewModel)
    }

    @Test
    fun `getRetryDelay should use capped exponential backoff with jitter`() {
        assertEquals(0L, signalRClient.getRetryDelay(0))
        assertTrue(signalRClient.getRetryDelay(1) in 4_000L..5_499L)
        assertTrue(signalRClient.getRetryDelay(2) in 8_000L..9_499L)
        assertTrue(signalRClient.getRetryDelay(3) in 16_000L..17_499L)
        assertTrue(signalRClient.getRetryDelay(4) in 32_000L..33_499L)
        assertTrue(signalRClient.getRetryDelay(5) in 60_000L..61_499L)
        assertTrue(signalRClient.getRetryDelay(6) in 60_000L..61_499L)
    }
}
