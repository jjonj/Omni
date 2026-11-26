package com.omni.sync.data.taskboard

import com.omni.sync.data.repository.SignalRClient
import java.io.File
import java.util.UUID
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** OW-0023: phone producer for the authenticated PC TaskBoard-core writer.
 * No Markdown write or local pretend-success is performed by this client.
 */
class TaskBoardPhoneClient(
    private val signalR: SignalRClient,
    private val drafts: TaskBoardOfflineDrafts,
    context: android.content.Context? = null
) {
    data class Submission(val operationId: String, val status: String,
                          val revision: String? = null, val details: String? = null,
                          val taskId: String? = null)

    private val sendMutex = Mutex()
    private val boardReader = context?.let { TaskBoardRemoteReader(it.applicationContext, signalR) }

    suspend fun loadBoard(store: TaskBoardStore): TaskBoardSnapshot =
        boardReader?.load(store, boardIdentity(store.root)) ?: store.load()

    suspend fun cachedBoard(store: TaskBoardStore): TaskBoardSnapshot? =
        boardReader?.cached(store, boardIdentity(store.root))
            ?: runCatching { store.load() }.getOrNull()

    /** The live PC Inbox is the sole authority. Pending operations are durable,
     * so leaving the screen or restarting the app cannot resurrect a submitted round. */
    suspend fun loadInbox(includeResolved: Boolean): MobileInboxSnapshot {
        signalR.ensureAgentAuthentication()
        require(signalR.hubAuthenticated.value) { "PC disconnected" }
        val response = signalR.agentCall("taskboard.phone.inbox.list",
            mapOf("includeResolved" to includeResolved), timeoutSeconds = 30L)
        val data = JSONObject(response.toString())
        val pending = drafts.all().filter { it.state in setOf("draft", "unknown") }
        val pendingReviews = pending.mapNotNull { row ->
            val payload = runCatching { JSONObject(row.edited) }.getOrNull()
            if (payload?.optString("_operation") != "review") null
            else Triple(row.boardId, row.taskId, payload.optString("manifestRevision"))
        }.toSet()
        var suppressedReviews = 0
        val items = data.getJSONArray("items")
        val result = (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val boardId = item.getString("board")
            val id = item.getString("taskId")
            val historical = item.optBoolean("historical")
            val root = File(TaskBoardStore.projectsRoot(), boardId)
            val project = TaskBoardProject(item.getString("project"), boardId.substringBeforeLast('/'), root)
            val manifest = item.optJSONObject("manifest")
            val revision = manifest?.optString("revision")
            if (!historical && revision != null && Triple(boardId, id, revision) in pendingReviews) {
                suppressedReviews++
                return@mapNotNull null
            }
            // A text task with an unsent phone edit is not safe to answer again.
            if (!historical && manifest == null && pending.any {
                it.boardId == boardId && it.taskId == id &&
                    runCatching { JSONObject(it.edited).optString("_operation") }.getOrNull().isNullOrBlank()
            }) return@mapNotNull null
            val review = manifest?.let {
                MobileReview(project, id, item.getString("title"), it,
                    requireNotNull(revision), item.getString("taskRevision"), File(root, "Active/$id.md"))
            }
            MobileInboxItem(project, id, item.getString("title"), item.optString("created"),
                historical, if (review != null) "review" else item.optString("type", "user"),
                review, item.optJSONObject("question"))
        }
        val warnings = data.optJSONArray("warnings")
        return MobileInboxSnapshot(result,
            if (warnings == null) emptyList() else (0 until warnings.length()).map { warnings.getString(it) },
            suppressedReviews)
    }
    /** Keep JSON integer fields (e.g. review viewport_width) integral over
     * SignalR; Gson's raw Map deserialization coerces every number to Double. */
    private fun rpcJson(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { rpcJson(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { rpcJson(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    /** Replay only original, persisted IDs. Called on screen entry and after reconnect. */
    suspend fun syncPending(): Int {
        var applied = 0
        for (draft in drafts.all().filter { it.state == "draft" }) {
            when (sendSaved(draft.operationId).status) {
                "applied" -> applied++
                "queued" -> break // Connectivity/auth failure: try again on the next pass.
                // Conflicts and genuine server UNKNOWNs need explicit reconciliation.
            }
        }
        return applied
    }

    fun boardIdentity(root: File): String {
        val vault = TaskBoardStore.projectsRoot().canonicalFile
        val board = root.canonicalFile
        require(board.path.startsWith(vault.path + File.separator)) {
            "Phone board is outside the configured shared vault"
        }
        val relative = board.relativeTo(vault).invariantSeparatorsPath
        require(relative.endsWith("/Tasks")) { "Not a supported TaskBoard project root" }
        return relative
    }

    fun draftJson(draft: TaskBoardTaskDraft): String = JSONObject().apply {
        put("title", draft.title)
        put("column", draft.column)
        put("kind", draft.kind)
        put("depends_on", org.json.JSONArray(draft.dependsOn))
        put("related_to", org.json.JSONArray(draft.relatedTo))
        put("icon", draft.icon)
        put("parent", draft.parent)
        put("category", draft.category)
        put("attention", org.json.JSONArray(draft.attention))
        put("question", draft.question?.let { question ->
            JSONObject().apply {
                put("prompt", question.prompt)
                question.requestType?.let { put("request_type", it) }
                put("options", org.json.JSONArray(question.options))
                put("answer", question.answer)
            }
        })
        put("description", draft.description)
        put("acceptance_criteria", org.json.JSONArray(draft.acceptanceCriteria))
        put("user_notes", draft.userNotes)
        put("notes", draft.notes)
        put("position_after", draft.positionAfter)
        put("positioned_at", draft.positionedAt)
    }.toString()

    /** Persist first. A timeout is UNKNOWN until the exact operation receipt
     * is inspected. It is never replaced with a fresh UUID automatically. */
    suspend fun submitUpdate(root: File, task: TaskBoardTask, edited: TaskBoardTaskDraft): Submission {
        val board = boardIdentity(root)
        val operationId = UUID.randomUUID().toString()
        val encoded = draftJson(edited)
        drafts.saveOffline(
            boardId = board, taskId = task.id, baseRevision = task.revision,
            baseSource = task.sourceText ?: task.file.readText(Charsets.UTF_8),
            edited = encoded, operationId = operationId
        )
        return sendSaved(operationId)
    }

    suspend fun submitCreate(root: File, baseBoardRevision: String, draft: TaskBoardTaskDraft,
                             operationId: String = UUID.randomUUID().toString(),
                             boardSource: String? = null): Submission {
        val board = boardIdentity(root)
        drafts.saveOffline(boardId = board, taskId = "CREATE", baseRevision = baseBoardRevision,
            baseSource = boardSource ?: File(root, "_board.md").readText(Charsets.UTF_8),
            edited = draftJson(draft), operationId = operationId)
        return sendSaved(operationId)
    }

    /** Recover legacy create previews from the PC journal without replaying a write.
     * A task may have changed revision many times before Syncthing receives it. */
    suspend fun recoverAppliedCreateIdentities(): Int {
        var recovered = 0
        for (pending in drafts.all().filter {
            it.taskId == "CREATE" && it.state == "applied" && it.createdTaskId == null
        }.take(8)) {
            val response = try {
                signalR.agentCall("taskboard.phone.create.receipt", mapOf(
                    "operationId" to pending.operationId, "board" to pending.boardId,
                    "baseBoardRevision" to pending.baseRevision,
                    "draft" to com.google.gson.Gson().fromJson(pending.edited, Map::class.java)
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Older Hub versions may not expose the read-only lookup yet.
                Log.w("TaskBoardPhone", "Create receipt lookup deferred: ${error.message}")
                break
            }
            if (!response.isJsonObject) break
            val receipt = response.asJsonObject
            if (receipt.get("operationId")?.asString != pending.operationId) break
            if (receipt.get("status")?.asString != "applied") continue
            val taskId = receipt.get("taskId")?.asString ?: continue
            val revision = receipt.get("revision")?.asString ?: continue
            drafts.recordCreateIdentity(pending.operationId, revision, taskId)
            recovered++
        }
        return recovered
    }

    /** Real desktop execution intents, not a phone-only simulated queue. */
    data class QueueIntent(val mode: String, val revision: String,
                           val state: String? = null, val sessionId: String? = null,
                           val error: String? = null)

    suspend fun queueIntents(root: File): Map<String, QueueIntent> {
        val response = signalR.agentCall("taskboard.phone.queue.list",
            mapOf("board" to boardIdentity(root)))
        val modes = response.asJsonObject.getAsJsonObject("modes") ?: return emptyMap()
        return modes.entrySet().associate { (id, intent) ->
            val current = intent.asJsonObject
            fun optionalString(key: String): String? = current.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            id to QueueIntent(current.get("mode").asString, current.get("revision").asString,
                optionalString("queue_state"), optionalString("queue_session_id"),
                optionalString("queue_error"))
        }
    }

    suspend fun queueModes(root: File): Map<String, String> = queueIntents(root)
        .mapValues { (_, intent) -> intent.mode }

    /** The PC's T3 open_task implementation owns task/session deduplication.
     * Never emulate a spawn with generic sessions.create from the phone. */
    data class T3SessionReceipt(val status: String, val sessionId: String?, val details: String)

    suspend fun openTaskSession(root: File, task: TaskBoardTask, intent: String): T3SessionReceipt {
        require(intent == "conversation" || intent == "implement")
        val response = signalR.agentCall("taskboard.phone.session.open", mapOf(
            "board" to boardIdentity(root), "taskId" to task.id, "intent" to intent
        ))
        val receipt = response.asJsonObject
        val status = receipt.get("status")?.asString ?: "unknown"
        val sessionId = receipt.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { Regex("^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$").matches(it) }
        val message = receipt.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "Task-bound T3 request: $status"
        return T3SessionReceipt(status, sessionId, message)
    }

    suspend fun submitQueue(root: File, task: TaskBoardTask, queue: Boolean,
                            authoritativeRevision: String? = null): Submission {
        val operationId = UUID.randomUUID().toString()
        drafts.saveOffline(boardId = boardIdentity(root), taskId = task.id,
            baseRevision = authoritativeRevision ?: task.revision,
            baseSource = task.sourceText ?: task.file.readText(Charsets.UTF_8),
            edited = JSONObject().put("_operation", "queue")
                .put("mode", if (queue) "queue" else "backlog").toString(),
            operationId = operationId)
        return sendSaved(operationId)
    }

    suspend fun submitArchive(root: File, task: TaskBoardTask): Submission {
        val operationId = UUID.randomUUID().toString()
        drafts.saveOffline(boardId = boardIdentity(root), taskId = task.id,
            baseRevision = task.revision, baseSource = task.sourceText ?: task.file.readText(Charsets.UTF_8),
            edited = JSONObject().put("_operation", "archive").toString(), operationId = operationId)
        return sendSaved(operationId)
    }

    /** Human review decisions go through TaskBoard-core's manifest+task CAS.
     * Attachments are content-addressed in the synced tree; only this journaled
     * PC operation may resolve the review, even while the phone is offline. */
    suspend fun submitReview(review: MobileReview, decision: MobileReviewDecision): Submission {
        val operationId = UUID.randomUUID().toString()
        val attachments = TaskBoardReviewStore.stageAttachments(review, decision.attachments)
        val combinedNote = (listOf(decision.note) + attachments)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val payload = JSONObject().put("_operation", "review")
            .put("manifestRevision", review.manifestRevision)
            .put("decision", JSONObject().apply {
                put("task_id", review.taskId)
                put("verdict", decision.verdict)
                put("choices", org.json.JSONArray(decision.choices))
                put("ranking", org.json.JSONArray(decision.ranking))
                put("pins", org.json.JSONArray().apply {
                    decision.pins.forEach { put(it.json()) }
                })
                put("note", combinedNote)
                put("expected_task_revision", review.taskRevision)
                put("expected_manifest_revision", review.manifestRevision)
            })
        drafts.saveOffline(boardId = boardIdentity(review.project.root),
            taskId = review.taskId, baseRevision = review.taskRevision,
            baseSource = "Review ${review.taskId} · round ${review.round.optInt("number")} · ${review.title}",
            edited = payload.toString(), operationId = operationId)
        return sendSaved(operationId)
    }

    /** Undo a PC-confirmed archive, using the exact archived content revision. */
    suspend fun submitRestore(root: File, taskId: String, archivedRevision: String): Submission {
        val operationId = UUID.randomUUID().toString()
        drafts.saveOffline(boardId = boardIdentity(root), taskId = taskId,
            baseRevision = archivedRevision,
            baseSource = "Undo archived $taskId at $archivedRevision",
            edited = JSONObject().put("_operation", "restore").toString(),
            operationId = operationId)
        return sendSaved(operationId)
    }

    suspend fun submitColumns(root: File, expectedBoardRevision: String,
                              columns: List<TaskBoardColumn>, boardSource: String? = null): Submission {
        val operationId = UUID.randomUUID().toString()
        val encoded = JSONObject().put("_operation", "columns").put("columns",
            org.json.JSONArray().apply {
                columns.forEach { column ->
                    put(JSONObject().apply {
                        put("id", column.id)
                        put("name", column.name)
                        put("order", column.order)
                        put("terminal", column.terminal)
                        put("hidden", column.hidden)
                        put("ai_ready", column.aiReady)
                        put("advisory", column.advisory)
                        put("auto_queue", column.autoQueue)
                        put("color", column.color)
                        put("snoozed_until", column.snoozedUntil)
                        put("awake_until", column.awakeUntil)
                    })
                }
            }).toString()
        drafts.saveOffline(boardId = boardIdentity(root), taskId = "COLUMNS",
            baseRevision = expectedBoardRevision,
            baseSource = boardSource ?: File(root, "_board.md").readText(Charsets.UTF_8),
            edited = encoded, operationId = operationId)
        return sendSaved(operationId)
    }

    suspend fun submitNewBoard(name: String): Submission {
        val operationId = UUID.randomUUID().toString()
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "Project name is required" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        drafts.saveOffline(boardId = "new-project", taskId = "NEWBOARD",
            baseRevision = digest, baseSource = "No previous board",
            edited = JSONObject().put("_operation", "board_create")
                .put("name", normalized).toString(), operationId = operationId)
        return sendSaved(operationId)
    }

    /** Replays only the SAME UUID and payload, with server-side receipt
     * deduplication. A server-side UNKNOWN remains unresolved, not retried. */
    suspend fun sendSaved(operationId: String): Submission = sendMutex.withLock {
        val pending = drafts.all().firstOrNull { it.operationId == operationId }
            ?: return@withLock Submission(operationId, "applied") // Another caller received the ACK.

        check(pending.state == "draft") {
            "Phone edit has a conflict; compare the PC version before making a new edit"
        }
        val isCreate = pending.taskId == "CREATE"
        val isNewBoard = pending.taskId == "NEWBOARD"
        val isColumns = pending.taskId == "COLUMNS"
        val isArchive = JSONObject(pending.edited).optString("_operation") == "archive"
        val isRestore = JSONObject(pending.edited).optString("_operation") == "restore"
        val isReview = JSONObject(pending.edited).optString("_operation") == "review"
        val isQueue = JSONObject(pending.edited).optString("_operation") == "queue"
        val wire = if (isNewBoard) mapOf(
            "operationId" to pending.operationId,
            "name" to JSONObject(pending.edited).getString("name")
        ) else if (isColumns) {
            val columns = JSONObject(pending.edited).getJSONArray("columns")
            val data = (0 until columns.length()).map { index ->
                val column = columns.getJSONObject(index)
                mapOf<String, Any?>(
                    "id" to column.getString("id"),
                    "name" to column.getString("name"),
                    "order" to column.getInt("order"),
                    "terminal" to column.getBoolean("terminal"),
                    "hidden" to column.getBoolean("hidden"),
                    "ai_ready" to column.getBoolean("ai_ready"),
                    "advisory" to column.getBoolean("advisory"),
                    "auto_queue" to column.optBoolean("auto_queue"),
                    "color" to column.getString("color"),
                    "snoozed_until" to column.optString("snoozed_until").takeUnless { it == "null" || it.isBlank() },
                    "awake_until" to column.optString("awake_until").takeUnless { it == "null" || it.isBlank() }
                )
            }
            mapOf("operationId" to pending.operationId, "board" to pending.boardId,
                "baseBoardRevision" to pending.baseRevision, "columns" to data)
        } else if (isCreate) mapOf(
            "operationId" to pending.operationId, "board" to pending.boardId,
            "baseBoardRevision" to pending.baseRevision,
            "draft" to com.google.gson.Gson().fromJson(pending.edited, Map::class.java)
        ) else if (isQueue) mapOf(
            "operationId" to pending.operationId, "board" to pending.boardId,
            "taskId" to pending.taskId, "baseRevision" to pending.baseRevision,
            "mode" to JSONObject(pending.edited).getString("mode")
        ) else if (isRestore || isArchive) mapOf(
            "operationId" to pending.operationId, "board" to pending.boardId,
            "taskId" to pending.taskId, "baseRevision" to pending.baseRevision
        ) else if (isReview) {
            val saved = JSONObject(pending.edited)
            mapOf("operationId" to pending.operationId, "board" to pending.boardId,
                "taskId" to pending.taskId, "baseRevision" to pending.baseRevision,
                "manifestRevision" to saved.getString("manifestRevision"),
                "decision" to rpcJson(saved.getJSONObject("decision")))
        } else mapOf(
            "operationId" to pending.operationId,
            "board" to pending.boardId,
            "taskId" to pending.taskId,
            "baseRevision" to pending.baseRevision,
            "draft" to com.google.gson.Gson().fromJson(pending.edited, Map::class.java)
        )
        val method = if (isNewBoard) "taskboard.phone.board.create" else if (isColumns)
            "taskboard.phone.columns" else if (isCreate)
            "taskboard.phone.create" else if (isArchive)
            "taskboard.phone.archive" else if (isRestore)
            "taskboard.phone.restore" else if (isReview)
            "taskboard.phone.review.resolve" else if (isQueue) "taskboard.phone.queue.set"
            else "taskboard.phone.update"
        val response = try {
            signalR.agentCall(method, wire)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The phone cannot know whether the PC received a timed-out call.
            // A journaled operation ID makes the *same* replay safe, never a new write.
            Log.w("TaskBoardPhone", "Delivery deferred for ${pending.operationId}: ${error.message}")
            signalR.schedulePhoneTaskBoardRecovery()
            return@withLock Submission(pending.operationId, "queued",
                details = "Saved on phone; will sync automatically")
        }
        require(response.isJsonObject) { "PC returned malformed TaskBoard receipt; draft retained" }
        val data = response.asJsonObject
        check(data.get("operationId")?.asString == pending.operationId) {
            "PC receipt operation mismatch; phone draft retained"
        }
        when (data.get("status")?.asString) {
            "applied" -> {
                val revision = data.get("revision")?.asString
                    ?: error("PC success omitted revision; phone draft retained")
                val createdTaskId = if (isCreate) data.get("taskId")?.asString
                    ?: data.getAsJsonObject("task")?.get("id")?.asString else null
                if (isCreate) {
                    val taskId = createdTaskId ?: error("PC create success omitted task ID; draft retained")
                    drafts.acknowledgeCreate(pending.operationId, revision, taskId)
                } else drafts.acknowledge(pending.operationId, pending.operationId)
                Submission(pending.operationId, "applied", revision, taskId = createdTaskId)
            }
            "conflict" -> {
                drafts.conflict(
                    pending.operationId,
                    data.get("current")?.toString().orEmpty(),
                    data.get("message")?.asString ?: "PC version differs; choose changes manually"
                )
                Submission(pending.operationId, "conflict", details =
                    if (isReview) data.get("message")?.asString ?: "Review changed; refresh the Inbox."
                    else "PC task changed. Phone edit remains private; compare both versions before resolving.")
            }
            "unknown" -> {
                drafts.uncertain(pending.operationId,
                    "PC accepted the operation but its outcome is uncertain; inspect the task before editing again.")
                Submission(pending.operationId, "unknown",
                    details = "PC write outcome uncertain; saved for review.")
            }
            else -> error("PC returned unsupported TaskBoard status; phone draft retained")
        }
    }
}
