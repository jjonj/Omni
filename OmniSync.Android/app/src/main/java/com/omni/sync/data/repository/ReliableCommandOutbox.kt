package com.omni.sync.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** One durable, bounded user command; never replayed with a new ID. */
data class ReliableCommandRecord(
    val id: String,
    val method: String,
    val argumentsJson: String,
    val createdAtMillis: Long,
    val state: String = "pending",
    val attempts: Int = 0,
    val error: String? = null,
    // UI progress is separate from the authoritative Hub result in state.
    val phase: String? = null
)

data class ReliableCommandReceipt(
    val id: String = "",
    val state: String = "unknown",
    val error: String? = null
)

/**
 * Maintains a small offline queue for discrete commands. The Rust Hub records
 * admission before executing: retrying the same ID queries that receipt first.
 * Realtime keyboard-down/up and continuous pointer movement bypass this outbox.
 */
class ReliableCommandOutbox(
    context: Context,
    private val connection: () -> HubConnection?,
    private val authenticated: () -> Boolean,
    private val unstableMode: () -> Boolean,
    private val onState: (ReliableCommandRecord) -> Unit,
    private val onLinkFailure: () -> Unit
) {
    private val prefs = context.getSharedPreferences("reliable_commands_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushing = AtomicBoolean(false)
    private val _records = MutableStateFlow<List<ReliableCommandRecord>>(emptyList())
    val records: StateFlow<List<ReliableCommandRecord>> = _records
    private var history: MutableList<ReliableCommandRecord> = mutableListOf()

    companion object {
        private const val MAX_QUEUED = 48
        private const val MAX_HISTORY = 12
        private const val MAX_AGE_MS = 5 * 60 * 1000L
        private const val SLOW_LINK_MAX_AGE_MS = 20 * 60 * 1000L
        private const val URGENT_AGE_MS = 40 * 1000L
        private const val MAX_ATTEMPTS = 8
    }

    private fun isTimeSensitive(method: String) = method in setOf(
        "ExecuteCommand", "ExecuteMacro", "WinActivate", "WinClose", "ToggleShutdownMode",
        "CycleFocusedWindowMonitor", "SendPayload", "SendBrowserCommand"
    )

    init {
        synchronized(lock) {
            val raw = prefs.getString("items", "[]") ?: "[]"
            history = runCatching {
                val type = object : TypeToken<List<ReliableCommandRecord>>() {}.type
                (gson.fromJson<List<ReliableCommandRecord>>(raw, type) ?: emptyList()).toMutableList()
            }.getOrElse {
                // Do not silently discard commands if the local ledger is unreadable.
                mutableListOf(ReliableCommandRecord(
                    id = "local-ledger-error", method = "Recovery", argumentsJson = "[]",
                    createdAtMillis = System.currentTimeMillis(), state = "unknown",
                    error = "Local command record is unreadable; verify previous actions manually"
                ))
            }
            if (unstableMode()) {
                // Restart is also a disconnect: do not silently resume an unsent
                // macro, click or other time-sensitive command.
                history = history.map { item ->
                    if (item.state == "pending" && item.attempts == 0 && isTimeSensitive(item.method))
                        item.copy(phase = "held") else item
                }.toMutableList()
            }
            expireLocked()
            publishLocked()
        }
    }

    fun submit(method: String, vararg args: Any?): String {
        val id = UUID.randomUUID().toString()
        val serialized = gson.toJson(args.toList())
        val createdAt = System.currentTimeMillis()
        // During flight-mode outages a queued macro or click needs a fresh tap
        // after reconnect, rather than executing against a changed PC state.
        val held = unstableMode() && !authenticated() && isTimeSensitive(method)
        var record = ReliableCommandRecord(id, method, serialized, createdAt,
            phase = if (held) "held" else "queued")
        synchronized(lock) {
            expireLocked()
            val queued = history.count { it.state == "pending" }
            if (queued >= MAX_QUEUED || serialized.length > 64 * 1024) {
                record = record.copy(state = "failed", error = "Command queue full or payload too large; not sent")
            }
            history.add(record)
            persistLocked()
        }
        onState(record)
        if (record.state == "pending" && !held) flushOnConnect()
        return id
    }

    private fun expireLocked() {
        val now = System.currentTimeMillis()
        history = history.map { item ->
            // Commands that depend on the *current* PC state must never wake up
            // long after the tap. Durable file writes can wait on a slow link.
            val urgent = if (unstableMode()) isTimeSensitive(item.method) else
                (item.method in setOf("WinActivate", "WinClose", "CycleFocusedWindowMonitor") ||
                    (item.method == "SendPayload" &&
                        (item.argumentsJson.contains("LEFT_CLICK") || item.argumentsJson.contains("RIGHT_CLICK"))))
            val lifespan = if (urgent) URGENT_AGE_MS
                else if (unstableMode()) SLOW_LINK_MAX_AGE_MS else MAX_AGE_MS
            if (item.state == "pending" && now - item.createdAtMillis > lifespan) {
                item.copy(
                    state = if (item.attempts == 0) "failed" else "unknown",
                    phase = null,
                    error = if (item.attempts == 0) "Offline command expired without sending"
                        else "Command timed out; execution must be verified before retrying"
                )
            } else item
        }.toMutableList()
    }

    private fun publishLocked() {
        // Always surface every unresolved command even after many later actions.
        val pending = history.filter { it.state == "pending" }.asReversed()
        val recent = history.filter { it.state != "pending" }.takeLast(MAX_HISTORY).asReversed()
        _records.value = pending + recent
    }

    private fun persistLocked() {
        // Keep pending operations, and only enough history to explain recent results.
        val pending = history.filter { it.state == "pending" }
        // The plaintext command body is needed only while delivery is pending.
        // Never retain completed typed text/macros in history.
        val final = history.filter { it.state != "pending" }.takeLast(MAX_HISTORY)
            .map { it.copy(argumentsJson = "[]") }
        val saved = (pending + final).sortedBy { it.createdAtMillis }
        if (!prefs.edit().putString("items", gson.toJson(saved)).commit()) {
            Log.e("ReliableCommands", "Could not persist command ledger")
            // Failing closed: callers must not transmit a command without a durable ID.
            throw IllegalStateException("Cannot persist command ledger")
        }
        history = saved.toMutableList()
        publishLocked()
    }

    private fun update(id: String, change: (ReliableCommandRecord) -> ReliableCommandRecord) {
        var result: ReliableCommandRecord? = null
        synchronized(lock) {
            val index = history.indexOfFirst { it.id == id }
            if (index < 0) return
            history[index] = change(history[index])
            result = history[index]
            persistLocked()
        }
        result?.let(onState)
    }

    /** A command can be cancelled only before the first transmission is claimed. */
    fun cancelUnsent(id: String): Boolean {
        var cancelled: ReliableCommandRecord? = null
        synchronized(lock) {
            val index = history.indexOfFirst { it.id == id && it.state == "pending" && it.attempts == 0 }
            if (index < 0) return false
            cancelled = history[index].copy(state = "cancelled", phase = null, error = "Cancelled before sending")
            history[index] = cancelled!!
            persistLocked()
        }
        cancelled?.let(onState)
        return true
    }

    /** Prevent unsent time-sensitive work from replaying after a disconnect. */
    fun holdUnsentSensitive() {
        if (!unstableMode()) return
        synchronized(lock) {
            history = history.map { item ->
                if (item.state == "pending" && item.attempts == 0 && isTimeSensitive(item.method))
                    item.copy(phase = "held") else item
            }.toMutableList()
            persistLocked()
        }
    }

    /** A manual tap releases a held command; repeated taps keep the same ID. */
    fun retryPending(id: String): Boolean {
        if (!authenticated()) return false
        val pending = synchronized(lock) {
            val index = history.indexOfFirst { it.id == id && it.state == "pending" }
            if (index < 0) false else {
                history[index] = history[index].copy(phase = "queued")
                persistLocked()
                true
            }
        }
        if (pending) flushOnConnect()
        return pending
    }

    /** Manual receipt lookup for an uncertain command; this NEVER resubmits it. */
    fun checkStatus(id: String) {
        val item = synchronized(lock) {
            history.firstOrNull { it.id == id && it.attempts > 0 && it.state in setOf("unknown", "failed") }
        } ?: return
        scope.launch {
            val conn = connection()?.takeIf { authenticated() && it.connectionState == HubConnectionState.CONNECTED }
                ?: return@launch
            try {
                val receipt = conn.invoke(ReliableCommandReceipt::class.java, "GetReliableCommandStatus", item.id)
                    .timeout(if (unstableMode()) 70L else 30L, TimeUnit.SECONDS).blockingGet()
                when (receipt.state) {
                    "succeeded", "failed", "unknown" -> update(item.id) {
                        it.copy(state = receipt.state, phase = null, error = receipt.error)
                    }
                    "pending" -> update(item.id) {
                        it.copy(state = "unknown", phase = null, error = "Hub accepted command; still processing. Check again later")
                    }
                    "not_found" -> update(item.id) {
                        it.copy(state = "unknown", phase = null, error = "No Hub receipt; do not resend without verifying the effect")
                    }
                }
            } catch (e: Exception) {
                update(item.id) { it.copy(state = "unknown", phase = null, error = "Status unavailable: ${e.message}") }
            }
        }
    }

    fun flushOnConnect() {
        if (!authenticated() || connection()?.connectionState != HubConnectionState.CONNECTED) return
        if (!flushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (authenticated()) {
                    val conn = connection()?.takeIf { it.connectionState == HubConnectionState.CONNECTED } ?: break
                    val item = synchronized(lock) {
                        expireLocked()
                        persistLocked()
                        history.firstOrNull { it.state == "pending" && it.phase != "held" }
                    } ?: break

                    // Resolve the previous attempt before deciding whether a replay
                    // of the *same* stable ID is appropriate.
                    if (item.attempts > 0) {
                        update(item.id) { it.copy(phase = "retrying") }
                        val status = try {
                            conn.invoke(ReliableCommandReceipt::class.java, "GetReliableCommandStatus", item.id)
                                .timeout(if (unstableMode()) 70L else 30L, TimeUnit.SECONDS).blockingGet()
                        } catch (error: Exception) {
                            Log.w("ReliableCommands", "Status query interrupted for ${item.id}: ${error.message}")
                            onLinkFailure()
                            break
                        }
                        when (status.state) {
                            "succeeded", "failed", "unknown" -> {
                                update(item.id) { it.copy(state = status.state, phase = null, error = status.error) }
                                continue
                            }
                            "pending" -> {
                                update(item.id) { it.copy(phase = "delivered", error = null) }
                                delay(if (unstableMode()) 12_000L else 1_500L)
                                continue
                            }
                            "not_found" -> {
                                // A stale click, macro, shutdown or browser action
                                // must NOT be automatically replayed after a lost reply.
                                if (item.method !in setOf("AppendToFile", "CreateDirectory")) {
                                    update(item.id) { it.copy(state = "failed", phase = null,
                                        error = "Hub did not receive it. Tap the original action again if still needed") }
                                    continue
                                }
                                val pause = (1000L shl item.attempts.coerceAtMost(5)).coerceAtMost(30_000L)
                                delay(pause + Random.nextLong(0, 900))
                            }
                            else -> {
                                update(item.id) { it.copy(state = "unknown", phase = null, error = "Unexpected Hub status: ${status.state}") }
                                continue
                            }
                        }
                    }

                    if (item.attempts >= MAX_ATTEMPTS) {
                        // Every prior attempt was definitively not_found.
                        update(item.id) { it.copy(state = "failed", phase = null, error = "No Hub admission after bounded retries") }
                        continue
                    }
                    val expiredNow = synchronized(lock) {
                        expireLocked()
                        persistLocked()
                        history.firstOrNull { it.id == item.id }?.state != "pending"
                    }
                    if (expiredNow) continue
                    // Atomically claim the first send, so cancellation cannot race it.
                    val claimed = synchronized(lock) {
                        val index = history.indexOfFirst { it.id == item.id && it.state == "pending" }
                        if (index < 0) false else {
                            history[index] = history[index].copy(attempts = history[index].attempts + 1, phase = "sending")
                            persistLocked()
                            true
                        }
                    }
                    if (!claimed) continue
                    try {
                        val receipt = conn.invoke(
                            ReliableCommandReceipt::class.java,
                            "RunReliableCommand",
                            item.id, item.method, JsonParser.parseString(item.argumentsJson).asJsonArray,
                            item.createdAtMillis
                        ).timeout(if (unstableMode()) 90L else 45L, TimeUnit.SECONDS).blockingGet()
                        if (receipt.state in listOf("succeeded", "failed", "unknown")) {
                            update(item.id) { it.copy(state = receipt.state, phase = null, error = receipt.error) }
                        } else {
                            update(item.id) { it.copy(phase = "delivered", error = null) }
                            delay(if (unstableMode()) 12_000L else 1_500L)
                        }
                    } catch (error: Exception) {
                        update(item.id) { it.copy(phase = "retrying", error = "Awaiting receipt after interrupted connection") }
                        Log.w("ReliableCommands", "Command ${item.id} response unknown: ${error.message}")
                        onLinkFailure()
                        break
                    }
                }
            } finally {
                flushing.set(false)
                // Recovery is rearmed by authentication / next explicit user action.
            }
        }
    }
}
