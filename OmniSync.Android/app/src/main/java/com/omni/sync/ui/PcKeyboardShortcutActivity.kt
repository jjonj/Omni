package com.omni.sync.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.ComponentActivity
import com.omni.sync.OmniSyncApplication

/**
 * Translucent, bottom-anchored keyboard target. The previous Android app stays visible;
 * the IME edits this proxy text field and changes are forwarded to the PC, never to the
 * underlying Android app. Does not require the separate system overlay permission.
 */
class PcKeyboardShortcutActivity : ComponentActivity() {
    private val client get() = (application as OmniSyncApplication).signalRClient
    private var lastText = ""
    private var editing = false
    private var disconnectedNoticeShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        // Connection establishment can happen while the keyboard is opening.
        client.startConnection()

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(30, 33, 40))
        }
        val editor = EditText(this).apply {
            hint = "Type on PC…"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendKey(0x0Du)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    if (editing) return
                    val next = value?.toString() ?: ""
                    val prefix = lastText.zip(next).takeWhile { (a, b) -> a == b }.count()
                    val removed = lastText.length - prefix
                    val added = next.substring(prefix)
                    repeat(removed) { sendKey(0x08u) }
                    if (added.isNotEmpty()) sendText(added)
                    lastText = next

                    // Avoid growing an unlimited hidden editor and keep the caret at the end.
                    editing = true
                    if (next.length > 160) {
                        setText("")
                        lastText = ""
                    } else {
                        setSelection(next.length)
                    }
                    editing = false
                }
            })
        }
        panel.addView(editor, LinearLayout.LayoutParams(0, dp(48), 1f))
        val close = TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Close PC keyboard"
            setOnClickListener { finish() }
        }
        panel.addView(close, LinearLayout.LayoutParams(dp(44), dp(48)))
        setContentView(panel)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        editor.requestFocus()
        editor.postDelayed({
            if (!isFinishing) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 200)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun sendText(text: String) {
        if (client.connectionState.value == "Connected") {
            client.sendText(text)
        } else {
            reportDisconnected()
        }
    }

    private fun sendKey(code: UShort) {
        if (client.connectionState.value == "Connected") {
            client.sendKeyEvent("INPUT_KEY_PRESS", code)
        } else {
            reportDisconnected()
        }
    }

    private fun reportDisconnected() {
        if (!disconnectedNoticeShown) {
            disconnectedNoticeShown = true
            Toast.makeText(this, "Omni Hub not connected; reconnect to type on PC", Toast.LENGTH_LONG).show()
        }
    }
}
