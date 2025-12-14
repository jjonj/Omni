package com.omni.sync.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.repository.SignalRClient

class OmniAccessibilityService : AccessibilityService() {

    private lateinit var signalRClient: SignalRClient

    companion object {
        private var instance: OmniAccessibilityService? = null

        fun getInstance(): OmniAccessibilityService? {
            return instance
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for key events, but can be useful for other accessibility tasks.
    }

    override fun onInterrupt() {
        Log.w("OmniAccessibility", "Accessibility service interrupted.")
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        signalRClient = (application as OmniSyncApplication).signalRClient
        Log.i("OmniAccessibility", "Accessibility service connected.")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    Log.d("OmniAccessibility", "Volume Up pressed.")
                    signalRClient.sendPayload("VOLUME_UP", mapOf("action" to "next_track"))
                    return true // Consume the event
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    Log.d("OmniAccessibility", "Volume Down pressed.")
                    signalRClient.sendPayload("VOLUME_DOWN", mapOf("action" to "prev_track"))
                    return true // Consume the event
                }
            }
        }
        return super.onKeyEvent(event)
    }

    fun injectText(text: String) {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    public override fun findFocus(focus: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        return root?.findFocus(focus)
    }
}
