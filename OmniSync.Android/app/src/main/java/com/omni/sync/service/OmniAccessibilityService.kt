package com.omni.sync.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omni.sync.MainActivity
import com.omni.sync.viewmodel.AppScreen
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.repository.SignalRClient

class OmniAccessibilityService : AccessibilityService() {

    private lateinit var signalRClient: SignalRClient
    private var lastEventLog = 0L

    companion object {
        private var instance: OmniAccessibilityService? = null

        fun getInstance(): OmniAccessibilityService? {
            return instance
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType in listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            val now = System.currentTimeMillis()
            if (now - lastEventLog >= 60_000) {
                lastEventLog = now
                (application as? OmniSyncApplication)?.mainViewModel?.addLog(
                    "[Wake detection] Accessibility event type=${event?.eventType}, app=${event?.packageName}", com.omni.sync.ui.screen.LogType.INFO)
            }
            com.omni.sync.utils.WakeAlarmActions.stillAwake(this, automatic = true)
        }
    }

    override fun onInterrupt() {
        Log.w("OmniAccessibility", "Accessibility service interrupted.")
        (application as? OmniSyncApplication)?.mainViewModel?.addLog("[Wake detection] Accessibility service interrupted", com.omni.sync.ui.screen.LogType.INFO)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        signalRClient = (application as OmniSyncApplication).signalRClient
        Log.i("OmniAccessibility", "Accessibility service connected.")
        (application as? OmniSyncApplication)?.mainViewModel?.addLog("[Wake detection] OmniSync accessibility service connected; listening for clicks, typing, scrolling and keys", com.omni.sync.ui.screen.LogType.INFO)
    }

    override fun onDestroy() {
        instance = null
        (application as? OmniSyncApplication)?.mainViewModel?.addLog("[Wake detection] Accessibility service disconnected", com.omni.sync.ui.screen.LogType.INFO)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            com.omni.sync.utils.WakeAlarmActions.stillAwake(this, automatic = true)
            // Only hijack volume keys for PC remote control while Omni is the open app.
            // Everywhere else Android must receive the original volume key.
            val remoteControlActive = MainActivity.isForeground &&
                (application as? OmniSyncApplication)?.mainViewModel?.currentScreen?.value == AppScreen.REMOTECONTROL
            if (remoteControlActive) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", 0xAFu)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", 0xAEu)
                        return true
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    /** Dispatch a screen-coordinate tap using the already-enabled Accessibility service. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Paste into whichever Android text field currently has input focus. */
    fun pasteIntoFocusedApp(): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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
