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
import kotlinx.coroutines.launch
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.Completable
import java.lang.Exception
import com.omni.sync.data.model.FileSystemEntry // New import
import java.util.Date // Added for FileSystemEntry deserialization
import java.io.File // Added for getFileChunk logic if needed, might remove later


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
        Log.d("SignalRClient", "=== CONNECTION DEBUG START ===")
        Log.d("SignalRClient", "Hub URL: $hubUrl")
        Log.d("SignalRClient", "API Key present: ${apiKey.isNotEmpty()}")
        
        // Run network diagnostics in background
        CoroutineScope(Dispatchers.IO).launch {
            NetworkDebugger.debugConnection(context, hubUrl)
        }
        
        // Test various URL formats
        val urlVariants = listOf(
            hubUrl,
            hubUrl.replace("http://", "https://"),
            hubUrl.replace("/signalrhub", "/signalRHub"),
            hubUrl.replace(":5000", ":5001")
        )
        
        Log.d("SignalRClient", "Testing URL variants:")
        urlVariants.forEach { url ->
            Log.d("SignalRClient", "  - $url")
        }
        
        hubConnection = HubConnectionBuilder.create(hubUrl).build()
        
        Log.d("SignalRClient", "HubConnection built successfully")
        Log.d("SignalRClient", "Connection State: ${hubConnection?.connectionState}")

        hubConnection?.onClosed { error ->
            val errorMessage = "Connection closed: ${error?.message}"
            _connectionState.value = "Disconnected: ${error?.message}"
            mainViewModel.setConnected(false)
            mainViewModel.setErrorMessage(errorMessage)
            Log.e("SignalRClient", "=== CONNECTION CLOSED ===")
            Log.e("SignalRClient", errorMessage, error)
            error?.printStackTrace()
        }

        Log.d("SignalRClient", "Starting connection attempts...")
        var connected = false
        for (url in urlVariants) {
            if (connected) break
            Log.d("SignalRClient", "Attempting connection with URL: $url")
            
            // Run network diagnostics for the current URL variant
            CoroutineScope(Dispatchers.IO).launch {
                NetworkDebugger.debugConnection(context, url)
            }

            hubConnection = HubConnectionBuilder.create(url).build()
            
            hubConnection?.onClosed { error ->
                val errorMessage = "Connection to $url closed: ${error?.message}"
                _connectionState.value = "Disconnected: ${error?.message}"
                mainViewModel.setConnected(false)
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", "=== CONNECTION TO $url CLOSED ===")
                Log.e("SignalRClient", errorMessage, error)
                error?.printStackTrace()
            }

            hubConnection?.start()?.doOnComplete {
                _connectionState.value = "Connected"
                mainViewModel.setConnected(true)
                mainViewModel.setErrorMessage(null) // Clear any previous errors
                Log.d("SignalRClient", "=== CONNECTION SUCCESSFUL with URL: $url ===")
                Log.d("SignalRClient", "Connection State: ${hubConnection?.connectionState}")
                connected = true // Mark as connected
                authenticateClient()
            }?.doOnError { error ->
                val errorMessage = "Connection Error with URL $url: ${error.message}"
                _connectionState.value = "Connection Error: ${error.message}"
                mainViewModel.setConnected(false)
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", "=== CONNECTION FAILED with URL: $url ===")
                Log.e("SignalRClient", errorMessage, error)
                Log.e("SignalRClient", "Error type: ${error.javaClass.name}")
                error.printStackTrace()
                
                // Log detailed debugging info
                Log.e("SignalRClient", "Hub URL attempted: $url")
                Log.e("SignalRClient", "Possible issues for this URL:")
                Log.e("SignalRClient", "  1. Hub not running on PC")
                Log.e("SignalRClient", "  2. Wrong IP address (check PC's actual IP with 'ipconfig')")
                Log.e("SignalRClient", "  3. Firewall blocking port 5000")
                Log.e("SignalRClient", "  4. Phone and PC on different networks")
                Log.e("SignalRClient", "  5. Hub endpoint is /signalrhub not /rpcHub")
            }?.subscribe({
                // onComplete - connection successful
            }, { error ->
                // onError - handle the error here
                Log.e("SignalRClient", "Subscription error: ${error.message}", error)
            })
        }

        if (!connected) {
            Log.e("SignalRClient", "All connection attempts failed.")
            mainViewModel.setErrorMessage("All connection attempts failed.")
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

    fun listDirectory(relativePath: String): Single<List<FileSystemEntry>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            // SignalR client library returns a Single<Any> for invoke calls, need to cast
            return hubConnection?.invoke(List::class.java, "ListDirectory", relativePath)?.doOnError { error ->
                val errorMessage = "Error listing directory '$relativePath': ${error.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, error)
            }?.map {
                // The SignalR client will deserialize the list of FileSystemEntry objects
                // We need to ensure the casting is safe.
                // Assuming it comes as a List<LinkedTreeMap> from JSON, then convert.
                (it as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { map ->
                    FileSystemEntry(
                        name = map["name"] as? String ?: "",
                        path = map["path"] as? String ?: "",
                        isDirectory = map["isDirectory"] as? Boolean ?: false,
                        size = (map["size"] as? Number)?.toLong() ?: 0L,
                        lastModified = Date(map["lastModified"] as? Long ?: 0L)
                    )
                } ?: emptyList()
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

    fun sendKeyEvent(command: String, keyCode: UShort) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                // Assuming payload for key events is { "keyCode": <value> }
                val payload = mapOf("keyCode" to keyCode.toShort()) // UShort to Short for JSON serialization
                sendPayload(command, payload)
                mainViewModel.setErrorMessage(null)
            } catch (e: Exception) {
                val errorMessage = "Error sending key event $command (keyCode $keyCode): ${e.message}"
                mainViewModel.setErrorMessage(errorMessage)
                Log.e("SignalRClient", errorMessage, e)
            }
        } else {
            val warningMessage = "Not connected, cannot send key event $command."
            mainViewModel.setErrorMessage(warningMessage)
            Log.w("SignalRClient", warningMessage)
        }
    }

    fun sendText(text: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                // Assuming payload for text is { "text": "<value>" }
                val payload = mapOf("text" to text)
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