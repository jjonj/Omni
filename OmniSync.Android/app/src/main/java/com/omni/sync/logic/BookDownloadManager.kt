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

    fun downloadBook(entry: FileSystemEntry) {
        if (isDownloaded(entry.path)) return
        if (_downloadStatuses.value.containsKey(entry.path)) return

        val dm = downloadManager ?: return // Skip if service not available (e.g. unit tests)

        val baseUrl = mainViewModel.getBaseUrl()
        val encodedPath = URLEncoder.encode(entry.path, "UTF-8")
        val downloadUrl = "$baseUrl/api/stream?path=$encodedPath"

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Downloading ${entry.name}")
            .setDescription("OmniSync Library")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "downloaded_books", entry.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = dm.enqueue(request)
        _downloadStatuses.value += (entry.path to DownloadStatus(entry, 0, downloadId = id))
        
        // In a full implementation, we'd poll the DownloadManager for progress
        // or use a BroadcastReceiver to update status.
    }

    fun isDownloaded(path: String): Boolean {
        val booksDir = File(context.getExternalFilesDir("downloaded_books"), "")
        val file = File(booksDir, File(path).name)
        return file.exists()
    }

    fun getLocalPath(path: String): String? {
        val booksDir = File(context.getExternalFilesDir("downloaded_books"), "")
        val file = File(booksDir, File(path).name)
        return if (file.exists()) file.absolutePath else null
    }
}
