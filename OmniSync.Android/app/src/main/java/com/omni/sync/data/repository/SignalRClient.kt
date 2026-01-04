package com.omni.sync.data.repository

import com.omni.sync.utils.WindowsKeyCodes.VK_BACK
import com.omni.sync.utils.WindowsKeyCodes.VK_CONTROL
import com.omni.sync.utils.WindowsKeyCodes.VK_RETURN
import com.omni.sync.utils.WindowsKeyCodes.VK_A
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

data class ReceivePayload(
    @SerializedName("Target") val target: String,
    @SerializedName("Command") val command: String,
    @SerializedName("Payload") val payload: JsonElement,
    @SerializedName("Timestamp") val timestamp: Long
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

    private val _availableDrivesReceived = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    val availableDrivesReceived: SharedFlow<List<String>> = _availableDrivesReceived.asSharedFlow()

    private val _aiMessagesMap = MutableStateFlow<Map<Int, List<Pair<String, String>>>>(emptyMap())
    private val _aiStatusMap = MutableStateFlow<Map<Int, String?>>(emptyMap())
    private val _selectedPid = MutableStateFlow(-1)

    val selectedPid: StateFlow<Int> = _selectedPid

    private val _aiMessages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val aiMessages: StateFlow<List<Pair<String, String>>> = _aiMessages

    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus: StateFlow<String?> = _aiStatus

    private val _aiSessions = MutableStateFlow<Map<Int, String>>(emptyMap())
    val aiSessions: StateFlow<Map<Int, String>> = _aiSessions

    val lastCreatedSessionPid = MutableSharedFlow<Int>()

    private var _isStartingSession = false
    private val messageQueue = mutableListOf<String>()
    val isStartingSessionFlow = MutableStateFlow(false)

    private var isNextResponseNewBubble = true

    companion object {
        const val SHARED_PREFS_NAME = "OmniSyncPrefs"
        const val KEY_LAST_CONNECTED_HUB_URL = "last_connected_hub_url"
    }

    private fun onConnected() {
        _connectionState.value = "Connected"
        mainViewModel.setConnected(true)
        authenticateClient()
        getAiSessions()
        val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_LAST_CONNECTED_HUB_URL, hubUrl).apply()    
        mainViewModel.addLog("Connected to hub: $hubUrl", com.omni.sync.ui.screen.LogType.SUCCESS)

        if (isReconnecting.compareAndSet(true, false)) {
            reconnectJob?.cancel()
            reconnectJob = null
            mainViewModel.addLog("Reconnection successful!", com.omni.sync.ui.screen.LogType.SUCCESS)
        }
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun startConnection() {
        _connectionState.value = "Connecting..."
        mainViewModel.setErrorMessage(null)

        val localUrl = mainViewModel.appConfig.hubUrl
        val remoteUrl = "http://${mainViewModel.appConfig.wanIp}:5000/signalrhub"

        if (isWifiConnected()) {
            mainViewModel.addLog("WiFi detected. Attempting local connection: $localUrl", com.omni.sync.ui.screen.LogType.INFO)
            buildAndStartConnection(localUrl) { localError ->
                mainViewModel.addLog("Local connection failed, trying Remote: $remoteUrl", com.omni.sync.ui.screen.LogType.WARNING)
                buildAndStartConnection(remoteUrl) { remoteError ->
                    _connectionState.value = "Disconnected (All attempts failed)"
                    mainViewModel.setConnected(false)
                    mainViewModel.addLog("All connection attempts failed.", com.omni.sync.ui.screen.LogType.ERROR)
                }
            }
        } else {
            mainViewModel.addLog("WiFi NOT detected. Skipping local, trying Remote: $remoteUrl", com.omni.sync.ui.screen.LogType.INFO)
            buildAndStartConnection(remoteUrl) { remoteError ->
                _connectionState.value = "Disconnected (Remote failed, no WiFi)"
                mainViewModel.setConnected(false)
                mainViewModel.addLog("Remote connection failed and WiFi is unavailable.", com.omni.sync.ui.screen.LogType.ERROR)
            }
        }
    }

    private fun buildAndStartConnection(url: String, onFailure: (Throwable) -> Unit) {
        hubConnection = HubConnectionBuilder.create(url).build()

        hubConnection?.onClosed { error ->
            _connectionState.value = "Disconnected: ${error?.message}"
            mainViewModel.setConnected(false)
            mainViewModel.setScheduledShutdownTime(null)

            if (isReconnecting.compareAndSet(false, true)) {
                mainViewModel.addLog("Connection lost. Starting auto-reconnect...", com.omni.sync.ui.screen.LogType.WARNING)
                reconnectJob = coroutineScope.launch {
                    while (true) {
                        delay(10000)
                        mainViewModel.addLog("Attempting to reconnect (Local First)...", com.omni.sync.ui.screen.LogType.INFO)
                        // Just call startConnection again which does the local-then-remote logic
                        startConnection()
                        break 
                    }
                }
            }
        }

        registerHubHandlers()

        hubConnection?.start()
            ?.doOnComplete { 
                onConnected() 
                mainViewModel.addLog("Connected to: $url", com.omni.sync.ui.screen.LogType.SUCCESS)
            }
            ?.doOnError { error ->
                onFailure(error)
            }
            ?.subscribe({
                // Success handled by doOnComplete
            }, { error ->
                // Error handled by doOnError
                Log.e("SignalRClient", "Connection subscription error for $url", error)
            })
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

        hubConnection?.on("ReceiveAvailableDrives", { drivesData: Any ->
            try {
                val jsonStr = gson.toJson(drivesData)
                val type = object : TypeToken<List<FileSystemEntry>>() {}.type
                val drives: List<FileSystemEntry> = gson.fromJson(jsonStr, type)
                coroutineScope.launch { _availableDrivesReceived.emit(drives.map { it.path }) }
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing drives", e)
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
        
                hubConnection?.on("ReceiveAiMessage", { senderId: String, message: String, pid: Int ->
                    val senderName = if (senderId == hubConnection?.connectionId) "Me" else "User"
                    updateSessionMessages(pid) { it + Pair(senderName, message) }
                }, String::class.java, String::class.java, Int::class.java)
        
                hubConnection?.on("ReceiveAiResponse", { response: String, pid: Int ->
                    if (response == "[TURN_FINISHED]") {
                        updateSessionStatus(pid, null)
                        isNextResponseNewBubble = true
                        return@on
                    }
        
                    if (response.isBlank()) return@on
        
                    // If we receive a real response, clear any "Switching..." status
                    val currentStatus = _aiStatusMap.value[pid]
                    if (currentStatus?.contains("Switching") == true || currentStatus?.contains("Reloading") == true) {
                        updateSessionStatus(pid, null)
                    }
        
                    val isSystem = response.startsWith("Error:") || response.contains("A new version of Gemini CLI is available")
                    val sender = if (isSystem) "System" else "AI"
        
                    updateSessionMessages(pid) { currentMessages ->
                        val mutable = currentMessages.toMutableList()
                        if (!isNextResponseNewBubble && !isSystem && mutable.isNotEmpty() && mutable.last().first == "AI") {
                            val lastMsg = mutable.last()
                            mutable[mutable.size - 1] = Pair("AI", lastMsg.second + response)
                            mutable
                        } else {
                            if (!isSystem) isNextResponseNewBubble = false
                            mutable + Pair(sender, response)
                        }
                    }
                }, String::class.java, Int::class.java)
        
                hubConnection?.on("ReceiveAiStatus", { status: String?, pid: Int ->
                    if (status == "FINISHED" || status == "DONE" || status == null || status.isBlank()) {
                        updateSessionStatus(pid, null)
                        isNextResponseNewBubble = true
                    } else {
                        updateSessionStatus(pid, status)
                        if (status == "AI Thinking...") {
                            isNextResponseNewBubble = true
                        }
                    }
                }, String::class.java, Int::class.java)
        
                hubConnection?.on("ReceiveNewAiSessionPid", { pid: Int ->
                    getAiSessions()
                    _isStartingSession = false
                    isStartingSessionFlow.value = false
                    updateSessionStatus(pid, null)
                    setSelectedPid(pid)
                    
                    coroutineScope.launch { lastCreatedSessionPid.emit(pid) }
        
                    // Flush queue
                    messageQueue.forEach { msg ->
                        sendAiMessage(msg, pid)
                    }
                    messageQueue.clear()
                }, Int::class.java)
        
                hubConnection?.on("ReceiveCortexActivity", { name: String, type: String ->
                    mainViewModel.onCortexActivityChanged(name, type)
                }, String::class.java, String::class.java)
        
                hubConnection?.on("ReceiveAiSessions", { sessionsData: Any ->
                    try {
                        val jsonStr = gson.toJson(sessionsData)
                        val type = object : TypeToken<Map<Int, String>>() {}.type
                        val sessionsMap: Map<Int, String> = gson.fromJson(jsonStr, type)
                        _aiSessions.value = sessionsMap
                        
                        // If current selectedPid is not in sessions anymore, pick a new one
                        if (_selectedPid.value != -1 && !sessionsMap.containsKey(_selectedPid.value)) {
                            if (sessionsMap.isNotEmpty()) setSelectedPid(sessionsMap.keys.first())
                            else setSelectedPid(-1)
                        } else if (_selectedPid.value == -1 && sessionsMap.isNotEmpty()) {
                            setSelectedPid(sessionsMap.keys.first())
                        }
                    } catch (e: Exception) {
                        Log.e("SignalRClient", "Error parsing AI sessions", e)
                    }
                }, Any::class.java)
        
                hubConnection?.on("ReceiveAiHistory", { historyJson: String, pid: Int ->
                    try {
                        val type = object : TypeToken<List<Map<String, String>>>() {}.type
                        val history: List<Map<String, String>> = gson.fromJson(historyJson, type)
                        val mappedHistory = history.map { Pair(it["sender"] ?: "Unknown", it["text"] ?: "") }
                        
                        _aiMessagesMap.value = _aiMessagesMap.value + (pid to mappedHistory)
                        updateSessionStatus(pid, null)
                        isNextResponseNewBubble = true
                        updateActiveView()
                    } catch (e: Exception) {
                        Log.e("SignalRClient", "Error parsing AI history", e)
                    }
                }, String::class.java, Int::class.java)
        
                hubConnection?.on("ReceivePayload", { payloadData: Any ->
                    try {
                        val jsonStr = gson.toJson(payloadData)
                        Log.d("SignalRClient", "Raw ReceivePayload JSON: $jsonStr")
        
                        val type = object : TypeToken<Map<String, Any>>() {}.type
                        val map: Map<String, Any> = gson.fromJson(jsonStr, type)
        
                        val command = (map["Command"] ?: map["command"]) as? String
                        val target = (map["Target"] ?: map["target"]) as? String
                        val payloadObj = (map["Payload"] ?: map["payload"]) as? Map<String, Any>
        
                        mainViewModel.addLog("Received Payload: $command", com.omni.sync.ui.screen.LogType.INFO)  
        
                        if ((target == "Android" || target == "android") && command != null && payloadObj != null) {
                            when (command) {
                                "OPEN_FILE" -> {
                                    val path = (payloadObj["Path"] ?: payloadObj["path"]) as? String
                                    if (path != null) mainViewModel.handleOpenFile(path)
                                }
                                "OPEN_FOLDER" -> {
                                    val path = (payloadObj["Path"] ?: payloadObj["path"]) as? String
                                    if (path != null) mainViewModel.handleOpenFolder(path)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SignalRClient", "Error parsing ReceivePayload", e)
                        mainViewModel.addLog("Error parsing payload: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                    }
                }, Any::class.java)
            }
        
            private fun updateSessionMessages(pid: Int, block: (List<Pair<String, String>>) -> List<Pair<String, String>>) {
                val currentMap = _aiMessagesMap.value
                val sessionMessages = currentMap[pid] ?: emptyList()
                val newMessages = block(sessionMessages)
                _aiMessagesMap.value = currentMap + (pid to newMessages)
                updateActiveView()
            }
        
            private fun updateSessionStatus(pid: Int, status: String?) {
                val currentMap = _aiStatusMap.value
                _aiStatusMap.value = currentMap + (pid to status)
                updateActiveView()
            }
        
            private fun updateActiveView() {
                val pid = _selectedPid.value
                _aiMessages.value = _aiMessagesMap.value[pid] ?: emptyList()
                _aiStatus.value = _aiStatusMap.value[pid]
            }
        
            fun setSelectedPid(pid: Int) {
                _selectedPid.value = pid
                updateActiveView()
            }
        
            fun sendTabToPhone(url: String) {        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendTabToPhone", url)
        }
    }

    fun sendCortexWakeTime(wakeTime: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SetCortexWakeTime", wakeTime)
        }
    }

    fun sendCortexTemplates(templatesJson: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SetCortexTemplates", templatesJson)
        }
    }

        fun sendAiMessage(message: String, pid: Int? = null) {
            if (_isStartingSession) {
                messageQueue.add(message)
                return
            }
    
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                val targetPid = pid ?: _selectedPid.value
                if (!message.startsWith("/")) {
                    updateSessionStatus(targetPid, "AI Thinking...")
                }
                hubConnection?.send("SendAiMessage", message, pid)
            }
        }
    fun getAiSessions() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("GetAiSessions")
        }
    }

        fun requestAiHistory() {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                updateSessionStatus(_selectedPid.value, "Reloading history...")
                hubConnection?.send("RequestAiHistory")
            }
        }
    
        fun switchAiSession(pid: Int) {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                setSelectedPid(pid)
                updateSessionStatus(pid, "Switching session...")
                hubConnection?.send("SwitchAiSession", pid)
            }
        }
        fun startNewAiSession() {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                updateSessionStatus(-1, "Starting new session...")
                _isStartingSession = true
                isStartingSessionFlow.value = true
                messageQueue.clear()
                hubConnection?.send("StartNewAiSession")
            }
        }
    
        fun stopAiSession(pid: Int) {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                updateSessionStatus(pid, "Closing session...")
                hubConnection?.send("StopAiSession", pid)
            }
        }
    
        fun renameAiSession(pid: Int, name: String) {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                hubConnection?.send("RenameAiSession", pid, name)
            }
        }
    fun setAiZoom(pid: Int, level: Double) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SetAiZoom", pid, level)
        }
    }

        fun startCliAtWorkspace(path: String) {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
                _aiStatus.value = "Starting session at workspace..."
                hubConnection?.send("StartCliAtWorkspace", path)
            }
        }
    
        fun clearAiMessages(pid: Int? = null) {
            val targetPid = pid ?: _selectedPid.value
            if (hubConnection != null && hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED && aiSessions.value.isNotEmpty()) {
                hubConnection?.send("SendAiMessage", "/clear", pid)
            }
            
            val currentMap = _aiMessagesMap.value
            _aiMessagesMap.value = currentMap + (targetPid to emptyList())
            updateActiveView()
        }
    
        fun stopConnection() {        reconnectJob?.cancel()
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

    fun getAvailableDrives(): Single<List<FileSystemEntry>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "GetAvailableDrives")
                ?.map { rawList ->
                    val jsonElement = gson.toJsonTree(rawList)
                    val listType = object : TypeToken<List<FileSystemEntry>>() {}.type
                    gson.fromJson(jsonElement, listType)
                } as? Single<List<FileSystemEntry>>
        }
        return null
    }

    fun isMuted(): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "IsMuted")
        }
        return null
    }

    fun writeFileContent(path: String, content: String): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "WriteFileContent", path, content)
        }
        return null
    }

    fun deleteFile(path: String): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "DeleteFile", path)
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