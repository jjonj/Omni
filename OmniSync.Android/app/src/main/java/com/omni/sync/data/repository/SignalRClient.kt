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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.reactivex.rxjava3.core.Single
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

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

data class AiMessage(
    val sender: String,
    val text: String,
    val id: String = java.util.UUID.randomUUID().toString(),
    val isQueued: Boolean = false
)

class SignalRClient(
    private val context: Context,
    private val mainViewModel: MainViewModel
) {
    private var hubConnection: HubConnection? = null
    private val hubUrl: String get() = mainViewModel.appConfig.value.hubUrl
    private val apiKey: String get() = mainViewModel.appConfig.value.apiKey

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
    private val connectionMutex = Mutex()
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private val connectionToken = AtomicLong(0)
    private val activeConnectionToken = AtomicLong(0)
    private val isConnecting = AtomicBoolean(false)
    private val manualStop = AtomicBoolean(false)
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var lastSuccessfulHubUrl: String? = sharedPrefs.getString(KEY_LAST_CONNECTED_HUB_URL, null)
    private var handlersRegistered = false

    @Volatile
    var isUpdatingClipboardInternally: Boolean = false
        private set

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _currentBaseUrl = MutableStateFlow("")
    val currentBaseUrl: StateFlow<String> = _currentBaseUrl

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

    data class AiDialog(val type: String, val prompt: String, val options: List<String>?)

    private val _aiMessagesMap = MutableStateFlow<Map<Int, List<AiMessage>>>(emptyMap())
    val aiMessagesMap: StateFlow<Map<Int, List<AiMessage>>> = _aiMessagesMap
    private val _aiStatusMap = MutableStateFlow<Map<Int, String?>>(emptyMap())
    val aiStatusMap: StateFlow<Map<Int, String?>> = _aiStatusMap
    private val _aiThoughtMap = MutableStateFlow<Map<Int, String?>>(emptyMap())
    private val _aiDialogMap = MutableStateFlow<Map<Int, AiDialog?>>(emptyMap())
    private val _selectedPid = MutableStateFlow(-1)

    private val _isWaitingForAiResponseMap = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val isWaitingForAiResponseMap: StateFlow<Map<Int, Boolean>> = _isWaitingForAiResponseMap

    val selectedPid: StateFlow<Int> = _selectedPid

    private val _aiMessages = MutableStateFlow<List<AiMessage>>(emptyList())
    val aiMessages: StateFlow<List<AiMessage>> = _aiMessages

    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus: StateFlow<String?> = _aiStatus

    private val _aiThought = MutableStateFlow<String?>(null)
    val aiThought: StateFlow<String?> = _aiThought

    private val _aiDialog = MutableStateFlow<AiDialog?>(null)
    val aiDialog: StateFlow<AiDialog?> = _aiDialog

    private val _aiSessions = MutableStateFlow<Map<Int, String>>(emptyMap())
    val aiSessions: StateFlow<Map<Int, String>> = _aiSessions

    private val _aiWorkspaces = MutableStateFlow<Map<Int, String>>(emptyMap())
    val aiWorkspaces: StateFlow<Map<Int, String>> = _aiWorkspaces

    private val _aiPresets = MutableStateFlow<List<String>>(emptyList())
    val aiPresets: StateFlow<List<String>> = _aiPresets

    private val _aiModels = MutableStateFlow<List<String>>(emptyList())
    val aiModels: StateFlow<List<String>> = _aiModels

    private val _defaultAiModel = MutableStateFlow("")
    val defaultAiModel: StateFlow<String> = _defaultAiModel

    private val _aiInputText = MutableStateFlow("")
    val aiInputText: StateFlow<String> = _aiInputText

    fun updateAiInputText(text: String) {
        _aiInputText.value = text
    }

    val anyAiActivityEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val lastCreatedSessionPid = MutableSharedFlow<Int>()

    private var _isStartingSession = false
    private val messageQueue = mutableListOf<String>()
    val isStartingSessionFlow = MutableStateFlow(false)
    private var _isTriggeringTellPcLocal = false
    private var _latestTellPcPid: Int? = null
    private var _latestTellPcTime: Long = 0
    private val _isTriggeringTellPc = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val isTriggeringTellPc = _isTriggeringTellPc.asSharedFlow()

    private val _isNextBubbleMap = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    
    // Per-session stopwatch state
    private val _sessionTimers = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val sessionTimers: StateFlow<Map<Int, Long>> = _sessionTimers

    fun recordSessionActivity(pid: Int) {
        _sessionTimers.value = _sessionTimers.value + (pid to System.currentTimeMillis())
    }

    private fun setIsNextBubble(pid: Int, isNew: Boolean) {
        _isNextBubbleMap.value = _isNextBubbleMap.value + (pid to isNew)
    }

    private fun getIsNextBubble(pid: Int): Boolean {
        return _isNextBubbleMap.value[pid] ?: true
    }

    companion object {
        const val SHARED_PREFS_NAME = "OmniSyncPrefs"
        const val KEY_LAST_CONNECTED_HUB_URL = "last_connected_hub_url"
    }

    fun getRetryDelay(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val cappedAttempt = attempt.coerceAtMost(5)
        val baseDelay = 2000L * (1L shl cappedAttempt)
        val capped = minOf(baseDelay, 60000L)
        val jitter = Random.nextLong(0, 1500)
        return capped + jitter
    }

    private fun onConnected(url: String) {
        _connectionState.value = "Connected"
        val baseUrl = url.substringBefore("/signalrhub")
        _currentBaseUrl.value = baseUrl
        mainViewModel.setActiveBaseUrl(baseUrl)
        mainViewModel.setConnected(true)
        mainViewModel.recordActivity() // Record activity on connect
        authenticateClient()
        getAiSessions()
        getAiModels()
        lastSuccessfulHubUrl = url
        sharedPrefs.edit().putString(KEY_LAST_CONNECTED_HUB_URL, url).apply()
        mainViewModel.addLog("Connected to hub: $url", com.omni.sync.ui.screen.LogType.SUCCESS)

        val hadReconnect = reconnectJob != null
        reconnectJob?.cancel()
        reconnectJob = null
        if (hadReconnect) {
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
        requestConnect(force = false, reason = "start")
    }

    private fun requestConnect(force: Boolean, reason: String) {
        manualStop.set(false)
        reconnectJob?.cancel()
        reconnectJob = null

        val token = connectionToken.incrementAndGet()
        connectJob?.cancel()
        connectJob = coroutineScope.launch {
            connectInternal(token, force = force, reason = reason, allowScheduleReconnect = true)
        }
    }

    private suspend fun connectInternal(
        token: Long,
        force: Boolean,
        reason: String,
        allowScheduleReconnect: Boolean
    ): Boolean {
        if (!force && hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            _connectionState.value = "Connected"
            return true
        }

        while (!isConnecting.compareAndSet(false, true)) {
            if (token != connectionToken.get()) return false
            delay(200)
        }

        try {
            _connectionState.value = if (allowScheduleReconnect) "Connecting..." else "Reconnecting..."
            mainViewModel.setErrorMessage(null)

            stopHubConnectionInternal()

            val candidates = resolveCandidateUrls()
            if (candidates.isEmpty()) {
                _connectionState.value = "Disconnected (No hub URL)"
                mainViewModel.setConnected(false)
                return false
            }

            mainViewModel.addLog("Connecting (reason: $reason). Candidates: ${candidates.joinToString()}", com.omni.sync.ui.screen.LogType.INFO)

            for (url in candidates) {
                if (token != connectionToken.get()) return false
                val success = tryConnect(url, token)
                if (success) return true
            }

            _connectionState.value = "Disconnected (All attempts failed)"
            mainViewModel.setConnected(false)
            if (allowScheduleReconnect && !manualStop.get()) {
                scheduleReconnect("All attempts failed")
            }
            return false
        } finally {
            isConnecting.set(false)
        }
    }

    private fun resolveCandidateUrls(): List<String> {
        val localUrl = mainViewModel.appConfig.value.hubUrl.trim()
        val remoteUrl = "http://${mainViewModel.appConfig.value.wanIp}:5000/signalrhub".trim()

        val ordered = mutableListOf<String>()
        if (!lastSuccessfulHubUrl.isNullOrBlank()) ordered.add(lastSuccessfulHubUrl!!)

        if (isWifiConnected()) {
            ordered.add(localUrl)
            ordered.add(remoteUrl)
        } else {
            ordered.add(remoteUrl)
            ordered.add(localUrl)
        }

        return ordered.filter { it.isNotBlank() }.distinct()
    }

    private suspend fun tryConnect(url: String, token: Long): Boolean {
        _connectionState.value = "Connecting..."
        mainViewModel.addLog("Attempting connection: $url", com.omni.sync.ui.screen.LogType.INFO)

        val connection = HubConnectionBuilder.create(url).build()
        hubConnection = connection
        handlersRegistered = false
        activeConnectionToken.set(token)

        connection.onClosed { error ->
            handleConnectionClosed(error, token)
        }

        registerHubHandlers()

        return try {
            connection.start()
                .timeout(10, TimeUnit.SECONDS)
                .blockingAwait()

            if (token != connectionToken.get()) {
                stopHubConnectionInternal(connection)
                return false
            }

            onConnected(url)
            mainViewModel.addLog("Connected successfully to: $url", com.omni.sync.ui.screen.LogType.SUCCESS)
            true
        } catch (e: Exception) {
            Log.e("SignalRClient", "Connection error for $url: ${e.message}")
            mainViewModel.addLog("Connection error ($url): ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
            stopHubConnectionInternal(connection)
            false
        }
    }

    private fun handleConnectionClosed(error: Throwable?, token: Long) {
        if (token != activeConnectionToken.get()) {
            Log.d("SignalRClient", "Ignoring onClosed for stale connection (token $token)")
            return
        }

        val reason = error?.message ?: "Unknown reason"
        _connectionState.value = "Disconnected: $reason"
        mainViewModel.setConnected(false)
        mainViewModel.setScheduledShutdownTime(null)
        try {
            removeHubHandlers()
        } catch (_: Exception) {
        }
        hubConnection = null

        if (manualStop.get()) {
            mainViewModel.addLog("Disconnected (manual stop).", com.omni.sync.ui.screen.LogType.INFO)
            return
        }

        if (isConnecting.get()) {
            mainViewModel.addLog("Connection closed during connect: $reason", com.omni.sync.ui.screen.LogType.WARNING)
            return
        }

        // Suspect sleep if disconnected (e.g. PC shutdown at night)
        mainViewModel.startSleep()
        mainViewModel.addLog("Connection closed. Reason: $reason. Starting auto-reconnect...", com.omni.sync.ui.screen.LogType.WARNING)
        scheduleReconnect("Closed: $reason")
    }

    private fun scheduleReconnect(reason: String) {
        if (manualStop.get()) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = coroutineScope.launch {
            // RE-EVALUATE IP ON FAILURE
            updateHubUrlBasedOnLocalIp()
            
            var attempt = 0
            while (!manualStop.get()) {
                val delayMs = getRetryDelay(attempt)
                if (delayMs > 0) {
                    _connectionState.value = "Reconnecting in ${delayMs / 1000}s..."
                    mainViewModel.addLog("Reconnecting in ${delayMs / 1000}s (Attempt ${attempt + 1})...", com.omni.sync.ui.screen.LogType.INFO)
                    delay(delayMs)
                } else {
                    mainViewModel.addLog("Attempting immediate reconnection...", com.omni.sync.ui.screen.LogType.INFO)
                }

                val token = connectionToken.incrementAndGet()
                val success = connectInternal(token, force = true, reason = reason, allowScheduleReconnect = false)
                if (success) return@launch
                attempt++
            }
        }
    }

    private suspend fun stopHubConnectionInternal(target: HubConnection? = null) {
        connectionMutex.withLock {
            val hub = target ?: hubConnection ?: return
            val isCurrent = hubConnection === hub
            if (isCurrent) {
                try {
                    removeHubHandlers()
                } catch (_: Exception) {
                }
            }
            try {
                hub.stop()
                    .timeout(5, TimeUnit.SECONDS)
                    .blockingAwait()
            } catch (e: Exception) {
                Log.w("SignalRClient", "Error stopping hub connection: ${e.message}")
            } finally {
                if (isCurrent) {
                    hubConnection = null
                }
            }
        }
    }

    private fun registerHubHandlers() {
        if (handlersRegistered) return
        handlersRegistered = true
        
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
            mainViewModel.addLog("[Remote] Hub reports $modifierName is ${if (isPressed) "DOWN" else "UP"}", com.omni.sync.ui.screen.LogType.INFO)
            when (modifierName) {
                "Shift" -> mainViewModel.setShiftPressed(isPressed)
                "Ctrl" -> mainViewModel.setCtrlPressed(isPressed)
                "Alt" -> mainViewModel.setAltPressed(isPressed)
                "Win" -> mainViewModel.setWinPressed(isPressed)
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
            val senderName = if (senderId == hubConnection?.connectionId || senderId == "CLI_USER") "Me" else "User"
            updateSessionMessages(pid) { it + AiMessage(senderName, message) }
        }, String::class.java, String::class.java, Int::class.java)

        hubConnection?.on("ReceiveAiResponse", { response: String, pid: Int ->
            Log.d("SignalRClient", "ReceiveAiResponse: pid=$pid, text=${response.take(20)}...")
            handleAiResponse(response, pid)
        }, String::class.java, Int::class.java)

        hubConnection?.on("ReceiveAiThought", { thought: String, pid: Int ->
            Log.d("SignalRClient", "ReceiveAiThought: pid=$pid")
            mainViewModel.addLog("[AI] Thought from PID $pid (${thought.length} chars)", com.omni.sync.ui.screen.LogType.INFO)
            recordSessionActivity(pid)
            updateSessionThought(pid, thought)
        }, String::class.java, Int::class.java)

        hubConnection?.on("ReceiveAiCodeDiff", { diff: String, pid: Int ->
            Log.d("SignalRClient", "ReceiveAiCodeDiff: pid=$pid")
            mainViewModel.addLog("[AI] Code diff from PID $pid (${diff.length} chars)", com.omni.sync.ui.screen.LogType.INFO)
            handleAiCodeDiff(diff, pid)
        }, String::class.java, Int::class.java)

        hubConnection?.on("ReceiveAiStatus", { status: String?, pid: Int ->
            mainViewModel.addLog("[AI] Status update for PID $pid: ${status ?: "NULL"}", com.omni.sync.ui.screen.LogType.INFO)
            if (status == "FINISHED" || status == "DONE" || status == null || status.isBlank()) {
                updateSessionStatus(pid, null)
                updateSessionThought(pid, null)
                updateSessionDialog(pid, null)
                _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value + (pid to false)
                setIsNextBubble(pid, true)
                // Clear queued status from all messages in this session
                updateSessionMessages(pid) { messages ->
                    messages.map { it.copy(isQueued = false) }
                }
            } else if (status == "QUEUED") {
                updateSessionStatus(pid, "Queued (AI busy)")
                _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value + (pid to false)
                // Mark the latest message from "Me" as queued
                updateSessionMessages(pid) { messages ->
                    val lastMeIndex = messages.indexOfLast { it.sender == "Me" }
                    if (lastMeIndex != -1) {
                        messages.toMutableList().also {
                            it[lastMeIndex] = it[lastMeIndex].copy(isQueued = true)
                        }
                    } else messages
                }
            } else {
                updateSessionStatus(pid, status)
                _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value + (pid to false)
                if (status == "AI Thinking...") {
                    setIsNextBubble(pid, true)
                }
                // Clear queued status when AI starts responding or thinking
                updateSessionMessages(pid) { messages ->
                    messages.map { it.copy(isQueued = false) }
                }
            }
        }, String::class.java, Int::class.java)

        hubConnection?.on("ReceiveAiPresets", { presets: List<String> ->
            _aiPresets.value = presets
        }, List::class.java)

        hubConnection?.on("ReceiveAiModels", { models: List<String> ->
            _aiModels.value = models
        }, List::class.java)

        hubConnection?.on("ReceiveDefaultAiModel", { model: String ->
            _defaultAiModel.value = model
        }, String::class.java)

                  hubConnection?.on("ReceiveNewAiSessionPid", { pid: Int ->
                      Log.e("SignalRClient", "DEBUG: Received ReceiveNewAiSessionPid: $pid")
                      getAiSessions()
                      val wasStartingOurOwn = _isStartingSession
                      val wasTellPc = _isTriggeringTellPcLocal
                      Log.e("SignalRClient", "DEBUG: wasStartingOurOwn=$wasStartingOurOwn, wasTellPc=$wasTellPc")
                      
                      if (wasTellPc) {
                          _latestTellPcPid = pid
                          _latestTellPcTime = System.currentTimeMillis()
                      }

                      _isStartingSession = false
                      isStartingSessionFlow.value = false
                      _isTriggeringTellPcLocal = false
                      updateSessionStatus(pid, null)
                      updateSessionStatus(-1, null) // Clear the "starting" session status too
                      Log.e("SignalRClient", "DEBUG: Reset session flags and updated statuses.")
            if (wasStartingOurOwn || wasTellPc) {
                // mainViewModel.addLog("[AI] Switching to new session PID $pid", com.omni.sync.ui.screen.LogType.INFO) // REMOVED redundant log
                switchAiSession(pid) // Notifies Hub AND updates local state
            }
            
            coroutineScope.launch { lastCreatedSessionPid.emit(pid) }

            // Flush queue
            if (messageQueue.isNotEmpty()) {
                mainViewModel.addLog("[AI] Flushing ${messageQueue.size} queued messages to PID $pid", com.omni.sync.ui.screen.LogType.INFO)
                messageQueue.forEach { msg ->
                    sendAiMessage(msg, pid)
                }
                messageQueue.clear()
            }

            // Clear the temporary -1 session state
            _aiMessagesMap.value = _aiMessagesMap.value - (-1)
            _aiStatusMap.value = _aiStatusMap.value - (-1)
            _aiThoughtMap.value = _aiThoughtMap.value - (-1)
            _aiDialogMap.value = _aiDialogMap.value - (-1)
        }, Int::class.java)

        hubConnection?.on("ReceiveCortexActivity", { name: String, type: String ->
            mainViewModel.onCortexActivityChanged(name, type)
        }, String::class.java, String::class.java)

        hubConnection?.on("ReceiveAiSessions", { sessionsData: List<Any> ->
            try {
                val jsonStr = gson.toJson(sessionsData)
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val sessionsList: List<Map<String, Any>> = gson.fromJson(jsonStr, type)
                
                // Maintain order from Hub (sorted by start time)
                val sessionsMap = LinkedHashMap<Int, String>()
                val workspacesMap = mutableMapOf<Int, String>()
                sessionsList.forEach { session ->
                    val pidRaw = session["pid"] ?: session["Pid"]
                    val pid = when (pidRaw) {
                        is Double -> pidRaw.toInt()
                        is Int -> pidRaw
                        is Long -> pidRaw.toInt()
                        is Float -> pidRaw.toInt()
                        is String -> pidRaw.toIntOrNull() ?: 0
                        else -> 0
                    }
                    val name = (session["name"] ?: session["Name"]) as? String ?: "Session $pid"
                    val workspace = (session["workspace"] ?: session["Workspace"]) as? String ?: ""
                    if (pid != 0) {
                        sessionsMap[pid] = name
                        workspacesMap[pid] = workspace
                    }
                }

                Log.d("SignalRClient", "ReceiveAiSessions: Found ${sessionsMap.size} sessions")
                _aiSessions.value = sessionsMap
                _aiWorkspaces.value = workspacesMap
                
                // Clean up statuses for sessions that no longer exist
                val currentStatusMap = _aiStatusMap.value
                val keysToRemove = currentStatusMap.keys.filter { it != -1 && !sessionsMap.containsKey(it) }
                if (keysToRemove.isNotEmpty()) {
                    _aiStatusMap.value = currentStatusMap - keysToRemove.toSet()
                }

                // Auto-selection logic
                val currentPid = _selectedPid.value
                if (currentPid != -1 && !sessionsMap.containsKey(currentPid)) {
                    // Current session died
                    mainViewModel.addLog("[AI] Current session PID $currentPid died.", com.omni.sync.ui.screen.LogType.WARNING)
                    if (sessionsMap.isNotEmpty()) setSelectedPid(sessionsMap.keys.first())
                    else setSelectedPid(-1)
                } else if (currentPid == -1 && sessionsMap.isNotEmpty() && !_isStartingSession) {
                    // We were on -1 (maybe initial state) and sessions exist, pick the first one
                    setSelectedPid(sessionsMap.keys.first())
                }
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing AI sessions", e)
            }
        }, List::class.java)

        hubConnection?.on("ReceiveAiHistory", { historyJson: String, pid: Int ->
            try {
                Log.d("SignalRClient", "ReceiveAiHistory from Hub: pid=$pid, json length=${historyJson.length}")
                mainViewModel.addLog("[AI] Received history for PID $pid (${historyJson.length} bytes)", com.omni.sync.ui.screen.LogType.SUCCESS)
                
                if (pid == -1 && (historyJson == "[]" || historyJson.isNullOrBlank())) {
                    mainViewModel.addLog("[AI] History for -1 was empty, ignoring.", com.omni.sync.ui.screen.LogType.INFO)
                    return@on
                }

                val type = object : TypeToken<List<Map<String, String>>>() {}.type
                val history: List<Map<String, String>> = gson.fromJson(historyJson, type)
                mainViewModel.addLog("[AI] Successfully parsed ${history.size} history items for PID $pid", com.omni.sync.ui.screen.LogType.INFO)
                
                val mappedHistory = history.map { 
                    val sender = it["sender"] ?: "Unknown"
                    val text = it["text"] ?: ""
                    
                    val mappedSender = if (sender == "AI" || sender == "System" || sender == "Unknown") {
                        when {
                            text.startsWith("Error:") -> "Error"
                            text.contains("A new version of Gemini CLI is available") || 
                            text.startsWith("System:") || 
                            text.startsWith("Info:") ||
                            text.startsWith("Replacement") ||
                            text.startsWith("Read") ||
                            text.startsWith("Tool Call") ||
                            text.startsWith("Thinking") ||
                            text.startsWith("Executing") -> "System"
                            else -> "AI"
                        }
                    } else {
                        sender 
                    }
                    AiMessage(mappedSender, text)
                }
                
                _aiMessagesMap.value = _aiMessagesMap.value + (pid to mappedHistory)
                updateSessionStatus(pid, null)
                setIsNextBubble(pid, true)
                updateActiveView()
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing AI history", e)
                mainViewModel.addLog("[AI] Failed to parse history for PID $pid: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
            }
        }, String::class.java, Int::class.java)

        hubConnection?.on("ReceiveAiDialog", { pid: Int, type: String, prompt: String, options: List<String>? ->
            Log.d("SignalRClient", "ReceiveAiDialog from PID $pid: $type - $prompt")
            updateSessionDialog(pid, AiDialog(type, prompt, options))
        }, Int::class.java, String::class.java, String::class.java, List::class.java)

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

    private fun updateSessionMessages(pid: Int, block: (List<AiMessage>) -> List<AiMessage>) {
        val currentMap = _aiMessagesMap.value
        val sessionMessages = currentMap[pid] ?: emptyList()
        var newMessages = block(sessionMessages)
        
        // --- Truncation Logic ---
        val maxChars = mainViewModel.appConfig.value.maxAiHistory
        if (maxChars > 0) {
            var currentTotal = newMessages.sumOf { it.text.length }
            if (currentTotal > maxChars) {
                val truncated = mutableListOf<AiMessage>()
                var runningTotal = 0
                // Keep the NEWEST messages (they are at the end of the list)
                for (i in newMessages.indices.reversed()) {
                    val msg = newMessages[i]
                    if (runningTotal + msg.text.length <= maxChars) {
                        truncated.add(0, msg)
                        runningTotal += msg.text.length
                    } else {
                        // Part of this message or previous ones are too much
                        // For simplicity, we just stop adding whole bubbles.
                        break
                    }
                }
                newMessages = truncated
            }
        }
        // ------------------------

        _aiMessagesMap.value = currentMap + (pid to newMessages)
        updateActiveView()
    }

    private fun updateSessionStatus(pid: Int, status: String?) {
        val effectiveStatus = if (status == "FINISHED") null else status
        val currentMap = _aiStatusMap.value
        _aiStatusMap.value = currentMap + (pid to effectiveStatus)
        updateActiveView()
    }

    private fun updateSessionThought(pid: Int, thought: String?) {
        val currentMap = _aiThoughtMap.value
        _aiThoughtMap.value = currentMap + (pid to thought)
        updateActiveView()
    }

    private fun updateSessionDialog(pid: Int, dialog: AiDialog?) {
        val currentMap = _aiDialogMap.value
        _aiDialogMap.value = currentMap + (pid to dialog)
        updateActiveView()
    }

    private fun updateActiveView() {
        val pid = _selectedPid.value
        _aiMessages.value = _aiMessagesMap.value[pid] ?: emptyList()
        _aiStatus.value = _aiStatusMap.value[pid]
        _aiThought.value = _aiThoughtMap.value[pid]
        _aiDialog.value = _aiDialogMap.value[pid]
    }

    fun setSelectedPid(pid: Int) {
        mainViewModel.addLog("[AI] Selected PID set to: $pid", com.omni.sync.ui.screen.LogType.INFO)
        _selectedPid.value = pid
        updateActiveView()
    }

    fun sendTabToPhone(url: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
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

    fun sendAiSpecialKey(key: String, pid: Int? = null) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val targetPid = pid ?: _selectedPid.value
            hubConnection?.send("SendAiSpecialKey", key, targetPid)
        }
    }

        fun sendAiYolo(pid: Int? = null) {

            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {

                val targetPid = pid ?: _selectedPid.value

                hubConnection?.send("SendAiYolo", targetPid)

            }

        }

    

        fun moveAiSessionToMonitor(pid: Int, monitorIndex: Int) {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                hubConnection?.send("MoveAiSessionToMonitor", pid, monitorIndex)
            }
        }

        fun toggleAiSessionMonitor(pid: Int) {
            if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                val payload = mapOf("Pid" to pid)
                hubConnection?.send("SendPayload", "MOVE_WINDOW_OPPOSITE", payload)
            }
        }

    

        fun sendAiDialogResponse(response: String, pid: Int? = null) {

    
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val targetPid = pid ?: _selectedPid.value
            hubConnection?.send("SendAiDialogResponse", response, targetPid)
            // Clear local dialog after responding
            updateSessionDialog(targetPid, null)
        }
    }

    fun sendAiMessage(message: String, pid: Int? = null) {
        var targetPid = pid ?: _selectedPid.value

        // Resolve pending Tell PC PID if target is -1
        if (targetPid == -1 && _latestTellPcPid != null) {
            val delta = System.currentTimeMillis() - _latestTellPcTime
            if (delta < 10000) { // 10 seconds validity
                 targetPid = _latestTellPcPid!!
                 mainViewModel.addLog("[AI] Resolved -1 to recent Tell PC PID $targetPid", com.omni.sync.ui.screen.LogType.INFO)
            }
        }
        
        if (targetPid == -1 || (_isStartingSession && pid == null) || (_isTriggeringTellPcLocal && targetPid == -1)) {
            messageQueue.add(message)
            // Immediate feedback for queued messages
            updateSessionMessages(-1) { it + AiMessage("Me", message) }
            
            if (targetPid == -1 && !message.startsWith("/") && !_isTriggeringTellPcLocal) {
                // Auto-create session
                startNewAiSession()
            }
            return
        }

        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            if (!message.startsWith("/")) {
                updateSessionStatus(targetPid, "AI Thinking...")
                _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value + (targetPid to true)
            }
            
            val hubPid = if (targetPid <= 0) null else targetPid
            hubConnection?.send("SendAiMessage", message, hubPid)
        }
    }

    fun getAiSessions() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("GetAiSessions")
        }
    }

    fun requestAiHistory(pid: Int? = null) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            val targetPid = pid ?: _selectedPid.value
            
            // If we already have messages for this session, don't clear them immediately
            // to allow for a smoother transition, but still show reloading status.
            val currentMessages = _aiMessagesMap.value[targetPid]
            if (currentMessages.isNullOrEmpty()) {
                _aiMessagesMap.value = _aiMessagesMap.value + (targetPid to emptyList())
            }
            updateActiveView()
            
            updateSessionStatus(targetPid, "Reloading history...")
            mainViewModel.addLog("[AI] Requesting history for PID $targetPid (max: ${mainViewModel.appConfig.value.maxAiHistory} chars)", com.omni.sync.ui.screen.LogType.INFO)
            val hubPid = if (targetPid == -1) null else targetPid
            hubConnection?.send("RequestAiHistory", hubPid, mainViewModel.appConfig.value.maxAiHistory)
        }
    }

    fun switchAiSession(pid: Int) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            _isStartingSession = false
            isStartingSessionFlow.value = false
            messageQueue.clear()

            mainViewModel.addLog("[AI] Switching to session PID $pid", com.omni.sync.ui.screen.LogType.INFO)
            setSelectedPid(pid)
            updateSessionStatus(pid, "Switching session...")
            hubConnection?.send("SwitchAiSession", pid, mainViewModel.appConfig.value.maxAiHistory)
        }
    }

    fun startNewAiSession(workspace: String? = null) {
        Log.e("SignalRClient", "DEBUG: startNewAiSession CALLED with workspace: $workspace")
        android.widget.Toast.makeText(context, "AI Launching: $workspace", android.widget.Toast.LENGTH_SHORT).show()
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            if (_isStartingSession) {
                Log.d("SignalRClient", "Already starting a session, ignoring request.")
                return
            }
            
            _isStartingSession = true
            isStartingSessionFlow.value = true
            messageQueue.clear()

            // Ensure -1 session is clean and switch to it immediately
            _aiMessagesMap.value = _aiMessagesMap.value + (-1 to emptyList())
            setSelectedPid(-1)
            updateSessionStatus(-1, "Starting new session...")

            try {
                Log.e("SignalRClient", "DEBUG: Sending StartNewAiSessionAndroid to Hub with workspace: $workspace")
                hubConnection?.send("StartNewAiSessionAndroid", workspace)
                Log.e("SignalRClient", "DEBUG: StartNewAiSessionAndroid send call completed successfully.")
            } catch (e: Exception) {
                Log.e("SignalRClient", "ERROR: Failed to send StartNewAiSessionAndroid: ${e.message}")
                android.widget.Toast.makeText(context, "SignalR Send Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stopAiSession(pid: Int) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            updateSessionStatus(pid, "Closing session...")
            try {
                hubConnection?.send("StopAiSession", pid)
            } catch (e: Exception) {
                Log.e("SignalRClient", "ERROR: Failed to send StopAiSession: ${e.message}")
            }
        }
    }

    fun triggerTellPc() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            if (_isTriggeringTellPcLocal) return
            _isTriggeringTellPcLocal = true
            coroutineScope.launch { _isTriggeringTellPc.emit(Unit) }
            hubConnection?.send("TriggerTellPc")
        }
    }

    fun renameAiSession(pid: Int, name: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            hubConnection?.send("RenameAiSession", pid, name)
        }
    }

    fun clearSessions() {
        _aiSessions.value = emptyMap()
        _aiWorkspaces.value = emptyMap()
    }

    fun reloadAiSessions() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            mainViewModel.addLog("[AI] Requesting session reload and debug report...", com.omni.sync.ui.screen.LogType.INFO)
            
            // Construct the list of current sessions to send to Hub
            val currentSessions = _aiSessions.value.map { (pid, name) ->
                mapOf(
                    "pid" to pid,
                    "name" to name,
                    "workspace" to (_aiWorkspaces.value[pid] ?: "")
                )
            }
            
            hubConnection?.send("ReloadAiSessions", currentSessions)
        }
    }

    fun focusAiSession(pid: Int) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            hubConnection?.send("FocusAiSession", pid)
        }
    }

    fun getAiPresets() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            hubConnection?.send("GetAiPresets")
        }
    }

    fun getAiModels() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("GetAiModels")
        }
    }

    fun addAiPreset(preset: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            hubConnection?.send("AddAiPreset", preset)
        }
    }

    fun removeAiPreset(preset: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            hubConnection?.send("RemoveAiPreset", preset)
        }
    }

    fun setDefaultAiModel(model: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SetDefaultAiModel", model)
        }
    }

    fun resetAiSessions() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            mainViewModel.addLog("[AI] Requesting session reset (Nuke all)...", com.omni.sync.ui.screen.LogType.WARNING)
            hubConnection?.send("ResetAiSessions")
            // Clear local state
            _aiMessagesMap.value = emptyMap()
            _aiStatusMap.value = emptyMap()
            _aiThoughtMap.value = emptyMap()
            _aiDialogMap.value = emptyMap()
            setSelectedPid(-1)
            updateActiveView()
        }
    }

    fun setAiZoom(pid: Int, level: Double) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SetAiZoom", pid, level)
        }
    }

    fun startCliAtWorkspace(path: String) {
        Log.e("SignalRClient", "DEBUG: startCliAtWorkspace CALLED with path: $path")
        android.widget.Toast.makeText(context, "AI Workspace: $path", android.widget.Toast.LENGTH_SHORT).show()
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            _aiStatus.value = "Starting session at workspace..."
            try {
                Log.e("SignalRClient", "DEBUG: Sending StartCliAtWorkspaceAndroid to Hub with path: $path")
                hubConnection?.send("StartCliAtWorkspaceAndroid", path)
                Log.e("SignalRClient", "DEBUG: StartCliAtWorkspaceAndroid send call completed.")
            } catch (e: Exception) {
                Log.e("SignalRClient", "ERROR: Failed to send StartCliAtWorkspaceAndroid: ${e.message}")
                android.widget.Toast.makeText(context, "SignalR Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun getMacros(): Single<List<com.omni.sync.data.model.Macro>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "GetMacros")
                ?.map { rawList ->
                    val jsonElement = gson.toJsonTree(rawList)
                    val listType = object : TypeToken<List<com.omni.sync.data.model.Macro>>() {}.type
                    gson.fromJson(jsonElement, listType)
                } as? Single<List<com.omni.sync.data.model.Macro>>
        }
        return null
    }

    fun saveMacro(macro: com.omni.sync.data.model.Macro) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SaveMacro", macro)
        }
    }

    fun deleteMacro(id: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("DeleteMacro", id)
        }
    }

    fun clearAiMessages(pid: Int? = null) {
        val targetPid = pid ?: _selectedPid.value
        if (hubConnection != null && hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED && aiSessions.value.containsKey(targetPid)) {
            hubConnection?.send("SendAiMessage", "/clear", targetPid)
        }

        // Insta-clear local view for this session
        _aiMessagesMap.value = _aiMessagesMap.value.toMutableMap().apply {
            put(targetPid, emptyList())
        }
        updateActiveView()

        // Repopulate (request history from Hub which should now be empty or have a system message)
        requestAiHistory(targetPid)
    }

    fun stopConnection() {
        manualStop.set(true)
        connectJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        connectionToken.incrementAndGet()
        coroutineScope.launch {
            stopHubConnectionInternal()
            _connectionState.value = "Disconnected"
            mainViewModel.setConnected(false)
        }
    }

    fun manualReconnect() {
        requestConnect(force = true, reason = "manual")
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

    fun getHubStatus(): Single<Map<String, Any>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Any::class.java, "GetHubStatus")
                ?.map { rawResponse ->
                    try {
                        val jsonElement = gson.toJsonTree(rawResponse)
                        val mapType = object : TypeToken<Map<String, Any>>() {}.type
                        gson.fromJson(jsonElement, mapType)
                    } catch (e: Exception) {
                        emptyMap<String, Any>()
                    }
                } as? Single<Map<String, Any>>
        }
        return null
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

    fun processMacro(script: String): Single<String>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "ProcessMacro", script)
        }
        return null
    }

    fun winActivate(target: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("WinActivate", target)
        }
    }

    fun winClose(target: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("WinClose", target)
        }
    }

    fun listDirectory(path: String): Single<List<FileSystemEntry>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "ListDirectory", path)
                ?.map { rawList ->
                    val jsonElement = gson.toJsonTree(rawList)
                    val listType = object : TypeToken<List<FileSystemEntry>>() {}.type
                    gson.fromJson(jsonElement, listType)
                } as? Single<List<FileSystemEntry>>
        }
        return null
    }

    fun getFileInfo(path: String): Single<FileSystemEntry>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(FileSystemEntry::class.java, "GetFileInfo", path)
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

    fun sendUnicodeEvent(command: String, char: Char) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf("Char" to char.toString())
            hubConnection?.send("SendPayload", command, payload)
        }
    }

    fun sendUnicodePress(char: Char) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            sendUnicodeEvent("INPUT_UNICODE_DOWN", char)
            sendUnicodeEvent("INPUT_UNICODE_UP", char)
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

    fun sendCleanupPatterns(patterns: List<String>) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendCleanupPatterns", patterns)
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

    fun getHubLog(): Single<List<String>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "GetHubLog")
                ?.map { rawList ->
                    val jsonElement = gson.toJsonTree(rawList)
                    val listType = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(jsonElement, listType)
                } as? Single<List<String>>
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

    fun copyFile(source: String, dest: String): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "CopyFile", source, dest)
        }
        return null
    }

    fun moveFile(source: String, dest: String): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "MoveFile", source, dest)
        }
        return null
    }

    fun isGitRepository(path: String): Single<Boolean>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(Boolean::class.java, "IsGitRepository", path)
        }
        return null
    }

    fun getGitLog(path: String, count: Int = 20): Single<String>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "GetGitLog", path, count)
        }
        return null
    }

    fun getCommitDiff(path: String, commitHash: String): Single<String>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(String::class.java, "GetCommitDiff", path, commitHash)
        }
        return null
    }

    fun executeMacroBatch(commands: List<com.omni.sync.logic.macro.MacroCommand>) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("ExecuteMacro", commands)
        }
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

    private fun handleAiResponse(response: String, pid: Int) {
        if (response == "[TURN_FINISHED]") {
            updateSessionStatus(pid, null)
            _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value + (pid to false)
            setIsNextBubble(pid, true)
            return
        }

        if (response.isBlank()) return

        mainViewModel.addLog("[AI] Response from PID $pid (${response.length} chars)", com.omni.sync.ui.screen.LogType.INFO)

        // Update session idle timer on AI activity
        recordSessionActivity(pid)

        // Notify any AI activity
        coroutineScope.launch { anyAiActivityEvent.emit(Unit) }

        // If we receive a real response, clear any "Switching..." status
        val currentStatus = _aiStatusMap.value[pid]
        if (currentStatus?.contains("Switching") == true || currentStatus?.contains("Reloading") == true || currentStatus?.contains("Thinking") == true) {
            updateSessionStatus(pid, null)
        }
        _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value + (pid to false)
        updateSessionThought(pid, null)

        val isError = response.startsWith("Error:")
        val isSystem = response.contains("A new version of Gemini CLI is available") ||
                response.startsWith("System:") ||
                response.startsWith("Info:") ||
                response.startsWith("Replacement") ||
                response.startsWith("Read") ||
                response.startsWith("Tool Call") ||
                response.startsWith("Thinking") ||
                response.startsWith("Executing")

        val sender = when {
            isError -> "Error"
            isSystem -> "System"
            else -> "AI"
        }

        updateSessionMessages(pid) { currentMessages ->
            val mutable = currentMessages.toMutableList()
            val isNextNew = getIsNextBubble(pid)
            
            if (!isNextNew && !isError && !isSystem && mutable.isNotEmpty() && mutable.last().sender == "AI") {
                val lastMsg = mutable.last()
                mutable[mutable.size - 1] = lastMsg.copy(text = lastMsg.text + response)
                mutable
            } else {
                if (!isError && !isSystem) setIsNextBubble(pid, false)
                mutable + AiMessage(sender, response)
            }
        }
    }

    private fun handleAiCodeDiff(diff: String, pid: Int) {
        if (diff.isBlank()) return
        
        // Update session idle timer on AI activity
        recordSessionActivity(pid)

        // Notify any AI activity
        coroutineScope.launch { anyAiActivityEvent.emit(Unit) }
        
        updateSessionThought(pid, null)
        
        // Always treat code diffs as new bubbles for proper rendering
        updateSessionMessages(pid) { currentMessages ->
            currentMessages + AiMessage("CodeDiff", diff)
        }
        setIsNextBubble(pid, true)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SignalRClient", "Error getting local IP address", e)
        }
        return null
    }

    private fun updateHubUrlBasedOnLocalIp() {
        val localIp = getLocalIpAddress() ?: return
        mainViewModel.addLog("Local IP detected: $localIp", com.omni.sync.ui.screen.LogType.INFO)
        
        val newHubIp = when {
            localIp.startsWith("192.") -> "192.168.0.37"
            localIp.startsWith("10.") -> "10.0.0.37"
            else -> null
        }
        
        if (newHubIp != null) {
            val currentHubUrl = mainViewModel.appConfig.value.hubUrl
            val newHubUrl = "http://$newHubIp:5000/signalrhub"
            
            if (currentHubUrl != newHubUrl) {
                mainViewModel.addLog("Updating Hub URL to $newHubUrl based on local IP $localIp", com.omni.sync.ui.screen.LogType.INFO)
                mainViewModel.updateConfig { it.copy(hubUrl = newHubUrl) }
            }
        }
    }

    private fun removeHubHandlers() {
        if (!handlersRegistered) return
        handlersRegistered = false
        
        val hub = hubConnection ?: return
        hub.remove("ClipboardUpdated")
        hub.remove("InjectText")
        hub.remove("ReceiveCommandOutput")
        hub.remove("ModifierStateUpdated")
        hub.remove("ShutdownScheduled")
        hub.remove("ShutdownModeUpdated")
        hub.remove("FileChanged")
        hub.remove("ReceiveCleanupPatterns")
        hub.remove("ReceiveAvailableDrives")
        hub.remove("ReceiveTabInfo")
        hub.remove("ReceiveTabList")
        hub.remove("ReceiveTabToPhone")
        hub.remove("ReceiveAiMessage")
        hub.remove("ReceiveAiResponse")
        hub.remove("ReceiveAiThought")
        hub.remove("ReceiveAiCodeDiff")
        hub.remove("ReceiveAiStatus")
        hub.remove("ReceiveNewAiSessionPid")
        hub.remove("ReceiveCortexActivity")
        hub.remove("ReceiveAiSessions")
        hub.remove("ReceiveAiHistory")
        hub.remove("ReceiveAiDialog")
        hub.remove("ReceivePayload")
    }
}
