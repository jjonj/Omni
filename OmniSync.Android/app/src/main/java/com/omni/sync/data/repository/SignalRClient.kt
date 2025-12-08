package com.omni.sync.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.omni.sync.service.OmniAccessibilityService
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.reactivex.Single
import java.lang.Exception

data class ProcessInfo(val id: Int, val name: String, val mainWindowTitle: String?)

class SignalRClient(
    private val context: Context,
    private val hubUrl: String,
    private val apiKey: String,
    private val mainViewModel: MainViewModel
) {
    private var hubConnection: HubConnection? = null
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Volatile
    var isUpdatingClipboardInternally: Boolean = false
        private set

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    init {
        // Initialize SignalR connection here, but don't start it automatically
    }

    fun startConnection() {
        hubConnection = HubConnectionBuilder.create(hubUrl).build()

        hubConnection?.onClosed { error ->
            val errorMessage = "Connection closed: ${error?.message}"
            _connectionState.value = "Disconnected: ${error?.message}"
            mainViewModel.setConnected(false)
            mainViewModel.setErrorMessage(errorMessage)
            Log.e("SignalRClient", errorMessage, error)
        }

        hubConnection?.start()?.doOnComplete {
            _connectionState.value = "Connected"
            mainViewModel.setConnected(true)
            mainViewModel.setErrorMessage(null) // Clear any previous errors
            Log.d("SignalRClient", "Connection started.")
            // Authenticate after connection is established
            authenticateClient()
        }?.doOnError { error ->
            val errorMessage = "Connection Error: ${error.message}"
            _connectionState.value = "Connection Error: ${error.message}"
            mainViewModel.setConnected(false)
            mainViewModel.setErrorMessage(errorMessage)
            Log.e("SignalRClient", errorMessage, error)
        }

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
        hubConnection?.stop()
        _connectionState.value = "Disconnected"
        mainViewModel.setConnected(false)
        mainViewModel.setErrorMessage(null) // Clear any previous errors
        Log.d("SignalRClient", "Connection stopped.")
    }

    fun sendPayload(command: String, payload: Any) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                hubConnection?.send("SendPayload", command, payload)
                Log.d("SignalRClient", "Sent payload: $command")
                mainViewModel.setErrorMessage(null) // Clear any previous errors
            } catch (e: Exception) {
                val errorMessage = "Error sending payload for command $command: ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            }
        } else {
            val warningMessage = "Not connected, cannot send payload for command $command."
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

    fun listProcesses(): Single<List<ProcessInfo>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "ListProcesses")?.doOnError { error ->
                val errorMessage = "Error listing processes: ${error.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, error)
            } as? Single<List<ProcessInfo>>
        }
        val warningMessage = "Not connected, cannot list processes."
        mainViewModel.setErrorMessage(warningMessage)
        Log.w("SignalRClient", warningMessage)
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

    private fun authenticateClient() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.invoke(Boolean::class.java, "Authenticate", apiKey)?.doOnSuccess { isAuthenticated ->
                if (isAuthenticated) {
                    Log.d("SignalRClient", "Client authenticated successfully.")
                    mainViewModel.setErrorMessage(null) // Clear any previous errors
                } else {
                    val errorMessage = "Client authentication failed."
                    Log.e("SignalRClient", errorMessage)
                    mainViewModel.setErrorMessage(errorMessage)
                    stopConnection() // Stop connection if authentication fails
                }
            }?.doOnError { error ->
                val errorMessage = "Authentication invocation error: ${error.message}"
                Log.e("SignalRClient", errorMessage, error)
                mainViewModel.setErrorMessage(errorMessage)
                stopConnection()
            }
        }
    }
}
