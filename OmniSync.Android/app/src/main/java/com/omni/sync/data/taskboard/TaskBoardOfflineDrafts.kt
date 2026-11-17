package com.omni.sync.data.taskboard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * OW-0023: local-only, durable disconnected edits. Never write a TaskBoard
 * Markdown source file from this store. The PC's TaskBoard core owns semantic
 * mutations; a replay must use its expected revision and operation ID.
 */
class TaskBoardOfflineDrafts(context: Context) {
    private val root = File(context.filesDir, "taskboard-offline-drafts").apply { mkdirs() }

    data class Pending(
        val operationId: String,
        val boardId: String,
        val taskId: String,
        val baseRevision: String,
        val baseSource: String,
        val edited: String,
        val createdAt: Long,
        val state: String = "draft",
        val currentSource: String? = null,
        val explanation: String? = null
    )

    private fun fileFor(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid operation ID" }
        return File(root, "$id.json")
    }

    private fun encode(row: Pending): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("operationId", row.operationId)
        put("boardId", row.boardId)
        put("taskId", row.taskId)
        put("baseRevision", row.baseRevision)
        put("baseSource", row.baseSource)
        put("edited", row.edited)
        put("createdAt", row.createdAt)
        put("state", row.state)
        put("currentSource", row.currentSource)
        put("explanation", row.explanation)
    }

    private fun decode(file: File): Pending {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.optInt("schema") == 1) { "Unknown draft schema" }
        return Pending(
            operationId = json.getString("operationId"),
            boardId = json.getString("boardId"),
            taskId = json.getString("taskId"),
            baseRevision = json.getString("baseRevision"),
            baseSource = json.getString("baseSource"),
            edited = json.getString("edited"),
            createdAt = json.getLong("createdAt"),
            state = json.getString("state"),
            currentSource = json.optString("currentSource").takeIf { it.isNotBlank() && it != "null" },
            explanation = json.optString("explanation").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    @Synchronized
    fun saveOffline(
        boardId: String, taskId: String, baseRevision: String, baseSource: String, edited: String,
        operationId: String = UUID.randomUUID().toString()
    ): Pending {
        require(boardId.isNotBlank() && taskId.matches(Regex("[A-Za-z][A-Za-z0-9-]{1,63}")))
        require(baseRevision.matches(Regex("[a-fA-F0-9]{64}"))) { "Expected a canonical TaskBoard revision" }
        require(edited.length <= 2_000_000 && baseSource.length <= 2_000_000)
        val destination = fileFor(operationId)
        if (destination.exists()) {
            val original = decode(destination)
            require(original.boardId == boardId && original.taskId == taskId &&
                original.baseRevision == baseRevision && original.edited == edited) {
                "Operation ID already bound to different draft"
            }
            return original
        }
        val row = Pending(operationId, boardId, taskId, baseRevision, baseSource, edited,
            System.currentTimeMillis())
        val temporary = File(root, "$operationId.tmp")
        temporary.writeText(encode(row).toString(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Failed to persist offline TaskBoard draft", error)
        }
        return row
    }

    @Synchronized
    fun all(): List<Pending> = root.listFiles()
        .orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        .mapNotNull { runCatching { decode(it) }.getOrNull() }
        .sortedBy { it.createdAt }

    /** Server 409 provides BOTH revisions. Never silently apply a merge. */
    @Synchronized
    fun conflict(operationId: String, currentSource: String, reason: String): Pending {
        val file = fileFor(operationId)
        val previous = decode(file)
        val changed = previous.copy(state = "conflict", currentSource = currentSource, explanation = reason)
        val temporary = File(root, "$operationId.tmp")
        temporary.writeText(encode(changed).toString(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Failed to persist TaskBoard conflict", error)
        }
        return changed
    }

    /** A PC journal UNKNOWN is not transient transport failure: never blindly retry it. */
    @Synchronized
    fun uncertain(operationId: String, reason: String) {
        val file = fileFor(operationId)
        val previous = decode(file)
        val changed = previous.copy(state = "unknown", explanation = reason)
        val temporary = File(root, "$operationId.tmp")
        temporary.writeText(encode(changed).toString(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Failed to persist uncertain operation", error)
        }
    }

    /** Keep confirmed creates visible until the synced Markdown carries the exact
     * PC revision. Retaining the original journal entry avoids matching by title
     * (two legitimate tasks can have identical titles). */
    @Synchronized
    fun acknowledgeCreate(operationId: String, revision: String) {
        val file = fileFor(operationId)
        val previous = decode(file)
        require(previous.taskId == "CREATE" && revision.matches(Regex("[a-fA-F0-9]{64}")))
        val changed = previous.copy(state = "applied", currentSource = revision)
        val temporary = File(root, "$operationId.tmp")
        temporary.writeText(encode(changed).toString(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Failed to persist confirmed create receipt", error)
        }
    }

    /** Retire predictions only when the authoritative synced task is present. */
    @Synchronized
    fun retireSyncedCreates(boardId: String, revisions: Set<String>) {
        all().filter { it.boardId == boardId && it.taskId == "CREATE" &&
            it.state == "applied" && it.currentSource in revisions }
            .forEach { fileFor(it.operationId).delete() }
    }

    /** Only call this after the PC returns a durable receipt for this exact ID. */
    @Synchronized
    fun acknowledge(operationId: String, receiptOperationId: String) {
        require(operationId == receiptOperationId) { "Wrong PC operation receipt" }
        val file = fileFor(operationId)
        if (file.exists()) check(file.delete()) { "Could not retire acknowledged draft" }
    }
}
