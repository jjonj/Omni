package com.omni.sync.data.repository

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap

data class AgentProviderUi(
    val id: String, val name: String, val enabled: Boolean, val active: Boolean,
    val capabilities: List<String> = emptyList(),
    val kinds: List<String> = emptyList()
)
data class AgentSessionUi(
    val provider: String, val id: String, val title: String,
    val kind: String, val activity: String, val confidence: String,
    val conversation: String?, val modes: List<String>,
    val capabilities: List<String> = emptyList(),
    val attention: String = "None",
    val unread: Boolean? = null
) {
    val key get() = "$provider:$id"
}
data class AgentHistoryUi(
    val id: String, val kind: String, val text: String, val partial: Boolean,
    val revision: Long = 1
)
data class AgentQueuedUi(val id: String, val text: String)
data class PendingAgentUi(
    val id: String, val target: String, val text: String, val state: String,
    val error: String?, val createdAt: Long,
    val needsVerification: Boolean = false,
    val provider: String = "",
    val targetType: String = "session"
)
data class AddressIdentityPreview(
    val requestId: String, val summary: String, val stableRecipientJson: String
)
private data class SavedAgentRequest(
    val id: String,
    val provider: String,
    val targetType: String, // session or address
    val target: String,
    val conversation: String?,
    val message: String,
    var state: String = "Pending",
    val createdAt: Long = System.currentTimeMillis(),
    var operationId: String? = null,
    var error: String? = null,
    var stableRecipientJson: String? = null,
    val kind: String? = null
)

/**
 * Android-side client infrastructure for the generic provider API.
 *
 * Important: the Hub cannot store a message when the Android device is offline.
 * Pending requests are stored locally before they are sent, and use the same
 * clientRequestId on every transport invocation. InFlight becomes Unknown after
 * a process restart, because submission could have succeeded before a crash.
 */
class GenericAgentClient(
    private val context: Context,
    private val signalR: SignalRClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val operationMutex = Mutex()
    private val diskGuard = Any()
    private val gson = Gson()
    private val disk = AtomicFile(File(context.filesDir, "agent-offline-outbox-v1.json"))
    private val historyDisk = AtomicFile(File(context.filesDir, "agent-history-cache-v1.json"))
    private val sessionDisk = AtomicFile(File(context.filesDir, "agent-sessions-cache-v1.json"))
    private val archivePrefs=context.getSharedPreferences("agent-history-archive",Context.MODE_PRIVATE)
    private val providerPrefs=context.getSharedPreferences("agent-provider-binding",Context.MODE_PRIVATE)
    private val archive:AgentHistoryArchive by lazy {AgentHistoryArchive(context)}
    private val workCreate by lazy { AgentWorkCreateStore(context) }
    @Volatile private var outboxWritable = true
    private val entries = linkedMapOf<String, SavedAgentRequest>()
    // History paging and live session events can finish on different IO threads.
    // Preserve recent-session cache ordering without exposing an unsynchronized map.
    private val historyCache: MutableMap<String, List<AgentHistoryUi>> =
        Collections.synchronizedMap(LinkedHashMap<String, List<AgentHistoryUi>>(64, .75f, true))
    private val historyOlderCursors: MutableMap<String, String?> =
        Collections.synchronizedMap(mutableMapOf())
    @Volatile private var lastSessionEventReadAt = 0L
    private val historyLoadMutex = Mutex()
    private val refreshMutex = Mutex()
    private val draftPrefs = context.getSharedPreferences("agent-compose-drafts-v1", Context.MODE_PRIVATE)

    // Drafts are scoped to a specific session (or the contact composer), never shared
    // across recipients. Keep them after process death, but clear only after durable enqueue.
    fun getDraft(key: String): String = draftPrefs.getString(key, "").orEmpty()
    fun saveDraft(key: String, text: String) {
        if (text.isEmpty()) draftPrefs.edit().remove(key).apply()
        else draftPrefs.edit().putString(key, text.take(30_000)).apply()
    }

    private val _providers = MutableStateFlow<List<AgentProviderUi>>(emptyList())
    val providers: StateFlow<List<AgentProviderUi>> = _providers
    private val clientId=providerPrefs.getString("clientId",null) ?: UUID.randomUUID().toString().also {
        providerPrefs.edit().putString("clientId",it).apply()
    }
    private val _lastProvider = MutableStateFlow(providerPrefs.getString("lastActive",null))
    val lastProvider: StateFlow<String?> = _lastProvider
    private val _sessions = MutableStateFlow<List<AgentSessionUi>>(emptyList())
    val sessions: StateFlow<List<AgentSessionUi>> = _sessions
    private val _history = MutableStateFlow<List<AgentHistoryUi>>(emptyList())
    val history: StateFlow<List<AgentHistoryUi>> = _history
    private val _olderHistoryCursor = MutableStateFlow<String?>(null)
    val olderHistoryCursor: StateFlow<String?> = _olderHistoryCursor
    private val _archiveEnabled=MutableStateFlow(archivePrefs.getBoolean("enabled",false))
    val archiveEnabled:StateFlow<Boolean> = _archiveEnabled
    private val _archiveSearch=MutableStateFlow<List<Pair<String,AgentHistoryUi>>>(emptyList())
    val archiveSearch:StateFlow<List<Pair<String,AgentHistoryUi>>> = _archiveSearch
    fun setArchiveEnabled(enabled:Boolean){
        _archiveEnabled.value=enabled
        archivePrefs.edit().putBoolean("enabled",enabled).apply()
    }
    private val _archiveQuery = MutableStateFlow("")
    suspend fun searchArchive(phrase: String) {
        // Record intent before dispatching to IO; worker startup itself can be
        // reordered, not just completion of the underlying SQLite search.
        _archiveQuery.value = phrase
        val results = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (!_archiveEnabled.value || phrase.isBlank()) emptyList()
            else archive.search(phrase)
        }
        if (_archiveQuery.value == phrase) _archiveSearch.value = results
    }
    private val _historyNotice = MutableStateFlow<String?>(null)
    val historyNotice: StateFlow<String?> = _historyNotice
    private val _remoteQueue = MutableStateFlow<List<AgentQueuedUi>>(emptyList())
    val remoteQueue: StateFlow<List<AgentQueuedUi>> = _remoteQueue
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected
    private val _outbox = MutableStateFlow<List<PendingAgentUi>>(emptyList())
    val outbox: StateFlow<List<PendingAgentUi>> = _outbox
    private val _status = MutableStateFlow<String>("")
    val status: StateFlow<String> = _status
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading
    private val _createdSessionKey = MutableStateFlow<String?>(null)
    val createdSessionKey: StateFlow<String?> = _createdSessionKey

    init {
        synchronized(diskGuard) {
            loadOutbox()
            loadHistory()
            loadSessions()
            // A process can die after calling Hub, before persisting the result.
            // Such messages cannot safely be retransmitted without reconciliation.
            for (item in entries.values) {
                if (item.state == "InFlight") {
                    item.state = "Unknown"
                    item.error = "App restarted while delivery was in progress. Check destination before retrying."
                }
            }
            if (outboxWritable) persistOutbox()
            publishOutbox()
        }
        scope.launch {
            signalR.hubAuthenticated.collectLatest { authenticated ->
                if (authenticated) {
                    refresh()
                    drain()
                } else {
                    _status.value = "Offline; messages are saved on this device."
                }
            }
        }
        scope.launch {
            signalR.agentEvents.collectLatest { event ->
                when (event.get("method")?.asString) {
                    "providers.changed" -> {refresh();drain()}
                    "session.changed" -> {
                        val summary = event.obj("payload")
                        val updated = summary?.let { parseSession(it) }
                        if (updated != null) {
                            val previous = _sessions.value.firstOrNull { it.key == updated.key }
                            // Some providers emit compact state-only deltas. Preserve
                            // identity/capabilities rather than briefly erasing the
                            // controls or changing a title to an opaque ID.
                            val preserved = updated.copy(
                                title = if (summary.has("title")) updated.title
                                    else previous?.title ?: updated.title,
                                kind = if (summary.has("kind")) updated.kind
                                    else previous?.kind ?: updated.kind,
                                conversation = updated.conversation ?: previous?.conversation,
                                modes = if (updated.modes.isNotEmpty()) updated.modes
                                    else previous?.modes.orEmpty(),
                                capabilities = if (updated.capabilities.isNotEmpty()) updated.capabilities
                                    else previous?.capabilities.orEmpty(),
                                attention = if (summary.has("attention")) updated.attention
                                    else previous?.attention ?: updated.attention,
                                unread = updated.unread ?: previous?.unread,
                                activity = if (summary.obj("state")?.has("activity") == true)
                                    updated.activity else previous?.activity ?: updated.activity,
                                confidence = if (summary.obj("state")?.has("confidence") == true)
                                    updated.confidence else previous?.confidence ?: updated.confidence
                            )
                            _sessions.value = if (previous == null) listOf(preserved) + _sessions.value
                                else _sessions.value.map { if (it.key == updated.key) preserved else it }
                            val current = _selected.value
                            val now = System.currentTimeMillis()
                            if (current == updated.key && now - lastSessionEventReadAt > 1800) {
                                lastSessionEventReadAt = now
                                scope.launch { select(current, reload = true) }
                            }
                        }
                    }
                    "operation.changed" -> {
                        val payload = event.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
                        val id = payload?.str("clientRequestId")
                        if (id != null) synchronized(diskGuard) {
                            val item = entries[id]
                            val state = payload.str("state")
                            if (item != null && state in listOf("Delivered","Failed","Unknown","Accepted","Queued")) {
                                item.state = state!!
                                item.operationId = payload.str("id")
                                item.error = payload.str("error")
                                if (outboxWritable) persistOutbox()
                                publishOutbox()
                            }
                        }
                    }
                    "resync.required" -> refresh()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                if (signalR.hubAuthenticated.value) {
                    refresh()
                    // Accepted/Queued operations also need periodic reconciliation even
                    // when no new pending items exist.
                    drain()
                }
            }
        }
    }
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.flag(key: String): Boolean =
        get(key)?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false
    private fun JsonObject.strings(key: String): List<String> =
        array(key)?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()
    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun publishOutbox() {
        _outbox.value = entries.values.sortedByDescending { it.createdAt }.map {
            PendingAgentUi(it.id, it.target, it.message, it.state, it.error, it.createdAt,
                it.targetType=="address" && it.stableRecipientJson.isNullOrBlank(),
                it.provider, it.targetType)
        }
    }
    private fun loadOutbox() {
        if (!disk.baseFile.exists()) return
        try {
            if (disk.baseFile.length() > 2 * 1024 * 1024) throw IllegalStateException("Outbox too large")
            val root = disk.openRead().bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            if (root.get("version")?.asInt != 1) throw IllegalStateException("Unsupported outbox format")
            root.array("items")?.forEach { row ->
                val item = gson.fromJson(row, SavedAgentRequest::class.java)
                if (item.id.isNotBlank() && entries.size < 1000) entries[item.id] = item
            }
        } catch (e: Exception) {
            Log.e("GenericAgents", "Preserving unreadable outbox", e)
            _status.value = "Local outbox damaged. Original preserved; sending disabled."
            outboxWritable = false
            _status.value = "Local outbox unreadable; messages cannot be queued or sent until manually repaired."
        }
    }
    private fun persistOutbox() {
        check(outboxWritable) { "Outbox file damaged; original preserved" }
        val snapshot = JsonObject().also {
            it.addProperty("version", 1)
            it.add("items", gson.toJsonTree(entries.values.toList()))
        }
        val encoded = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 2 * 1024 * 1024) { "Outbox capacity exceeded" }
        val stream = disk.startWrite()
        try {
            stream.write(encoded)
            disk.finishWrite(stream)
        } catch (e: Exception) {
            disk.failWrite(stream)
            throw e
        }
    }
    private fun loadSessions() {
        if (!sessionDisk.baseFile.exists()) return
        try {
            if (sessionDisk.baseFile.length() > 2 * 1024 * 1024) return
            val rows = sessionDisk.openRead().bufferedReader().use {
                JsonParser.parseReader(it).asJsonArray
            }
            _sessions.value = rows.take(1500).mapNotNull {
                runCatching { gson.fromJson(it,AgentSessionUi::class.java) }.getOrNull()
            }
        } catch (e: Exception) { Log.w("GenericAgents", "Offline session listing unavailable", e) }
    }
    private fun saveSessions() {
        try {
            val encoded=gson.toJson(_sessions.value.take(1500)).toByteArray(Charsets.UTF_8)
            if (encoded.size>2*1024*1024) return
            val stream=sessionDisk.startWrite()
            try {stream.write(encoded);sessionDisk.finishWrite(stream)}
            catch (e: Exception) {sessionDisk.failWrite(stream);throw e}
        } catch (e: Exception) {Log.w("GenericAgents","Session cache write failed",e)}
    }
    private fun loadHistory() {
        if (!historyDisk.baseFile.exists()) return
        try {
            if (historyDisk.baseFile.length() > 3 * 1024 * 1024) return
            val root = historyDisk.openRead().bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            for ((id, array) in root.entrySet()) {
                if (id.length > 280 || !array.isJsonArray) continue
                historyCache[id] = array.asJsonArray.toList().takeLast(200).mapNotNull {
                    runCatching { gson.fromJson(it, AgentHistoryUi::class.java) }.getOrNull()
                }
            }
        } catch (e: Exception) {
            Log.w("GenericAgents", "Partial offline history unavailable", e)
        }
    }
    private fun saveHistory() = synchronized(diskGuard) {
        val root = JsonObject()
        val recent = synchronized(historyCache) { historyCache.entries.toList().takeLast(64) }
        for ((id, items) in recent) {
            root.add(id, gson.toJsonTree(items.takeLast(200)))
        }
        val encoded = gson.toJson(root).toByteArray(Charsets.UTF_8)
        if (encoded.size > 3 * 1024 * 1024) return@synchronized
        val stream = historyDisk.startWrite()
        try {
            stream.write(encoded)
            historyDisk.finishWrite(stream)
        } catch (e: Exception) {
            historyDisk.failWrite(stream)
            Log.w("GenericAgents", "Unable to save offline history", e)
        }
    }
    private suspend fun watchSelection() {
        val key=_selected.value
        val session=_sessions.value.firstOrNull {it.key==key}
        runCatching {
            signalR.agentCall("sessions.watch",mapOf("sessions" to (
                if(session==null) emptyList() else listOf(mapOf(
                    "provider" to session.provider,"id" to session.id)))))
        }.onFailure {Log.d("GenericAgents","Watch unavailable: ${it.message}")}
    }
    private fun parseSession(obj: JsonObject): AgentSessionUi? {
        val ref = obj.obj("ref") ?: return null
        val provider = ref.str("provider") ?: return null
        val id = ref.str("id") ?: return null
        return AgentSessionUi(provider, id, obj.str("title") ?: id,
            obj.str("kind") ?: "session", obj.obj("state")?.str("activity") ?: "Unknown",
            obj.obj("state")?.str("confidence") ?: "Unknown",
            obj.str("conversation"), obj.strings("deliveryModes"), obj.strings("capabilities"),
            obj.str("attention") ?: "None",
            obj.get("unread")?.takeIf { it.isJsonPrimitive }?.let {
                runCatching { it.asBoolean }.getOrNull()
            })
    }

    suspend fun refresh() = refreshMutex.withLock {
        if (!signalR.hubAuthenticated.value) return@withLock
        try {
            val rows = signalR.agentCall("providers.list").asJsonArray
            _providers.value = rows.mapNotNull { item ->
                val p = item.asObjectOrNull() ?: return@mapNotNull null
                val id = p.str("id") ?: return@mapNotNull null
                AgentProviderUi(id, p.str("name") ?: id, p.flag("enabled"), p.flag("active"),
                    p.strings("capabilities"),p.strings("kinds"))
            }
            _providers.value.firstOrNull {it.active}?.let {
                _lastProvider.value=it.id
                providerPrefs.edit().putString("lastActive",it.id).apply()
            }
            // The Hub exposes a paginated listing; the old UI silently stopped after
            // the first page. Bound traversal and deduplicate overlapping observations.
            val collected = LinkedHashMap<String, AgentSessionUi>()
            val cursors = HashSet<String>()
            var cursor: String? = null
            var warning: String? = null
            for (pageIndex in 0 until 4) {
                val params = mutableMapOf<String, Any>("clientId" to clientId, "limit" to 400)
                cursor?.let { params["page"] = it }
                val page = signalR.agentCall("sessions.list", params).asJsonObject
                page.array("items")?.forEach { item ->
                    val session = item.asObjectOrNull()?.let { parseSession(it) } ?: return@forEach
                    collected[session.key] = session
                }
                warning = page.str("warning") ?: warning
                val next = page.str("next")
                if (next.isNullOrBlank() || !cursors.add(next)) {
                    cursor = null
                    break
                }
                cursor = next
                if (pageIndex == 3) warning = "Only the most recent 1600 sessions are loaded; older history may be missing."
            }
            _sessions.value = collected.values.toList()
            saveSessions()
            watchSelection()
            _status.value = warning ?: ""
        } catch (e: Exception) {
            _status.value = "Provider refresh failed: ${e.message}"
        }
    }
    suspend fun select(key: String?, reload: Boolean = false) {
        val switched = _selected.value != key
        if (switched || !reload) {
            _selected.value = key
            if (signalR.hubAuthenticated.value) watchSelection()
            _history.value = if (key!=null && _archiveEnabled.value) {
                runCatching {archive.read(key,500)}.getOrElse {historyCache[key].orEmpty()}
            } else historyCache[key].orEmpty()
            _remoteQueue.value = emptyList()
            _olderHistoryCursor.value = key?.let { historyOlderCursors[it] }
            _historyNotice.value = if (key != null) "Offline cache may be partial." else null
        }
        if (key == null || !signalR.hubAuthenticated.value) return
        if (reload && switched) return
        if (_selected.value == key && !reload) _historyLoading.value = true
        try {
            val session = _sessions.value.firstOrNull { it.key == key } ?: return
            val page = signalR.agentCall("sessions.history", mapOf(
                "session" to mapOf("provider" to session.provider, "id" to session.id),
                "limit" to 100
            )).asJsonObject
            val rows = page.array("items")?.mapNotNull { item ->
                val obj = item.asObjectOrNull() ?: return@mapNotNull null
                val id = obj.str("id") ?: return@mapNotNull null
                val parts = obj.array("parts")?.mapNotNull { part ->
                    part.asObjectOrNull()?.let { it.str("markdown") ?: it.str("text") }
                } ?: emptyList()
                AgentHistoryUi(id, obj.str("kind") ?: "Notice",
                    parts.joinToString("\n"), obj.str("completeness") != "Full",
                    obj.get("revision")?.let { runCatching { it.asLong }.getOrNull() } ?: 1L)
            } ?: emptyList()
            // A full read is only the *latest page*, not a replacement for pages
            // fetched with Load earlier. Even a manual refresh/reconnect preserves them.
            // Merge against the cache as it stands after the network call, since an
            // older-page request can finish while the latest page is in flight.
            val merged = synchronized(historyCache) {
                val byId = linkedMapOf<String, AgentHistoryUi>()
                (historyCache[key].orEmpty() + rows).forEach { item ->
                    val prev = byId[item.id]
                    if (prev == null || prev.revision <= item.revision) byId[item.id] = item
                }
                byId.values.toList().takeLast(500).also { historyCache[key] = it }
            }
            // A null cursor after paging to the beginning means 'exhausted', not
            // 'uninitialized'. Do not resurrect the Load earlier button on refresh.
            if (!historyOlderCursors.containsKey(key)) historyOlderCursors[key] = page.str("older")
            if (_selected.value == key) _olderHistoryCursor.value = historyOlderCursors[key]
            saveHistory()
            if (_archiveEnabled.value) {
                runCatching {archive.record(key,merged)}
                    .onFailure {Log.w("GenericAgents","Offline history archive write failed",it)}
            }
            if (session.capabilities.contains("NativeQueue") && !reload) fetchQueue(session)
            if (_selected.value == key) {
                if (!reload) rows.lastOrNull()?.let { latest ->
                    runCatching {
                        signalR.agentCall("sessions.markSeen",mapOf(
                            "session" to mapOf("provider" to session.provider, "id" to session.id),
                            "item" to latest.id, "clientId" to clientId))
                    }.onSuccess {
                        _sessions.value = _sessions.value.map { item ->
                            if (item.key == key) item.copy(unread = false) else item
                        }
                    }.onFailure {Log.d("GenericAgents","MarkSeen not supported: ${it.message}")}
                }
                _history.value = if (_archiveEnabled.value) {
                    runCatching {archive.read(key,500)}.getOrDefault(merged)
                } else merged
                _historyNotice.value = page.str("warning") ?: if (!page.flag("complete")) "History is partial." else null
            }
        } catch (e: Exception) {
            if (_selected.value == key) _historyNotice.value =
                "History read failed; displaying cached entries: ${e.message}"
        } finally {
            if (_selected.value == key) _historyLoading.value = false
        }
    }
    /** One true provider-owned history page at a time. The cursor is opaque;
     * it is never synthesized from timestamps, turns or browser DOM. */
    suspend fun loadOlderHistory() = historyLoadMutex.withLock {
        val key = _selected.value ?: return@withLock
        if (!signalR.hubAuthenticated.value) return@withLock
        val session = _sessions.value.firstOrNull { it.key == key } ?: return@withLock
        val cursor = historyOlderCursors[key] ?: return@withLock
        try {
            val page = signalR.agentCall("sessions.history", mapOf(
                "session" to mapOf("provider" to session.provider, "id" to session.id),
                "before" to cursor, "limit" to 100
            )).asJsonObject
            val olderRows = page.array("items")?.mapNotNull { element ->
                val row = element.asObjectOrNull() ?: return@mapNotNull null
                val id = row.str("id") ?: return@mapNotNull null
                val parts = row.array("parts")?.mapNotNull { part ->
                    part.asObjectOrNull()?.let { it.str("markdown") ?: it.str("text") }
                }.orEmpty()
                AgentHistoryUi(id, row.str("kind") ?: "Notice", parts.joinToString("\n"),
                    row.str("completeness") != "Full",
                    row.get("revision")?.let { runCatching { it.asLong }.getOrNull() } ?: 1L)
            }.orEmpty()
            // Preserve provider ordering while deduplicating by stable source ID.
            val merged = synchronized(historyCache) {
                val byId = linkedMapOf<String,AgentHistoryUi>()
                (olderRows + historyCache[key].orEmpty()).forEach { row ->
                    val existing=byId[row.id]
                    if(existing==null || existing.revision<=row.revision) byId[row.id]=row
                }
                byId.values.toList().takeLast(500).also { historyCache[key] = it }
            }
            // A provider repeating the same cursor must not create an infinite
            // 'Load earlier' button that keeps fetching the same page.
            historyOlderCursors[key] = page.str("older")?.takeIf { it != cursor }
            saveHistory()
            if (_archiveEnabled.value) runCatching { archive.record(key,merged) }
                .onFailure { Log.w("GenericAgents","Older history archive write failed",it) }
            if (_selected.value == key) {
                _olderHistoryCursor.value = historyOlderCursors[key]
                _history.value = if(_archiveEnabled.value)
                    runCatching {archive.read(key,500)}.getOrDefault(merged) else merged
                _historyNotice.value = page.str("warning") ?: if (!page.flag("complete"))
                    "History is partial; older pages may still be available." else null
            }
        } catch(e:Exception) {
            if(_selected.value==key) _historyNotice.value =
                "Older history unavailable; retained loaded messages: ${e.message}"
        }
    }

    fun enqueueSession(session: AgentSessionUi, text: String): String {
        return enqueue(SavedAgentRequest(UUID.randomUUID().toString(), session.provider,
            "session", session.id, session.conversation, text, kind = session.kind))
    }
    fun enqueueAddress(address: String, text: String, provider: String): String {
        require(address.contains("@") && address.length in 3..320) { "Invalid contact address" }
        return enqueue(SavedAgentRequest(UUID.randomUUID().toString(),provider,
            "address",address,null,text))
    }
    private fun enqueue(item: SavedAgentRequest): String {
        require(item.message.isNotBlank() && item.message.length <= 30_000) { "Message is empty or too long" }
        synchronized(diskGuard) {
            check(outboxWritable) { "Outbox unreadable. Original preserved; queue disabled." }
            check(entries.size < 1000) { "Outbox is full" }
            entries[item.id] = item
            try { persistOutbox() }
            catch (e: Exception) { entries.remove(item.id); throw e }
            publishOutbox()
        }
        scope.launch { drain() }
        return item.id
    }
    private fun buildRequest(item: SavedAgentRequest): Map<String, Any?> {
        val recipient: Map<String, Any> = if (item.targetType == "session") {
            mapOf("type" to "session", "session" to
                mapOf("provider" to item.provider,"id" to item.target))
        } else {
            mapOf("type" to "address", "address" to item.target)
        }
        return mapOf(
            "provider" to item.provider,
            "recipient" to recipient,
            "content" to listOf(mapOf("type" to "Text", "markdown" to item.message)),
            "delivery" to if (item.kind == "t3/thread") "Queue" else "WhenIdle",
            "routing" to if (item.targetType == "session") "pinSession" else "followAgent",
            "clientRequestId" to item.id,
            "expectedConversation" to item.conversation,
            "expectedRecipient" to item.stableRecipientJson?.let { JsonParser.parseString(it) }
        )
    }
    /**
     * Read-only readiness probe, done before durable InFlight admission.
     * For a compatibility provider without a native Queue, wait until idle
     * rather than committing a Busy rejection permanently to the idempotency
     * journal. Missing provider capabilities means the item stays on device.
     */
    suspend fun previewAddressRecipient(id: String): AddressIdentityPreview {
        if (!signalR.hubAuthenticated.value) throw IllegalStateException("Hub offline")
        val item=synchronized(diskGuard){entries[id]?.copy()}
            ?:throw IllegalStateException("Outbox message not found")
        require(item.targetType=="address" && item.state in listOf("Pending","Blocked")) {
            "Only pending contact messages can be verified"
        }
        val resolved=signalR.agentCall("addresses.resolve",mapOf(
            "provider" to item.provider,"address" to item.target)).asJsonObject
        if (resolved.str("address")!=item.target || !resolved.flag("available")) {
            throw IllegalStateException("Contact currently unavailable or not verified")
        }
        val identity=resolved.obj("recipient")
            ?:throw IllegalStateException("Contact did not resolve to a stable identity")
        val label=when(identity.str("type")){
            "agent"->{
                val agent=identity.obj("agent")
                    ?:throw IllegalStateException("Missing agent identity")
                val provider=agent.str("provider")
                    ?:throw IllegalStateException("Agent missing provider")
                val durableId=agent.str("id")
                    ?:throw IllegalStateException("Agent missing durable ID")
                if (provider!=item.provider) throw IllegalStateException("Address redirected to a different provider")
                "Agent $durableId on provider $provider"
            }
            "human"->{
                val human=identity.obj("human")
                    ?:throw IllegalStateException("Missing human identity")
                "Human ${human.str("id") ?: throw IllegalStateException("Missing identity")} at ${human.str("system") ?: throw IllegalStateException("Missing system")}"
            }
            else->throw IllegalStateException("Address did not resolve to a stable agent/human identity")
        }
        return AddressIdentityPreview(item.id,
            "${item.target} → $label",gson.toJson(identity))
    }
    fun confirmAddressRecipient(candidate: AddressIdentityPreview) {
        synchronized(diskGuard) {
            val item=entries[candidate.requestId] ?:throw IllegalStateException("Message not found")
            require(item.targetType=="address" && item.state in listOf("Pending","Blocked")) {
                "Cannot change recipient identity after submission"
            }
            val current=item.stableRecipientJson
            if (!current.isNullOrBlank() &&
                JsonParser.parseString(current)!=JsonParser.parseString(candidate.stableRecipientJson)) {
                throw IllegalStateException("Previously verified recipient differs; user must compose a new message")
            }
            item.stableRecipientJson=candidate.stableRecipientJson
            item.state="Pending"
            item.error=null
            persistOutbox()
            publishOutbox()
        }
        scope.launch {drain()}
    }
    private suspend fun canDeliver(item: SavedAgentRequest): Boolean {
        if (item.targetType=="address") {
            if(item.stableRecipientJson.isNullOrBlank()) {
                notePending(item.id,"Recipient identity needs confirmation before dispatch; select Verify")
                return false
            }
            val supported = _providers.value.any { it.active && it.id==item.provider &&
                it.capabilities.contains("SubmitToAgent") }
            if (!supported) {
                notePending(item.id, "Waiting for original provider ${item.provider} with verified address delivery")
                return false
            }
            return try {
                val resolved=signalR.agentCall("addresses.resolve",mapOf(
                    "provider" to item.provider,"address" to item.target)).asJsonObject
                val identity=resolved.obj("recipient")
                if (!resolved.flag("available") || resolved.str("address")!=item.target ||
                    identity==null || identity!=JsonParser.parseString(item.stableRecipientJson)) {
                    notePending(item.id,"Contact identity changed or unavailable; message remains on this device")
                    false
                } else {
                    notePending(item.id,null)
                    true
                }
            } catch (e: Exception){
                notePending(item.id,"Could not confirm contact identity: ${e.message}")
                false
            }
        }
        return try {
            val detail=signalR.agentCall("sessions.get", mapOf("session" to mapOf(
                "provider" to item.provider,"id" to item.target))).asJsonObject
            val status=detail.obj("state")?.str("activity") ?: "Unknown"
            val currentBinding=detail.str("conversation")
            if (item.conversation!=currentBinding) {
                notePending(item.id, "Conversation binding changed; this message will not be redirected")
                return false
            }
            val nativeQueue = item.kind == "t3/thread"
            if (nativeQueue && (!detail.strings("deliveryModes").contains("Queue") ||
                !detail.strings("capabilities").contains("Submit"))) {
                notePending(item.id, "Native T3 queue is unavailable; keeping message on the phone")
                return false
            }
            if (!nativeQueue && (status!="Idle" || !detail.strings("deliveryModes").contains("WhenIdle"))) {
                notePending(item.id, "Waiting until the selected session accepts a WhenIdle message")
                return false
            }
            notePending(item.id,null)
            true
        } catch (e: Exception) {
            notePending(item.id,"Session unavailable; queued on device")
            false
        }
    }
    private fun notePending(id:String,detail:String?) = synchronized(diskGuard) {
        val item=entries[id] ?: return@synchronized
        if (item.error!=detail) {
            item.error=detail
            persistOutbox()
            publishOutbox()
        }
    }
    /**
     * Single serialized drain: Pending/Blocked messages are auto-submitted
     * ONLY if preflight capabilities/readiness allow it. Network ambiguity
     * remains Unknown and is NEVER retransmitted automatically.
     */
    suspend fun drain() = operationMutex.withLock {
        if (!outboxWritable) return@withLock
        if (!signalR.hubAuthenticated.value) return@withLock
        reconcileAccepted()
        val pending = synchronized(diskGuard) { entries.values.filter { it.state in listOf("Pending","Blocked") }.map { it.id } }
        for (id in pending) {
            if (!signalR.hubAuthenticated.value) break
            val preview = synchronized(diskGuard) { entries[id]?.copy() } ?: continue
            if (!canDeliver(preview)) continue
            val item = synchronized(diskGuard) {
                val current = entries[id] ?: return@synchronized null
                if (current.state !in listOf("Pending","Blocked")) return@synchronized null
                current.state = "InFlight"
                persistOutbox()
                publishOutbox()
                current.copy()
            } ?: continue
            try {
                val answer = signalR.agentCall("messages.submit", buildRequest(item)).asJsonObject
                val operationId = answer.str("id")
                val state = answer.str("state") ?: "Unknown"
                synchronized(diskGuard) {
                    entries[id]?.let {
                        it.state = if (state in listOf("Delivered","Accepted","Queued","Failed")) state else "Unknown"
                        it.operationId = operationId
                        it.error = answer.str("error")
                    }
                    persistOutbox()
                    publishOutbox()
                }
            } catch (e: Exception) {
                synchronized(diskGuard) {
                    entries[id]?.let {
                        // Unsupported is a pre-dispatch capability refusal, so
                        // it is safe to wait for a future provider upgrade.
                        val knownUnsupported=e.message?.startsWith("Unsupported:")==true
                        it.state = if (knownUnsupported) "Blocked" else "Unknown"
                        it.error = if (knownUnsupported)
                            "Waiting for provider support: ${e.message}"
                        else "Unconfirmed delivery; check the recipient before retrying: ${e.message}"
                    }
                    persistOutbox()
                    publishOutbox()
                }
                // Stop here. Disconnects can have ambiguous side effects and the
                // queued messages after this one must wait for fresh connectivity.
                if (!signalR.hubAuthenticated.value) break
            }
        }
    }
    private suspend fun reconcileAccepted() {
        // A large offline backlog must not monopolize the single serialized
        // drain with hundreds of network round trips on reconnect.
        val statuses = synchronized(diskGuard) { entries.values.filter {
            it.state in listOf("Accepted","Queued") && it.operationId != null
        }.take(12).map { it.id to it.operationId!! } }
        for ((id, operation) in statuses) {
            if (!signalR.hubAuthenticated.value) return
            try {
                val result = signalR.agentCall("operations.get", mapOf("operation" to operation)).asJsonObject
                val state = result.str("state") ?: "Unknown"
                synchronized(diskGuard) {
                    if (state in listOf("Delivered","Failed","Unknown","Accepted","Queued")) {
                        val item = entries[id]
                        val error = result.str("error")
                        if (item != null && (item.state != state || item.error != error)) {
                            item.state = state
                            item.error = error
                            persistOutbox()
                            publishOutbox()
                        }
                    }
                }
            } catch (_: Exception) { /* a missing journal entry is not proof of failed delivery */ }
        }
    }
    /** Discard only messages known not to be in transport. A concurrent drain
     * rechecks their existence/state before transitioning to InFlight. */
    fun cancelPending(id: String) = synchronized(diskGuard) {
        val item = entries[id] ?: return@synchronized
        require(item.state in listOf("Pending", "Blocked")) {
            "Message is already being processed; cannot cancel locally"
        }
        entries.remove(id)
        try { persistOutbox() } catch (e: Exception) { entries[id] = item; throw e }
        publishOutbox()
    }
    /** The user has checked the destination and explicitly accepted losing the
     * uncertain local receipt. This NEVER retransmits an ambiguous message. */
    fun dismissReceipt(id: String) = synchronized(diskGuard) {
        val item = entries[id] ?: return@synchronized
        require(item.state in listOf("Delivered", "Failed", "Unknown")) {
            "Only finished/unconfirmed receipts may be dismissed"
        }
        entries.remove(id)
        try { persistOutbox() } catch (e: Exception) { entries[id] = item; throw e }
        publishOutbox()
    }
    suspend fun fetchQueue(session: AgentSessionUi) {
        if (!signalR.hubAuthenticated.value) return
        try {
            val result=signalR.agentCall("sessions.queue",mapOf(
                "session" to mapOf("provider" to session.provider,"id" to session.id)))
            val list=result.asJsonArray.mapNotNull { element ->
                val row=element.asObjectOrNull() ?: return@mapNotNull null
                val id=row.str("Id") ?: row.str("id") ?: return@mapNotNull null
                AgentQueuedUi(id,row.str("Text") ?: row.str("text") ?: "")
            }
            if (_selected.value==session.key) _remoteQueue.value=list
        } catch (e: Exception) {_status.value="Queue read failed: ${e.message}"}
    }
    suspend fun sessionAction(session: AgentSessionUi, action:String,
        extra: Map<String, Any?> = emptyMap()): String {
        if (!signalR.hubAuthenticated.value) throw IllegalStateException("Hub offline")
        val method=when(action) {
            "Interrupt"->"sessions.interrupt"
            "Rename"->"sessions.update"
            "Delete"->"sessions.delete"
            "QueueEdit"->"sessions.queue.edit"
            else->throw IllegalArgumentException("Unknown agent operation")
        }
        val params=mutableMapOf<String,Any?>(
            "session" to mapOf("provider" to session.provider,"id" to session.id),
            "clientRequestId" to UUID.randomUUID().toString()
        )
        params.putAll(extra)
        val receipt=signalR.agentCall(method,params).asJsonObject
        val state = receipt.str("state") ?: "Unknown"
        if (state == "Failed") throw IllegalStateException(receipt.str("error") ?: "Provider rejected $action")
        refresh()
        if (action=="QueueEdit") fetchQueue(session)
        _status.value="Provider operation: $state"
        return state
    }
    suspend fun createSession(provider: AgentProviderUi,kind:String,workspace:String,firstMessage:String): String {
        require(provider.active&&provider.capabilities.contains("CreateSession")) {
            "Selected provider cannot create a session"
        }
        require(kind in provider.kinds) {"Session kind not supported by this provider"}
        require(workspace.isNotBlank()&&firstMessage.isNotBlank()) {
            "Workspace and first message are required"
        }
        _createdSessionKey.value = null
        val resultText = if (provider.id == "t3") {
            require(kind == "t3/chatgpt") { "Native T3 MAIN does not support session creation" }
            // A repeated tap with the same work request must recover its
            // original operation instead of silently creating a second T3 tab.
            val previous = workCreate.latest("OmniHub.Android")
            // Identical instructions can legitimately start *another* session.
            // Only reuse an unresolved request: successful prior work must not
            // silently swallow an explicit second Create tap.
            val same = previous?.state == "Unknown" && previous.kind == kind &&
                previous.project == workspace && previous.instructions == firstMessage &&
                previous.title == "Android AI work"
            val incidentId = if (same) previous!!.incidentId else "manual-" + UUID.randomUUID()
            val receipt = workCreate.create(
                source = "OmniHub.Android", incidentId = incidentId,
                project = workspace, instructions = firstMessage,
                title = "Android AI work", kind = kind,
                send = { method, params -> signalR.agentCall(method, params).asJsonObject },
            )
            if (receipt.state == "Started" && receipt.sessionId != null)
                _createdSessionKey.value = "${receipt.sessionProvider ?: provider.id}:${receipt.sessionId}"
            when (receipt.state) {
                "Started" -> "T3 session ${receipt.sessionId ?: "ID pending"} created. Initial prompt: ${receipt.delivery ?: "unconfirmed"}."
                "Failed" -> "T3 creation failed: ${receipt.error ?: "provider rejected"}"
                else -> "T3 creation uncertain; check last creation before trying again."
            }
        } else {
            val receipt = signalR.agentCall("sessions.create",mapOf(
                "provider" to provider.id, "kind" to kind,
                "workspace" to workspace, "initialMessage" to firstMessage,
                "clientRequestId" to UUID.randomUUID().toString()
            )).asJsonObject
            val ref = receipt.obj("result")?.obj("session") ?: receipt.obj("session")
            if (ref != null && ref.str("provider") != null && ref.str("id") != null)
                _createdSessionKey.value = "${ref.str("provider")}:${ref.str("id")}"
            "Session creation: ${receipt.str("state") ?: "Unknown"}"
        }
        refresh()
        _status.value = resultText
        return resultText
    }
    /** Read-only recovery by the original durable Android client request ID.
     * Never calls work.create or starts another session. */
    suspend fun checkLastWork(): String {
        require(signalR.hubAuthenticated.value) { "Connect to OmniHub to reconcile AI work" }
        val existing = workCreate.latest("OmniHub.Android")
            ?: return "No application AI creation recorded on this phone."
        val observed = workCreate.check(
            existing.source, existing.incidentId,
            send = { method, params -> signalR.agentCall(method, params).asJsonObject },
        ) ?: existing
        val state = "AI work: ${observed.state}"
        val id = observed.sessionId?.let { " · T3 session $it" } ?: ""
        val turn = observed.delivery?.let { " · first prompt $it" } ?: ""
        val message = "$state$id$turn"
        if (observed.state == "Started" && observed.sessionId != null)
            _createdSessionKey.value = "${observed.sessionProvider ?: "t3"}:${observed.sessionId}"
        refresh()
        _status.value = message
        return message
    }
    suspend fun activate(provider: AgentProviderUi) {
        if (!signalR.hubAuthenticated.value) throw IllegalStateException("Hub offline")
        if (!provider.enabled) signalR.agentCall("providers.configure", mapOf("id" to provider.id,"enabled" to true))
        signalR.agentCall("providers.activate", mapOf("id" to provider.id))
        refresh()
        select(null)
    }
}
