package com.omni.sync.logic.terminal

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.service.OmniAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PhoneCommandEntry(
    val id: String,
    val source: String,
    val command: String,
    val state: String = "Queued",
    val output: String = "",
    val exitCode: Int? = null
)

/**
 * One app-owned execution queue shared by the local phone UI and commands from OmniHub.
 * Shell commands run as the normal Android app UID, never as ADB/root/system.
 */
class PhoneCommandService(private val context: Context, private val hub: SignalRClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _history = MutableStateFlow<List<PhoneCommandEntry>>(emptyList())
    val history: StateFlow<List<PhoneCommandEntry>> = _history

    private fun update(id: String, transform: (PhoneCommandEntry) -> PhoneCommandEntry) {
        synchronized(_history) {
            _history.value = _history.value.map { if (it.id == id) transform(it) else it }
        }
    }

    fun run(command: String, source: String = "Local", requestId: String = UUID.randomUUID().toString()): String {
        val text = command.trim()
        if (text.isEmpty()) return requestId
        synchronized(_history) {
            _history.value = (_history.value + PhoneCommandEntry(requestId, source, text)).takeLast(100)
        }
        scope.launch {
            mutex.withLock {
                update(requestId) { it.copy(state = "Running") }
                hub.reportPhoneCommandStarted(requestId, text, source)
                fun emit(chunk: String) {
                    if (chunk.isEmpty()) return
                    update(requestId) {
                        it.copy(output = (it.output + chunk).takeLast(64000))
                    }
                    chunk.chunked(4000).forEach { part ->
                        hub.reportPhoneCommandOutput(requestId, part)
                    }
                }
                val exit = try {
                    execute(text, ::emit)
                } catch (error: Exception) {
                    emit("[ERROR] ${error.message ?: error.javaClass.simpleName}\n")
                    -1
                }
                update(requestId) { it.copy(state = if (exit == 0) "Completed" else "Failed", exitCode = exit) }
                hub.reportPhoneCommandCompleted(requestId, exit)
            }
        }
        return requestId
    }

    private suspend fun execute(command: String, emit: (String) -> Unit): Int {
        val words = command.split(Regex("\\s+"))
        val action = words.first().lowercase()
        val argument = command.substringAfter(' ', "").trim()
        when (action) {
            "help" -> {
                emit("Commands: help, info, apps, open <package>, url <url>, settings [wifi|sound|display|bluetooth|accessibility|apps|battery], volume [show|up|down|media N|ring N], home, back, recents, tap X Y, swipe X1 Y1 X2 Y2 [milliseconds], shell <command>.\n")
                emit("Other commands run through Android /system/bin/sh under Omni's app permissions.\n")
            }
            "info" -> {
                emit("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                emit("App files: ${context.filesDir.absolutePath}\n")
                emit("Package: ${context.packageName}\n")
            }
            "apps" -> {
                val manager = context.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val names = manager.queryIntentActivities(launcher, 0)
                    .mapNotNull { result ->
                        result.activityInfo?.packageName?.let {
                            "${result.loadLabel(manager)} — $it"
                        }
                    }.distinct().sorted()
                emit(if (names.isEmpty()) "No visible launcher apps.\n" else names.joinToString("\n", postfix = "\n"))
            }
            "open" -> {
                require(argument.isNotBlank()) { "Usage: open <package.name>" }
                withContext(Dispatchers.Main) {
                    val manager = context.packageManager
                    val intent = manager.getLaunchIntentForPackage(argument)
                        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(argument).let { manager.queryIntentActivities(it, 0).firstOrNull()?.let { entry ->
                                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setClassName(entry.activityInfo.packageName, entry.activityInfo.name)
                            } }
                        ?: throw IllegalArgumentException("App not found or not launchable: $argument")
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                emit("Launched $argument\n")
            }
            "url" -> {
                require(argument.startsWith("https://") || argument.startsWith("http://")) { "Usage: url https://example.com" }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(argument))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                emit("Opened $argument\n")
            }
            "settings" -> {
                val page = when (argument.lowercase()) {
                    "", "main" -> Settings.ACTION_SETTINGS
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    else -> throw IllegalArgumentException("Unknown settings page: $argument")
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                emit("Opened Android settings: ${argument.ifEmpty { "main" }}\n")
            }
            "volume" -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val parts = argument.lowercase().split(Regex("\\s+"))
                fun describe(stream: Int, name: String) {
                    emit("$name: ${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}\n")
                }
                when (parts.firstOrNull()) {
                    "", "show", null -> {
                        describe(AudioManager.STREAM_MUSIC, "Media")
                        describe(AudioManager.STREAM_RING, "Ring")
                        describe(AudioManager.STREAM_ALARM, "Alarm")
                    }
                    "up", "down" -> {
                        val direction = if (parts[0] == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                        describe(AudioManager.STREAM_MUSIC, "Media")
                    }
                    "media", "ring", "alarm" -> {
                        val stream = when (parts[0]) {
                            "media" -> AudioManager.STREAM_MUSIC
                            "ring" -> AudioManager.STREAM_RING
                            else -> AudioManager.STREAM_ALARM
                        }
                        val value = parts.getOrNull(1)?.toIntOrNull()
                            ?: throw IllegalArgumentException("Usage: volume ${parts[0]} <number>")
                        require(value in 0..audio.getStreamMaxVolume(stream)) { "Volume out of range" }
                        audio.setStreamVolume(stream, value, 0)
                        describe(stream, parts[0])
                    }
                    else -> throw IllegalArgumentException("Usage: volume show|up|down|media N|ring N|alarm N")
                }
            }
            "home", "back", "recents", "tap", "swipe" -> {
                val accessibility = OmniAccessibilityService.getInstance()
                    ?: throw IllegalStateException("Enable Omni Accessibility to use gestures and navigation")
                val ok = withContext(Dispatchers.Main) {
                    when (action) {
                        "home" -> accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                        "back" -> accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        "recents" -> accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                        "tap" -> {
                            require(words.size == 3) { "Usage: tap X Y" }
                            accessibility.tap(words[1].toFloat(), words[2].toFloat())
                        }
                        else -> {
                            require(words.size in 5..6) { "Usage: swipe X1 Y1 X2 Y2 [milliseconds]" }
                            accessibility.swipe(
                                words[1].toFloat(), words[2].toFloat(),
                                words[3].toFloat(), words[4].toFloat(),
                                words.getOrNull(5)?.toLongOrNull()?.coerceIn(50, 10000) ?: 400L
                            )
                        }
                    }
                }
                check(ok) { "Android rejected the accessibility action" }
                emit("Sent $action\n")
            }
            else -> return runShell(if (action == "shell") argument else command, emit)
        }
        return 0
    }

    private fun runShell(command: String, emit: (String) -> Unit): Int {
        require(command.isNotBlank()) { "Shell command is empty" }
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true).start()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { emit(it + "\n") }
                }
            } catch (_: Exception) {
                // The process can close stdout when cancelled.
            }
        }.apply { isDaemon = true; start() }
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            emit("[TIMEOUT] Command exceeded 30 seconds\n")
        }
        reader.join(1200)
        return if (completed) process.exitValue() else 124
    }
}
