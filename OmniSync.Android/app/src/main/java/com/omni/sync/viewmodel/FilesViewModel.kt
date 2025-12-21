package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.model.PendingEdit
import com.omni.sync.data.model.DownloadedVideo
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import android.util.Log
import java.io.File
import android.content.Context
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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.security.MessageDigest
import java.io.FileInputStream
import java.util.UUID

class FilesViewModel(
    application: Application, // Add application to constructor
    val signalRClient: SignalRClient,
    val mainViewModel: MainViewModel // To access connection status, etc.
) : AndroidViewModel(application) {

    // Track pending offline edits (paths waiting to sync)
    private val _pendingEditPaths = MutableStateFlow<Set<String>>(emptySet())
    val pendingEditPaths: StateFlow<Set<String>> = _pendingEditPaths

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

    // --- Downloaded Videos State ---
    private val _downloadedVideos = MutableStateFlow<List<DownloadedVideo>>(emptyList())
    val downloadedVideos: StateFlow<List<DownloadedVideo>> = _downloadedVideos
    
    private val downloadedVideosPrefs = application.getSharedPreferences("downloaded_videos_prefs", Context.MODE_PRIVATE)
    // -------------------------------------

    // --- Text Editor State ---
    private val _editingFile = MutableStateFlow<FileSystemEntry?>(null)
    val editingFile: StateFlow<FileSystemEntry?> = _editingFile

    private val _editingContent = MutableStateFlow("")
    val editingContent: StateFlow<String> = _editingContent

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving
    // -------------------------

    // --- Bookmarks State ---
    private val _folderBookmarks = MutableStateFlow<List<FileSystemEntry>>(emptyList())
    val folderBookmarks: StateFlow<List<FileSystemEntry>> = _folderBookmarks
    
    private val prefs = application.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)
    private val cachePrefs = application.getSharedPreferences("files_cache_prefs", Context.MODE_PRIVATE)
    private val textCachePrefs = application.getSharedPreferences("text_cache_prefs", Context.MODE_PRIVATE)
    private val pendingEditsPrefs = application.getSharedPreferences("pending_edits_prefs", Context.MODE_PRIVATE)
    private val gson = com.google.gson.Gson()

    init {
        loadFolderBookmarks()
        loadDownloadedVideos()
        loadPendingEditPaths()
        
        // Listen for connection changes to trigger sync
        viewModelScope.launch {
            mainViewModel.isConnected.collect { connected ->
                if (connected) {
                    _errorMessage.value = null
                    _downloadErrorMessage.value = null
                    syncPendingChanges()
                }
            }
        }

        // Listen to Hub file change events to invalidate caches (no polling)
        viewModelScope.launch {
            signalRClient.fileChangeEvents.collect { (path, _) ->
                onHubFileChanged(path)
            }
        }

        // Listen for deep link navigation
        viewModelScope.launch {
            mainViewModel.pendingNavigationPath.collect { path ->
                if (path != null) {
                    loadDirectory(path)
                    mainViewModel.setPendingNavigationPath(null) // Consume it
                }
            }
        }
    }

    private fun loadFolderBookmarks() {
        val json = prefs.getString("folder_bookmarks", null)
        if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<FileSystemEntry>>() {}.type
            _folderBookmarks.value = gson.fromJson(json, type)
        }
    }

    private fun saveFolderBookmarks() {
        val json = gson.toJson(_folderBookmarks.value)
        prefs.edit().putString("folder_bookmarks", json).apply()
    }

    private fun isPathInsideAnyBookmark(path: String): Boolean {
        if (path.isEmpty()) return true // Always cache root/drives for navigation
        val normalizedPath = path.replace("\\", "/").lowercase()
        return _folderBookmarks.value.any { bookmark ->
            val bookmarkPath = bookmark.path.replace("\\", "/").lowercase()
            normalizedPath == bookmarkPath || 
            normalizedPath.startsWith(if (bookmarkPath.endsWith("/")) bookmarkPath else "$bookmarkPath/")
        }
    }

    private fun saveToCache(path: String, entries: List<FileSystemEntry>) {
        val json = gson.toJson(entries)
        cachePrefs.edit().putString("cache_$path", json).apply()
    }

    private fun getFromCache(path: String): List<FileSystemEntry>? {
        val json = cachePrefs.getString("cache_$path", null)
        if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<FileSystemEntry>>() {}.type
            return try {
                gson.fromJson(json, type)
            } catch (e: Exception) {
                null
            }
        }

        // If not explicitly cached, check if this path is a parent of any cached paths
        // This allows navigating C -> A -> B -> C if C:/A/B/C is cached
        val allCachedPaths = cachePrefs.all.keys
            .filter { it.startsWith("cache_") }
            .map { it.removePrefix("cache_") }

        val normalizedPath = path.replace("\\", "/").removeSuffix("/")
        
        // Find cached paths that are deeper than current path
        val deeperCachedPaths = allCachedPaths.filter { cachedPath ->
            val normalizedCached = cachedPath.replace("\\", "/").removeSuffix("/")
            if (normalizedPath.isEmpty()) true 
            else normalizedCached.startsWith("$normalizedPath/") && normalizedCached.length > normalizedPath.length
        }

        if (deeperCachedPaths.isNotEmpty()) {
            val entries = mutableListOf<FileSystemEntry>()
            
            // Add ".." if not at root
            if (normalizedPath.isNotEmpty()) {
                val parentPath = getParentPath(path)
                entries.add(FileSystemEntry(
                    name = "..",
                    path = parentPath,
                    isDirectory = true,
                    size = 0,
                    lastModified = java.util.Date(0)
                ))
            }

            // Synthesize immediate children that lead to cached paths
            val immediateChildren = deeperCachedPaths.map { childPath ->
                val normalizedChild = childPath.replace("\\", "/").removeSuffix("/")
                val relativeToParent = if (normalizedPath.isEmpty()) normalizedChild else normalizedChild.removePrefix("$normalizedPath/")
                val parts = relativeToParent.split("/").filter { it.isNotEmpty() }
                parts.firstOrNull() ?: ""
            }.filter { it.isNotEmpty() }.distinct()

            for (childName in immediateChildren) {
                val childPath = if (normalizedPath.isEmpty()) childName else {
                    if (normalizedPath.endsWith(":")) "$normalizedPath/$childName" // Handle C:/
                    else "$normalizedPath/$childName"
                }
                
                entries.add(FileSystemEntry(
                    name = childName,
                    path = childPath,
                    isDirectory = true,
                    size = 0,
                    lastModified = java.util.Date(0)
                ))
            }
            
            return entries.sortedWith(compareByDescending<FileSystemEntry> { it.name == ".." }.thenBy { it.name })
        }

        return null
    }

    fun getAllCachedPaths(): List<String> {
        return cachePrefs.all.keys
            .filter { it.startsWith("cache_") }
            .map { it.removePrefix("cache_") }
            .sorted()
    }

    fun uncachePath(path: String) {
        cachePrefs.edit().remove("cache_$path").apply()
    }

    fun clearAllCaches() {
        cachePrefs.edit().clear().apply()
        textCachePrefs.edit().clear().apply()
    }

    fun toggleFolderBookmark(entry: FileSystemEntry) {
        if (!entry.isDirectory) return
        
        val current = _folderBookmarks.value.toMutableList()
        val existing = current.find { it.path == entry.path }
        
        if (existing != null) {
            current.remove(existing)
        } else {
            current.add(0, entry)
        }
        
        _folderBookmarks.value = current
        saveFolderBookmarks()
    }

    fun isFolderBookmarked(path: String): Boolean {
        return _folderBookmarks.value.any { it.path == path }
    }

    fun removeFolderBookmark(entry: FileSystemEntry) {
        val current = _folderBookmarks.value.toMutableList()
        current.removeAll { it.path == entry.path }
        _folderBookmarks.value = current
        saveFolderBookmarks()
    }

    fun moveFolderBookmarkUp(entry: FileSystemEntry) {
        val current = _folderBookmarks.value.toMutableList()
        val index = current.indexOfFirst { it.path == entry.path }
        if (index > 0) {
            val item = current.removeAt(index)
            current.add(index - 1, item)
            _folderBookmarks.value = current
            saveFolderBookmarks()
        }
    }

    fun moveFolderBookmarkDown(entry: FileSystemEntry) {
        val current = _folderBookmarks.value.toMutableList()
        val index = current.indexOfFirst { it.path == entry.path }
        if (index != -1 && index < current.size - 1) {
            val item = current.removeAt(index)
            current.add(index + 1, item)
            _folderBookmarks.value = current
            saveFolderBookmarks()
        }
    }

    fun loadDirectory(path: String) {
        if (path == "" || path == "/") {
            // Root
        } else if (path == "VIRTUAL_DOWNLOADS") {
            val entries = _downloadedVideos.value.filter { !it.isEncrypted }.map { video ->
                FileSystemEntry(video.fileName, video.localPath, false, video.fileSize, video.downloadDate)
            }.toMutableList()
            // Add ".." to go back to root
            entries.add(0, FileSystemEntry("..", "", true, 0, java.util.Date(0)))
            _fileSystemEntries.value = entries
            _currentPath.value = path
            _isLoading.value = false
            return
        } else if (path == "VIRTUAL_ENCRYPTED") {
            val entries = _downloadedVideos.value.filter { it.isEncrypted }.map { video ->
                FileSystemEntry(video.fileName, video.localPath, false, video.fileSize, video.downloadDate)
            }.toMutableList()
            // Add ".." to go back to root
            entries.add(0, FileSystemEntry("..", "", true, 0, java.util.Date(0)))
            _fileSystemEntries.value = entries
            _currentPath.value = path
            _isLoading.value = false
            return
        }

        if (!mainViewModel.isConnected.value) {
            val cachedEntries = getFromCache(path)
            if (cachedEntries != null) {
                val enrichedEntries = enrichWithPendingFiles(path, cachedEntries)
                _fileSystemEntries.value = enrichedEntries
                _currentPath.value = path
                _errorMessage.value = null
                mainViewModel.addLog("Loaded directory from cache: ${if (path.isEmpty()) "(root)" else path}", com.omni.sync.ui.screen.LogType.INFO)
            } else {
                _errorMessage.value = "Not connected and directory not cached."
            }
            return
        }

        _searchQuery.value = "" // Clear search when navigating
        _isLoading.value = true
        _errorMessage.value = null
        resetDownloadState() // Reset download state when navigating
        mainViewModel.addLog("Loading directory: ${if (path.isEmpty()) "(root)" else path}", com.omni.sync.ui.screen.LogType.INFO)

        val directoryObservable = signalRClient.listDirectory(path)
        if (directoryObservable == null) {
            _isLoading.value = false
            _errorMessage.value = "Failed to initiate directory listing (not connected?)."
            mainViewModel.addLog("Failed to initiate directory listing", com.omni.sync.ui.screen.LogType.ERROR)
            return
        }

        directoryObservable
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .timeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .subscribe(
                { entries ->
                    mainViewModel.addLog("Loaded ${entries.size} entries", com.omni.sync.ui.screen.LogType.SUCCESS)
                    val enrichedEntries = enrichWithPendingFiles(path, entries)
                    _fileSystemEntries.value = enrichedEntries
                    _currentPath.value = path
                    _isLoading.value = false
                    _errorMessage.value = null

                    // Cache if it's inside a bookmark or root
                    if (isPathInsideAnyBookmark(path)) {
                        saveToCache(path, entries)
                    }
                },
                { error ->
                    _errorMessage.value = "Error loading directory: ${error.message}"
                    _isLoading.value = false
                    mainViewModel.addLog("Error loading directory: ${error.message}", com.omni.sync.ui.screen.LogType.ERROR)
                    Log.e("FilesViewModel", "Error loading directory", error)
                }
            )
    }

    private fun enrichWithPendingFiles(directoryPath: String, entries: List<FileSystemEntry>): List<FileSystemEntry> {
        val newEntries = entries.toMutableList()

        if (directoryPath.isEmpty() || directoryPath == "/") {
            // Add virtual folders
            if (newEntries.none { it.path == "VIRTUAL_DOWNLOADS" }) {
                newEntries.add(FileSystemEntry(
                    name = "Downloads:/",
                    path = "VIRTUAL_DOWNLOADS",
                    isDirectory = true,
                    size = 0,
                    lastModified = java.util.Date(0)
                ))
            }
            if (newEntries.none { it.path == "VIRTUAL_ENCRYPTED" }) {
                newEntries.add(FileSystemEntry(
                    name = "Add...",
                    path = "VIRTUAL_ENCRYPTED",
                    isDirectory = true,
                    size = 0,
                    lastModified = java.util.Date(0)
                ))
            }
        }

        val pendingPaths = _pendingEditPaths.value
        if (pendingPaths.isEmpty()) return newEntries

        val normalizedDir = directoryPath.replace("\\", "/").removeSuffix("/")
        
        for (pendingPath in pendingPaths) {
            val parent = getParentPath(pendingPath).replace("\\", "/").removeSuffix("/")
            
            if (parent == normalizedDir) {
                val fileName = pendingPath.substringAfterLast("/").substringAfterLast("\\")
                if (newEntries.none { it.name == fileName }) {
                    // It's a new file created offline
                    newEntries.add(FileSystemEntry(
                        name = fileName,
                        path = pendingPath,
                        isDirectory = false,
                        size = 0,
                        lastModified = java.util.Date()
                    ))
                }
            }
        }
        
        return newEntries.sortedWith(
            compareByDescending<FileSystemEntry> { it.name == ".." }
                .thenByDescending { it.isDirectory }
                .thenBy { it.name }
        )
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
            val cachedContent = textCachePrefs.getString("text_${entry.path}", null)
            if (cachedContent != null) {
                _editingFile.value = entry
                _editingContent.value = cachedContent
                mainViewModel.navigateTo(AppScreen.EDITOR)
                mainViewModel.addLog("Opened file from cache: ${entry.name}", com.omni.sync.ui.screen.LogType.INFO)
            } else {
                _errorMessage.value = "Not connected and file not cached."
            }
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

                val content = contentBuilder.toString()
                _editingFile.value = entry
                _editingContent.value = content
                _isLoading.value = false
                
                // Cache it
                textCachePrefs.edit().putString("text_${entry.path}", content).apply()

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
        if (_editingContent.value != newContent) {
            _editingContent.value = newContent
            _hasUnsavedChanges.value = true
        }
    }

    fun markSaved() {
        _hasUnsavedChanges.value = false
    }

    fun saveEditingContent() {
        val entry = _editingFile.value ?: return
        val content = _editingContent.value

        if (!mainViewModel.isConnected.value) {
            // Save as pending edit
            val pendingEdit = PendingEdit(
                path = entry.path,
                content = content,
                originalLastModified = entry.lastModified.time,
                isNewFile = entry.size == -1L // Marker for new files
            )
            val json = gson.toJson(pendingEdit)
            pendingEditsPrefs.edit().putString("pending_${entry.path}", json).apply()
            
            // Track pending path for UI indicator
            addPendingPath(entry.path)
            
            // Also update text cache so if we reopen it offline, we see our changes
            textCachePrefs.edit().putString("text_${entry.path}", content).apply()
            markSaved()

            mainViewModel.addLog("Saved locally (offline): ${entry.name}", com.omni.sync.ui.screen.LogType.INFO)
            mainViewModel.goBack()
            return
        }

        _isSaving.value = true
        viewModelScope.launch(Schedulers.io().asCoroutineDispatcher()) {
            try {
                signalRClient.sendPayload("SAVE_FILE", mapOf(
                    "Path" to entry.path,
                    "Content" to content
                ))
                
                // Update cache
                textCachePrefs.edit().putString("text_${entry.path}", content).apply()
                markSaved()

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

    private fun syncPendingChanges() {
        val allPending = pendingEditsPrefs.all
        if (allPending.isEmpty()) return

        viewModelScope.launch(Schedulers.io().asCoroutineDispatcher()) {
            for ((key, value) in allPending) {
                if (value !is String) continue
                val pendingEdit = try {
                    gson.fromJson(value, PendingEdit::class.java)
                } catch (e: Exception) {
                    continue
                }

                try {
                    // 1. Get current file info from Hub
                    val parentPath = getParentPath(pendingEdit.path)
                    val currentEntries = signalRClient.listDirectory(parentPath)
                        ?.subscribeOn(Schedulers.io())
                        ?.blockingGet()
                    
                    val currentEntry = currentEntries?.find { it.path == pendingEdit.path }

                    val savePath: String

                    if (pendingEdit.isNewFile) {
                        savePath = if (currentEntry != null) {
                            pendingEdit.path + ".conflicted"
                        } else {
                            pendingEdit.path
                        }
                    } else {
                        savePath = if (currentEntry == null || currentEntry.lastModified.time != pendingEdit.originalLastModified) {
                            pendingEdit.path + ".conflicted"
                        } else {
                            pendingEdit.path
                        }
                    }

                    signalRClient.sendPayload("SAVE_FILE", mapOf(
                        "Path" to savePath,
                        "Content" to pendingEdit.content
                    ))
                    
                    viewModelScope.launch(AndroidSchedulers.mainThread().asCoroutineDispatcher()) {
                        mainViewModel.addLog("Synced offline edit: ${savePath}", com.omni.sync.ui.screen.LogType.SUCCESS)
                    }

                    // Remove from pending and update indicator set
                    pendingEditsPrefs.edit().remove(key).apply()
                    removePendingPath(pendingEdit.path)

                } catch (e: Exception) {
                    viewModelScope.launch(AndroidSchedulers.mainThread().asCoroutineDispatcher()) {
                        mainViewModel.addLog("Failed to sync ${pendingEdit.path}: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                    }
                }
            }
        }
    }

    private fun loadPendingEditPaths() {
        val paths = pendingEditsPrefs.all.keys
            .filter { it.startsWith("pending_") }
            .map { it.removePrefix("pending_") }
            .toSet()
        _pendingEditPaths.value = paths
    }

    private fun addPendingPath(path: String) {
        _pendingEditPaths.value = _pendingEditPaths.value + path
    }

    private fun removePendingPath(path: String) {
        _pendingEditPaths.value = _pendingEditPaths.value - path
    }

    private val _remoteChangeDetected = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val remoteChangeDetected: kotlinx.coroutines.flow.SharedFlow<String> = _remoteChangeDetected.asSharedFlow()

    private val _recentlyChangedPaths = MutableStateFlow<Set<String>>(emptySet())
    val recentlyChangedPaths: StateFlow<Set<String>> = _recentlyChangedPaths

    private fun onHubFileChanged(path: String) {
        try {
            // Check if currently editing this file
            val currentEditing = _editingFile.value
            if (currentEditing != null && currentEditing.path == path) {
                viewModelScope.launch {
                    _remoteChangeDetected.emit(currentEditing.name)
                }
            }

            // Track for flashing in UI
            _recentlyChangedPaths.value = _recentlyChangedPaths.value + path
            viewModelScope.launch {
                delay(3000) // Flash for 3 seconds
                _recentlyChangedPaths.value = _recentlyChangedPaths.value - path
            }

            // Invalidate text cache for this exact file
            textCachePrefs.edit().remove("text_$path").apply()
            // Invalidate directory cache for its parent so next browse reloads
            val parent = getParentPath(path)
            cachePrefs.edit().remove("cache_$parent").apply()
            // If we are currently in that parent directory and connected, refresh listing
            if (mainViewModel.isConnected.value && _currentPath.value == parent) {
                loadDirectory(parent)
            }
            // If this was pending, keep the pending mark until a successful sync clears it
        } catch (_: Exception) {}
    }

    private fun getParentPath(path: String): String {
        if (path.isEmpty()) return ""
        val separator = if (path.contains("/")) "/" else "\\"
        
        // Check if it's a root drive (e.g. "C:\" or "C:/")
        if (path.length <= 3 && path.contains(":")) {
            return "" // Go to drive list (root)
        }

        val lastIndex = path.lastIndexOf(separator)
        if (lastIndex > 0) {
            val parent = path.substring(0, lastIndex)
            if (parent.endsWith(":")) {
                return parent + separator
            }
            return parent
        }
        return ""
    }

    fun createNewFile(name: String) {
        var fileName = name
        if (!fileName.contains(".")) {
            fileName += ".txt"
        }
        
        val separator = if (_currentPath.value.contains("/")) "/" else "\\"
        val fullPath = if (_currentPath.value.isEmpty()) fileName else {
            if (_currentPath.value.endsWith(separator)) _currentPath.value + fileName
            else _currentPath.value + separator + fileName
        }
        
        val newEntry = FileSystemEntry(
            name = fileName,
            path = fullPath,
            isDirectory = false,
            size = -1L, // Marker for new file
            lastModified = java.util.Date()
        )
        
        _editingFile.value = newEntry
        _editingContent.value = ""
        mainViewModel.navigateTo(AppScreen.EDITOR)
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
    
    // --- UI State Helpers ---
    private val _scrollPositions = mutableMapOf<String, Int>() // Path -> Index
    private val _scrollOffsets = mutableMapOf<String, Int>() // Path -> Offset

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges

    private val _autoSaveEnabled = MutableStateFlow(mainViewModel.appConfig.autosaveEnabled)
    val autoSaveEnabled: StateFlow<Boolean> = _autoSaveEnabled

    fun setAutoSaveEnabled(enabled: Boolean) {
        _autoSaveEnabled.value = enabled
        mainViewModel.appConfig.autosaveEnabled = enabled
        mainViewModel.saveAppConfig()
    }

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        _scrollPositions[path] = index
        _scrollOffsets[path] = offset
    }

    fun getScrollPosition(path: String): Pair<Int, Int> {
        return Pair(_scrollPositions[path] ?: 0, _scrollOffsets[path] ?: 0)
    }

    fun setGlobalPassword(oldPassword: String?, newPassword: String): Boolean {
        val currentHash = mainViewModel.appConfig.globalPasswordHash
        if (currentHash != null) {
            if (oldPassword == null || hashPassword(oldPassword) != currentHash) {
                return false
            }
        }
        mainViewModel.appConfig.globalPasswordHash = hashPassword(newPassword)
        mainViewModel.saveAppConfig()
        return true
    }

    fun verifyGlobalPassword(password: String): Boolean {
        val currentHash = mainViewModel.appConfig.globalPasswordHash ?: return true // No password set
        return hashPassword(password) == currentHash
    }

    fun isGlobalPasswordSet(): Boolean {
        return mainViewModel.appConfig.globalPasswordHash != null
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun formatBytesPerSecond(bytesPerSecond: Double): String {
        val df = DecimalFormat("#.##")
        return when {
            bytesPerSecond >= 1_000_000_000 -> "${df.format(bytesPerSecond / 1_000_000_000)} GB/s"
            bytesPerSecond >= 1_000_000 -> "${df.format(bytesPerSecond / 1_000_000)} MB/s"
            bytesPerSecond >= 1_000 -> "${df.format(bytesPerSecond / 1_000)} KB/s"
            else -> "${df.format(bytesPerSecond)} B/s"
        }
    }

    fun formatFileSize(size: Long): String {
        if (size < 0) return "" // New file
        val mb = size.toDouble() / (1024.0 * 1024.0)
        return "${mb.toInt()} MB"
    }

    // ============ Downloaded Videos Management ============
    
    private fun loadDownloadedVideos() {
        val json = downloadedVideosPrefs.getString("downloaded_videos", null)
        if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<DownloadedVideo>>() {}.type
            _downloadedVideos.value = gson.fromJson(json, type)
        }
    }

    private fun saveDownloadedVideos() {
        val json = gson.toJson(_downloadedVideos.value)
        downloadedVideosPrefs.edit().putString("downloaded_videos", json).apply()
    }

    fun deleteByPath(path: String) {
        val video = _downloadedVideos.value.find { it.localPath == path }
        if (video != null) {
            deleteDownloadedVideo(video)
        }
    }

    fun deleteAllEncrypted() {
        val encrypted = _downloadedVideos.value.filter { it.isEncrypted }
        encrypted.forEach { deleteDownloadedVideo(it) }
    }

    fun downloadVideoToAppData(entry: FileSystemEntry, isEncrypted: Boolean) {
        if (!mainViewModel.isConnected.value) {
            _downloadErrorMessage.value = "Not connected to OmniSync Hub."
            return
        }
        
        val password = if (isEncrypted) {
            // Get from settings? User wants ONE global password. 
            // We should have it cached or prompt? 
            // Design says "one global password that can be set in settings"
            // For download, we need the actual password string to derive the key.
            // We can't use the hash. We'll have to ask the user for it or store it temporarily.
            // Let's assume for now we pass it in.
            null // Placeholder
        } else null

        // Need to refactor this to take the password string from UI
    }

    fun downloadVideoWithGlobalPassword(entry: FileSystemEntry, password: String?, isEncrypted: Boolean) {
        if (!mainViewModel.isConnected.value) {
            _downloadErrorMessage.value = "Not connected to OmniSync Hub."
            return
        }
        if (entry.isDirectory) {
            _downloadErrorMessage.value = "Cannot download a directory."
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
                val videoId = UUID.randomUUID().toString()
                val videosDir = File(mainViewModel.applicationContext.filesDir, "downloaded_videos")
                if (!videosDir.exists()) {
                    videosDir.mkdirs()
                }

                val chunkSize = 64 * 1024
                var currentOffset = 0L
                val totalSize = entry.size
                var downloadedBytes = 0L
                val startTime = System.currentTimeMillis()

                val fileName = if (isEncrypted) "$videoId.encrypted" else "$videoId.${entry.name.substringAfterLast('.')}"
                val outputFile = File(videosDir, fileName)
                
                val fos = FileOutputStream(outputFile)
                val cipher = if (isEncrypted && password != null) {
                    val key = deriveKeyFromPassword(password)
                    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        val iv = ByteArray(16)
                        SecureRandom().nextBytes(iv)
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        fos.write(iv) // Write IV at the beginning of file
                    }
                } else null

                try {
                    while (currentOffset < totalSize) {
                        val remainingBytes = totalSize - currentOffset
                        val currentChunkSize = minOf(chunkSize.toLong(), remainingBytes).toInt()

                        if (currentChunkSize == 0) break

                        signalRClient.getFileChunk(entry.path, currentOffset, currentChunkSize)
                            ?.subscribeOn(Schedulers.io())
                            ?.blockingGet()
                            ?.let { chunk ->
                                val bytes = chunk as ByteArray
                                val processedBytes = if (cipher != null) {
                                    cipher.update(bytes)
                                } else bytes
                                
                                fos.write(processedBytes)
                                downloadedBytes += bytes.size
                                currentOffset += bytes.size

                                val progress = ((downloadedBytes * 100) / totalSize).toInt()
                                _downloadProgress.value = progress

                                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                                if (elapsedSeconds > 0) {
                                    val speedBps = downloadedBytes / elapsedSeconds
                                    _downloadingSpeed.value = formatBytesPerSecond(speedBps)
                                }
                            } ?: throw Exception("Failed to get file chunk.")
                    }
                    
                    if (cipher != null) {
                        val finalBytes = cipher.doFinal()
                        fos.write(finalBytes)
                    }
                } finally {
                    fos.close()
                }

                val downloadedVideo = DownloadedVideo(
                    id = videoId,
                    originalPath = entry.path,
                    fileName = entry.name,
                    localPath = outputFile.absolutePath,
                    fileSize = entry.size,
                    downloadDate = java.util.Date(),
                    isEncrypted = isEncrypted
                )

                val updatedList = _downloadedVideos.value.toMutableList()
                updatedList.add(downloadedVideo)
                _downloadedVideos.value = updatedList
                saveDownloadedVideos()

                _downloadErrorMessage.value = null
                mainViewModel.addLog("Video downloaded: ${entry.name}", com.omni.sync.ui.screen.LogType.SUCCESS)
            } catch (e: Exception) {
                _downloadErrorMessage.value = "Download failed: ${e.message}"
                Log.e("FilesViewModel", "Video download error", e)
            } finally {
                _isDownloading.value = false
                _downloadingFile.value = null
                _downloadProgress.value = 0
                _downloadingSpeed.value = null
            }
        }
    }

    fun deleteDownloadedVideo(video: DownloadedVideo) {
        try {
            val file = File(video.localPath)
            if (file.exists()) {
                file.delete()
            }

            val updatedList = _downloadedVideos.value.toMutableList()
            updatedList.remove(video)
            _downloadedVideos.value = updatedList
            saveDownloadedVideos()

            mainViewModel.addLog("Deleted video: ${video.fileName}", com.omni.sync.ui.screen.LogType.INFO)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to delete video: ${e.message}"
            Log.e("FilesViewModel", "Delete video error", e)
        }
    }

    fun playDownloadedVideo(video: DownloadedVideo, password: String? = null) {
        if (video.isEncrypted && password == null) {
            _errorMessage.value = "Password required to play encrypted video."
            return
        }

        viewModelScope.launch(Schedulers.io().asCoroutineDispatcher()) {
            try {
                val file = File(video.localPath)
                if (!file.exists()) {
                    _errorMessage.value = "Video file not found."
                    return@launch
                }

                val playableFile = if (video.isEncrypted && password != null) {
                    val decryptedFile = File(mainViewModel.applicationContext.cacheDir, "temp_${video.id}.${video.fileName.substringAfterLast('.')}")
                    decryptVideo(file, decryptedFile, password)
                    decryptedFile
                } else {
                    file
                }

                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    getApplication<Application>().packageName + ".fileprovider",
                    playableFile
                )

                viewModelScope.launch(AndroidSchedulers.mainThread().asCoroutineDispatcher()) {
                    mainViewModel.playVideo(uri.toString(), emptyList())
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to play video: ${e.message}"
                Log.e("FilesViewModel", "Play video error", e)
            }
        }
    }

    fun setGlobalPassword(oldPassword: String?, newPassword: String): Boolean {
        val currentHash = mainViewModel.appConfig.globalPasswordHash
        if (currentHash != null) {
            if (oldPassword == null || hashPassword(oldPassword) != currentHash) {
                return false
            }
        }
        mainViewModel.appConfig.globalPasswordHash = hashPassword(newPassword)
        mainViewModel.saveAppConfig()
        return true
    }

    fun verifyGlobalPassword(password: String): Boolean {
        val currentHash = mainViewModel.appConfig.globalPasswordHash ?: return true // No password set
        return hashPassword(password) == currentHash
    }

    fun isGlobalPasswordSet(): Boolean {
        return mainViewModel.appConfig.globalPasswordHash != null
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun deriveKeyFromPassword(password: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(password.toByteArray(Charsets.UTF_8))
    }

    private fun decryptVideo(encryptedFile: File, decryptedFile: File, password: String) {
        val key = deriveKeyFromPassword(password)
        val fis = FileInputStream(encryptedFile)
        val fos = FileOutputStream(decryptedFile)

        try {
            val iv = ByteArray(16)
            fis.read(iv)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                val decrypted = cipher.update(buffer, 0, bytesRead)
                if (decrypted != null) {
                    fos.write(decrypted)
                }
            }

            val finalBytes = cipher.doFinal()
            if (finalBytes != null) {
                fos.write(finalBytes)
            }
        } finally {
            fis.close()
            fos.close()
        }
    }
}
