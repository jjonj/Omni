package com.omni.sync.data.repository

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/**
 * A write-ahead Android operation handle for application-created AI work.
 * An ambiguous create may already own a browser; never retry it with a new ID.
 * All records use the same AgentCall wire surface as OmniHub desktop.
 */
internal class AgentWorkCreateStore(context: Context) {
    data class Intent(
        val clientRequestId: String,
        val source: String,
        val incidentId: String,
        val project: String,
        val kind: String,
        val instructions: String,
        val title: String,
        var state: String = "Unknown",
        var operationId: String? = null,
        var sessionProvider: String? = null,
        var sessionId: String? = null,
        var conversationId: String? = null,
        var delivery: String? = null,
        var error: String? = null
    )
    private val file = AtomicFile(File(context.filesDir, "agent-app-work-v1.json"))
    private val json = Gson()
    private val guard = Any()
    private fun read(): MutableList<Intent> = synchronized(guard) {
        if (!file.baseFile.exists()) return@synchronized mutableListOf()
        val data = file.openRead().bufferedReader().use { it.readText() }
        require(data.length <= 1_500_000) { "App work ledger exceeds capacity; no worker created" }
        val root = JsonParser.parseString(data).asJsonArray
        root.map { json.fromJson(it, Intent::class.java) }.toMutableList()
    }
    private fun persist(rows: List<Intent>) = synchronized(guard) {
        require(rows.size <= 100) { "App work ledger full; no worker created" }
        val bytes = json.toJson(rows).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_500_000) { "App work ledger too large" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    fun latest(source: String): Intent? = synchronized(guard) {
        read().lastOrNull { it.source == source }
    }

    suspend fun create(
        source: String,
        incidentId: String,
        project: String,
        instructions: String,
        title: String,
        kind: String = "t3/chatgpt",
        send: suspend (String, Map<String, Any?>) -> JsonObject,
    ): Intent {
        require(source.isNotBlank() && incidentId.isNotBlank() && project.isNotBlank())
        require(kind == "t3/chatgpt") { "Native T3 MAIN cannot create new sessions" }
        require(instructions.isNotBlank() && instructions.length <= 30_000)
        val admission = synchronized(guard) {
            val records = read()
            val existing = records.lastOrNull { it.source == source && it.incidentId == incidentId }
            if (existing != null) {
                require(existing.project == project && existing.kind == kind &&
                    existing.instructions == instructions && existing.title == title) {
                    "The same incident ID belongs to different work; refusing duplicate"
                }
                return@synchronized existing to false
            }
            // Unknown results remain in the ledger for reconciliation, but do not
            // prevent an explicitly different Create request with its own identity.
            val fresh = Intent(UUID.randomUUID().toString(),source,incidentId,project,kind,instructions,title)
            persist(records + fresh) // write-ahead admission, before network mutation
            fresh to true
        }
        val (pending,isNew) = admission
        // Network uncertainty is indistinguishable from a completed create
        // until reconciled; never replay a prior ledger intent.
        if (!isNew) return pending
        val params = mapOf(
            "mode" to "direct", "provider" to "t3", "kind" to kind,
            "source" to source, "incidentId" to incidentId,
            "project" to project, "workspace" to project,
            "instructions" to instructions, "title" to title,
            "clientRequestId" to pending.clientRequestId,
        )
        return try {
            val answer = send("work.create", params)
            val result = answer.getAsJsonObject("result")
            val session = result?.getAsJsonObject("session") ?: answer.getAsJsonObject("session")
            pending.operationId = answer.str("id")
            pending.state = when (answer.str("state")) {
                "Failed" -> "Failed"
                "Accepted", "Created", "Running", "Queued" -> "Started"
                else -> "Unknown"
            }
            pending.sessionProvider = session?.str("provider")
            pending.sessionId = session?.str("id")
            pending.conversationId = result?.getAsJsonObject("details")?.str("conversationId")
            pending.delivery = result?.getAsJsonObject("details")?.str("initialPromptDelivery")
            pending.error = answer.str("error")
            synchronized(guard) { val rows = read(); val i = rows.indexOfFirst { it.clientRequestId == pending.clientRequestId }
                if (i >= 0) { rows[i] = pending; persist(rows) } }
            pending
        } catch (failure: Exception) {
            pending.error = "Creation outcome unconfirmed; do not start another worker"
            synchronized(guard) { val rows=read(); val i=rows.indexOfFirst { it.clientRequestId == pending.clientRequestId }
                if (i >= 0) { rows[i]=pending; persist(rows) } }
            pending
        }
    }

    suspend fun check(
        source: String,
        incidentId: String,
        send: suspend (String, Map<String, Any?>) -> JsonObject,
    ): Intent? {
        val intent = synchronized(guard) { read().lastOrNull { it.source == source && it.incidentId == incidentId } }
            ?: return null
        val query: Map<String, Any?> = intent.operationId?.let { mapOf("operation" to it) }
            ?: mapOf("provider" to "t3", "clientRequestId" to intent.clientRequestId)
        val result = try { send("work.get",query) } catch (_: Exception) { return intent }
        intent.operationId = result.str("id") ?: intent.operationId
        intent.state = when (result.str("state")) {
            "Failed" -> "Failed"
            "Accepted", "Created", "Running", "Queued" -> "Started"
            else -> "Unknown"
        }
        intent.error = result.str("error")
        val session = result.getAsJsonObject("session") ?: result.getAsJsonObject("result")?.getAsJsonObject("session")
        intent.sessionProvider = session?.str("provider")
        intent.sessionId = session?.str("id")
        val details = result.getAsJsonObject("result")?.getAsJsonObject("details")
        intent.conversationId = details?.str("conversationId") ?: intent.conversationId
        intent.delivery = details?.str("initialPromptDelivery") ?: intent.delivery
        synchronized(guard) { val rows=read(); val i=rows.indexOfFirst { it.clientRequestId==intent.clientRequestId }
            if(i>=0) {rows[i]=intent;persist(rows)} }
        return intent
    }
}
