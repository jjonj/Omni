package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.text.DecimalFormat
import android.app.Application // New import
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.rx3.asCoroutineDispatcher
import kotlin.math.min
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap

class FilesViewModel(
    application: Application, // Add application to constructor
    private val signalRClient: SignalRClient,
    val mainViewModel: MainViewModel // To access connection status, etc.
) : AndroidViewModel(application) {

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath

    private val _fileSystemEntries = MutableStateFlow<List<FileSystemEntry>>(emptyList())
    val fileSystemEntries: StateFlow<List<FileSystemEntry>> = _fileSystemEntries

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

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

    private val _downloadErrorMessage = MutableStateFlow<String?>(null)
    val downloadErrorMessage: StateFlow<String?> = _downloadErrorMessage
    // -------------------------------------

    // --- Text Editor State ---
    private val _editingFile = MutableStateFlow<FileSystemEntry?>(null)
    val editingFile: StateFlow<FileSystemEntry?> = _editingFile

    private val _editingContent = MutableStateFlow("")
    val editingContent: StateFlow<String> = _editingContent

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving
    // -------------------------

    init {
        // Observe connection state to potentially trigger a refresh or show a message
        /*
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
        */
    }

    fun loadDirectory(path: String) {
        if (!mainViewModel.isConnected.value) {
            _errorMessage.value = "Not connected to OmniSync Hub. Please connect first."
            return
        }

        _searchQuery.value = "" // Clear search when navigating
        _isLoading.value = true
        _errorMessage.value = null
        resetDownloadState() // Reset download state when navigating
        mainViewModel.addLog("Loading directory: ${if (path.isEmpty()) "(root)" else path}", com.omni.sync.ui.screen.LogType.INFO)

        signalRClient.listDirectory(path)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.timeout(8, java.util.concurrent.TimeUnit.SECONDS)
            ?.subscribe(
                { entries ->
                    mainViewModel.addLog("Loaded ${entries.size} entries", com.omni.sync.ui.screen.LogType.SUCCESS)
                    _fileSystemEntries.value = entries
                    _currentPath.value = path
                    _isLoading.value = false
                    _errorMessage.value = null
                },
                { error ->
                    _errorMessage.value = "Error loading directory: ${error.message}"
                    _isLoading.value = false
                    mainViewModel.addLog("Error loading directory: ${error.message}", com.omni.sync.ui.screen.LogType.ERROR)
                    Log.e("FilesViewModel", "Error loading directory", error)
                }
            )
            ?: run {
                _isLoading.value = false
                _errorMessage.value = "Failed to initiate directory listing (not connected?)."
                mainViewModel.addLog("Failed to initiate directory listing", com.omni.sync.ui.screen.LogType.ERROR)
            }
    }

    fun performSearch(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            loadDirectory(_currentPath.value)
            return
        }

        if (!mainViewModel.isConnected.value) return

        _isLoading.value = true
        signalRClient.searchFiles(_currentPath.value, query)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe(
                { entries ->
                    _fileSystemEntries.value = entries
                    _isLoading.value = false
                },
                { error ->
                    _errorMessage.value = "Search error: ${error.message}"
                    _isLoading.value = false
                }
            )
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
                                fos.write(chunk as ByteArray)
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
                    // Open the file with an appropriate app
                    openFile(outputFile)
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

    fun openFileOnPC(entry: FileSystemEntry) {
        if (!mainViewModel.isConnected.value) {
            _errorMessage.value = "Not connected to OmniSync Hub. Please connect first."
            return
        }
        mainViewModel.addLog("Opening on PC: ${entry.name}", com.omni.sync.ui.screen.LogType.INFO)
        signalRClient.sendPayload("OPEN_ON_PC", mapOf("Path" to entry.path))
    }

    fun openForEditing(entry: FileSystemEntry) {
        if (!mainViewModel.isConnected.value) {
            _errorMessage.value = "Not connected to OmniSync Hub. Please connect first."
            return
        }

        _isLoading.value = true
        viewModelScope.launch(Schedulers.io().asCoroutineDispatcher()) {
            try {
                // Download the entire file into memory as a string
                val totalSize = entry.size
                var downloadedBytes = 0L
                val contentBuilder = StringBuilder()
                val chunkSize = 128 * 1024 // 128 KB for text is plenty

                var currentOffset = 0L
                while (currentOffset < totalSize) {
                    val remainingBytes = totalSize - currentOffset
                    val currentChunkSize = minOf(chunkSize.toLong(), remainingBytes).toInt()

                    if (currentChunkSize == 0) break

                    val chunk = signalRClient.getFileChunk(entry.path, currentOffset, currentChunkSize)
                        ?.subscribeOn(Schedulers.io())
                        ?.blockingGet() as? ByteArray

                    if (chunk != null) {
                        contentBuilder.append(String(chunk, Charsets.UTF_8))
                        downloadedBytes += chunk.size
                        currentOffset += chunk.size
                    } else {
                        throw Exception("Failed to get file chunk during editing.")
                    }
                }

                _editingFile.value = entry
                _editingContent.value = contentBuilder.toString()
                _isLoading.value = false
                
                viewModelScope.launch(AndroidSchedulers.mainThread().asCoroutineDispatcher()) {
                    mainViewModel.navigateTo(AppScreen.EDITOR)
                }
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = "Failed to open for editing: ${e.message}"
            }
        }
    }

    fun updateEditingContent(newContent: String) {
        _editingContent.value = newContent
    }

    fun saveEditingContent() {
        val entry = _editingFile.value ?: return
        if (!mainViewModel.isConnected.value) return

        _isSaving.value = true
        viewModelScope.launch(Schedulers.io().asCoroutineDispatcher()) {
            try {
                signalRClient.sendPayload("SAVE_FILE", mapOf(
                    "Path" to entry.path,
                    "Content" to _editingContent.value
                ))
                
                viewModelScope.launch(AndroidSchedulers.mainThread().asCoroutineDispatcher()) {
                    mainViewModel.addLog("File saved: ${entry.name}", com.omni.sync.ui.screen.LogType.SUCCESS)
                    _isSaving.value = false
                    mainViewModel.goBack()
                }
            } catch (e: Exception) {
                _isSaving.value = false
                _errorMessage.value = "Save failed: ${e.message}"
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

    private fun openFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            getApplication(),
            getApplication<Application>().packageName + ".fileprovider",
            file
        )
        
        var mimeType: String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        
        if (mimeType == null) {
            // Fallback to text/plain for unknown file types as requested
            mimeType = "text/plain"
        }
        
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Needed when starting activity from non-activity context
        
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            // If opening with detected/fallback mime type fails, try one last time with */*
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW)
                genericIntent.setDataAndType(uri, "*/*")
                genericIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                genericIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(genericIntent)
            } catch (e2: Exception) {
                _errorMessage.value = "No app found to open this file: ${e2.message}"
            }
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
