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
    val error: String? = null
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
        private const val URGENT_AGE_MS = 40 * 1000L
        private const val MAX_ATTEMPTS = 8
    }

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
            expireLocked()
            publishLocked()
        }
    }

    fun submit(method: String, vararg args: Any?): String {
        val id = UUID.randomUUID().toString()
        val serialized = gson.toJson(args.toList())
        val createdAt = System.currentTimeMillis()
        var record = ReliableCommandRecord(id, method, serialized, createdAt)
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
        if (record.state == "pending") flushOnConnect()
        return id
    }

    private fun expireLocked() {
        val now = System.currentTimeMillis()
        history = history.map { item ->
            val urgent = item.method in listOf("WinActivate", "WinClose", "CycleFocusedWindowMonitor") ||
                (item.method == "SendPayload" &&
                    (item.argumentsJson.contains("LEFT_CLICK") || item.argumentsJson.contains("RIGHT_CLICK")))
            val lifespan = if (urgent) URGENT_AGE_MS else MAX_AGE_MS
            if (item.state == "pending" && now - item.createdAtMillis > lifespan) {
                item.copy(
                    state = if (item.attempts == 0) "failed" else "unknown",
                    error = if (item.attempts == 0) "Offline command expired without sending"
                        else "Command timed out; execution must be verified before retrying"
                )
            } else item
        }.toMutableList()
    }

    private fun publishLocked() {
        _records.value = history.takeLast(MAX_HISTORY).reversed()
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
                        history.firstOrNull { it.state == "pending" }
                    } ?: break

                    // Resolve the previous attempt before deciding whether a replay
                    // of the *same* stable ID is appropriate.
                    if (item.attempts > 0) {
                        val status = try {
                            conn.invoke(ReliableCommandReceipt::class.java, "GetReliableCommandStatus", item.id)
                                .timeout(30, TimeUnit.SECONDS).blockingGet()
                        } catch (error: Exception) {
                            Log.w("ReliableCommands", "Status query interrupted for ${item.id}: ${error.message}")
                            onLinkFailure()
                            break
                        }
                        when (status.state) {
                            "succeeded", "failed", "unknown" -> {
                                update(item.id) { it.copy(state = status.state, error = status.error) }
                                continue
                            }
                            "pending" -> {
                                delay(1500)
                                continue
                            }
                            "not_found" -> {
                                // Hub definitively never admitted this ID. Reuse it
                                // with bounded exponential backoff and jitter.
                                val pause = (1000L shl item.attempts.coerceAtMost(5)).coerceAtMost(30_000L)
                                delay(pause + Random.nextLong(0, 900))
                            }
                            else -> {
                                update(item.id) { it.copy(state = "unknown", error = "Unexpected Hub status: ${status.state}") }
                                continue
                            }
                        }
                    }

                    if (item.attempts >= MAX_ATTEMPTS) {
                        // Every prior attempt was definitively not_found.
                        update(item.id) { it.copy(state = "failed", error = "No Hub admission after bounded retries") }
                        continue
                    }
                    val expiredNow = synchronized(lock) {
                        expireLocked()
                        persistLocked()
                        history.firstOrNull { it.id == item.id }?.state != "pending"
                    }
                    if (expiredNow) continue
                    update(item.id) { it.copy(attempts = it.attempts + 1) }
                    try {
                        val receipt = conn.invoke(
                            ReliableCommandReceipt::class.java,
                            "RunReliableCommand",
                            item.id, item.method, JsonParser.parseString(item.argumentsJson).asJsonArray,
                            item.createdAtMillis
                        ).timeout(45, TimeUnit.SECONDS).blockingGet()
                        if (receipt.state in listOf("succeeded", "failed", "unknown")) {
                            update(item.id) { it.copy(state = receipt.state, error = receipt.error) }
                        } else {
                            delay(1500)
                        }
                    } catch (error: Exception) {
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
