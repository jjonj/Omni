package com.omni.sync.data.taskboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import android.util.Base64
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Reads the same per-board Reviews/<task-id>/manifest.json format as desktop TaskBoard.
 * No file deletions: desktop GC owns retention. Writes are serialized against other
 * Android TaskBoard writers and checked against BOTH manifest and Markdown revisions.
 */
data class MobileReview(
    val project: TaskBoardProject,
    val taskId: String,
    val title: String,
    val manifest: JSONObject,
    val manifestRevision: String,
    val taskRevision: String,
    val taskFile: File,
) {
    val round: JSONObject get() = manifest.getJSONArray("rounds")
        .getJSONObject(manifest.getJSONArray("rounds").length() - 1)
    val mode: String get() = round.optString("mode", "pick")
    val status: String get() = manifest.optString("status", "open")
}

data class MobileReviewPin(
    val x: Double, val y: Double,
    val artifact: String? = null,
    val timestamp: Double? = null,
    val viewportWidth: Int? = null, val viewportHeight: Int? = null,
    val scrollX: Double? = null, val scrollY: Double? = null,
    val note: String = "",
) {
    fun json(): JSONObject = JSONObject().apply {
        put("x", x.coerceIn(0.0, 1.0))
        put("y", y.coerceIn(0.0, 1.0))
        artifact?.let { put("artifact", it) }
        timestamp?.let { put("timestamp", it) }
        viewportWidth?.let { put("viewport_width", it) }
        viewportHeight?.let { put("viewport_height", it) }
        scrollX?.let { put("scroll_x", it) }
        scrollY?.let { put("scroll_y", it) }
        put("note", note)
    }
}
data class MobileReviewAttachment(val name: String, val mime: String, val bytes: ByteArray)

data class MobileReviewDecision(
    val verdict: String,
    val choices: List<String> = emptyList(),
    val ranking: List<String> = emptyList(),
    val pins: List<MobileReviewPin> = emptyList(),
    val note: String = "",
    val attachments: List<MobileReviewAttachment> = emptyList(),
)

/** No assumptions about project prefix: review dirs are keyed by the manifest task_id. */
object TaskBoardReviewStore {
    private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(raw).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String = sha(file.readBytes())
    private fun array(items: Collection<String>) = JSONArray().apply { items.forEach { put(it) } }
    private fun quoteTime(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    private fun taskFile(root: File, id: String) = File(root, "Active/$id.md")

    /** Refreshes from phone's synced SharedWithPc tree, including user-created boards. */
    fun queue(includeResolved: Boolean = false): List<MobileReview> =
        TaskBoardStore.discoverBoards().flatMap { board ->
            val dir = File(board.root, "Reviews")
            dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { folder ->
                runCatching {
                    val file = File(folder, "manifest.json")
                    if (!file.isFile) return@runCatching null
                    val bytes = file.readBytes()
                    val manifest = JSONObject(String(bytes, StandardCharsets.UTF_8).trimStart('\uFEFF'))
                    val id = manifest.optString("task_id")
                    if (id != folder.name || !id.matches(Regex("[A-Za-z0-9-]+"))) return@runCatching null
                    val status = manifest.optString("status")
                    if (status != "open" && (!includeResolved || status != "resolved")) return@runCatching null
                    val task = taskFile(board.root, id)
                    if (!task.isFile) return@runCatching null
                    val taskRaw = task.readBytes()
                    val text = String(taskRaw, StandardCharsets.UTF_8)
                    val title = Regex("(?m)^title:\\s*(.+)$").find(text)?.groupValues?.get(1)
                        ?.trim()?.trim('"', '\'') ?: id
                    if ((manifest.optJSONArray("rounds")?.length() ?: 0) == 0) return@runCatching null
                    MobileReview(board, id, title, manifest, sha(bytes), sha(taskRaw), task)
                }.getOrNull()
            } ?: emptyList()
        }.sortedWith(compareByDescending<MobileReview> { it.round.optString("requested_at") }
            .thenBy { it.taskId })

    fun artifactFile(review: MobileReview, artifact: JSONObject): File? {
        val stored = artifact.optString("storage", "synced")
        if (stored == "local" || stored == "url") {
            val poster = artifact.optString("poster")
            return if (poster.isBlank()) null else File(review.project.root, poster)
        }
        val src = artifact.optString("path")
        if (src.isBlank()) return null
        // Desktop copies artifacts inside the same synced Tasks/Reviews tree but
        // records its own B:/GDrive absolute path. Rebase Reviews/ on phone.
        val normalized = src.replace('\\', '/')
        val marker = "/Reviews/"
        val syncedRelative = normalized.indexOf(marker, ignoreCase = true)
            .takeIf { it >= 0 }?.let { normalized.substring(it + 1) }
        if (syncedRelative != null) {
            val rebased = File(review.project.root, syncedRelative)
            return runCatching { rebased.canonicalFile.takeIf {
                it.path.startsWith(review.project.root.canonicalPath + File.separator)
            }?.let { if (it.isDirectory) File(it, "index.html") else it } }.getOrNull()
        }
        if (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(src)) {
            val poster = artifact.optString("poster")
            return if (poster.isBlank()) null else File(review.project.root, poster)
        }
        val path = File(src)
        val root = review.project.root.canonicalFile
        val file = if (path.isAbsolute) path else File(root, src)
        return runCatching { file.canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
            ?.let { if (it.isDirectory) File(it, "index.html") else it } }.getOrNull()
    }

    /** Inline only files within the mirrored HTML folder; WebView has all file and
     * content access disabled. A data-origin page cannot read app state. */
    fun htmlForPreview(file: File): String {
        val folder = requireNotNull(file.parentFile).canonicalFile
        val html = file.readText()
        val references = Regex("""(?i)(src|href)\s*=\s*(["'])([^"']+)\2""")
        return references.replace(html) { found ->
            val attribute = found.groupValues[1]
            val ref = found.groupValues[3]
            if (ref.startsWith("#") || ref.startsWith("/") || ref.contains("://") ||
                ref.startsWith("data:") || ref.startsWith("javascript:")) return@replace found.value
            val asset = runCatching {
                File(folder, ref.substringBefore('?').substringBefore('#')).canonicalFile
            }.getOrNull() ?: return@replace found.value
            if (!asset.isFile || !asset.path.startsWith(folder.path + File.separator) ||
                asset.length() > 10 * 1024 * 1024) return@replace found.value
            val mime = when (asset.extension.lowercase()) {
                "css" -> "text/css"
                "js", "mjs" -> "text/javascript"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                else -> return@replace found.value
            }
            val encoded = Base64.encodeToString(asset.readBytes(), Base64.NO_WRAP)
            """$attribute="data:$mime;base64,$encoded""""
        }
    }

    fun resolve(review: MobileReview, decision: MobileReviewDecision) {
        require(decision.verdict in setOf("picked", "approved", "rejected", "ranked", "marked", "more-variants"))
        val round = review.round
        val options = round.getJSONArray("artifacts")
        val ids = (0 until options.length()).map { options.getJSONObject(it).getString("id") }
        require(decision.choices.all { it in ids } && decision.ranking.all { it in ids })
        require(decision.ranking.distinct().size == decision.ranking.size)
        when (decision.verdict) {
            "picked" -> require(review.mode == "pick" && decision.choices.size == 1)
            "approved" -> require(review.mode == "approve" && decision.choices.size == 1)
            "ranked" -> require(review.mode == "rank" && decision.ranking.size == ids.size)
            "marked" -> require(review.mode == "markup")
        }
        require(decision.pins.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 &&
            (it.timestamp == null || it.timestamp >= 0.0) })
        val root = review.project.root
        val manifestFile = File(root, "Reviews/${review.taskId}/manifest.json")
        val lockFile = File(root, ".omni-android-taskboard.lock")
        RandomAccessFile(lockFile, "rw").use { handle ->
            val lock = handle.channel.lock()
            try {
                if (!manifestFile.isFile || sha(manifestFile) != review.manifestRevision ||
                    !review.taskFile.isFile || sha(review.taskFile) != review.taskRevision)
                    throw TaskBoardConflictException("Review changed during sync; refresh before submitting.")
                val manifest = JSONObject(manifestFile.readText())
                require(manifest.optString("status") == "open") { "Review is already resolved." }
                val currentRound = manifest.getJSONArray("rounds")
                    .getJSONObject(manifest.getJSONArray("rounds").length() - 1)
                require(!currentRound.has("result") || currentRound.isNull("result")) {
                    "Round was already answered."
                }
                val now = quoteTime()
                require(decision.attachments.size <= 10) { "Maximum 10 images per review." }
                // Desktop uses the same content-addressed Attachments/<task-id>/<hash>.<ext> paths.
                val imageRefs = decision.attachments.map { image ->
                    val ext = when (image.mime) {
                        "image/png" -> "png"; "image/jpeg" -> "jpg"
                        "image/webp" -> "webp"; "image/gif" -> "gif"
                        else -> error("Unsupported image type")
                    }
                    require(image.bytes.size <= 20 * 1024 * 1024) { "Each image must be under 20 MB." }
                    val relative = "Attachments/${review.taskId}/${sha(image.bytes)}.$ext"
                    val file = File(root, relative)
                    if (!file.exists()) {
                        file.parentFile?.mkdirs()
                        atomicWrite(file, image.bytes)
                    }
                    "![${image.name.replace("[", "").replace("]", "")}]($relative)"
                }
                val combinedNote = (listOf(decision.note) + imageRefs).filter { it.isNotBlank() }
                    .joinToString("\n\n")
                val answer = JSONObject().apply {
                    put("verdict", decision.verdict)
                    put("choices", array(decision.choices))
                    put("ranking", array(decision.ranking))
                    put("pins", JSONArray().apply { decision.pins.forEach { put(it.json()) } })
                    put("note", combinedNote)
                    put("resolved_at", now)
                }
                currentRound.put("result", answer)
                currentRound.put("loser_prune_at",
                    Instant.now().plus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS).toString())
                val final = decision.verdict !in setOf("rejected", "more-variants")
                manifest.put("status", if (final) "resolved" else "open")
                manifest.put("revision", "")

                var taskText = review.taskFile.readText()
                val first = taskText.indexOf("---")
                val second = taskText.indexOf("---", first + 3)
                require(first == 0 && second > 0) { "Invalid TaskBoard frontmatter." }
                var front = taskText.substring(4, second).trimEnd()
                front = putFrontField(front, "review_status", if (final) "resolved" else "open")
                front = putFrontField(front, "review_round", manifest.optInt("current_round").toString())
                if (final) {
                    front = front.replace(Regex("(?m)^attention:\\s*\\[(.*?)\\]\\s*$")) { m ->
                        val remaining = m.groupValues[1].split(",").map(String::trim)
                            .filter { it.isNotBlank() && it.trim('"', '\'') != "question" }
                        "attention: [" + remaining.joinToString(", ") + "]"
                    }
                    // A review-created question is no longer actionable.
                    front = removeFrontObject(front, "question")
                }
                taskText = "---\n$front\n---" + taskText.substring(second + 3)
                val summary = "Review round ${manifest.optInt("current_round")}: ${decision.verdict}. " +
                    "Choice: [${decision.choices.joinToString(", ")}]. " +
                    "Ranking: [${decision.ranking.joinToString(", ")}]. " +
                    "Pins: ${JSONArray().apply { decision.pins.forEach { put(it.json()) } }}. Note: ${combinedNote}"
                val timeline = "<!-- taskboard:update:user-note|$now -->\n" +
                    summary.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" } +
                    "\n<!-- taskboard:end-update -->"
                val nextHeading = Regex("(?m)^## (?!#)").find(taskText, taskText.indexOf("## Description") + "## Description".length)
                val insertAt = nextHeading?.range?.first ?: taskText.length
                taskText = taskText.substring(0, insertAt).trimEnd() + "\n\n" + timeline + "\n\n" +
                    taskText.substring(insertAt)

                if (sha(manifestFile) != review.manifestRevision || sha(review.taskFile) != review.taskRevision)
                    throw TaskBoardConflictException("Desktop or sync changed the review; refresh before submitting.")
                // Each file is individually atomically replaced. Crash between writes is
                // recoverable: manifest is authority and next load reports the revision.
                atomicWrite(manifestFile, (manifest.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8))
                atomicWrite(review.taskFile, taskText.toByteArray(StandardCharsets.UTF_8))
            } finally { lock.release() }
        }
    }

    private fun putFrontField(front: String, key: String, value: String): String {
        val match = Regex("(?m)^" + Regex.escape(key) + ":.*$").find(front)
        return if (match != null) front.replaceRange(match.range, "$key: $value")
            else front + "\n$key: $value"
    }
    private fun removeFrontObject(front: String, key: String): String {
        val lines = front.lines().toMutableList()
        val first = lines.indexOfFirst { it.startsWith("$key:") }
        if (first < 0) return front
        var end = first + 1
        while (end < lines.size && (lines[end].startsWith(" ") || lines[end].isBlank())) end++
        repeat(end - first) { lines.removeAt(first) }
        return lines.joinToString("\n")
    }
    private fun atomicWrite(file: File, data: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.review-${System.nanoTime()}.tmp")
        temp.writeBytes(data)
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
