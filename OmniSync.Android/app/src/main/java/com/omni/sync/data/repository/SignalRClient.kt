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
import com.omni.sync.OmniSyncApplication
import com.omni.sync.service.OmniAccessibilityService
import com.omni.sync.update.AndroidUpdateReceiver
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.utils.NetworkDebugger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import io.reactivex.rxjava3.core.Completable
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

data class T3BusyPolicyState(
    val keepT3Busy: Boolean,
    val shutdownWhenIdle: Boolean,
    val busyUntil: Long?
)

data class AndroidAlarmActionData(
    val id: Int = 0,
    val token: String = "",
    val action: String = ""
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
    val isQueued: Boolean = false,
    val isTerminal: Boolean = false
)

data class AssistantVoiceAck(
    @SerializedName(value = "summary", alternate = ["Summary"]) val summary: String = "",
    @SerializedName(value = "runId", alternate = ["RunId"]) val runId: String? = null,
    @SerializedName(value = "runState", alternate = ["RunState"]) val runState: String? = null,
    @SerializedName(value = "commandId", alternate = ["CommandId"]) val commandId: String? = null,
    @SerializedName(value = "decision", alternate = ["Decision"]) val decision: String? = null
)

data class AssistantRunUpdateData(
    @SerializedName(value = "runId", alternate = ["RunId"]) val runId: String = "",
    @SerializedName(value = "requestId", alternate = ["RequestId"]) val requestId: String = "",
    @SerializedName(value = "commandId", alternate = ["CommandId"]) val commandId: String? = null,
    @SerializedName(value = "state", alternate = ["State"]) val state: String = "",
    @SerializedName(value = "reason", alternate = ["Reason"]) val reason: String? = null,
    @SerializedName(value = "summary", alternate = ["Summary"]) val summary: String? = null
)

data class AssistantDeviceArgumentData(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String
)

data class AssistantDeviceExampleData(
    val utterance: String,
    val arguments: Map<String, String> = emptyMap()
)

data class AssistantDeviceCommandDescriptorData(
    val id: String,
    val version: Int,
    val description: String,
    val patterns: List<String>,
    val arguments: List<AssistantDeviceArgumentData> = emptyList(),
    val examples: List<AssistantDeviceExampleData> = emptyList(),
    val acceptsRelativeTime: Boolean,
    val maxDelayMillis: Long?,
    val risk: String,
    val minTrustWithoutConfirmation: String,
    val idempotent: Boolean,
    val timeoutSeconds: Int,
    val concurrencyKey: String? = null
)

data class RemoteAssistantInvocationData(
    val runId: String = "",
    val commandId: String = "",
    val arguments: Map<String, Any?> = emptyMap(),
    val runAtUnixMillis: Long? = null
)

data class AgentCapabilitiesData(
    @SerializedName("CanSend") val canSend: Boolean = false,
    @SerializedName("CanQueue") val canQueue: Boolean = false,
    @SerializedName("CanSendParallel") val canSendParallel: Boolean = false,
    @SerializedName("CanInterrupt") val canInterrupt: Boolean = false,
    @SerializedName("CanAnswerQuestions") val canAnswerQuestions: Boolean = false,
    @SerializedName("CanApproveTools") val canApproveTools: Boolean = false,
    @SerializedName("CanChangeModel") val canChangeModel: Boolean = false,
    @SerializedName("CanObserveLiveEvents") val canObserveLiveEvents: Boolean = false,
    @SerializedName("CanOpenTerminal") val canOpenTerminal: Boolean = false,
    @SerializedName("Backend") val backend: String = "",
    @SerializedName("Note") val note: String = ""
)

data class AgentQueuedMessageData(
    @SerializedName("Id") val id: String = "",
    @SerializedName("Text") val text: String = "",
    @SerializedName("At") val at: String = "",
    /** "after-turn" (Hub sends it when the turn ends) or "pc-pickup" (waiting for the open PC client). */
    @SerializedName("Delivery") val delivery: String = "after-turn"
)

/** One way a message can reach the session right now; the Hub lists the default first. */
data class AgentSendOptionData(
    @SerializedName("Mode") val mode: String = "",
    @SerializedName("Label") val label: String = "",
    @SerializedName("Hint") val hint: String = ""
)

data class AgentQuestionData(
    @SerializedName("Text") val text: String = "",
    @SerializedName("Options") val options: List<String> = emptyList()
)

/** Attention state shared by summaries and details; derived by the Hub. */
interface AgentSessionState {
    val id: String
    val provider: String
    val title: String
    val cwd: String
    val model: String
    val modified: String
    val state: String
    val stateSince: String
    val activity: String
    val liveElsewhere: Boolean
    val omniTurn: Boolean
    val error: String?
    val question: AgentQuestionData?
    val queued: List<AgentQueuedMessageData>?
    val openIn: String?
    val sendOptions: List<AgentSendOptionData>?
    val capabilities: AgentCapabilitiesData?
    val terminalCommand: String
}

data class AgentSessionSummaryData(
    @SerializedName("Id") override val id: String = "",
    @SerializedName("Provider") override val provider: String = "",
    @SerializedName("Title") override val title: String = "",
    @SerializedName("SessionId") val sessionId: String = "",
    @SerializedName("Modified") override val modified: String = "",
    @SerializedName("Cwd") override val cwd: String = "",
    @SerializedName("Model") override val model: String = "",
    @SerializedName("State") override val state: String = "idle",
    @SerializedName("StateSince") override val stateSince: String = "",
    @SerializedName("Activity") override val activity: String = "",
    @SerializedName("LiveElsewhere") override val liveElsewhere: Boolean = false,
    @SerializedName("OmniTurn") override val omniTurn: Boolean = false,
    @SerializedName("Error") override val error: String? = null,
    @SerializedName("Question") override val question: AgentQuestionData? = null,
    @SerializedName("Queued") override val queued: List<AgentQueuedMessageData>? = null,
    @SerializedName("OpenIn") override val openIn: String? = null,
    @SerializedName("SendOptions") override val sendOptions: List<AgentSendOptionData>? = null,
    @SerializedName("Capabilities") override val capabilities: AgentCapabilitiesData? = null,
    @SerializedName("TerminalCommand") override val terminalCommand: String = ""
) : AgentSessionState

data class AgentEventData(
    @SerializedName("Kind") val kind: String = "output",
    @SerializedName("Title") val title: String = "",
    @SerializedName("Text") val text: String = "",
    @SerializedName("Time") val time: String = "",
    @SerializedName("Result") val result: String? = null,
    @SerializedName("ResultError") val resultError: Boolean = false
)

data class AgentSessionDetailData(
    @SerializedName("Id") override val id: String = "",
    @SerializedName("Provider") override val provider: String = "",
    @SerializedName("Title") override val title: String = "",
    @SerializedName("SessionId") val sessionId: String = "",
    @SerializedName("Cwd") override val cwd: String = "",
    @SerializedName("Model") override val model: String = "",
    @SerializedName("Modified") override val modified: String = "",
    @SerializedName("Events") val events: List<AgentEventData> = emptyList(),
    @SerializedName("TotalEvents") val totalEvents: Int = 0,
    @SerializedName("AvailableEvents") val availableEvents: Int = 0,
    @SerializedName("HasMoreBefore") val hasMoreBefore: Boolean = false,
    @SerializedName("NextBefore") val nextBefore: Int = 0,
    @SerializedName("Status") val status: String = "",
    @SerializedName("State") override val state: String = "idle",
    @SerializedName("StateSince") override val stateSince: String = "",
    @SerializedName("Activity") override val activity: String = "",
    @SerializedName("LiveElsewhere") override val liveElsewhere: Boolean = false,
    @SerializedName("OmniTurn") override val omniTurn: Boolean = false,
    @SerializedName("Error") override val error: String? = null,
    @SerializedName("Question") override val question: AgentQuestionData? = null,
    @SerializedName("Queued") override val queued: List<AgentQueuedMessageData>? = null,
    @SerializedName("OpenIn") override val openIn: String? = null,
    @SerializedName("SendOptions") override val sendOptions: List<AgentSendOptionData>? = null,
    @SerializedName("Capabilities") override val capabilities: AgentCapabilitiesData? = null,
    @SerializedName("TerminalCommand") override val terminalCommand: String = ""
) : AgentSessionState

data class AgentSendStatusData(
    @SerializedName("Id") val id: String = "",
    @SerializedName("State") val state: String = "",
    @SerializedName("Error") val error: String? = null
)

data class ReminderData(
    @SerializedName(value = "Id", alternate = ["id"]) val id: String = "",
    @SerializedName(value = "Kind", alternate = ["kind"]) val kind: Int = 0,
    @SerializedName(value = "State", alternate = ["state"]) val state: Int = 0,
    @SerializedName(value = "Ownership", alternate = ["ownership"]) val ownership: Int = 0,
    @SerializedName(value = "Title", alternate = ["title"]) val title: String = "",
    @SerializedName(value = "DueAt", alternate = ["dueAt"]) val dueAt: String = "",
    @SerializedName(value = "NextFireAt", alternate = ["nextFireAt"]) val nextFireAt: String? = null,
    @SerializedName(value = "Revision", alternate = ["revision"]) val revision: Long = 0,
    @SerializedName(value = "Source", alternate = ["source"]) val source: Int = 0,
    @SerializedName(value = "SourceOriginalTitle", alternate = ["sourceOriginalTitle"]) val sourceOriginalTitle: String? = null,
    @SerializedName(value = "SourceCalendarId", alternate = ["sourceCalendarId"]) val sourceCalendarId: String? = null,
    @SerializedName(value = "RecurrenceSeriesId", alternate = ["recurrenceSeriesId"]) val recurrenceSeriesId: String? = null,
    @SerializedName(value = "IsAllDay", alternate = ["isAllDay"]) val isAllDay: Boolean = false
) {
    val isTerminal: Boolean get() = state == 3 || state == 4 || state == 5
}

class SignalRClient(
    private val context: Context,
    private val mainViewModel: MainViewModel
) {
    private var hubConnection: HubConnection? = null
    private var terminalHubConnection: HubConnection? = null
    val remoteTerminalClient = RemoteTerminalClient()
    val phoneCommands by lazy { com.omni.sync.logic.terminal.PhoneCommandService(context, this) }
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
    private val _hubAuthenticated = MutableStateFlow(false)
    val hubAuthenticated: StateFlow<Boolean> = _hubAuthenticated
    val genericAgents: GenericAgentClient by lazy { GenericAgentClient(context, this) }
    val agentEvents = MutableSharedFlow<com.google.gson.JsonObject>(extraBufferCapacity=32)

    @Volatile
    private var isHubAuthenticated = false
        set(value) {
            field = value
            _hubAuthenticated.value = value
        }

    @Volatile
    var isUpdatingClipboardInternally: Boolean = false
        private set

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _reminders = MutableStateFlow(com.omni.sync.utils.ReminderDelivery.load(context))
    private val reminderActionInFlight = AtomicBoolean(false)
    val reminders: StateFlow<List<ReminderData>> = _reminders

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

    data class AiDialog(
        val type: String, 
        val prompt: String, 
        val options: List<String>?,
        val questions: List<AiQuestion>? = null
    )

    data class AiQuestion(
        val question: String,
        val type: String,
        val header: String,
        val placeholder: String?,
        val multiSelect: Boolean?,
        val options: List<AiOption>?
    )

    data class AiOption(
        val label: String,
        val description: String
    )

    data class TerminalSession(
        val pid: Int,
        val workspace: String,
        val shell: String = "cmd.exe"
    )

    private val _terminalMessagesMap = MutableStateFlow<Map<Int, List<AiMessage>>>(emptyMap())
    val terminalMessagesMap: StateFlow<Map<Int, List<AiMessage>>> = _terminalMessagesMap

    private val _terminalSessions = MutableStateFlow<Map<Int, TerminalSession>>(emptyMap())
    val terminalSessions: StateFlow<Map<Int, TerminalSession>> = _terminalSessions

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

    // One Hub-owned persistent mode; defaults to new T3 when disconnected.
    private val _legacyAiEnabled = MutableStateFlow(false)
    val legacyAiEnabled: StateFlow<Boolean> = _legacyAiEnabled
    private val _t3SessionsJson = MutableStateFlow<String?>(null)
    val t3SessionsJson: StateFlow<String?> = _t3SessionsJson
    private val _t3DetailJson = MutableStateFlow<String?>(null)
    val t3DetailJson: StateFlow<String?> = _t3DetailJson
    private val _t3LastResult = MutableStateFlow<String?>(null)
    val t3LastResult: StateFlow<String?> = _t3LastResult
    private val _selectedT3Id = MutableStateFlow<String?>(null)
    val selectedT3Id: StateFlow<String?> = _selectedT3Id

    private val _agentSessions = MutableStateFlow<List<AgentSessionSummaryData>>(emptyList())
    val agentSessions: StateFlow<List<AgentSessionSummaryData>> = _agentSessions

    private val _agentSessionDetail = MutableStateFlow<AgentSessionDetailData?>(null)
    val agentSessionDetail: StateFlow<AgentSessionDetailData?> = _agentSessionDetail

    val agentSendStatus = MutableSharedFlow<AgentSendStatusData>(extraBufferCapacity = 8)
    val agentSessionsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    init {
        // Keep agent attention state (and its notifications) current while the Agents screen is closed.
        coroutineScope.launch {
            while (true) {
                delay(45_000)
                if (_legacyAiEnabled.value) requestAgentSessions()
            }
        }
    }

    fun emitAgentSendStatus(status: AgentSendStatusData) {
        agentSendStatus.tryEmit(status)
    }

    private val _aiWorkspaces = MutableStateFlow<Map<Int, String>>(emptyMap())
    val aiWorkspaces: StateFlow<Map<Int, String>> = _aiWorkspaces

    private val _aiPresets = MutableStateFlow<List<String>>(emptyList())
    val aiPresets: StateFlow<List<String>> = _aiPresets

    private val _aiModels = MutableStateFlow(listOf("agy", "codex"))
    val aiModels: StateFlow<List<String>> = _aiModels

    private val _defaultAiModel = MutableStateFlow(mainViewModel.appConfig.value.terminalLauncher.ifBlank { "agy" })
    val defaultAiModel: StateFlow<String> = _defaultAiModel

    private val _terminalLauncher = MutableStateFlow(mainViewModel.appConfig.value.terminalLauncher.ifBlank { "agy" })
    val terminalLauncher: StateFlow<String> = _terminalLauncher

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
        private const val REMOTE_TERMINAL_PID = -2
        private const val REMOTE_OUTPUT_MESSAGE_ID = "remote-terminal-output"
        private const val DEFAULT_REMOTE_WORKSPACE = "D:\\SSDProjects"
    }

    init {
        coroutineScope.launch {
            while (true) {
                delay(10_000)
                flushReminderActions()
                refreshReminders()
            }
        }
        observeRemoteTerminalForAiUi()
    }

    private fun observeRemoteTerminalForAiUi() {
        coroutineScope.launch {
            var hadSession = false
            remoteTerminalClient.session.collect { active ->
                if (active == null) {
                    if (hadSession) removeRemoteTerminalFromAiUi()
                    hadSession = false
                    return@collect
                }

                hadSession = true
                _terminalSessions.value = _terminalSessions.value + (
                    REMOTE_TERMINAL_PID to TerminalSession(
                        REMOTE_TERMINAL_PID,
                        active.workspace,
                        active.command
                    )
                )
                _aiSessions.value = _aiSessions.value + (
                    REMOTE_TERMINAL_PID to "${active.command} (${active.sessionId.take(8)})"
                )
                _aiWorkspaces.value = _aiWorkspaces.value + (REMOTE_TERMINAL_PID to active.workspace)
                _isStartingSession = false
                isStartingSessionFlow.value = false
                setSelectedPid(REMOTE_TERMINAL_PID)
                recordSessionActivity(REMOTE_TERMINAL_PID)
                lastCreatedSessionPid.emit(REMOTE_TERMINAL_PID)

                val queued = messageQueue.toList()
                messageQueue.clear()
                queued.forEach { sendAiMessage(it, REMOTE_TERMINAL_PID) }
            }
        }

        coroutineScope.launch {
            remoteTerminalClient.output.collect { output ->
                Log.d("SignalRClient", "Remote terminal UI update chars=${output.length} hash=${output.hashCode()}")
                val current = _terminalMessagesMap.value[REMOTE_TERMINAL_PID].orEmpty()
                    .filterNot { it.id == REMOTE_OUTPUT_MESSAGE_ID }
                val updated = if (output.isEmpty()) {
                    current
                } else {
                    current + AiMessage(
                        sender = "Terminal",
                        text = output,
                        id = REMOTE_OUTPUT_MESSAGE_ID,
                        isTerminal = true
                    )
                }
                _terminalMessagesMap.value = _terminalMessagesMap.value + (REMOTE_TERMINAL_PID to updated)
                Log.d("SignalRClient", "Remote terminal UI messages=${updated.size} hasOutput=${output.isNotEmpty()} terminalMessagePresent=${updated.any { it.id == REMOTE_OUTPUT_MESSAGE_ID }}")
                if (output.isNotEmpty()) {
                    recordSessionActivity(REMOTE_TERMINAL_PID)
                    anyAiActivityEvent.emit(Unit)
                }
                updateActiveView()
            }
        }

        coroutineScope.launch {
            remoteTerminalClient.sessionStatus.collect { status ->
                updateSessionStatus(REMOTE_TERMINAL_PID, status.replaceFirstChar { it.uppercase() })
            }
        }

        coroutineScope.launch {
            remoteTerminalClient.error.collect { error ->
                if (error == null) return@collect
                _isStartingSession = false
                isStartingSessionFlow.value = false
                updateSessionStatus(REMOTE_TERMINAL_PID, "Error")
                mainViewModel.addLog("[AI terminal] $error", com.omni.sync.ui.screen.LogType.ERROR)
            }
        }
    }

    private fun removeRemoteTerminalFromAiUi() {
        _terminalSessions.value = _terminalSessions.value - REMOTE_TERMINAL_PID
        _aiSessions.value = _aiSessions.value - REMOTE_TERMINAL_PID
        _aiWorkspaces.value = _aiWorkspaces.value - REMOTE_TERMINAL_PID
        _isWaitingForAiResponseMap.value = _isWaitingForAiResponseMap.value - REMOTE_TERMINAL_PID
        _isStartingSession = false
        isStartingSessionFlow.value = false
        if (_selectedPid.value == REMOTE_TERMINAL_PID) setSelectedPid(-1)
    }

    private fun startRemoteTerminalForAiUi(command: String, workspace: String?, replace: Boolean = false) {
        if (_isStartingSession && !replace) return
        _isStartingSession = true
        isStartingSessionFlow.value = true
        messageQueue.clear()
        _terminalMessagesMap.value = _terminalMessagesMap.value + (
            REMOTE_TERMINAL_PID to listOf(
                AiMessage("System", "Launching $command in ${workspace ?: DEFAULT_REMOTE_WORKSPACE}", isTerminal = true)
            )
        )
        setSelectedPid(REMOTE_TERMINAL_PID)
        updateSessionStatus(REMOTE_TERMINAL_PID, "Starting new session...")
        remoteTerminalClient.startSession(command, workspace ?: DEFAULT_REMOTE_WORKSPACE, replace = replace)
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
        schedulePhoneTaskBoardRecovery()
        remoteTerminalClient.connect(url, apiKey)
        hubConnection?.send("GetOmniAiMode")
        // Legacy Gemini discovery and model requests are gated by the Hub mode.
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

    @Synchronized
    private fun requestConnect(force: Boolean, reason: String) {
        // Multiple Android entry points (activity, foreground service, receivers) call startConnection.
        // A routine ensure-connected request must not cancel an active connection or retry cycle.
        if (!force && !manualStop.get() && (
                hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED ||
                isConnecting.get() || connectJob?.isActive == true || reconnectJob?.isActive == true
            )) return

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
            if (token != connectionToken.get()) return false
            // Another attempt may have connected while we waited for the connection lock.
            if (!force && hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                _connectionState.value = "Connected"
                return true
            }
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

            if (token != connectionToken.get()) return false
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
        val config = mainViewModel.appConfig.value
        return HubConnectionTargets.resolve(config.hubUrl, config.wanIp, isWifiConnected())
    }

    /**
     * The Hub re-resolves its own public address on a timer and pushes it here, so
     * the WAN address this app connects back on is never a stale typed in value.
     */
    private fun handleExternalIpUpdate(externalIp: String, updatedAtUnix: Long) {
        val trimmed = externalIp.trim()
        if (trimmed.isBlank()) return
        if (!isPlausibleIpv4(trimmed)) {
            mainViewModel.addLog(
                "Hub sent an unusable public address, ignoring it.",
                com.omni.sync.ui.screen.LogType.WARNING
            )
            return
        }

        val previous = mainViewModel.appConfig.value.wanIp.trim()
        if (trimmed == previous) return

        mainViewModel.updateConfig { it.copy(wanIp = trimmed) }
        val checkedAt = if (updatedAtUnix > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(updatedAtUnix * 1000))
        } else {
            "unknown time"
        }
        mainViewModel.addLog(
            "Hub public address updated: $previous -> $trimmed (checked $checkedAt).",
            com.omni.sync.ui.screen.LogType.INFO
        )
    }

    private fun isPlausibleIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    private suspend fun tryConnect(url: String, token: Long): Boolean {
        _connectionState.value = "Connecting..."
        mainViewModel.addLog("Attempting connection: $url", com.omni.sync.ui.screen.LogType.INFO)

        val connection = HubConnectionBuilder.create(url).build()
        isHubAuthenticated = false
        hubConnection = connection

        // Follow the winning candidate: WAN fallback must not leave terminal traffic on LAN.
        val termConnection = HubConnectionBuilder.create(url.replace("/signalrhub", "/terminalhub")).build()
        terminalHubConnection = termConnection

        handlersRegistered = false
        activeConnectionToken.set(token)

        connection.onClosed { error ->
            handleConnectionClosed(error, token)
        }

        termConnection.onClosed { error ->
            Log.d("SignalRClient", "Terminal connection closed: ${error?.message}")
        }

        registerHubHandlers()
        registerTerminalHubHandlers()

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

            // Asynchronously start and authenticate terminal hub
            coroutineScope.launch {
                try {
                    if (terminalHubConnection !== termConnection) return@launch
                    termConnection.start().blockingAwait()
                    if (terminalHubConnection !== termConnection) {
                        termConnection.stop().blockingAwait()
                        return@launch
                    }
                    termConnection.invoke(Boolean::class.java, "Authenticate", apiKey)
                        ?.subscribe({ success ->
                            Log.d("SignalR", "Terminal Auth success: $success")
                        }, { error ->
                            Log.e("SignalR", "Terminal Auth failed", error)
                        })
                } catch (e: Exception) {
                    Log.e("SignalRClient", "TerminalHub connection failed: ${e.message}")
                }
            }

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

        isHubAuthenticated = false
        val reason = error?.message ?: "Unknown reason"
        _connectionState.value = "Disconnected: $reason"
        mainViewModel.setConnected(false)
        mainViewModel.setScheduledShutdownTime(null)
        remoteTerminalClient.disconnect()
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

        mainViewModel.addLog("Connection closed. Reason: $reason. Starting auto-reconnect...", com.omni.sync.ui.screen.LogType.WARNING)
        scheduleReconnect("Closed: $reason")
    }

    @Synchronized
    private fun scheduleReconnect(reason: String) {
        if (manualStop.get() || hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) return
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

                // A concurrent start may have recovered connectivity during the retry delay.
                if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) return@launch
                val token = connectionToken.incrementAndGet()
                val success = connectInternal(token, force = false, reason = reason, allowScheduleReconnect = false)
                if (success) return@launch
                attempt++
            }
        }
    }

    private suspend fun stopHubConnectionInternal(target: HubConnection? = null) {
        connectionMutex.withLock {
            val hub = target ?: hubConnection ?: return
            val isCurrent = hubConnection === hub
            val terminal = if (isCurrent) terminalHubConnection else null
            if (isCurrent) {
                terminalHubConnection = null
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
                    remoteTerminalClient.disconnect()
                }
                // Do not accumulate stale terminal sockets on every hub reconnect.
                if (terminal != null) {
                    try {
                        terminal.stop().timeout(5, TimeUnit.SECONDS).blockingAwait()
                    } catch (e: Exception) {
                        Log.w("SignalRClient", "Error stopping terminal connection: ${e.message}")
                    }
                }
            }
        }
    }

    private fun registerTerminalHubHandlers() {
        val hub = terminalHubConnection ?: return
        
        hub.on("ReceiveOutput", { pid: Int, data: String ->
            updateTerminalMessages(pid) { it + AiMessage("Terminal", data, isTerminal = true) }
            coroutineScope.launch { anyAiActivityEvent.emit(Unit) }
        }, Int::class.java, String::class.java)
    }

    private fun updateTerminalMessages(pid: Int, block: (List<AiMessage>) -> List<AiMessage>) {
        val currentMap = _terminalMessagesMap.value
        val sessionMessages = currentMap[pid] ?: emptyList()
        val newMessages = block(sessionMessages)
        _terminalMessagesMap.value = currentMap + (pid to newMessages)
        updateActiveView()
    }

    fun startTerminalSession(workspace: String? = null) {
        startRemoteTerminalForAiUi(_terminalLauncher.value, workspace)
    }

    private fun registerHubHandlers() {
        if (handlersRegistered) return
        handlersRegistered = true
        hubConnection?.on("RemindersChanged", { refreshReminders() })
        hubConnection?.on("AndroidAlarmAction", { request: AndroidAlarmActionData ->
            com.omni.sync.service.AlarmService.logBridge(context, "desktop_action_received action=${request.action} id=${request.id} token=${request.token.take(8)}")
            Log.i("AndroidAlarm", "Desktop command received action=${request.action} id=${request.id} token=${request.token.take(8)}")
            val serviceAction = when (request.action) {
                "dismiss" -> com.omni.sync.service.AlarmService.ACTION_REMOTE_DISMISS
                "snooze" -> com.omni.sync.service.AlarmService.ACTION_REMOTE_SNOOZE
                else -> null
            }
            val ringing = com.omni.sync.service.AlarmService.isRinging.value &&
                request.token == com.omni.sync.service.AlarmService.currentRemoteToken
            val snoozed = com.omni.sync.service.AlarmService.isSnoozing.value &&
                request.action == "dismiss" && request.token == currentDesktopAlarmToken(request.id)
            if (serviceAction != null && request.id != 0 && request.token.isNotBlank() &&
                (ringing || snoozed) &&
                request.id == com.omni.sync.service.AlarmService.lastTriggeredAlarmId) {
                val intent = android.content.Intent(context, com.omni.sync.service.AlarmService::class.java).apply {
                    action = serviceAction
                    putExtra("ALARM_ID", request.id)
                    putExtra("ALARM_TOKEN", request.token)
                }
                com.omni.sync.service.AlarmService.logBridge(context, "desktop_action_forwarded id=${request.id} action=${request.action}")
                Log.i("AndroidAlarm", "Desktop action accepted and forwarded to AlarmService id=${request.id}")
                context.startService(intent)
            } else {
                com.omni.sync.service.AlarmService.logBridge(context, "desktop_action_ignored id=${request.id} ringing=$ringing snoozed=$snoozed")
                Log.w("AndroidAlarm", "Desktop action ignored (stale token, inactive alarm, or invalid action) id=${request.id} ringing=$ringing snoozed=$snoozed")
            }
        }, AndroidAlarmActionData::class.java)

        hubConnection?.on("AssistantRunUpdated", { update: AssistantRunUpdateData ->
            val message = update.summary ?: update.reason ?: update.state
            when (update.state) {
                "Succeeded" -> {
                    mainViewModel.addLog(
                        "[Assistant] ${update.commandId ?: "command"} succeeded: $message",
                        com.omni.sync.ui.screen.LogType.SUCCESS
                    )
                    com.omni.sync.logic.assistant.AssistantFeedback.notify(
                        context,
                        "Jarvis completed",
                        message,
                        update.runId
                    )
                }
                "Failed", "Interrupted" -> {
                    mainViewModel.addLog(
                        "[Assistant] ${update.commandId ?: "command"} ${update.state.lowercase()}: $message",
                        com.omni.sync.ui.screen.LogType.ERROR
                    )
                    com.omni.sync.logic.assistant.AssistantFeedback.notify(
                        context,
                        "Jarvis ${update.state.lowercase()}",
                        message,
                        update.runId
                    )
                }
                "Skipped", "NeedsClarification", "Cancelled" -> {
                    mainViewModel.addLog(
                        "[Assistant] ${update.commandId ?: "command"} ${update.state}: $message",
                        com.omni.sync.ui.screen.LogType.WARNING
                    )
                    com.omni.sync.logic.assistant.AssistantFeedback.notify(
                        context,
                        "Jarvis ${update.state}",
                        message,
                        update.runId
                    )
                }
            }
        }, AssistantRunUpdateData::class.java)

        hubConnection?.on(
            "ExecuteAssistantCommand",
            { invocation: RemoteAssistantInvocationData ->
                handleRemoteAssistantInvocation(invocation, schedule = false)
            },
            RemoteAssistantInvocationData::class.java
        )

        hubConnection?.on(
            "ScheduleAssistantCommand",
            { invocation: RemoteAssistantInvocationData ->
                handleRemoteAssistantInvocation(invocation, schedule = true)
            },
            RemoteAssistantInvocationData::class.java
        )

        hubConnection?.on("AndroidUpdateRequested", { requestId: String ->
            val activeBaseUrl = mainViewModel.getBaseUrl()
            Log.i(
                "AndroidDeploy",
                "Received deployment $requestId through SignalR; active base URL=$activeBaseUrl"
            )
            mainViewModel.addLog(
                "Android deployment requested by Hub.",
                com.omni.sync.ui.screen.LogType.INFO
            )
            reportAndroidUpdateStatus(
                requestId,
                "received",
                "Android client received the deployment request."
            )
            val application = context.applicationContext as? OmniSyncApplication
            if (application == null) {
                reportAndroidUpdateStatus(requestId, "error", "Android application is unavailable.")
            } else {
                application.androidUpdateManager.deployLatest(requestId, activeBaseUrl, apiKey) { status, message ->
                    reportAndroidUpdateStatus(requestId, status, message)
                }
            }
        }, String::class.java)
        
        hubConnection?.on("UpdateExternalIp", { externalIp: String, updatedAtUnix: Long ->
            handleExternalIpUpdate(externalIp, updatedAtUnix)
        }, String::class.java, java.lang.Long::class.java)

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

        hubConnection?.on("PhoneCommandRequested", { requestId: String, command: String ->
            if (isHubAuthenticated) phoneCommands.run(command, "Agent", requestId)
        }, String::class.java, String::class.java)

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
            setIsNextBubble(pid, true) // Force break bubble stream on any user message
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
            } else if (status == "RESET_BUBBLE") {
                Log.d("SignalRClient", "RESET_BUBBLE received for pid=$pid")
                setIsNextBubble(pid, true)
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

        hubConnection?.on("ReceiveOmniAiMode", { enabled: Boolean ->
            _legacyAiEnabled.value = enabled
            if (enabled) {
                requestAgentSessions()
                hubConnection?.send("GetAiSessions")
            } else {
                _aiSessions.value = emptyMap()
                _agentSessions.value = emptyList()
            }
        }, java.lang.Boolean::class.java)

        hubConnection?.on("AgentEvent", { payload: Any ->
            try {
                val event = com.google.gson.JsonParser.parseString(gson.toJson(payload)).asJsonObject
                agentEvents.tryEmit(event)
            } catch (error: Exception) {
                Log.w("SignalRClient","Invalid provider event",error)
            }
        }, Any::class.java)

        hubConnection?.on("ReceiveT3Result", { payload: String ->
            runCatching {
                val row = com.google.gson.JsonParser.parseString(payload).asJsonObject
                val action = row.get("action")?.asString ?: return@runCatching
                val ok = row.get("ok")?.asBoolean ?: false
                if (!ok) {
                    _t3LastResult.value = row.get("error")?.asString ?: "T3 request failed"
                } else {
                    val data = row.get("data")?.toString() ?: "{}"
                    when (action) {
                        "list" -> _t3SessionsJson.value = data
                        "read" -> _t3DetailJson.value = data
                        "send" -> _t3LastResult.value = "T3 received the message"
                    }
                }
            }.onFailure { _t3LastResult.value = "Invalid T3 response: ${it.message}" }
        }, String::class.java)

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
                    val workspace = (session["workspacePath"] ?: session["WorkspacePath"] ?: session["workspace"] ?: session["Workspace"]) as? String ?: ""
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

        hubConnection?.on("ReceiveAgentSessions", { sessionsJson: String ->
            try {
                val type = object : TypeToken<List<AgentSessionSummaryData>>() {}.type
                val sessions: List<AgentSessionSummaryData> = gson.fromJson(sessionsJson, type)
                Log.d("SignalRClient", "ReceiveAgentSessions: ${sessions.size} transcripts")
                _agentSessions.value = sessions
                com.omni.sync.utils.AgentAttentionNotifier.onSessions(context, sessions)
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing agent sessions", e)
            }
        }, String::class.java)

        hubConnection?.on("ReceiveAgentSessionDetail", { detailJson: String ->
            try {
                val detail = gson.fromJson(detailJson, AgentSessionDetailData::class.java)
                Log.d("SignalRClient", "ReceiveAgentSessionDetail: ${detail.title} events=${detail.events.size}")
                _agentSessionDetail.value = detail
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing agent session detail", e)
            }
        }, String::class.java)

        hubConnection?.on("AgentSessionsChanged", { _: String ->
            requestAgentSessions()
            agentSessionsChanged.tryEmit(Unit)
        }, String::class.java)

        hubConnection?.on("ReceiveAgentSendStatus", { statusJson: String ->
            try {
                val status = gson.fromJson(statusJson, AgentSendStatusData::class.java)
                Log.d("SignalRClient", "ReceiveAgentSendStatus: ${status.id.take(8)} -> ${status.state}")
                agentSendStatus.tryEmit(status)
            } catch (e: Exception) {
                Log.e("SignalRClient", "Error parsing agent send status", e)
            }
        }, String::class.java)

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

        hubConnection?.on("ReceiveAiDialog", { pid: Int, type: String, prompt: String, options: List<String>?, questionsData: Any? ->
            Log.d("SignalRClient", "ReceiveAiDialog from PID $pid: $type - $prompt")
            
            var questions: List<AiQuestion>? = null
            if (questionsData != null) {
                try {
                    val jsonStr = gson.toJson(questionsData)
                    val listType = object : TypeToken<List<AiQuestion>>() {}.type
                    questions = gson.fromJson(jsonStr, listType)
                } catch (e: Exception) {
                    Log.e("SignalRClient", "Error parsing questions", e)
                }
            }
            
            updateSessionDialog(pid, AiDialog(type, prompt, options, questions))
        }, Int::class.java, String::class.java, String::class.java, List::class.java, Any::class.java)

        hubConnection?.on("ReceivePayload", { payloadData: Any ->
            try {
                val jsonStr = gson.toJson(payloadData)
                // Log.d("SignalRClient", "Raw ReceivePayload JSON: $jsonStr")

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
        if (_terminalSessions.value.containsKey(pid)) {
            _aiMessages.value = _terminalMessagesMap.value[pid] ?: emptyList()
            _aiStatus.value = "Terminal Active"
            _aiThought.value = null
            _aiDialog.value = null
        } else {
            _aiMessages.value = _aiMessagesMap.value[pid] ?: emptyList()
            _aiStatus.value = _aiStatusMap.value[pid]
            _aiThought.value = _aiThoughtMap.value[pid]
            _aiDialog.value = _aiDialogMap.value[pid]
        }
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
        val targetPid = pid ?: _selectedPid.value
        if (targetPid == REMOTE_TERMINAL_PID) {
            val sequence = when (key.lowercase()) {
                "enter", "return" -> "\r"
                "escape", "esc" -> "\u001b"
                "up" -> "\u001b[A"
                "down" -> "\u001b[B"
                "left" -> "\u001b[D"
                "right" -> "\u001b[C"
                "tab" -> "\t"
                "ctrl+c", "control+c" -> "\u0003"
                else -> key
            }
            remoteTerminalClient.sendKey(sequence)
            return
        }
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendAiSpecialKey", key, targetPid)
        }
    }

    fun sendRemoteTerminalInput(input: String) {
        remoteTerminalClient.sendText(input)
    }

    fun resizeRemoteTerminal(columns: Int, rows: Int) {
        remoteTerminalClient.resizeSession(columns, rows)
    }

        fun sendAiYolo(pid: Int? = null) {
            if ((pid ?: _selectedPid.value) == REMOTE_TERMINAL_PID) {
                remoteTerminalClient.sendText("y", appendEnter = true)
                return
            }
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
        if ((pid ?: _selectedPid.value) == REMOTE_TERMINAL_PID) {
            remoteTerminalClient.sendText(response, appendEnter = true)
            updateSessionDialog(REMOTE_TERMINAL_PID, null)
            return
        }
    
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val targetPid = pid ?: _selectedPid.value
            hubConnection?.send("SendAiDialogResponse", response, targetPid)
            // Clear local dialog after responding
            updateSessionDialog(targetPid, null)
        }
    }

    /** All T3 Busy scheduling is authoritative on the PC, including its deadline. */
    suspend fun t3BusyPolicy(enabled: Boolean? = null, scheduleMinutes: Int? = null): T3BusyPolicyState =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val conn = hubConnection ?: throw IllegalStateException("OmniHub connection unavailable")
            check(isHubAuthenticated &&
                conn.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                "OmniHub is not authenticated"
            }
            val result = when {
                scheduleMinutes != null -> conn.invoke(Any::class.java, "T3CaffySchedule", scheduleMinutes)
                enabled != null -> conn.invoke(Any::class.java, "T3CaffySetBusy", enabled)
                else -> conn.invoke(Any::class.java, "T3CaffyStatus")
            }
            val response = gson.toJsonTree(result.timeout(18, TimeUnit.SECONDS).blockingGet()).asJsonObject
            val state = response.get("keepT3Busy")
            require(state != null && state.isJsonPrimitive && state.asJsonPrimitive.isBoolean) {
                "T3 returned no verified Keep T3 Busy state"
            }
            T3BusyPolicyState(
                keepT3Busy = state.asBoolean,
                shutdownWhenIdle = response.get("shutdownWhenIdle")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                busyUntil = response.get("busyUntil")?.takeUnless { it.isJsonNull }?.asLong
            )
        }

    /**
     * Shared v1 provider-neutral AgentCall transport. Invocations only run after
     * SignalR Authenticate; this does not use the removed legacy AI target names.
     * Call on Dispatchers.IO (or another background dispatcher).
     */
    suspend fun agentCall(method: String, params: Map<String, Any?> = emptyMap()): com.google.gson.JsonElement {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (hubConnection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) {
                throw IllegalStateException("OmniHub connection unavailable")
            }
            // The socket reports Connected before Authenticate has received its ACK.
            // A TaskBoard write must wait for the authenticated session, not fail in that gap.
            withTimeoutOrNull(15_000) { hubAuthenticated.first { it } }
                ?: throw IllegalStateException("OmniHub authentication did not complete")
            val conn = hubConnection ?: throw IllegalStateException("OmniHub connection unavailable")
            check(isHubAuthenticated &&
                conn.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                "OmniHub authentication lost before TaskBoard write"
            }
            val raw = conn.invoke(Any::class.java, "AgentCall", method, params)
                .timeout(105, TimeUnit.SECONDS)
                .blockingGet()
            com.google.gson.JsonParser.parseString(gson.toJson(raw))
        }
    }

    fun requestT3Sessions(): Boolean = sendAgentControl("T3ListSessions")
    fun selectT3Session(id: String) {
        _selectedT3Id.value = id
        _t3DetailJson.value = null
        sendAgentControl("T3ReadSession", id)
    }
    fun refreshT3Session(): Boolean {
        val id = _selectedT3Id.value ?: return false
        return sendAgentControl("T3ReadSession", id)
    }
    fun sendT3Message(message: String): Boolean {
        val id = _selectedT3Id.value ?: return false
        return sendAgentControl("T3SendSession", id, message)
    }
    fun setLegacyAiEnabled(enabled: Boolean): Boolean = sendAgentControl("SetOmniAiMode", enabled)
    fun refreshLegacyAiMode(): Boolean = sendAgentControl("GetOmniAiMode")

    fun sendAiMessage(message: String, pid: Int? = null) {
        if (!_legacyAiEnabled.value) {
            // Old callers (clipboard, Files, editor) do not resurrect Gemini.
            // Natural-language messages may be sent to the explicitly selected T3 session only.
            if (message.startsWith("/") || !sendT3Message(message)) {
                mainViewModel.addLog("AI message not sent: select a T3 session first", com.omni.sync.ui.screen.LogType.WARNING)
            }
            return
        }
        var targetPid = pid ?: _selectedPid.value

        if (targetPid == REMOTE_TERMINAL_PID) {
            if (message == "/model") return
            if (message.startsWith("/model ")) {
                setDefaultAiModel(message.removePrefix("/model ").trim())
                return
            }
            if (message.startsWith("/dir add ")) {
                updateTerminalMessages(targetPid) {
                    it + AiMessage("System", "Directory context commands are unavailable in raw terminal mode.", isTerminal = true)
                }
                return
            }
            updateTerminalMessages(targetPid) { it + AiMessage("Me", message, isTerminal = true) }
            recordSessionActivity(targetPid)
            remoteTerminalClient.sendText(message, appendEnter = true)
            return
        }

        // Handle terminal session
        if (_terminalSessions.value.containsKey(targetPid)) {
            if (terminalHubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                updateTerminalMessages(targetPid) { it + AiMessage("Me", message, isTerminal = true) }
                terminalHubConnection?.send("SendCommand", targetPid, message)
            }
            return
        }

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
            setIsNextBubble(targetPid, true)
            hubConnection?.send("SendAiMessage", message, hubPid)
        }
    }

    fun getAiSessions() {
        if (!_legacyAiEnabled.value) return
        val active = remoteTerminalClient.session.value ?: return
        _terminalSessions.value = _terminalSessions.value + (
            REMOTE_TERMINAL_PID to TerminalSession(REMOTE_TERMINAL_PID, active.workspace, active.command)
        )
        _aiSessions.value = _aiSessions.value + (
            REMOTE_TERMINAL_PID to "${active.command} (${active.sessionId.take(8)})"
        )
        _aiWorkspaces.value = _aiWorkspaces.value + (REMOTE_TERMINAL_PID to active.workspace)
    }

    fun requestAiHistory(pid: Int? = null) {
        if ((pid ?: _selectedPid.value) == REMOTE_TERMINAL_PID) {
            updateActiveView()
            return
        }
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

    fun requestAgentSessions() {
        if (!_legacyAiEnabled.value) return
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("RequestAgentSessions")
        }
    }

    fun requestAgentSessionDetail(id: String, limit: Int = 120, before: Int = 0, clear: Boolean = true) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            if (clear) _agentSessionDetail.value = null
            hubConnection?.send("RequestAgentSessionDetail", id, limit, before)
        }
    }

    /** Returns false when the Hub is not connected, so callers can say so instead of silently dropping. */
    /** [mode]: "auto" (send now or queue while a turn runs), "queue", or "now" (parallel turn while another client is mid-turn). */
    fun sendAgentMessage(id: String, message: String, mode: String = "auto"): Boolean =
        sendAgentControl("SendAgentMessage", id, message, mode)

    fun cancelQueuedAgentMessage(id: String, queuedId: String): Boolean = sendAgentControl("CancelQueuedAgentMessage", id, queuedId)

    fun retryAgentTurn(id: String): Boolean = sendAgentControl("RetryAgentTurn", id)

    fun interruptAgentTurn(id: String): Boolean = sendAgentControl("InterruptAgentTurn", id)

    fun createAgentSession(provider: String, cwd: String, message: String): Boolean =
        sendAgentControl("CreateAgentSession", provider, cwd, message)

    /** Omni-side title; blank restores the provider's own title. */
    fun renameAgentSession(id: String, title: String): Boolean = sendAgentControl("RenameAgentSession", id, title)

    /** Permanently deletes the session's transcript files on the PC. */
    fun deleteAgentSession(id: String): Boolean = sendAgentControl("DeleteAgentSession", id)

    private fun sendAgentControl(method: String, vararg arguments: Any): Boolean {
        val connection = hubConnection
        if (connection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) return false
        return try {
            connection.send(method, *arguments)
            true
        } catch (error: Exception) {
            Log.e("SignalRClient", "Agent control $method failed", error)
            false
        }
    }

    /** Raw terminal escape hatch: run the provider's interactive resume command in the session folder. */
    fun openAgentTerminal(command: String, workspace: String) {
        startRemoteTerminalForAiUi(command, workspace.ifBlank { null }, replace = true)
        mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.AGENT_TERMINAL)
    }

    /** New raw terminal from the Agents screen; opens the terminal view without leaving it. */
    fun startAgentTerminal(command: String, workspace: String): Boolean {
        if (remoteTerminalClient.connectionState.value != "Connected") return false
        if (command.isNotBlank()) setTerminalLauncher(command)
        startRemoteTerminalForAiUi(command.ifBlank { _terminalLauncher.value }, workspace.ifBlank { null }, replace = true)
        mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.AGENT_TERMINAL)
        return true
    }

    fun switchAiSession(pid: Int) {
        if (pid == REMOTE_TERMINAL_PID) {
            _isStartingSession = false
            isStartingSessionFlow.value = false
            messageQueue.clear()
            setSelectedPid(pid)
            return
        }
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
        startRemoteTerminalForAiUi(_terminalLauncher.value, workspace)
    }

    fun stopAiSession(pid: Int) {
        if (pid == REMOTE_TERMINAL_PID) {
            updateSessionStatus(pid, "Closing session...")
            remoteTerminalClient.stopSession()
            return
        }
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

    enum class VoiceMode { JARVIS, TYPE }

    private val _voiceCaptureRequests = MutableSharedFlow<VoiceMode>(extraBufferCapacity = 1)
    val voiceCaptureRequests = _voiceCaptureRequests.asSharedFlow()

    // A single Android recognizer UI may be active at once. Dashboard, Settings and
    // quick actions all share this gate, including during Activity recreation.
    private val voiceCaptureLock = Any()
    private var voiceCaptureActive = false
    private var voiceCaptureStartedAt = 0L

    // Held when requested while the UI isn't collecting yet (e.g. a notification cold start).
    @Volatile private var pendingVoiceMode: VoiceMode? = null

    fun requestVoiceCapture(mode: VoiceMode) {
        synchronized(voiceCaptureLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (voiceCaptureActive && now - voiceCaptureStartedAt < 60_000L) {
                mainViewModel.addLog("[Voice] Recognition already in progress; ignoring duplicate tap.", com.omni.sync.ui.screen.LogType.INFO)
                return
            }
            voiceCaptureActive = true
            voiceCaptureStartedAt = now
            if (_voiceCaptureRequests.subscriptionCount.value == 0 ||
                !_voiceCaptureRequests.tryEmit(mode)
            ) {
                pendingVoiceMode = mode
            }
        }
    }

    fun consumePendingVoiceCapture(): VoiceMode? = synchronized(voiceCaptureLock) {
        val mode = pendingVoiceMode
        pendingVoiceMode = null
        mode
    }

    fun completeVoiceCapture() {
        synchronized(voiceCaptureLock) {
            pendingVoiceMode = null
            voiceCaptureActive = false
        }
    }

    private fun advertiseAssistantCommands() {
        val connection = hubConnection ?: return
        if (!isHubAuthenticated ||
            connection.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED
        ) return

        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "android"

        val descriptors = listOf(
            AssistantDeviceCommandDescriptorData(
                id = "wake.pc",
                version = 1,
                description = "Wake the configured PC using Wake-on-LAN.",
                patterns = listOf(
                    """^(?:please\s+)?(?:turn|switch|power)\s+(?:my\s+|the\s+)?(?:pc|computer)\s+on\s*[.!?]?\s*$""",
                    """^(?:please\s+)?(?:turn|switch|power)\s+on\s+(?:my\s+|the\s+)?(?:pc|computer)\s*[.!?]?\s*$""",
                    """^(?:please\s+)?wake\s+(?:up\s+)?(?:my\s+|the\s+)?(?:pc|computer)\s*[.!?]?\s*$""",
                    """^(?:please\s+)?(?:boot|start)\s+(?:up\s+)?(?:my\s+|the\s+)?(?:pc|computer)\s*[.!?]?\s*$"""
                ),
                examples = listOf(
                    AssistantDeviceExampleData("turn on my PC"),
                    AssistantDeviceExampleData("wake my computer")
                ),
                acceptsRelativeTime = true,
                maxDelayMillis = 15 * 60 * 1000L,
                risk = "Low",
                minTrustWithoutConfirmation = "Low",
                idempotent = true,
                timeoutSeconds = 15,
                concurrencyKey = "wake.pc"
            )
        )

        try {
            connection.send("AdvertiseAssistantCommands", deviceId, descriptors)
            mainViewModel.addLog(
                "[Assistant] Advertised ${descriptors.size} Android command(s).",
                com.omni.sync.ui.screen.LogType.INFO
            )
        } catch (error: Exception) {
            mainViewModel.addLog(
                "[Assistant] Could not advertise Android commands: ${error.message}",
                com.omni.sync.ui.screen.LogType.WARNING
            )
        }
    }

    private fun handleRemoteAssistantInvocation(
        invocation: RemoteAssistantInvocationData,
        schedule: Boolean
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                when (invocation.commandId) {
                    "wake.pc" -> {
                        if (schedule) {
                            val runAt = invocation.runAtUnixMillis
                                ?: throw IllegalArgumentException("Missing runAt for scheduled wake.pc")
                            com.omni.sync.logic.assistant.AssistantScheduleStore.add(
                                context = context,
                                commandId = invocation.commandId,
                                runAtMillis = runAt,
                                origin = "hub",
                                runId = invocation.runId
                            )
                            com.omni.sync.logic.assistant.AssistantFeedback.notify(
                                context,
                                "PC wake scheduled",
                                "Wake-on-LAN is scheduled for ${java.util.Date(runAt)}.",
                                invocation.runId
                            )
                            mainViewModel.addLog(
                                "[Assistant] Scheduled remote wake.pc ${invocation.runId} for ${java.util.Date(runAt)}.",
                                com.omni.sync.ui.screen.LogType.SUCCESS
                            )
                            reportAssistantRunResult(
                                invocation.runId,
                                "scheduled",
                                "Persisted wake.pc schedule for ${java.util.Date(runAt)}."
                            )
                        } else {
                            val config = mainViewModel.appConfig.value
                            val result = com.omni.sync.logic.assistant.WakeOnLanSender.send(
                                macAddress = config.wakeOnLanMac,
                                broadcastIp = config.subnetBroadcastIp,
                                wanIp = config.wanIp
                            )
                            result.messages.forEach { message ->
                                mainViewModel.addLog(
                                    "[Assistant] $message",
                                    if (message.contains("failed", ignoreCase = true))
                                        com.omni.sync.ui.screen.LogType.WARNING
                                    else
                                        com.omni.sync.ui.screen.LogType.SUCCESS
                                )
                            }

                            if (result.anySuccess) {
                                com.omni.sync.receiver.AssistantCommandReceiver.scheduleWakeVerification(
                                    context,
                                    invocation.runId,
                                    reportToHub = true
                                )
                                startConnection()
                            } else {
                                reportAssistantRunResult(
                                    invocation.runId,
                                    "failed",
                                    "No Wake-on-LAN packet could be sent."
                                )
                            }
                        }
                    }
                    else -> reportAssistantRunResult(
                        invocation.runId,
                        "failed",
                        "Android does not implement ${invocation.commandId}."
                    )
                }
            } catch (error: Exception) {
                mainViewModel.addLog(
                    "[Assistant] Remote ${invocation.commandId} failed: ${error.message}",
                    com.omni.sync.ui.screen.LogType.ERROR
                )
                reportAssistantRunResult(
                    invocation.runId,
                    "failed",
                    error.message ?: "Remote assistant command failed."
                )
            }
        }
    }

    fun reportAssistantRunResult(
        runId: String,
        state: String,
        summary: String?
    ) {
        val connection = hubConnection
        if (!isHubAuthenticated ||
            connection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED
        ) {
            mainViewModel.addLog(
                "[Assistant] Could not report $state for $runId because Hub is offline.",
                com.omni.sync.ui.screen.LogType.WARNING
            )
            return
        }

        try {
            connection.send("ReportAssistantRunResult", runId, state, summary)
        } catch (error: Exception) {
            mainViewModel.addLog(
                "[Assistant] Failed reporting $state for $runId: ${error.message}",
                com.omni.sync.ui.screen.LogType.WARNING
            )
        }
    }

    fun sendVoiceTranscript(mode: VoiceMode, transcript: String) {
        if (mode == VoiceMode.JARVIS &&
            com.omni.sync.logic.assistant.AndroidAssistantCommandExecutor.tryHandle(
                context,
                mainViewModel,
                transcript
            )
        ) {
            return
        }

        if (mode == VoiceMode.JARVIS) {
            val item = com.omni.sync.logic.assistant.AssistantVoiceOutbox.enqueue(
                context,
                transcript
            )
            mainViewModel.addLog(
                "[Assistant] Queued Jarvis request ${item.requestId}.",
                com.omni.sync.ui.screen.LogType.INFO
            )
            com.omni.sync.logic.assistant.AssistantFeedback.toast(
                context,
                if (isHubAuthenticated) "Sending Jarvis request" else "Jarvis request queued until Hub is online"
            )
            flushAssistantVoiceOutbox()
            return
        }

        if (isHubAuthenticated &&
            hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED
        ) {
            try {
                // Use the same payload as the functioning remote keyboard.
                sendText(transcript)
                mainViewModel.addLog("[Voice] Type sent ${transcript.length} characters to PC.", com.omni.sync.ui.screen.LogType.INFO)
                com.omni.sync.logic.assistant.AssistantFeedback.toast(context, "Text sent to PC")
            } catch (error: Exception) {
                mainViewModel.addLog("[Voice] Type delivery failed: ${error.message}", com.omni.sync.ui.screen.LogType.ERROR)
                com.omni.sync.logic.assistant.AssistantFeedback.toast(context, "Could not send text to PC")
            }
        } else {
            mainViewModel.addLog("[Voice] Type not sent: PC is disconnected or not authenticated.", com.omni.sync.ui.screen.LogType.WARNING)
            com.omni.sync.logic.assistant.AssistantFeedback.toast(context, "PC offline; nothing typed")
        }
    }

    private fun flushAssistantVoiceOutbox() {
        val connection = hubConnection ?: return
        if (!isHubAuthenticated ||
            connection.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED
        ) return

        coroutineScope.launch(Dispatchers.IO) {
            for (item in com.omni.sync.logic.assistant.AssistantVoiceOutbox.items(context)) {
                if (!isHubAuthenticated) break
                try {
                    val ack = connection.invoke(
                        AssistantVoiceAck::class.java,
                        "SubmitAssistantRequest",
                        item.requestId,
                        item.text,
                        item.occurredAtMillis,
                        item.conversationId
                    ).blockingGet()
                    com.omni.sync.logic.assistant.AssistantVoiceOutbox.remove(context, item.requestId)
                    val state = ack.runState ?: "Accepted"
                    mainViewModel.addLog(
                        "[Assistant] $state ${ack.commandId ?: "request"}: ${ack.summary}",
                        com.omni.sync.ui.screen.LogType.SUCCESS
                    )
                    com.omni.sync.logic.assistant.AssistantFeedback.toast(
                        context,
                        if (state == "Scheduled") "Jarvis command scheduled" else "Jarvis request accepted"
                    )
                } catch (error: Exception) {
                    mainViewModel.addLog(
                        "[Assistant] Delivery paused; request remains queued: ${error.message}",
                        com.omni.sync.ui.screen.LogType.WARNING
                    )
                    break
                }
            }
        }
    }

    fun renameAiSession(pid: Int, name: String) {
        if (pid == REMOTE_TERMINAL_PID) {
            _aiSessions.value = _aiSessions.value + (pid to name)
            return
        }
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {       
            hubConnection?.send("RenameAiSession", pid, name)
        }
    }

    fun clearSessions() {
        _aiSessions.value = emptyMap()
        _aiWorkspaces.value = emptyMap()
    }

    fun reloadAiSessions() {
        if (remoteTerminalClient.session.value != null) {
            getAiSessions()
            return
        }
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
        if (pid == REMOTE_TERMINAL_PID) return
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
        _aiModels.value = listOf("agy", "codex")
        if (_defaultAiModel.value.isBlank()) _defaultAiModel.value = remoteTerminalClient.preferredCommand.value
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
        val command = model.ifBlank { "agy" }
        _defaultAiModel.value = command
        remoteTerminalClient.setPreferredCommand(command)
    }

    fun setTerminalLauncher(command: String) {
        if (command.isBlank()) return
        _terminalLauncher.value = command
        remoteTerminalClient.setPreferredCommand(command)
        mainViewModel.updateConfig { it.copy(terminalLauncher = command) }
    }

    fun resetAiSessions() {
        if (remoteTerminalClient.session.value != null) remoteTerminalClient.stopSession()
        _terminalMessagesMap.value = _terminalMessagesMap.value - REMOTE_TERMINAL_PID
        removeRemoteTerminalFromAiUi()
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
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
        startRemoteTerminalForAiUi(_terminalLauncher.value, path)
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

    fun refreshReminders() {
        val connection = hubConnection
        if (connection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return
        }

        connection.invoke(List::class.java, "GetReminders")
            ?.map { rawList ->
                val jsonElement = gson.toJsonTree(rawList)
                val listType = object : TypeToken<List<ReminderData>>() {}.type
                gson.fromJson<List<ReminderData>>(jsonElement, listType)
            }
            ?.subscribe({ loaded ->
                _reminders.value = com.omni.sync.utils.ReminderDelivery.merge(context, loaded)
            }, { error ->
                com.omni.sync.utils.ReminderDelivery.log(context, "GetReminders failed: ${error.message}", true)
            })
        connection.invoke(String::class.java, "GetTaskResurfacingTimes").subscribe({ times ->
            val prefs = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
            if (prefs.getString("task_times", null) != times) {
                prefs.edit().putString("task_times", times).commit()
                com.omni.sync.utils.ReminderDelivery.load(context).filter { it.kind == 0 }.forEach {
                    com.omni.sync.utils.ReminderDelivery.schedule(context, it, restore = true)
                }
            }
        }, { error -> com.omni.sync.utils.ReminderDelivery.log(context, "Task window sync failed: ${error.message}", true) })
    }

    fun completeReminder(id: String) {
        sendReminderAction("CompleteReminder", id)
    }

    fun dismissReminder(id: String) {
        sendReminderAction("DismissReminder", id)
    }

    fun snoozeReminder(id: String, minutes: Int) {
        sendReminderAction("PostponeReminder", id, java.time.OffsetDateTime.now().plusMinutes(minutes.toLong()).toString())
    }

    fun postponeReminder(id: String, dueAtIso: String) {
        sendReminderAction("PostponeReminder", id, dueAtIso)
    }

    private fun sendReminderAction(method: String, vararg arguments: Any) {
        val id = arguments.firstOrNull() as? String ?: return
        com.omni.sync.utils.ReminderDelivery.act(context, id, method, arguments.getOrNull(1) as? String)
        _reminders.value = com.omni.sync.utils.ReminderDelivery.load(context)
        flushReminderActions()
    }

    private fun flushReminderActions() {
        val connection = hubConnection
        if (connection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return
        }
        val pending = com.omni.sync.utils.ReminderDelivery.pending(context).firstOrNull() ?: return
        if (!reminderActionInFlight.compareAndSet(false, true)) return
        try {
            val arguments: Array<Any> = if (pending.dueAt == null) arrayOf(pending.id) else arrayOf(pending.id, pending.dueAt)
            com.omni.sync.utils.ReminderDelivery.log(context, "Sending ${pending.method} ${pending.id} token=${pending.token}")
            connection.invoke(ReminderData::class.java, pending.method, *arguments).subscribe({ result ->
                com.omni.sync.utils.ReminderDelivery.acknowledge(context, pending.token)
                _reminders.value = com.omni.sync.utils.ReminderDelivery.merge(context, listOf(result))
                reminderActionInFlight.set(false)
                flushReminderActions()
                refreshReminders()
            }, { error ->
                reminderActionInFlight.set(false)
                com.omni.sync.utils.ReminderDelivery.log(context, "Action ${pending.token} failed; retained for retry: ${error.message}", true)
            })
        } catch (error: Exception) {
            reminderActionInFlight.set(false)
            com.omni.sync.utils.ReminderDelivery.log(context, "Action ${pending.token} failed: ${error.message}", true)
        }
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
        if (targetPid == REMOTE_TERMINAL_PID) {
            remoteTerminalClient.clearOutput()
            _terminalMessagesMap.value = _terminalMessagesMap.value + (targetPid to emptyList())
            updateActiveView()
            return
        }
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
            hubConnection?.send("MouseMove", x.toDouble(), y.toDouble())
        }
    }

    fun getScreenCount(): Single<Int>? {
        return if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED)
            hubConnection?.invoke(Int::class.java, "GetScreenCount")
        else null
    }

    fun sendClipboardUpdate(text: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("UpdateClipboard", text)
        }
    }

    fun requestClipboard(pasteIntoFocusedApp: Boolean = false) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.invoke(String::class.java, "GetClipboardText")
                ?.subscribe({ text ->
                    if (!text.isNullOrEmpty()) {
                        coroutineScope.launch(Dispatchers.Main) {
                            try {
                                isUpdatingClipboardInternally = true
                                val clip = ClipData.newPlainText("OmniSyncClipboard", text)
                                clipboardManager.setPrimaryClip(clip)
                                mainViewModel.updateClipboardContent(text)
                                mainViewModel.addLog("Synced clipboard from PC", com.omni.sync.ui.screen.LogType.SUCCESS)
                                if (pasteIntoFocusedApp) {
                                    // The shortcut activity will have closed; allow the former app to regain focus.
                                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                    fun pasteWhenFocused(remainingAttempts: Int) {
                                        val pasted = OmniAccessibilityService.getInstance()?.pasteIntoFocusedApp() == true
                                        if (!pasted && remainingAttempts > 0) {
                                            handler.postDelayed({ pasteWhenFocused(remainingAttempts - 1) }, 200)
                                        } else if (!pasted) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "PC clipboard copied; paste manually or enable Omni Accessibility",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                    handler.postDelayed({ pasteWhenFocused(6) }, 350)
                                }
                            } finally {
                                isUpdatingClipboardInternally = false
                            }
                        }
                    }
                }, { error ->
                    mainViewModel.addLog("Failed to sync clipboard: ${error.message}", com.omni.sync.ui.screen.LogType.ERROR)
                })
        }
    }

    // Android reports both local and agent commands to the Hub's shared transcript.
    private fun reportPhoneCommand(method: String, vararg args: Any) {
        if (isHubAuthenticated &&
            hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send(method, *args)
        }
    }

    fun reportPhoneCommandStarted(id: String, command: String, source: String) =
        reportPhoneCommand("ReportPhoneCommandStarted", id, command, source)

    fun reportPhoneCommandOutput(id: String, chunk: String) =
        reportPhoneCommand("ReportPhoneCommandOutput", id, chunk)

    fun reportPhoneCommandCompleted(id: String, exitCode: Int) =
        reportPhoneCommand("ReportPhoneCommandCompleted", id, exitCode)

    fun executeCommand(command: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("ExecuteCommand", command)
        }
    }

    fun executeMacroScript(script: String) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            Log.d("SignalR", "ExecuteMacro script: $script")
            hubConnection?.send("ExecuteMacro", script)
        } else {
            Log.w("SignalR", "ExecuteMacro skipped: hub not connected")
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

    fun saveBookProgress(path: String, position: String): Unit? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            val payload = mapOf(
                "BookPath" to path,
                "Position" to position,
                "LastUpdated" to java.util.Date()
            )
            return hubConnection?.send("SaveBookProgress", payload)
        }
        return null
    }

    fun getBookProgress(path: String): Single<com.omni.sync.ui.screen.BookProgress>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(com.omni.sync.ui.screen.BookProgress::class.java, "GetBookProgress", path)
        }
        return null
    }

    fun scanBooks(path: String): Single<List<FileSystemEntry>>? {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            return hubConnection?.invoke(List::class.java, "ScanBooksRecursive", path)
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

    fun cycleFocusedWindowMonitor() {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("CycleFocusedWindowMonitor")
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

    fun appendToFile(path: String, content: String) {
        hubConnection?.send("AppendToFile", path, content)
    }

    fun createDirectory(path: String) {
        hubConnection?.send("CreateDirectory", path)
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

    fun sendPayload(command: String, payload: Any?) {
        if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
            hubConnection?.send("SendPayload", command, payload)
        }
    }

    /** Last phone-alarm cycle identity remains valid while Android is snoozing. */
    fun currentDesktopAlarmToken(id: Int): String? =
        if (id > 0 && sharedPrefs.getBoolean("android_alarm_active", false) &&
            sharedPrefs.getInt("android_alarm_id", 0) == id)
            sharedPrefs.getString("android_alarm_token", null) else null

    /** Active remains true through auto-snooze; only dismissal/exhaustion clears the desktop. */
    fun reportAndroidAlarm(id: Int, token: String, active: Boolean, title: String, snoozed: Boolean = false) {
        if (id <= 0 || token.isBlank()) return
        sharedPrefs.edit()
            .putInt("android_alarm_id", id)
            .putString("android_alarm_token", token)
            .putBoolean("android_alarm_active", active)
            .putBoolean("android_alarm_snoozed", snoozed)
            .putString("android_alarm_title", title.take(180))
            .apply()
        val connection = hubConnection
        com.omni.sync.service.AlarmService.logBridge(context, "report_attempt id=$id token=${token.take(8)} active=$active snoozed=$snoozed authenticated=$isHubAuthenticated state=${connection?.connectionState}")
        Log.i("AndroidAlarm", "Report pending id=$id token=${token.take(8)} active=$active snoozed=$snoozed hubAuthenticated=$isHubAuthenticated state=${connection?.connectionState}")
        if (!isHubAuthenticated || connection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) {
            com.omni.sync.service.AlarmService.logBridge(context, "report_deferred id=$id token=${token.take(8)} no_authenticated_connection")
            Log.w("AndroidAlarm", "Alarm state not delivered (Hub disconnected or authenticating); active=$active snoozed=$snoozed")
            if (active) startConnection()
            return
        }
        connection.invoke(Any::class.java, "ReportAndroidAlarm", id, token, active, title.take(180), snoozed)
            .subscribe({
                com.omni.sync.service.AlarmService.logBridge(context, "hub_ack id=$id token=${token.take(8)} active=$active snoozed=$snoozed")
                Log.i("AndroidAlarm", "Hub ACK id=$id token=${token.take(8)} active=$active snoozed=$snoozed")
            }, { error ->
                com.omni.sync.service.AlarmService.logBridge(context, "hub_error id=$id token=${token.take(8)} active=$active reason=${error.message?.take(240)}")
                Log.e("AndroidAlarm", "Hub REJECT id=$id token=${token.take(8)} active=$active: ${error.message}", error)
                if (active) startConnection()
            })
    }

    private fun replayAndroidAlarmAfterAuthentication() {
        com.omni.sync.service.AlarmService.logBridge(context, "hub_authenticated replay_ringing=${com.omni.sync.service.AlarmService.isRinging.value} replay_snoozing=${com.omni.sync.service.AlarmService.isSnoozing.value}")
        Log.i("AndroidAlarm", "Hub authenticated; reconciling local ring/snooze state")
        val activeToken = com.omni.sync.service.AlarmService.currentRemoteToken
        val previousId = sharedPrefs.getInt("android_alarm_id", 0)
        val previousToken = sharedPrefs.getString("android_alarm_token", null)
        val title = sharedPrefs.getString("android_alarm_title", "Phone alarm") ?: "Phone alarm"
        if (com.omni.sync.service.AlarmService.isRinging.value && activeToken != null) {
            reportAndroidAlarm(com.omni.sync.service.AlarmService.lastTriggeredAlarmId, activeToken, true, title)
        } else if (previousId > 0 && previousToken != null) {
            // A snoozing cycle outlives AlarmService. Reaffirm it on reconnect,
            // instead of the old behavior that cleared the popup between rings.
            val snoozed = sharedPrefs.getBoolean("android_alarm_active", false) &&
                com.omni.sync.service.AlarmService.isSnoozing.value
            reportAndroidAlarm(previousId, previousToken, snoozed, title, snoozed)
        }
    }

    fun reportAndroidUpdateStatus(requestId: String, status: String, message: String) {
        val connection = hubConnection
        if (connection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) {
            Log.w("AndroidDeploy", "Cannot report $status for $requestId: SignalR is disconnected.")
            return
        }
        Log.i("AndroidDeploy", "Reporting $status for deployment $requestId: $message")
        try {
            connection.send("ReportAndroidUpdateStatus", requestId, status, message)
            Log.d("AndroidDeploy", "Queued $status report for $requestId.")
        } catch (error: Exception) {
            Log.e("AndroidDeploy", "Failed reporting $status for $requestId.", error)
        }
    }

    private val recoveringPhoneTaskBoard = AtomicBoolean(false)

    /** A previously saved phone edit is retried even if TaskBoard is not open.
     * PC journal receipts deduplicate replayed operation IDs. */
    fun schedulePhoneTaskBoardRecovery() {
        if (!recoveringPhoneTaskBoard.compareAndSet(false, true)) return
        coroutineScope.launch {
            try {
                val drafts = com.omni.sync.data.taskboard.TaskBoardOfflineDrafts(context)
                val writer = com.omni.sync.data.taskboard.TaskBoardPhoneClient(this@SignalRClient, drafts)
                while (drafts.all().any { it.state == "draft" }) {
                    ensureAgentAuthentication()
                    if (hubAuthenticated.value) {
                        try {
                            writer.syncPending()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.w("TaskBoardPhone", "Background recovery deferred: ${failure.message}")
                        }
                    }
                    delay(10_000)
                }
            } finally {
                recoveringPhoneTaskBoard.set(false)
            }
        }
    }
    private val lastAgentAuthAttemptMs = AtomicLong(0L)

    /** A live socket can outlast an unsuccessful Authenticate RPC. Retry auth
     * without requiring the user to reconnect or to retry a TaskBoard edit. */
    fun ensureAgentAuthentication() {
        if (isHubAuthenticated ||
            hubConnection?.connectionState != com.microsoft.signalr.HubConnectionState.CONNECTED) return
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastAgentAuthAttemptMs.get()
        if (now - previous < 12_000L || !lastAgentAuthAttemptMs.compareAndSet(previous, now)) return
        authenticateClient()
    }
    private fun authenticateClient() {
        val connection = hubConnection ?: return
        lastAgentAuthAttemptMs.set(android.os.SystemClock.elapsedRealtime())
        connection.invoke(Boolean::class.java, "Authenticate", apiKey)
            .subscribe({ success ->
                if (hubConnection !== connection) return@subscribe
                Log.d("SignalR", "Auth success: $success")
                isHubAuthenticated = success
                if (success) {
                    advertiseAssistantCommands()
                    flushAssistantVoiceOutbox()
                    refreshReminders()
                    replayAndroidAlarmAfterAuthentication()
                    AndroidUpdateReceiver.consumePendingDeploymentReport(context)?.let { report ->
                        Log.i(
                            "AndroidDeploy",
                            "Re-reporting persisted install result for ${report.requestId}: ${report.status}"
                        )
                        reportAndroidUpdateStatus(report.requestId, report.status, report.message)
                    }
                }
            }, { error ->
                if (hubConnection === connection) {
                    isHubAuthenticated = false
                }
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
        val isSystemLine = response.startsWith("System:") ||
                response.startsWith("Info:") ||
                response.startsWith("Replacement") ||
                response.startsWith("Read") ||
                response.startsWith("Tool Call") ||
                response.startsWith("[Tool Call]") ||
                response.startsWith("Thinking") ||
                response.startsWith("Executing") ||
                response.startsWith("Calling") ||
                response.startsWith("Validating") ||
                (response.length < 200 && (response.startsWith("Strategy:") || response.startsWith("Research:")))

        val isSystem = response.contains("A new version of Gemini CLI is available") || isSystemLine

        val sender = when {
            isError -> "Error"
            isSystem -> "System"
            else -> "AI"
        }

        updateSessionMessages(pid) { currentMessages ->
            val mutable = currentMessages.toMutableList()
            val isNextNew = getIsNextBubble(pid)
            
            // If it's a system/error/user message (anything non-AI), we ALWAYS start a new bubble
            if (sender != "AI") {
                setIsNextBubble(pid, true) // Force next message to also be a new bubble
                mutable + AiMessage(sender, response)
            } else {
                // Regular AI chunk
                val lastMsg = mutable.lastOrNull()
                // If we're not explicitly starting a new bubble, AND the last message was AI, append it
                if (!isNextNew && lastMsg != null && lastMsg.sender == "AI") {
                    // Append to existing AI bubble
                    mutable[mutable.size - 1] = lastMsg.copy(text = lastMsg.text + response)
                    mutable
                } else {
                    // Start new AI bubble
                    setIsNextBubble(pid, false)
                    mutable + AiMessage(sender, response)
                }
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
        
        val octets = localIp.split('.')
        val newHubIp = if (octets.size == 4 &&
            (localIp.startsWith("192.168.") || localIp.startsWith("10."))
        ) {
            octets.take(3).joinToString(".") + ".37"
        } else null
        
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
        hub.remove("PhoneCommandRequested")
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
        hub.remove("AndroidUpdateRequested")
        hub.remove("UpdateExternalIp")
    }
}
