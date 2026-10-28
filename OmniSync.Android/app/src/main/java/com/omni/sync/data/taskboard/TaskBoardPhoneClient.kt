package com.omni.sync.data.taskboard

import com.omni.sync.data.repository.SignalRClient
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** OW-0023: phone producer for the authenticated PC TaskBoard-core writer.
 * No Markdown write or local pretend-success is performed by this client.
 */
class TaskBoardPhoneClient(
    private val signalR: SignalRClient,
    private val drafts: TaskBoardOfflineDrafts
) {
    data class Submission(val operationId: String, val status: String,
                          val revision: String? = null, val details: String? = null)

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
                put("options", org.json.JSONArray(question.options))
                put("answer", question.answer)
            }
        })
        put("description", draft.description)
        put("acceptance_criteria", org.json.JSONArray(draft.acceptanceCriteria))
        put("user_notes", draft.userNotes)
        put("notes", draft.notes)
    }.toString()

    /** Persist first. A timeout is UNKNOWN until the exact operation receipt
     * is inspected. It is never replaced with a fresh UUID automatically. */
    suspend fun submitUpdate(root: File, task: TaskBoardTask, edited: TaskBoardTaskDraft): Submission {
        val board = boardIdentity(root)
        val operationId = UUID.randomUUID().toString()
        val encoded = draftJson(edited)
        drafts.saveOffline(
            boardId = board, taskId = task.id, baseRevision = task.revision,
            baseSource = task.file.readText(Charsets.UTF_8),
            edited = encoded, operationId = operationId
        )
        return sendSaved(operationId)
    }

    suspend fun submitCreate(root: File, baseBoardRevision: String, draft: TaskBoardTaskDraft): Submission {
        val board = boardIdentity(root)
        val operationId = UUID.randomUUID().toString()
        drafts.saveOffline(boardId = board, taskId = "CREATE", baseRevision = baseBoardRevision,
            baseSource = File(root, "_board.md").readText(Charsets.UTF_8),
            edited = draftJson(draft), operationId = operationId)
        return sendSaved(operationId)
    }

    suspend fun submitArchive(root: File, task: TaskBoardTask): Submission {
        val operationId = UUID.randomUUID().toString()
        drafts.saveOffline(boardId = boardIdentity(root), taskId = task.id,
            baseRevision = task.revision, baseSource = task.file.readText(Charsets.UTF_8),
            edited = JSONObject().put("_operation", "archive").toString(), operationId = operationId)
        return sendSaved(operationId)
    }

    suspend fun submitColumns(root: File, expectedBoardRevision: String,
                              columns: List<TaskBoardColumn>): Submission {
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
                        put("color", column.color)
                        put("snoozed_until", column.snoozedUntil)
                        put("awake_until", column.awakeUntil)
                    })
                }
            }).toString()
        drafts.saveOffline(boardId = boardIdentity(root), taskId = "COLUMNS",
            baseRevision = expectedBoardRevision,
            baseSource = File(root, "_board.md").readText(Charsets.UTF_8),
            edited = encoded, operationId = operationId)
        return sendSaved(operationId)
    }

    /** Replays only the SAME UUID and payload, with server-side receipt
     * deduplication. A server-side UNKNOWN remains unresolved, not retried. */
    suspend fun sendSaved(operationId: String): Submission {
        val pending = drafts.all().firstOrNull { it.operationId == operationId }
            ?: error("Phone draft operation no longer exists")
        check(pending.state == "draft") {
            "Phone edit has a conflict; compare the PC version before making a new edit"
        }
        val isCreate = pending.taskId == "CREATE"
        val isColumns = pending.taskId == "COLUMNS"
        val isArchive = JSONObject(pending.edited).optString("_operation") == "archive"
        val wire = if (isColumns) {
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
        ) else mapOf(
            "operationId" to pending.operationId,
            "board" to pending.boardId,
            "taskId" to pending.taskId,
            "baseRevision" to pending.baseRevision,
            "draft" to com.google.gson.Gson().fromJson(pending.edited, Map::class.java)
        )
        val method = if (isColumns) "taskboard.phone.columns" else if (isCreate)
            "taskboard.phone.create" else if (isArchive)
            "taskboard.phone.archive" else "taskboard.phone.update"
        val response = try {
            signalR.agentCall(method, wire)
        } catch (error: Exception) {
            // No synced Markdown was changed. Keep all information privately
            // so an offline edit survives app crashes or failed connectivity.
            throw TaskBoardConflictException(
                "Edit saved privately on phone; PC not confirmed. Operation ${pending.operationId}. " +
                    "Reconnect and retry this exact pending operation. ${error.message.orEmpty()}"
            )
        }
        require(response.isJsonObject) { "PC returned malformed TaskBoard receipt; draft retained" }
        val data = response.asJsonObject
        check(data.get("operationId")?.asString == pending.operationId) {
            "PC receipt operation mismatch; phone draft retained"
        }
        return when (data.get("status")?.asString) {
            "applied" -> {
                val revision = data.get("revision")?.asString
                    ?: error("PC success omitted revision; phone draft retained")
                drafts.acknowledge(pending.operationId, pending.operationId)
                Submission(pending.operationId, "applied", revision)
            }
            "conflict" -> {
                drafts.conflict(
                    pending.operationId,
                    data.get("current")?.toString().orEmpty(),
                    data.get("message")?.asString ?: "PC version differs; choose changes manually"
                )
                Submission(pending.operationId, "conflict", details =
                    "PC task changed. Phone edit remains private; compare both versions before resolving.")
            }
            "unknown" -> Submission(pending.operationId, "unknown",
                details = "PC write outcome uncertain; do not create another operation.")
            else -> error("PC returned unsupported TaskBoard status; phone draft retained")
        }
    }
}
