package com.omni.sync.logic

import android.content.Context
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URLEncoder

data class DownloadStatus(
    val entry: FileSystemEntry,
    val progress: Int, // 0-100
    val isCompleted: Boolean = false,
    val error: String? = null,
    val downloadId: Long? = null
)

class BookDownloadManager(
    private val context: Context,
    private val mainViewModel: com.omni.sync.viewmodel.MainViewModel
) {
    private val _downloadStatuses = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStatuses: StateFlow<Map<String, DownloadStatus>> = _downloadStatuses

    private val downloadManager by lazy { 
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager 
    }

    init {
        refreshDownloads()
    }

    fun refreshDownloads() {
        val booksDir = context.getExternalFilesDir("downloaded_books")
        android.util.Log.d("BookDownload", "Refreshing downloads from: ${booksDir?.absolutePath}")
        
        if (booksDir == null || !booksDir.exists()) return
        val files = booksDir.listFiles() ?: return
        
        android.util.Log.d("BookDownload", "Found ${files.size} files on disk")
        
        // We don't necessarily have the full remote path here, but we can update 
        // local checks. The UI relies on isDownloaded(path).
    }

    fun onDownloadComplete(id: Long) {
        android.util.Log.d("BookDownload", "Download complete: $id")
        // Find which book this belongs to
        val entry = _downloadStatuses.value.entries.find { it.value.downloadId == id }
        if (entry != null) {
            android.util.Log.d("BookDownload", "Marking ${entry.value.entry.name} as completed")
            _downloadStatuses.value += (entry.key to entry.value.copy(isCompleted = true, progress = 100))
        }
    }

    fun downloadBook(entry: FileSystemEntry) {
        if (isDownloaded(entry.path)) {
            android.util.Log.d("BookDownload", "Already downloaded: ${entry.path}")
            return
        }
        if (_downloadStatuses.value.containsKey(entry.path)) return

        val dm = downloadManager ?: return 

        val baseUrl = mainViewModel.getBaseUrl()
        val encodedPath = URLEncoder.encode(entry.path, "UTF-8")
        val downloadUrl = "$baseUrl/api/stream?path=$encodedPath"
        
        android.util.Log.d("BookDownload", "Starting download for ${entry.name} from $downloadUrl")

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Downloading ${entry.name}")
            .setDescription("OmniSync Library")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "downloaded_books", entry.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        try {
            val id = dm.enqueue(request)
            _downloadStatuses.value += (entry.path to DownloadStatus(entry, 0, downloadId = id))
            android.util.Log.d("BookDownload", "Enqueued download ID: $id")
        } catch (e: Exception) {
            android.util.Log.e("BookDownload", "Failed to enqueue download", e)
            _downloadStatuses.value += (entry.path to DownloadStatus(entry, 0, error = e.message))
        }
    }

    private fun getFileNameFromPath(path: String): String {
        // Handle both Windows and Linux separators
        return path.substringAfterLast('\\').substringAfterLast('/')
    }

    fun isDownloaded(path: String): Boolean {
        val fileName = getFileNameFromPath(path)
        val booksDir = context.getExternalFilesDir("downloaded_books")
        val file = File(booksDir, fileName)
        val exists = file.exists()
        if (exists) {
            val status = _downloadStatuses.value[path]
            if (status != null && !status.isCompleted) {
                _downloadStatuses.value += (path to status.copy(isCompleted = true, progress = 100, error = null))
            }
        }
        return exists
    }

    fun getLocalPath(path: String): String? {
        val fileName = getFileNameFromPath(path)
        val booksDir = context.getExternalFilesDir("downloaded_books")
        val file = File(booksDir, fileName)
        return if (file.exists()) file.absolutePath else null
    }
}
