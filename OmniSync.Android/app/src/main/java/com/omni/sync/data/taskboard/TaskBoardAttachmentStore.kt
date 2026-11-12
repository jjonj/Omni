package com.omni.sync.data.taskboard

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Copies files selected from Android's gallery/files picker into the same
 * Syncthing-backed TaskBoard Attachments tree used by desktop and mobile review.
 * The task edit itself still goes through the authoritative PC phone-write API.
 */
object TaskBoardAttachmentStore {
    private const val MAX_FILES = 10
    private const val MAX_FILE_BYTES = 100L * 1024L * 1024L

    fun displayName(resolver: ContentResolver, uri: Uri): String {
        val fromProvider = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Attachment"
    }

    /** Returns links relative to an Active/<id>.md task file. */
    fun copyPickedFiles(resolver: ContentResolver, tasksRoot: File, uris: List<Uri>): List<String> {
        require(uris.size <= MAX_FILES) { "Choose up to $MAX_FILES attachments." }
        if (uris.isEmpty()) return emptyList()

        val folder = File(tasksRoot, "Attachments/MobileUploads")
        check(folder.isDirectory || folder.mkdirs()) { "Cannot write TaskBoard Attachments folder." }
        return uris.map { uri ->
            val name = displayName(resolver, uri)
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val byName = name.substringAfterLast('.', "").lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            val extension = byName ?: MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime)?.lowercase()?.takeIf {
                    it.matches(Regex("[a-z0-9]{1,10}"))
                } ?: "bin"
            val staging = File.createTempFile("mobile-", ".part", folder)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = resolver.openInputStream(uri)
                    ?: error("Cannot open $name from your phone.")
                input.use { source ->
                    FileOutputStream(staging).use { target ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_FILE_BYTES) {
                                "$name exceeds the 100 MB per-file limit."
                            }
                            digest.update(buffer, 0, count)
                            target.write(buffer, 0, count)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val file = File(folder, "$hash.$extension")
                if (!file.isFile) {
                    check(staging.renameTo(file) || file.isFile) {
                        "Could not save $name in the synced TaskBoard folder."
                    }
                }
                val label = name.replace(Regex("[\\[\\]()\\r\\n]"), "_").take(100)
                val link = "../Attachments/MobileUploads/${file.name}"
                if (mime.startsWith("image/")) "![$label]($link)" else "[$label]($link)"
            } finally {
                staging.delete()
            }
        }
    }

    /** Add files before timeline history, so subsequent editor saves retain links. */
    fun withReferences(draft: TaskBoardTaskDraft, references: List<String>): TaskBoardTaskDraft {
        if (references.isEmpty()) return draft
        val parsed = TaskBoardTimeline.parse(draft.description)
        val initial = listOf(parsed.initial.trimEnd(), references.joinToString("\n"))
            .filter { it.isNotBlank() }.joinToString("\n\n")
        return draft.copy(description = TaskBoardTimeline.format(initial, parsed.updates))
    }
}
