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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.Completable
import java.lang.Exception
import com.omni.sync.data.model.FileSystemEntry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.util.Date
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName

data class ProcessInfo(
    @SerializedName("id") val id: Double,
    @SerializedName("name") val name: String,
    @SerializedName("cpuUsage") val cpuUsage: Double = 0.0,
    @SerializedName("memoryUsage") val memoryUsage: Long = 0
)

class SignalRClient(
    private val context: Context,
    private val mainViewModel: MainViewModel
) {
    private var hubConnection: HubConnection? = null
    private val hubUrl: String get() = mainViewModel.appConfig.hubUrl
    private val apiKey: String get() = mainViewModel.appConfig.apiKey

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, JsonDeserializer<Date> { json, _, _ ->
            try {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                format.parse(json.asString)
            } catch (e: Exception) {
                try {
                     val formatMs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS", java.util.Locale.US)
                     formatMs.parse(json.asString)
                } catch (e2: Exception) {
                     Date(-62135769600000L) // Year 0001
                }
            }
        })
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

    private val _tabInfoReceived = MutableSharedFlow<Pair<String, String>>()        
    val tabInfoReceived: SharedFlow<Pair<String, String>> = _tabInfoReceived.asSharedFlow()

    private val _tabListReceived = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val tabListReceived: StateFlow<List<Map<String, Any>>> = _tabListReceived       

    private val _fileChangeEvents = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)
    val fileChangeEvents: SharedFlow<Pair<String, Long>> = _fileChangeEvents.asSharedFlow()

    private val _aiMessages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val aiMessages: StateFlow<List<Pair<String, String>>> = _aiMessages

    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus: StateFlow<String?> = _aiStatus

    private val _aiSessions = MutableStateFlow<List<Int>>(emptyList())
    val aiSessions: StateFlow<List<Int>> = _aiSessions

    companion object {
        const val SHARED_PREFS_NAME = "OmniSyncPrefs"
        const val KEY_LAST_CONNECTED_HUB_URL = "last_connected_hub_url"
    }

    private fun onConnected() {
        _connectionState.value = "Connected"
        mainViewModel.setConnected(true)
        authenticateClient()
        val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_LAST_CONNECTED_HUB_URL, hubUrl).apply()    
        mainViewModel.addLog("Connected to hub: $hubUrl", com.omni.sync.ui.screen.LogType.SUCCESS)

        if (isReconnecting.compareAndSet(true, false)) {
            reconnectJob?.cancel()
            reconnectJob = null
            mainViewModel.addLog("Reconnection successful!", com.omni.sync.ui.screen.LogType.SUCCESS)
        }
    }

    fun startConnection() {
        _connectionState.value = "Connecting..."
        mainViewModel.setErrorMessage(null)

        hubConnection = HubConnectionBuilder.create(hubUrl).build()

        hubConnection?.onClosed { error ->
            _connectionState.value = "Disconnected: ${error?.message}"
            mainViewModel.setConnected(false)
            mainViewModel.setScheduledShutdownTime(null)

            if (isReconnecting.compareAndSet(false, true)) {
                mainViewModel.addLog("Connection lost. Starting auto-reconnect...", com.omni.sync.ui.screen.LogType.WARNING)
                reconnectJob = coroutineScope.launch {
                    while (true) {
                        delay(10000)
                        mainViewModel.addLog("Attempting to reconnect...", com.omni.sync.ui.screen.LogType.INFO)
                        try {
                            hubConnection?.start()?.blockingAwait()
                            onConnected()
                            break
                        } catch (e: Exception) {
                            mainViewModel.addLog("Reconnect attempt failed: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                        }
                    }
                }
            }
        }

        hubConnection?.start()
            ?.doOnComplete { onConnected() }
            ?.doOnError { error ->
                _connectionState.value = "Error: ${error.message}"
                mainViewModel.setConnected(false)
            }
            ?.subscribe()

        registerHubHandlers()
    }

    private fun registerHubHandlers() {
        hubConnection?.on("ClipboardUpdated", { newText: String ->
            try {
                isUpdatingClipboardInternally = true
                val clip = ClipData.newPlainText("OmniSyncClipboard", newText)
                clipboardManager.setPrimaryClip(clip)
                mainViewModel.updateClipboardContent(newText)
            } finally {
                isUpdatingClipboardInternally = false
            }
        }, String::class.java)

        hubConnection?.on("InjectText", { text: String ->
            OmniAccessibilityService.getInstance()?.injectText(text)
        }, String::class.java)

        hubConnection?.on("ReceiveCommandOutput", { output: String ->
            mainViewModel.appendCommandOutput(output)
        }, String::class.java)

        hubConnection?.on("ModifierStateUpdated", { modifierName: String, isPressed: Boolean ->
            when (modifierName) {
                "Shift" -> mainViewModel.setShiftPressed(isPressed)
                "Ctrl" -> mainViewModel.setCtrlPressed(isPressed)
                "Alt" -> mainViewModel.setAltPressed(isPressed)
            }
        }, String::class.java, Boolean::class.java)

        hubConnection?.on("ShutdownScheduled", { scheduledTime: String? ->
            mainViewModel.setScheduledShutdownTime(scheduledTime)
        }, String::class.java)

        hubConnection?.on("ShutdownModeUpdated", { mode: String ->
            mainViewModel.setShutdownMode(mode)
        }, String::class.java)

       hubConnection?.on("FileChanged", { path: String, unixMillis: Long ->
           coroutineScope.launch { _fileChangeEvents.emit(Pair(path, unixMillis)) } 
       }, String::class.java, java.lang.Long::class.java)

        hubConnection?.on("ReceiveCleanupPatterns", { patternsData: Any ->
            try {
                val jsonStr = gson.toJson(patternsData)
                val type = object : TypeToken<List<String>>() {}.type
                val patterns: List<String> = gson.fromJson(jsonStr, type)
                _cleanupPatterns.value = patterns
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing cleanup patterns", e)
            }
        }, Any::class.java)

        hubConnection?.on("ReceiveTabInfo", { title: String, url: String ->
            coroutineScope.launch { _tabInfoReceived.emit(Pair(title, url)) }
        }, String::class.java, String::class.java)

        hubConnection?.on("ReceiveTabList", { tabsData: Any ->
            try {
                val jsonStr = gson.toJson(tabsData)
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val tabs: List<Map<String, Any>> = gson.fromJson(jsonStr, type)
                _tabListReceived.value = tabs
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing tab list", e)
            }
        }, Any::class.java)

        hubConnection?.on("ReceiveTabToPhone", { url: String ->
            mainViewModel.openUrlOnPhone(url)
        }, String::class.java)

        hubConnection?.on("ReceiveAiMessage", { senderId: String, message: String ->
            val senderName = if (senderId == hubConnection?.connectionId) "Me" else "User"
            _aiMessages.value = _aiMessages.value + Pair(senderName, message)
        }, String::class.java, String::class.java)

        hubConnection?.on("ReceiveAiResponse", { response: String ->
            if (response == "[TURN_FINISHED]") {
                _aiStatus.value = null
                return@on
            }

            val currentMessages = _aiMessages.value.toMutableList()
            if (currentMessages.isNotEmpty() && currentMessages.last().first == "AI") {
                val lastMsg = currentMessages.last()
                currentMessages[currentMessages.size - 1] = Pair("AI", lastMsg.second + response)
                _aiMessages.value = currentMessages
            } else {
                _aiMessages.value = _aiMessages.value + Pair("AI", response)
            }
        }, String::class.java)

        hubConnection?.on("ReceiveAiStatus", { status: String? ->
            _aiStatus.value = status
        }, String::class.java)

        hubConnection?.on("ReceiveCortexActivity", { name: String, type: String ->
            mainViewModel.onCortexActivityChanged(name, type)
        }, String::class.java, String::class.java)

        hubConnection?.on("ReceiveAiSessions", { pids: Any ->
            try {
                val jsonStr = gson.toJson(pids)
                val type = object : TypeToken<List<Int>>() {}.type
                val pidsList: List<Int> = gson.fromJson(jsonStr, type)
                _aiSessions.value = pidsList
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing AI sessions", e)
            }
        }, Any::class.java)

        hubConnection?.on("ReceiveAiHistory", { historyJson: String ->
            try {
                val type = object : TypeToken<List<Map<String, String>>>() {}.type
                val history: List<Map<String, String>> = gson.fromJson(historyJson, type)
                _aiMessages.value = history.map { Pair(it["sender"] ?: "Unknown", it["text"] ?: "") }
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing AI history", e)
            }
        }, String::class.java)
    }

    fun sendAiMessage(message: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendAiMessage", message)
        }
    }

    fun getAiSessions() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("GetAiSessions")
        }
    }

    fun switchAiSession(pid: Int) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SwitchAiSession", pid)
        }
    }

    fun startNewAiSession() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("StartNewAiSession")
        }
    }

    fun clearAiMessages() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendAiMessage", "/clear")
        }
        _aiMessages.value = emptyList()
    }

    fun stopConnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting.set(false)
        hubConnection?.stop()
        _connectionState.value = "Disconnected"
        mainViewModel.setConnected(false)
    }

    fun manualReconnect() {
        coroutineScope.launch {
            stopConnection()
            delay(500)
            startConnection()
        }
    }

    fun sendMouseMove(x: Float, y: Float) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("X" to x, "Y" to y)
            hubConnection?.send("MouseMove", payload)
        }
    }

    fun sendClipboardUpdate(text: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("UpdateClipboard", text)
        }
    }

    fun executeCommand(command: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("ExecuteCommand", command)
        }
    }

    fun listProcesses(): Single<List<ProcessInfo>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Any::class.java, "ListProcesses")?.map { rawResponse ->
                try {
                    val jsonElement = gson.toJsonTree(rawResponse)
                    val listType = object : TypeToken<List<ProcessInfo>>() {}.type  
                    gson.fromJson(jsonElement, listType)
                } catch (e: Exception) {
                    emptyList<ProcessInfo>()
                }
            }
        }
        return null
    }

    fun killProcess(processId: Int): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "KillProcess", processId) as? Single<Boolean>
        }
        return null
    }

    fun listDirectory(relativePath: String): Single<List<FileSystemEntry>>? {       
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "ListDirectory", relativePath)
                ?.map { rawList ->
                    val jsonElement = gson.toJsonTree(rawList)
                    val listType = object : TypeToken<List<FileSystemEntry>>() {}.type
                    gson.fromJson(jsonElement, listType)
                } as? Single<List<FileSystemEntry>>
        }
        return null
    }

    fun searchFiles(path: String, query: String): Single<List<FileSystemEntry>>? {  
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "SearchFiles", path, query)
                ?.map { rawList ->
                    val jsonElement = gson.toJsonTree(rawList)
                    val listType = object : TypeToken<List<FileSystemEntry>>() {}.type
                    gson.fromJson(jsonElement, listType)
                } as? Single<List<FileSystemEntry>>
        }
        return null
    }

    fun getFileChunk(filePath: String, offset: Long, chunkSize: Int): Single<ByteArray>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "GetFileChunk", filePath, offset, chunkSize)
                ?.map { base64String ->
                    android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
                } as? Single<ByteArray>
        }
        return null
    }

    fun sendKeyEvent(command: String, keyCode: UShort) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("KeyCode" to keyCode.toInt())
            hubConnection?.send("SendPayload", command, payload)
        }
    }

    fun sendText(text: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("Text" to text)
            hubConnection?.send("SendPayload", "INPUT_TEXT", payload)
        }
    }

    fun sendSetVolume(volumePercentage: Float) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("VolumePercentage" to volumePercentage)
            hubConnection?.send("SendPayload", "SET_VOLUME", payload)
        }
    }

    fun sendToggleMute() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", "TOGGLE_MUTE", null)
        }
    }

    fun sendScheduleShutdown(minutes: Int) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("Minutes" to minutes)
            hubConnection?.send("SendPayload", "SCHEDULE_SHUTDOWN", payload)
        }
    }

    fun toggleShutdownMode() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("ToggleShutdownMode")
        }
    }

    fun sendLeftClick() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", "LEFT_CLICK", null)
        }
    }

    fun sendRightClick() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", "RIGHT_CLICK", null)
        }
    }

    fun sendMouseClick(button: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val command = "${button.uppercase()}_CLICK"
            hubConnection?.send(command)
        }
    }

    fun sendBrowserCommand(command: String, url: String, newTab: Boolean) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendBrowserCommand", command, url, newTab)
        }
    }

    fun sendCommand(command: String, vararg args: Any?) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            try {
                hubConnection?.send(command, *args)
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error sending command $command", e)
            }
        }
    }

    fun listNotes(): Single<List<String>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "ListNotes") as? Single<List<String>>
        }
        return null
    }

    fun getNoteContent(filename: String): Single<String>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "GetNoteContent", filename) as? Single<String>
        }
        return null
    }

    fun getVolume(): Single<Float>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Float::class.java, "GetVolume")
        }
        return null
    }

    fun isMuted(): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "IsMuted")
        }
        return null
    }

    fun sendPayload(command: String, payload: Any?) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", command, payload)
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