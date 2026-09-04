package com.omni.sync.data.repository

import android.util.Base64
import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RemoteTerminalSession(
    val sessionId: String,
    val command: String,
    val workspace: String
)

class RemoteTerminalClient {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionMutex = Mutex()
    private val connectionVersion = AtomicLong(0)
    private val textBuffer = RemoteTerminalTextBuffer(MAX_SCROLLBACK_CHARS)

    private var connectionJob: Job? = null
    private var hubConnection: HubConnection? = null

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _session = MutableStateFlow<RemoteTerminalSession?>(null)
    val session: StateFlow<RemoteTerminalSession?> = _session

    private val _sessionStatus = MutableStateFlow("stopped")
    val sessionStatus: StateFlow<String> = _sessionStatus

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _preferredCommand = MutableStateFlow("agy")
    val preferredCommand: StateFlow<String> = _preferredCommand

    fun connect(signalRHubUrl: String, apiKey: String) {
        val version = connectionVersion.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = scope.launch {
            connectionMutex.withLock {
                closeConnectionLocked()
                if (version != connectionVersion.get()) return@withLock

                _connectionState.value = "Connecting..."
                _error.value = null
                val connection = HubConnectionBuilder.create(remoteTerminalUrl(signalRHubUrl)).build()
                hubConnection = connection
                registerHandlers(connection, version)

                try {
                    connection.start().timeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).blockingAwait()
                    val authenticated = connection
                        .invoke(Boolean::class.java, "Authenticate", apiKey)
                        .timeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .blockingGet()
                    if (authenticated != true) error("Remote terminal authentication failed")
                    if (version != connectionVersion.get()) {
                        connection.stop().blockingAwait()
                        return@withLock
                    }

                    _connectionState.value = "Connected"
                    _session.value?.let { active ->
                        connection.send("SubscribeRemoteTerminal", active.sessionId)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Remote terminal connection failed", error)
                    if (version == connectionVersion.get()) {
                        _connectionState.value = "Disconnected"
                        _error.value = error.message ?: "Remote terminal connection failed"
                    }
                    closeConnectionLocked()
                }
            }
        }
    }

    fun disconnect() {
        connectionVersion.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = scope.launch {
            connectionMutex.withLock {
                closeConnectionLocked()
                _connectionState.value = "Disconnected"
            }
        }
    }

    fun setPreferredCommand(command: String) {
        if (command.isNotBlank()) _preferredCommand.value = command.trim()
    }

    fun startSession(command: String, workspace: String, columns: Int = 80, rows: Int = 24) {
        val normalizedCommand = command.trim()
        val normalizedWorkspace = workspace.trim()
        if (normalizedCommand.isEmpty()) {
            _error.value = "Enter a command to launch"
            return
        }
        if (normalizedWorkspace.isEmpty()) {
            _error.value = "Enter an absolute workspace path"
            return
        }
        if (_session.value != null) {
            _error.value = "Stop the current terminal before starting another"
            return
        }

        setPreferredCommand(normalizedCommand)
        Log.d(TAG, "Starting remote terminal command=$normalizedCommand workspace=$normalizedWorkspace columns=$columns rows=$rows")
        scope.launch {
            val connection = connectedHub() ?: return@launch
            try {
                _error.value = null
                _sessionStatus.value = "starting"
                clearOutput()
                connection.send(
                    "StartRemoteTerminal",
                    normalizedCommand,
                    normalizedWorkspace,
                    columns,
                    rows
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not start remote terminal", error)
                _sessionStatus.value = "failed"
                _error.value = error.message ?: "Could not start remote terminal"
            }
        }
    }

    fun sendText(text: String, appendEnter: Boolean = false) {
        if (text.isEmpty() && !appendEnter) return
        val suffix = if (appendEnter) "\r" else ""
        sendBytes((text + suffix).toByteArray(Charsets.UTF_8))
    }

    fun sendKey(sequence: String) {
        sendBytes(sequence.toByteArray(Charsets.UTF_8))
    }

    fun resizeSession(columns: Int, rows: Int) {
        val active = _session.value ?: return
        scope.launch {
            val connection = connectedHub() ?: return@launch
            try {
                Log.d(TAG, "Resizing remote terminal session=${active.sessionId} columns=$columns rows=$rows")
                connection.send("ResizeRemoteTerminal", active.sessionId, columns, rows)
                _error.value = null
            } catch (error: Exception) {
                Log.e(TAG, "Could not resize remote terminal", error)
                _error.value = error.message ?: "Could not resize remote terminal"
            }
        }
    }

    fun stopSession() {
        val active = _session.value ?: return
        scope.launch {
            val connection = connectedHub() ?: return@launch
            try {
                _sessionStatus.value = "stopping"
                connection.send("StopRemoteTerminal", active.sessionId)
            } catch (error: Exception) {
                Log.e(TAG, "Could not stop remote terminal", error)
                _error.value = error.message ?: "Could not stop remote terminal"
            }
        }
    }

    @Synchronized
    fun clearOutput() {
        textBuffer.clear()
        _output.value = ""
    }

    private fun sendBytes(bytes: ByteArray) {
        val active = _session.value
        if (active == null) {
            _error.value = "No active remote terminal"
            return
        }
        scope.launch {
            val connection = connectedHub() ?: return@launch
            try {
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                connection.send("SendRemoteTerminalInput", active.sessionId, encoded)
                _error.value = null
            } catch (error: Exception) {
                Log.e(TAG, "Could not send remote terminal input", error)
                _error.value = error.message ?: "Could not send remote terminal input"
            }
        }
    }

    private fun connectedHub(): HubConnection? {
        val connection = hubConnection
        if (connection?.connectionState != HubConnectionState.CONNECTED) {
            _error.value = "Remote terminal transport is not connected"
            return null
        }
        return connection
    }

    private fun registerHandlers(connection: HubConnection, version: Long) {
        connection.on(
            "RemoteTerminalStarted",
            { sessionId: String, command: String, workspace: String ->
                if (version != connectionVersion.get()) return@on
                Log.d(TAG, "RemoteTerminalStarted session=$sessionId command=$command workspace=$workspace")
                _session.value = RemoteTerminalSession(sessionId, command, workspace)
                _sessionStatus.value = "launching"
            },
            String::class.java,
            String::class.java,
            String::class.java
        )

        connection.on(
            "RemoteTerminalStatus",
            { sessionId: String, status: String ->
                if (version != connectionVersion.get()) return@on
                val active = _session.value
                Log.d(TAG, "RemoteTerminalStatus session=$sessionId status=$status activeSession=${active?.sessionId}")
                if (active?.sessionId == sessionId) {
                    _sessionStatus.value = status
                    if (status == "stopped" || status == "failed" || status == "exited") {
                        _session.value = null
                    }
                }
            },
            String::class.java,
            String::class.java
        )

        connection.on(
            "RemoteTerminalOutput",
            { sessionId: String, dataBase64: String ->
                if (version != connectionVersion.get() || _session.value?.sessionId != sessionId) {
                    Log.w(TAG, "Ignoring terminal output session=$sessionId activeSession=${_session.value?.sessionId} version=$version currentVersion=${connectionVersion.get()}")
                    return@on
                }
                try {
                    val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
                    Log.d(TAG, "RemoteTerminalOutput received session=$sessionId base64Chars=${dataBase64.length} bytes=${bytes.size}")
                    appendOutput(bytes)
                } catch (error: IllegalArgumentException) {
                    Log.e(TAG, "Hub sent invalid terminal output", error)
                    _error.value = "Hub sent invalid terminal output"
                }
            },
            String::class.java,
            String::class.java
        )

        connection.onClosed { error ->
            if (version != connectionVersion.get()) return@onClosed
            _connectionState.value = "Disconnected"
            if (error != null) _error.value = error.message ?: "Remote terminal connection closed"
        }
    }

    @Synchronized
    private fun appendOutput(bytes: ByteArray) {
        Log.d(TAG, "RemoteTerminalOutput bytes=${bytes.size} preview=${debugPreview(bytes)}")
        _output.value = textBuffer.append(bytes)
        Log.d(TAG, "RemoteTerminalOutput bufferedChars=${_output.value.length} outputHash=${_output.value.hashCode()}")
    }

    private fun closeConnectionLocked() {
        val connection = hubConnection ?: return
        hubConnection = null
        try {
            connection.stop().timeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).blockingAwait()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing remote terminal connection: ${error.message}")
        }
    }

    private fun remoteTerminalUrl(signalRHubUrl: String): String {
        val withoutQuery = signalRHubUrl.substringBefore('?').trimEnd('/')
        return if (withoutQuery.endsWith("/signalrhub", ignoreCase = true)) {
            withoutQuery.dropLast("/signalrhub".length) + "/remoteterminalhub"
        } else {
            "$withoutQuery/remoteterminalhub"
        }
    }

    companion object {
        private const val TAG = "RemoteTerminalClient"
        private const val CONNECTION_TIMEOUT_SECONDS = 10L
        private const val MAX_SCROLLBACK_CHARS = 100_000

        private fun debugPreview(bytes: ByteArray): String {
            return bytes.take(120).joinToString(separator = "") { byte ->
                when (val value = byte.toInt() and 0xff) {
                    0x1b -> "<ESC>"
                    0x0d -> "<CR>"
                    0x0a -> "<LF>"
                    in 0x20..0x7e -> value.toChar().toString()
                    else -> "<${value.toString(16).padStart(2, '0')}>"
                }
            }
        }
    }
}

internal class RemoteTerminalTextBuffer(private val maxChars: Int) {
    private var pendingBytes = ByteArray(0)
    private var text = ""

    @Synchronized
    fun append(bytes: ByteArray): String {
        if (bytes.isEmpty()) return text
        val combined = pendingBytes + bytes
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(combined.size.coerceAtLeast(1))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        decoder.decode(input, output, false)
        pendingBytes = ByteArray(input.remaining())
        input.get(pendingBytes)
        output.flip()
        text = (text + output.toString()).takeLast(maxChars)
        return text
    }

    @Synchronized
    fun clear() {
        pendingBytes = ByteArray(0)
        text = ""
    }
}
