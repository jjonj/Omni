package com.omni.sync

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Diagnostic only: samples app-accessible microphone sources; never claims to record remote call audio. */
class CallAudioProbeActivity : Activity() {
    private lateinit var result: TextView
    private var active: AudioRecord? = null
    private var busy = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 20) }
        result = TextView(this).apply {
            text = "Call audio diagnostic (10 seconds per test).\nNo recordings start automatically.\nTest once outside a call, then during a consented test call on speakerphone.\nAndroid may silence or reject microphone capture during calls.\nFiles remain in Omni private storage."
        }
        layout.addView(result)
        fun add(label: String, source: Int) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { sample(source) } })
        }
        add("Test MIC input", MediaRecorder.AudioSource.MIC)
        add("Test VOICE_COMMUNICATION input", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        layout.addView(Button(this).apply { text = "Stop"; setOnClickListener { active?.stop() } })
        setContentView(layout)
    }

    private fun sample(source: Int) {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1092)
            return
        }
        busy = true
        result.text = "Sampling source=$source ..."
        thread(name = "CallAudioProbe") {
            var recorder: AudioRecord? = null
            try {
                val rate = 16000
                val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
                recorder = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                active = recorder
                recorder.startRecording()
                val output = File(getExternalFilesDir(null), "call_probe_${source}_${System.currentTimeMillis()}.pcm")
                var samples = 0L
                var sumSquares = 0.0
                var peak = 0
                output.outputStream().use { out ->
                    val buffer = ShortArray(2048)
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < 10_000 && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = recorder.read(buffer, 0, buffer.size)
                        if (n < 0) error("AudioRecord.read returned $n")
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val v = buffer[i].toInt()
                            sumSquares += v.toDouble() * v
                            peak = maxOf(peak, kotlin.math.abs(v))
                            bytes[i * 2] = v.toByte()
                            bytes[i * 2 + 1] = (v shr 8).toByte()
                        }
                        out.write(bytes)
                        samples += n
                    }
                }
                val rms = if (samples == 0L) 0.0 else sqrt(sumSquares / samples)
                ui.post { result.text = "Source $source: ${samples / 16000.0}s sampled; RMS %.1f / 32768; peak %d / 32768\nSaved: %s\nLow/zero levels during calls can indicate microphone capture restrictions. This does NOT test downlink audio capture.".format(rms, peak, output.absolutePath) }
            } catch (e: Exception) {
                ui.post { result.text = "Source $source FAILED: ${e.javaClass.simpleName}: ${e.message}" }
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
                active = null
                busy = false
            }
        }
    }
    override fun onDestroy() {
        try { active?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
