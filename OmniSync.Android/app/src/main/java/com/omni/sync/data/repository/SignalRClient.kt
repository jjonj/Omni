package com.omni.sync.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.omni.sync.service.OmniAccessibilityService
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.utils.NetworkDebugger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.Completable
import java.lang.Exception
import com.omni.sync.data.model.FileSystemEntry // New import
import com.google.gson.Gson 
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.util.Date // Added for FileSystemEntry deserialization
import java.io.File // Added for getFileChunk logic if needed, might remove later
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import android.content.SharedPreferences // Import SharedPreferences


import com.google.gson.annotations.SerializedName

data class ProcessInfo(
    @SerializedName("id") val id: Double,
    @SerializedName("name") val name: String,
    @SerializedName("mainWindowTitle") val mainWindowTitle: String?,
    @SerializedName("cpuUsage") val cpuUsage: Double = 0.0,
    @SerializedName("memoryUsage") val memoryUsage: Long = 0
)

class SignalRClient(
    private val context: Context,
    private val hubUrl: String,
    private val apiKey: String,
    private val mainViewModel: MainViewModel
) {
    private var hubConnection: HubConnection? = null
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val gson = GsonBuilder()
        .create()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    @Volatile
    var isUpdatingClipboardInternally: Boolean = false
        private set

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState
    
    private val _cleanupPatterns = MutableStateFlow<List<String>>(emptyList())
    val cleanupPatterns: StateFlow<List<String>> = _cleanupPatterns

    init {
       // Only if you haven't put the startConnection logic here yet.
    }
    
    companion object {
        const val SHARED_PREFS_NAME = "OmniSyncPrefs"
        const val KEY_LAST_CONNECTED_HUB_URL = "last_connected_hub_url"
    }
    
    fun startConnection() {
        _connectionState.value = "Connecting..."
        mainViewModel.setErrorMessage(null) // Clear previous errors
        
        hubConnection = HubConnectionBuilder.create(hubUrl).build()

        hubConnection?.onClosed { error ->
            _connectionState.value = "Disconnected: ${error?.message}"
            mainViewModel.setConnected(false)
            mainViewModel.setScheduledShutdownTime(null)

            if (isReconnecting.compareAndSet(false, true)) {
                mainViewModel.addLog("Connection lost. Starting auto-reconnect...", com.omni.sync.ui.screen.LogType.WARNING)
                reconnectJob = coroutineScope.launch {
                    while (true) {
                        delay(3000)
                        mainViewModel.addLog("Attempting to reconnect...", com.omni.sync.ui.screen.LogType.INFO)
                        try {
                            hubConnection?.start()?.subscribe({}, { e -> 
                                mainViewModel.addLog("Reconnect attempt failed: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                            })
                        } catch (e: Exception) {
                            mainViewModel.addLog("Reconnect attempt failed unexpectedly: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                        }
                    }
                }
            }
        }

        hubConnection?.start()
            ?.doOnComplete {
                _connectionState.value = "Connected"
                mainViewModel.setConnected(true)
                authenticateClient()
                // Save the connected hub URL to SharedPreferences
                val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs.edit().putString(KEY_LAST_CONNECTED_HUB_URL, hubUrl).apply()
                mainViewModel.addLog("Connected to hub: $hubUrl", com.omni.sync.ui.screen.LogType.SUCCESS)

                // If we were reconnecting, stop the loop
                if (isReconnecting.compareAndSet(true, false)) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    mainViewModel.addLog("Reconnection successful!", com.omni.sync.ui.screen.LogType.SUCCESS)
                }
            }
            ?.doOnError { error ->
                _connectionState.value = "Error: ${error.message}"
                Log.e("SignalR", "Connection failed side-effect", error)
                mainViewModel.setConnected(false) // Ensure connected state is false on error
            }
            ?.subscribe({
                Log.d("SignalR", "Connection process started successfully")
            }, { error ->
                Log.e("SignalR", "Fatal error in connection subscription", error)
                mainViewModel.setConnected(false) // Ensure connected state is false on fatal error
            })
        
        // Register handlers
        registerHubHandlers()
    }

    private fun registerHubHandlers() {

        hubConnection?.on("ClipboardUpdated", { newText: String ->
            Log.d("SignalRClient", "Received clipboard update: $newText")
            try {
                isUpdatingClipboardInternally = true
                val clip = ClipData.newPlainText("OmniSyncClipboard", newText)
                clipboardManager.setPrimaryClip(clip)
                mainViewModel.updateClipboardContent(newText)
                mainViewModel.setErrorMessage(null) // Clear any previous errors
            } catch (e: Exception) {
                val errorMessage = "Error setting Android clipboard: ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            } finally {
                isUpdatingClipboardInternally = false
            }
        }, String::class.java)

        hubConnection?.on("InjectText", { text: String ->
            Log.d("SignalRClient", "Received inject text command: $text")
            try {
                OmniAccessibilityService.getInstance()?.injectText(text)
                mainViewModel.setErrorMessage(null) // Clear any previous errors
            } catch (e: Exception) {
                val errorMessage = "Error injecting text via accessibility service: ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            }
        }, String::class.java)

        hubConnection?.on("ReceiveCommandOutput", { output: String ->
            Log.d("SignalRClient", "Received command output: $output")
            mainViewModel.appendCommandOutput(output)
        }, String::class.java)

        // New handler for modifier key state updates from the Hub
        hubConnection?.on("ModifierStateUpdated", { modifierName: String, isPressed: Boolean ->
            Log.d("SignalRClient", "ModifierStateUpdated: $modifierName = $isPressed")
            when (modifierName) {
                "Shift" -> mainViewModel.setShiftPressed(isPressed)
                "Ctrl" -> mainViewModel.setCtrlPressed(isPressed)
                "Alt" -> mainViewModel.setAltPressed(isPressed)
            }
        }, String::class.java, Boolean::class.java)

        hubConnection?.on("ShutdownScheduled", { scheduledTime: String? ->
            Log.d("SignalRClient", "ShutdownScheduled: $scheduledTime")
            mainViewModel.setScheduledShutdownTime(scheduledTime)
        }, String::class.java)
        
        // Handler for cleanup patterns from Chrome extension
        hubConnection?.on("ReceiveCleanupPatterns", { patternsData: Any ->
            Log.d("SignalRClient", "Received cleanup patterns: $patternsData")
            try {
                val jsonStr = gson.toJson(patternsData)
                val type = object : TypeToken<List<String>>() {}.type
                val patterns: List<String> = gson.fromJson(jsonStr, type)
                _cleanupPatterns.value = patterns
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing cleanup patterns", e)
            }
        }, Any::class.java)
    }

    fun stopConnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting.set(false)
        hubConnection?.stop()
        _connectionState.value = "Disconnected"
        mainViewModel.setConnected(false)
        mainViewModel.setScheduledShutdownTime(null)
        mainViewModel.setErrorMessage(null) // Clear any previous errors
        Log.d("SignalRClient", "Connection stopped.")
    }

    fun manualReconnect() {
        mainViewModel.addLog("Manual reconnection initiated", com.omni.sync.ui.screen.LogType.INFO)
        coroutineScope.launch {
            stopConnection()
            delay(500)
            startConnection()
        }
    }

    // Specific function for Mouse Move to ensure it hits the right Hub method
    fun sendMouseMove(x: Float, y: Float) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("X" to x, "Y" to y)
            // Try specific method name "MouseMove" if "SendPayload" isn't routing it
            hubConnection?.send("MouseMove", payload)
        } else {
            val warningMessage = "Not connected, cannot send mouse move."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendClipboardUpdate(text: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                hubConnection?.send("UpdateClipboard", text)
                Log.d("SignalRClient", "Sent clipboard update: $text")
                mainViewModel.setErrorMessage(null) // Clear any previous errors
            } catch (e: Exception) {
                val errorMessage = "Error sending clipboard update: ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            }
        } else {
            val warningMessage = "Not connected, cannot send clipboard update."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }



    fun listNotes(): Single<List<String>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "ListNotes")?.doOnError { error ->
                val errorMessage = "Error listing notes: ${error.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, error)
            } as? Single<List<String>>
        }
        val warningMessage = "Not connected, cannot list notes."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    fun getNoteContent(filename: String): Single<String>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "GetNoteContent", filename)?.doOnError { error ->
                val errorMessage = "Error getting note content for $filename: ${error.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, error)
            } as? Single<String>
        }
        val warningMessage = "Not connected, cannot get note content for $filename."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    fun executeCommand(command: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                hubConnection?.send("ExecuteCommand", command)
                mainViewModel.setErrorMessage(null) // Clear any previous errors
            } catch (e: Exception) {
                val errorMessage = "Error executing command: ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            }
        } else {
            val warningMessage = "Not connected, cannot execute command."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    // FIX: List Processes (Manual Deserialization)
    fun listProcesses(): Single<List<ProcessInfo>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Any::class.java, "ListProcesses")?.map { rawResponse ->
                try {
                    Log.d("SignalRClient", "Raw response from ListProcesses: $rawResponse")
                    // Convert the raw object to JSON tree, then back to List<ProcessInfo>
                    val jsonElement = gson.toJsonTree(rawResponse)
                    Log.d("SignalRClient", "JsonElement for ListProcesses: $jsonElement")
                    val listType = object : TypeToken<List<ProcessInfo>>() {}.type
                    val list: List<ProcessInfo> = gson.fromJson(jsonElement, listType)
                    list
                } catch (e: Exception) {
                    Log.e("SignalR", "Deserialization error", e)
                    emptyList<ProcessInfo>()
                }
            }?.doOnError { error ->
                mainViewModel.setErrorMessage("Error listing processes: ${error.message}")
            }
        }
        mainViewModel.setErrorMessage("Not connected.")
        return null
    }

    fun killProcess(processId: Int): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "KillProcess", processId)?.doOnError { error ->
                val errorMessage = "Error killing process $processId: ${error.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, error)
            } as? Single<Boolean>
        }
        val warningMessage = "Not connected, cannot kill process $processId."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    // List Directory using event-based response ("ReceiveDirectoryContents")
    fun listDirectory(relativePath: String): Single<List<FileSystemEntry>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return Single.create<List<FileSystemEntry>> { emitter ->
                try {
                    mainViewModel.addLog("Requesting directory: ${if (relativePath.isEmpty()) "(root)" else relativePath}", com.omni.sync.ui.screen.LogType.INFO)
                    val handlerName = "ReceiveDirectoryContents"
                    var handled = false
                    val handler: (Any) -> Unit = { rawResponse ->
                        if (!handled) {
                            handled = true
                            try {
                                val jsonElement = gson.toJsonTree(rawResponse)
                                val results = mutableListOf<FileSystemEntry>()
                                var skipped = 0
                                if (jsonElement.isJsonArray) {
                                    val arr = jsonElement.asJsonArray
                                    for (el in arr) {
                                        try {
                                            val entry = gson.fromJson(el, FileSystemEntry::class.java)
                                            if (entry != null) {
                                                results.add(entry)
                                            } else {
                                                skipped++
                                            }
                                        } catch (_: Exception) {
                                            skipped++
                                        }
                                    }
                                }
                                mainViewModel.addLog("Received ${results.size} entries${if (skipped > 0) " (skipped $skipped)" else ""}", com.omni.sync.ui.screen.LogType.SUCCESS)
                                emitter.onSuccess(results)
                            } catch (e: Exception) {
                                val msg = "Parse error for directory '$relativePath': ${e.message}"
                                mainViewModel.setErrorMessage(msg)
                                Log.e("SignalRClient", msg, e)
                                emitter.onError(e)
                            } finally {
                                try {
                                    hubConnection?.remove(handlerName)
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    hubConnection?.on(handlerName, handler, Any::class.java)
                    hubConnection?.send("ListDirectory", relativePath)
                } catch (e: Exception) {
                    val errorMessage = "Error requesting directory '$relativePath': ${e.message}"
                    mainViewModel.setErrorMessage(errorMessage)
                    Log.e("SignalRClient", errorMessage, e)
                    emitter.onError(e)
                }
            }
        }
        val warningMessage = "Not connected, cannot list directory '$relativePath'."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    fun getFileChunk(filePath: String, offset: Long, chunkSize: Int): Single<ByteArray>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "GetFileChunk", filePath, offset, chunkSize)
                ?.map { base64String ->
                    android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
                }
                ?.doOnError { error ->
                    val errorMessage = "Error getting file chunk for '$filePath' at offset $offset: ${error.message}"
                    mainViewModel.setErrorMessage(errorMessage)
                    Log.e("SignalRClient", errorMessage, error)
                } as? Single<ByteArray>
        }
        val warningMessage = "Not connected, cannot get file chunk for '$filePath'."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    // Update sendKeyEvent
    fun sendKeyEvent(command: String, keyCode: UShort) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("KeyCode" to keyCode.toInt())
            // Ensure command string is what server expects (e.g. "InputKeyDown")
            hubConnection?.send("SendPayload", command, payload)
        } else {
            val warningMessage = "Not connected, cannot send key event $command."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendText(text: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                val payload = mapOf("Text" to text)
                hubConnection?.send("SendPayload", "INPUT_TEXT", payload)
                mainViewModel.setErrorMessage(null)
            } catch (e: Exception) {
                val errorMessage = "Error sending text '$text': ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            }
        } else {
            val warningMessage = "Not connected, cannot send text '$text'."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendSetVolume(volumePercentage: Float) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("VolumePercentage" to volumePercentage)
            hubConnection?.send("SendPayload", "SET_VOLUME", payload)
            mainViewModel.addLog("Sent SET_VOLUME: $volumePercentage", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            val warningMessage = "Not connected, cannot set volume."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendToggleMute() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", "TOGGLE_MUTE", null)
            mainViewModel.addLog("Sent TOGGLE_MUTE", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            val warningMessage = "Not connected, cannot toggle mute."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendScheduleShutdown(minutes: Int) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("Minutes" to minutes)
            hubConnection?.send("SendPayload", "SCHEDULE_SHUTDOWN", payload)
            mainViewModel.addLog("Scheduled shutdown: $minutes min", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            mainViewModel.setErrorMessage("Not connected.")
        }
    }

    fun sendLeftClick() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", "LEFT_CLICK", null)
        } else {
            val warningMessage = "Not connected, cannot send left click."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendRightClick() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", "RIGHT_CLICK", null)
        } else {
            val warningMessage = "Not connected, cannot send right click."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
            }
        }

    fun sendMouseClick(button: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val command = "${button.uppercase()}_CLICK"
            hubConnection?.send(command)
        } else {
            val warningMessage = "Not connected, cannot send $button click."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendBrowserCommand(command: String, url: String, newTab: Boolean) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                hubConnection?.send("SendBrowserCommand", command, url, newTab)
                mainViewModel.addLog("Browser: $command", com.omni.sync.ui.screen.LogType.INFO)
            } catch (e: Exception) {
                mainViewModel.setErrorMessage("Browser command failed: ${e.message}")
            }
        } else {
            mainViewModel.setErrorMessage("Not connected.")
        }
    }

    fun getVolume(): Single<Float>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Float::class.java, "GetVolume")
                ?.doOnError { error ->
                    val errorMessage = "Error getting volume: ${error.message}"
                    mainViewModel.setErrorMessage(errorMessage)
                    Log.e("SignalRClient", errorMessage, error)
                }
        }
        val warningMessage = "Not connected, cannot get volume."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    fun isMuted(): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "IsMuted")
                ?.doOnError { error ->
                    val errorMessage = "Error getting mute state: ${error.message}"
                    mainViewModel.setErrorMessage(errorMessage)
                    Log.e("SignalRClient", errorMessage, error)
                }
        }
        val warningMessage = "Not connected, cannot get mute state."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    fun sendPayload(command: String, payload: Any?) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                hubConnection?.send("SendPayload", command, payload)
                Log.d("SignalRClient", "Sent payload: $command")
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error sending payload $command", e)
            }
        }
    }

    private fun authenticateClient() {
        hubConnection?.invoke(Boolean::class.java, "Authenticate", apiKey)
            ?.subscribe({ success -> 
                Log.d("SignalR", "Auth success: $success") 
            }, { error -> 
                Log.e("SignalR", "Auth failed", error) 
            })
    }
}
