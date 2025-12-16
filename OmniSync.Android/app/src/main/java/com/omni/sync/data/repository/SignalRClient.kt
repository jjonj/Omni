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
import com.google.gson.reflect.TypeToken
import java.util.Date // Added for FileSystemEntry deserialization
import java.io.File // Added for getFileChunk logic if needed, might remove later
import java.util.concurrent.atomic.AtomicBoolean


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
    private val gson = Gson() // Use Gson for manual deserialization
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    @Volatile
    var isUpdatingClipboardInternally: Boolean = false
        private set

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    init {
       // Only if you haven't put the startConnection logic here yet.
    }
    
    fun startConnection() {
        // ... (Keep your existing startConnection implementation) ...
        // Ensure you paste the existing startConnection logic here or keep the file intact
        // Just verify the hubUrl creation in OmniSyncApplication.kt matches your PC IP.
        
        hubConnection = HubConnectionBuilder.create(hubUrl).build()

        hubConnection?.onClosed { error ->
            _connectionState.value = "Disconnected: ${error?.message}"
            mainViewModel.setConnected(false)

            if (isReconnecting.compareAndSet(false, true)) {
                mainViewModel.addLog("Connection lost. Starting auto-reconnect...", com.omni.sync.ui.screen.LogType.WARNING)
                reconnectJob = coroutineScope.launch {
                    while (true) {
                        delay(3000)
                        mainViewModel.addLog("Attempting to reconnect...", com.omni.sync.ui.screen.LogType.INFO)
                        try {
                            // We just try to start, the existing callbacks will handle success/failure
                            hubConnection?.start()?.subscribe({}, { _ -> })
                        } catch (e: Exception) {
                            // This catch is for any unexpected synchronous exception from .start()
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
            }
            // FIX: Use subscribe({}, { error -> ... }) instead of just subscribe()
            ?.subscribe({
                Log.d("SignalR", "Connection process started successfully")
            }, { error ->
                Log.e("SignalR", "Fatal error in connection subscription", error)
                // This catch prevents the app from crashing on start-up errors
            })
        // Register handlers for server-side calls
        hubConnection?.on("ReceivePayload", { payload: Any ->
            Log.d("SignalRClient", "Received payload: $payload")
            // TODO: Process incoming payload via CommandDispatcher
        }, Any::class.java)

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
    }

    fun stopConnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting.set(false)
        hubConnection?.stop()
        _connectionState.value = "Disconnected"
        mainViewModel.setConnected(false)
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

    // Update sendPayload to log to Dashboard
    fun sendPayload(command: String, payload: Any) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                // CHANGE: Send "SendPayload" as the method, but command is the argument. 
                // If you are using specific methods for mouse, we should use invoke directly for those.
                // However, assuming your server uses a generic "SendPayload" method:
                hubConnection?.send("SendPayload", command, payload)
                mainViewModel.addLog("Sent $command", com.omni.sync.ui.screen.LogType.INFO)
            } catch (e: Exception) {
                mainViewModel.setErrorMessage("Send failed: ${e.message}")
            }
        } else {
            val warningMessage = "Not connected, cannot send payload for command $command."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    // Specific function for Mouse Move to ensure it hits the right Hub method
    fun sendMouseMove(x: Float, y: Float) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("X" to x, "Y" to y)
            // Try specific method name "MouseMove" if "SendPayload" isn't routing it
            hubConnection?.send("MouseMove", payload)
                .also { mainViewModel.addLog("Mouse: $x, $y", com.omni.sync.ui.screen.LogType.INFO) }
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

    // Add missing List Directory logic with generic handling if needed, similar to ListProcesses
    fun listDirectory(relativePath: String): Single<List<FileSystemEntry>>? {
         if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Any::class.java, "ListDirectory", relativePath)?.map { rawResponse ->
                try {
                    val json = gson.toJson(rawResponse)
                    val listType = object : TypeToken<List<FileSystemEntry>>() {}.type
                    gson.fromJson(json, listType)
                } catch (e: Exception) {
                    Log.e("SignalR", "Deserialization error for FileSystemEntry", e) // Added specific log
                    emptyList<FileSystemEntry>()
                }
            }?.doOnError { error ->
                val errorMessage = "Error listing directory '$relativePath': ${error.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, error)
            }
        }
        val warningMessage = "Not connected, cannot list directory '$relativePath'."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
        return null
    }

    fun getFileChunk(filePath: String, offset: Long, chunkSize: Int): Single<ByteArray>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(ByteArray::class.java, "GetFileChunk", filePath, offset, chunkSize)?.doOnError { error ->
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
                 // Wrap text in a map/object if your C# 'InputPayload' expects properties
                val payload = mapOf("Text" to text) // Use "Text" (PascalCase) if C# expects it
                sendPayload("INPUT_TEXT", payload)
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
            sendPayload("SET_VOLUME", payload)
            mainViewModel.addLog("Sent SET_VOLUME: $volumePercentage", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            val warningMessage = "Not connected, cannot set volume."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendToggleMute() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            sendPayload("TOGGLE_MUTE", emptyMap<String, Any>()) // Empty payload
            mainViewModel.addLog("Sent TOGGLE_MUTE", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            val warningMessage = "Not connected, cannot toggle mute."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendLeftClick() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            sendPayload("LEFT_CLICK", emptyMap<String, Any>()) // Empty payload
            mainViewModel.addLog("Sent LEFT_CLICK", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            val warningMessage = "Not connected, cannot send left click."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendRightClick() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            sendPayload("RIGHT_CLICK", emptyMap<String, Any>()) // Empty payload
            mainViewModel.addLog("Sent RIGHT_CLICK", com.omni.sync.ui.screen.LogType.INFO)
        } else {
            val warningMessage = "Not connected, cannot send right click."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
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

    // Authenticate needs to exist
    private fun authenticateClient() {
        hubConnection?.invoke(Boolean::class.java, "Authenticate", apiKey)
            ?.subscribe({ success -> 
                Log.d("SignalR", "Auth success: $success") 
            }, { error -> 
                Log.e("SignalR", "Auth failed", error) 
            })
    }
}