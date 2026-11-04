package com.omni.sync.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The accessibility shortcut opens this translucent, bottom-anchored activity rather than
 * navigating to Omni's main UI. SpeechRecognizer listens inside this activity so Android
 * keeps the original app visible behind the voice panel. No system overlay permission needed.
 */
class VoiceShortcutActivity : ComponentActivity(), RecognitionListener {
    companion object {
        const val EXTRA_MODE = "voice_mode"
        private const val MIC_PERMISSION_REQUEST = 41
    }

    private val client get() = (application as OmniSyncApplication).signalRClient
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var retry: Button
    private var recognizer: SpeechRecognizer? = null
    private var transcript: String? = null
    private var capturing = false
    private var sending = false
    private val mode by lazy {
        if (intent.getStringExtra(EXTRA_MODE) == SignalRClient.VoiceMode.JARVIS.name)
            SignalRClient.VoiceMode.JARVIS else SignalRClient.VoiceMode.TYPE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        window.setGravity(Gravity.BOTTOM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 31, 39))
                cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val title = TextView(this).apply {
            text = if (mode == SignalRClient.VoiceMode.TYPE) "Voice to PC" else "Ask Jarvis"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        panel.addView(title)

        status = TextView(this).apply {
            text = "Preparing microphone…"
            textSize = 14f
            setTextColor(Color.rgb(199, 205, 222))
            setPadding(0, dp(8), 0, dp(5))
        }
        panel.addView(status)

        preview = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 4
        }
        panel.addView(preview)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        retry = Button(this).apply {
            text = "Retry"
            visibility = android.view.View.GONE
            setOnClickListener {
                if (!sending) {
                    if (!transcript.isNullOrBlank()) deliver() else beginListening()
                }
            }
        }
        controls.addView(retry)
        val cancel = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        controls.addView(cancel)
        panel.addView(controls)

        setContentView(panel)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        client.startConnection()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
            else {
                status.text = "Microphone permission required to dictate."
                retry.text = "Grant access"
                retry.visibility = android.view.View.VISIBLE
                retry.setOnClickListener {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
                }
            }
        }
    }

    private fun beginListening() {
        if (capturing || sending) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "No speech recognition service is installed on this phone."
            retry.visibility = android.view.View.GONE
            return
        }
        transcript = null
        preview.text = ""
        retry.visibility = android.view.View.GONE
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@VoiceShortcutActivity)
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
            capturing = true
            status.text = "Listening… speak normally"
        } catch (error: Exception) {
            capturing = false
            status.text = "Unable to start speech recognition: ${error.message}"
            retry.text = "Try again"
            retry.visibility = android.view.View.VISIBLE
        }
    }

    override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening… speak normally" }
    override fun onBeginningOfSpeech() { status.text = "Listening…" }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { status.text = "Recognising…" }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!partial.isNullOrBlank()) preview.text = partial
    }

    override fun onResults(results: Bundle?) {
        capturing = false
        val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (result.isNullOrBlank()) {
            status.text = "No speech detected."
            retry.text = "Try again"
            retry.visibility = android.view.View.VISIBLE
            return
        }
        transcript = result
        preview.text = result
        deliver()
    }

    private fun deliver() {
        val text = transcript ?: return
        if (sending) return
        sending = true
        retry.visibility = android.view.View.GONE
        if (mode == SignalRClient.VoiceMode.JARVIS) {
            client.sendVoiceTranscript(mode, text) // Jarvis supports offline queueing.
            status.text = "Sent to Jarvis"
            preview.postDelayed({ finish() }, 700)
            return
        }
        status.text = "Sending to PC…"
        lifecycleScope.launch {
            val connected = client.connectionState.value == "Connected" ||
                withTimeoutOrNull(10_000) { client.connectionState.first { it == "Connected" } } != null
            sending = false
            if (connected && !isFinishing) {
                client.sendVoiceTranscript(mode, text)
                status.text = "Sent to PC"
                preview.postDelayed({ finish() }, 700)
            } else if (!isFinishing) {
                status.text = "PC is offline. Reconnect and send again."
                retry.text = "Send again"
                retry.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onError(error: Int) {
        capturing = false
        status.text = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "Didn't catch that. Try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is missing."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech service network unavailable."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Speech recognition is busy. Try again."
            else -> "Speech recognition error ($error). Try again."
        }
        retry.text = "Try again"
        retry.visibility = android.view.View.VISIBLE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
