package com.omni.sync.data.taskboard

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import android.util.Base64
import java.security.MessageDigest

/**
 * Reads the same per-board Reviews/<task-id>/manifest.json format as desktop TaskBoard.
 * Human verdicts are submitted through the PC TaskBoard core with both manifest
 * and task revisions. This phone store only reads synced previews and stages
 * content-addressed attachments; desktop GC owns retention.
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

    /** Stage user images in the synced content-addressed attachment tree.
     * Only the authenticated PC review resolver changes the manifest/task.
     * A deferred submission keeps these references intact across reconnects. */
    fun stageAttachments(review: MobileReview, attachments: List<MobileReviewAttachment>): List<String> {
        require(attachments.size <= 10) { "Maximum 10 images per review." }
        return attachments.map { image ->
            val ext = when (image.mime) {
                "image/png" -> "png"; "image/jpeg" -> "jpg"
                "image/webp" -> "webp"; "image/gif" -> "gif"
                else -> error("Unsupported review image type")
            }
            require(image.bytes.size <= 20 * 1024 * 1024) { "Each image must be under 20 MB." }
            val relative = "Attachments/${review.taskId}/${sha(image.bytes)}.$ext"
            val destination = File(review.project.root, relative)
            destination.parentFile?.mkdirs()
            if (!destination.exists()) atomicWrite(destination, image.bytes)
            "![${image.name.replace("[", "").replace("]", "")}]($relative)"
        }
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
