package com.omni.sync.data.taskboard

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class PcTaskSource(
    val relativePath: String,
    val size: Long,
    val modifiedAtMillis: Long,
    val text: String
)

/**
 * PC is the authority for online TaskBoard reads as well as writes. Keep the
 * last complete PC snapshot privately on the phone; never overwrite the
 * shared vault or turn partially downloaded cards into a successful refresh.
 */
internal class TaskBoardRemoteReader(context: Context, private val signalR: SignalRClient) {
    private data class BoardCache(
        val board: PcTaskSource,
        val tasks: List<PcTaskSource>,
        val archivedIds: Set<String>,
        val verifiedAt: Long
    )

    private val cacheDir = File(context.filesDir, "taskboard-pc-read-v1")
    private val gson = Gson()
    private val lock = Mutex()
    private val taskName = Regex("^[A-Za-z][A-Za-z0-9]{1,11}-[0-9]+\\.md$", RegexOption.IGNORE_CASE)
    private val downloadSlots = Semaphore(8)

    private fun cacheFile(boardId: String): AtomicFile {
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(boardId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(cacheDir, "$key.json"))
    }

    private fun readCache(file: AtomicFile): BoardCache? =
        runCatching { gson.fromJson(file.readFully().toString(Charsets.UTF_8), BoardCache::class.java) }
            .getOrNull()

    private fun saveCache(file: AtomicFile, value: BoardCache) {
        cacheDir.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun BoardCache.snapshot(store: TaskBoardStore, live: Boolean) =
        store.loadPcSnapshot(board.text, tasks, archivedIds, live)

    suspend fun load(store: TaskBoardStore, boardId: String): TaskBoardSnapshot =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val file = cacheFile(boardId)
                val cached = readCache(file)
                if (signalR.hubAuthenticated.value) {
                    try {
                        val fresh = fetch(boardId, cached)
                        val snapshot = fresh.snapshot(store, true)
                        // A local disk failure must not discard a successful live read.
                        if (fresh != cached) runCatching { saveCache(file, fresh) }
                            .onFailure { Log.w("TaskBoardRead", "PC snapshot cache write failed", it) }
                        return@withLock snapshot
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w("TaskBoardRead", "PC board refresh deferred", failure)
                    }
                }
                cached?.let { runCatching { it.snapshot(store, false) }.getOrNull() }
                    ?: store.load()
            }
        }

    private fun list(path: String): List<FileSystemEntry> =
        requireNotNull(signalR.listDirectory(path)) { "PC is disconnected" }.blockingGet()

    private fun taskFiles(path: String, relative: String): List<Pair<String, FileSystemEntry>> =
        list(path).filter { it.name != ".." }.flatMap { entry ->
            require(entry.name.isNotBlank() && entry.name != "." &&
                !entry.name.contains('/') && !entry.name.contains('\\')) { "Invalid board entry" }
            val name = "$relative/${entry.name}"
            when {
                entry.isDirectory -> taskFiles(entry.path, name)
                taskName.matches(entry.name) -> listOf(name to entry)
                else -> emptyList()
            }
        }

    private suspend fun source(relative: String, entry: FileSystemEntry, previous: PcTaskSource?,
                               force: Boolean): PcTaskSource = downloadSlots.withPermit {
        val modified = entry.lastModified.time
        if (!force && previous != null && previous.size == entry.size &&
            previous.modifiedAtMillis == modified) return@withPermit previous
        val bytes = ByteArrayOutputStream()
        var offset = 0L
        // Read through EOF; a size change between list and download invalidates the scan.
        do {
            val length = minOf(128 * 1024L, entry.size - offset + 1).toInt()
            val chunk = requireNotNull(signalR.getFileChunk(entry.path, offset, length)) {
                "PC is disconnected"
            }.timeout(20, TimeUnit.SECONDS).blockingGet()
            bytes.write(chunk)
            offset += chunk.size
            require(offset <= entry.size) { "Board changed during refresh" }
            if (chunk.size < length) break
        } while (offset <= entry.size)
        require(offset == entry.size) { "Incomplete PC task file" }
        PcTaskSource(relative, entry.size, modified, bytes.toByteArray().toString(Charsets.UTF_8))
    }

    private suspend fun fetch(boardId: String, previous: BoardCache?): BoardCache = coroutineScope {
        val root = "B:\\GDrive\\SharedWithPhone\\Obsidian\\jjj\\" + boardId.replace('/', '\\')
        val entries = list(root)
        val boardEntry = entries.single { it.name == "_board.md" && !it.isDirectory }
        val active = entries.single { it.name == "Active" && it.isDirectory }
        val activeFiles = taskFiles(active.path, "Active")
        val archived = entries.firstOrNull { it.name == "Archive" && it.isDirectory }
            ?.let { taskFiles(it.path, "Archive").map { (_, entry) ->
                entry.name.substringBeforeLast('.')
            }.toSet() }.orEmpty()
        val now = System.currentTimeMillis()
        // Some servers expose second-resolution mtimes. Periodically recheck
        // content even if the metadata is unchanged.
        val force = previous == null || now - previous.verifiedAt > 900_000
        val previousTasks = previous?.tasks.orEmpty().associateBy { it.relativePath }
        val board = async { source("_board.md", boardEntry, previous?.board, force) }
        val tasks = activeFiles.map { (relative, entry) ->
            async { source(relative, entry, previousTasks[relative], force) }
        }.awaitAll().sortedBy { it.relativePath }
        BoardCache(board.await(), tasks, archived, if (force) now else requireNotNull(previous).verifiedAt)
    }
}
