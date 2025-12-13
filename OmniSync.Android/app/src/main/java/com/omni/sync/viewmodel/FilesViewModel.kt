package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.text.DecimalFormat
import android.app.Application // New import
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.rx2.asCoroutineDispatcher
import kotlin.math.min
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap

class FilesViewModel(
    application: Application, // Add application to constructor
    private val signalRClient: SignalRClient,
    private val mainViewModel: MainViewModel // To access connection status, etc.
) : AndroidViewModel(application) {

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath

    private val _fileSystemEntries = MutableStateFlow<List<FileSystemEntry>>(emptyList())
    val fileSystemEntries: StateFlow<List<FileSystemEntry>> = _fileSystemEntries

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // --- File Streaming/Download State ---
    private val _downloadingFile = MutableStateFlow<FileSystemEntry?>(null)
    val downloadingFile: StateFlow<FileSystemEntry?> = _downloadingFile

    private val _downloadProgress = MutableStateFlow(0) // Percentage 0-100
    val downloadProgress: StateFlow<Int> = _downloadProgress

    private val _downloadingSpeed = MutableStateFlow<String?>(null) // e.g., "1.2 MB/s"
    val downloadingSpeed: StateFlow<String?> = _downloadingSpeed

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading
    // -------------------------------------

    init {
        // Observe connection state to potentially trigger a refresh or show a message
        viewModelScope.launch {
            mainViewModel.connectionState.collect { state ->
                if (state == "Connected" && _fileSystemEntries.value.isEmpty() && _currentPath.value.isEmpty()) {
                    // Automatically load root directory when connected, if not already loaded
                    loadDirectory("")
                } else if (state != "Connected") {
                    _fileSystemEntries.value = emptyList()
                    _errorMessage.value = "Disconnected from Hub."
                    resetDownloadState()
                }
            }
        }
    }

    fun loadDirectory(path: String) {
        if (!mainViewModel.isConnected.value) {
            _errorMessage.value = "Not connected to OmniSync Hub. Please connect first."
            return
        }

        _isLoading.value = true
        _errorMessage.value = null
        resetDownloadState() // Reset download state when navigating

        signalRClient.listDirectory(path)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe(
                { entries ->
                    _fileSystemEntries.value = entries
                    _currentPath.value = path
                    _isLoading.value = false
                    _errorMessage.value = null
                },
                { error ->
                    _errorMessage.value = "Error loading directory: ${error.message}"
                    _isLoading.value = false
                    Log.e("FilesViewModel", "Error loading directory", error)
                }
            )
            ?: run {
                _isLoading.value = false
                _errorMessage.value = "Failed to initiate directory listing (not connected?)."
            }
    }

    fun startFileDownload(entry: FileSystemEntry) {
        if (!mainViewModel.isConnected.value) {
            _errorMessage.value = "Not connected to OmniSync Hub. Please connect first."
            return
        }
        if (entry.isDirectory) {
            _errorMessage.value = "Cannot download a directory."
            return
        }
        if (_isDownloading.value) {
            _errorMessage.value = "Another download is already in progress."
            return
        }

        _downloadingFile.value = entry
        _downloadProgress.value = 0
        _downloadingSpeed.value = null
        _downloadErrorMessage.value = null
        _isDownloading.value = true

        viewModelScope.launch(Schedulers.io().asCoroutineDispatcher()) {
            try {
                val chunkSize = 64 * 1024 // 64 KB
                var currentOffset = 0L
                val totalSize = entry.size
                var downloadedBytes = 0L
                val startTime = System.currentTimeMillis()

                // Determine download location (e.g., Downloads folder)
                // For simplicity, let's just use app's cache directory for now.
                // In a real app, you'd ask for WRITE_EXTERNAL_STORAGE permission
                // and use Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val tempFile = File(mainViewModel.applicationContext.cacheDir, entry.name + ".tmp")
                val outputFile = File(mainViewModel.applicationContext.filesDir, entry.name) // Final destination

                FileOutputStream(tempFile).use { fos ->
                    while (currentOffset < totalSize) {
                        val remainingBytes = totalSize - currentOffset
                        val currentChunkSize = minOf(chunkSize.toLong(), remainingBytes).toInt()

                        if (currentChunkSize == 0) break // No more bytes to read

                        signalRClient.getFileChunk(entry.path, currentOffset, currentChunkSize)
                            ?.subscribeOn(Schedulers.io())
                            ?.blockingGet() // Blocking call for simplicity within coroutine
                            ?.let { chunk ->
                                fos.write(chunk)
                                downloadedBytes += chunk.size
                                currentOffset += chunk.size

                                val progress = ((downloadedBytes * 100) / totalSize).toInt()
                                _downloadProgress.value = progress

                                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                                if (elapsedSeconds > 0) {
                                    val speedBps = downloadedBytes / elapsedSeconds
                                    _downloadingSpeed.value = formatBytesPerSecond(speedBps)
                                }
                            } ?: run {
                                throw Exception("Failed to get file chunk.")
                            }
                    }
                }
                
                // Rename temp file to final file
                if (tempFile.renameTo(outputFile)) {
                    _downloadErrorMessage.value = null
                    Log.d("FilesViewModel", "File downloaded successfully to: ${outputFile.absolutePath}")
                    // Play video if it's a video file
                    if (isMediaFile(outputFile.name)) {
                        playFile(outputFile)
                    }
                } else {
                    throw Exception("Failed to rename temporary file to final destination.")
                }
            } catch (e: Exception) {
                _downloadErrorMessage.value = "Download failed: ${e.message}"
                Log.e("FilesViewModel", "File download error", e)
            } finally {
                _isDownloading.value = false
                _downloadingFile.value = null
                _downloadProgress.value = 0
                _downloadingSpeed.value = null
            }
        }
    }

    private fun resetDownloadState() {
        _downloadingFile.value = null
        _downloadProgress.value = 0
        _downloadingSpeed.value = null
        _downloadErrorMessage.value = null
        _isDownloading.value = false
    }

    private fun playFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            getApplication(),
            getApplication<Application>().packageName + ".fileprovider",
            file
        )
        val mimeType: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
        
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        // Ensure there's an app to handle the intent
        if (intent.resolveActivity(getApplication<Application>().packageManager) != null) {
            getApplication<Application>().startActivity(intent)
        } else {
            _errorMessage.value = "No app found to open this file type."
        }
    }

    private fun isMediaFile(fileName: String): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return mimeType?.startsWith("audio/") == true || mimeType?.startsWith("video/") == true
    }
    
    // Helper to format bytes per second
    private fun formatBytesPerSecond(bytesPerSecond: Double): String {
        val df = DecimalFormat("#.##")
        return when {
            bytesPerSecond >= 1_000_000_000 -> "${df.format(bytesPerSecond / 1_000_000_000)} GB/s"
            bytesPerSecond >= 1_000_000 -> "${df.format(bytesPerSecond / 1_000_000)} MB/s"
            bytesPerSecond >= 1_000 -> "${df.format(bytesPerSecond / 1_000)} KB/s"
            else -> "${df.format(bytesPerSecond)} B/s"
        }
    }
}
