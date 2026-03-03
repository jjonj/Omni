package com.omni.sync.logic

import android.content.Context
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

data class DownloadStatus(
    val entry: FileSystemEntry,
    val progress: Int, // 0-100
    val isCompleted: Boolean = false,
    val error: String? = null
)

class BookDownloadManager(
    private val context: Context,
    private val signalRClient: SignalRClient
) {
    private val _downloadStatuses = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStatuses: StateFlow<Map<String, DownloadStatus>> = _downloadStatuses

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun downloadBook(entry: FileSystemEntry) {
        if (_downloadStatuses.value.containsKey(entry.path)) return

        downloadScope.launch {
            _downloadStatuses.value += (entry.path to DownloadStatus(entry, 0))
            
            try {
                val booksDir = File(context.filesDir, "downloaded_books")
                if (!booksDir.exists()) booksDir.mkdirs()

                val outputFile = File(booksDir, entry.name)
                val totalSize = entry.size
                var downloadedBytes = 0L

                FileOutputStream(outputFile).use { output ->
                    // This is a simplified version of the chunked download logic
                    // In a real implementation, we'd call signalRClient.downloadFile(entry.path)
                    // and handle the stream of bytes.
                    
                    // Simulation for "Red" phase test
                    throw Exception("Not implemented yet")
                }
            } catch (e: Exception) {
                _downloadStatuses.value += (entry.path to DownloadStatus(entry, 0, error = e.message))
            }
        }
    }

    fun isDownloaded(path: String): Boolean {
        val booksDir = File(context.filesDir, "downloaded_books")
        val file = File(booksDir, File(path).name)
        return file.exists()
    }
}
