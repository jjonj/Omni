package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.BookType
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.net.Uri
import java.io.File
import androidx.media3.common.MediaItem

data class BookItem(
    val name: String,
    val path: String,
    val type: BookType,
    val size: Long = 0L,
    val category: String = "Unsorted",
    val isFolder: Boolean = false,
    val coverPath: String? = null
)

data class AudioTrackSpec(
    val name: String,
    val path: String
)

sealed class BookSource {
    data class SingleTrack(val track: AudioTrackSpec) : BookSource()
    data class MultiTrack(val tracks: List<AudioTrackSpec>) : BookSource()
}

sealed class LibraryState {
    object Idle : LibraryState()
    object Loading : LibraryState()
    data class Success(val books: List<BookItem>) : LibraryState()
    data class Error(val message: String) : LibraryState()
}

class BooksViewModel(
    private val signalRClient: SignalRClient,
    val downloadManager: com.omni.sync.logic.BookDownloadManager,
    private val mainViewModel: MainViewModel
) : ViewModel() {
    private val progressPrefs = mainViewModel.applicationContext.getSharedPreferences("book_progress_local", android.content.Context.MODE_PRIVATE)
    private val folderTracksPrefs = mainViewModel.applicationContext.getSharedPreferences("book_folder_tracks", android.content.Context.MODE_PRIVATE)
    private val libraryPrefs = mainViewModel.applicationContext.getSharedPreferences("book_library_cache", android.content.Context.MODE_PRIVATE)
    private val readerSettingsManager = com.omni.sync.utils.ReaderSettingsManager(mainViewModel.applicationContext)
    private val gson = com.google.gson.Gson()

    private val _readerTheme = MutableStateFlow(readerSettingsManager.getTheme())
    val readerTheme: StateFlow<com.omni.sync.utils.ReaderTheme> = _readerTheme

    fun updateTheme(theme: com.omni.sync.utils.ReaderTheme) {
        _readerTheme.value = theme
        readerSettingsManager.saveTheme(theme)
    }

    private val _folderTracks = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val _resolvedAudioTracks = MutableStateFlow<Map<String, List<AudioTrackSpec>>>(emptyMap())

    private val _libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
    val libraryState: StateFlow<LibraryState> = _libraryState

    private val _allBooks = MutableStateFlow<List<BookItem>>(emptyList())
    val allBooks: StateFlow<List<BookItem>> = _allBooks

    private val _inProgressBooks = MutableStateFlow<List<BookItem>>(emptyList())
    val inProgressBooks: StateFlow<List<BookItem>> = _inProgressBooks

    val downloadStatuses = downloadManager.downloadStatuses
    val downloadedFiles = downloadManager.downloadedFiles

    init {
        // Restore folder track cache
        val restored = mutableMapOf<String, List<String>>()
        folderTracksPrefs.all.forEach { (key, value) ->
            if (!key.startsWith("folder::")) return@forEach
            val folderPath = key.removePrefix("folder::")
            @Suppress("UNCHECKED_CAST")
            val tracks = value as? Set<String>
            if (!tracks.isNullOrEmpty()) {
                restored[folderPath] = tracks.toList()
            }
        }
        if (restored.isNotEmpty()) {
            _folderTracks.value = restored
            log("Restored folder track cache for ${restored.size} audiobook folders")
        }

        // Restore cached library
        val cachedLibraryJson = libraryPrefs.getString("library_json", null)
        if (!cachedLibraryJson.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<BookItem>>() {}.type
                val cachedBooks: List<BookItem> = gson.fromJson(cachedLibraryJson, type)
                _allBooks.value = cachedBooks
                _libraryState.value = LibraryState.Success(cachedBooks)
                log("Restored ${cachedBooks.size} books from local cache")
                refreshInProgressBooks()
            } catch (e: Exception) {
                log("Failed restoring cached library: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
            }
        }
    }

    fun log(message: String, type: com.omni.sync.ui.screen.LogType = com.omni.sync.ui.screen.LogType.INFO) {
        mainViewModel.addLog("[Books] $message", type)
    }

    private fun cacheLibrary(books: List<BookItem>) {
        try {
            val json = gson.toJson(books)
            libraryPrefs.edit().putString("library_json", json).apply()
        } catch (e: Exception) {
            log("Failed to cache library: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
        }
    }

    private fun cacheFolderTracks(folderPath: String, trackPaths: List<String>) {
        if (trackPaths.isEmpty()) return
        _folderTracks.value = _folderTracks.value + (folderPath to trackPaths)
        folderTracksPrefs.edit().putStringSet("folder::$folderPath", trackPaths.toSet()).apply()
    }

    fun downloadBook(book: BookItem) {
        if (book.type == BookType.AUDIOBOOK) {
            resolveBookSource(book) { source ->
                when (source) {
                    is BookSource.SingleTrack -> {
                        log("Queueing audiobook track: ${source.track.name}")
                        val entry = toEntry(source.track.name, source.track.path, false)
                        downloadManager.downloadBook(entry)
                    }
                    is BookSource.MultiTrack -> {
                        log("Queueing audiobook folder: ${book.name} (${source.tracks.size} tracks)")
                        cacheFolderTracks(book.path, source.tracks.map { it.path })
                        source.tracks.forEach { track ->
                            downloadManager.downloadBook(toEntry(track.name, track.path, false))
                        }
                    }
                    null -> log("Audiobook download failed: unable to resolve source", com.omni.sync.ui.screen.LogType.ERROR)
                }
            }
            return
        }
        log("Queueing download: ${book.name}")
        val entry = toEntry(book.name, book.path, book.isFolder, book.size)
        downloadManager.downloadBook(entry)
    }

    private fun toEntry(name: String, path: String, isFolder: Boolean, size: Long = 0L): com.omni.sync.data.model.FileSystemEntry {
        return com.omni.sync.data.model.FileSystemEntry(
            name = name,
            path = path,
            isDirectory = isFolder,
            size = size,
            lastModified = java.util.Date(),
            entryType = if (isFolder) "AudiobookFolder" else "File"
        )
    }

    fun isDownloaded(book: BookItem): Boolean {
        if (!book.isFolder) return downloadManager.isDownloaded(book.path)
        val tracks = _folderTracks.value[book.path] ?: return false
        return tracks.isNotEmpty() && tracks.all { downloadManager.isDownloaded(it) }
    }

    fun isDownloading(book: BookItem): Boolean {
        if (!book.isFolder) {
            val status = downloadStatuses.value[book.path]
            return status != null && !status.isCompleted && status.error == null
        }
        val tracks = _folderTracks.value[book.path] ?: return false
        if (tracks.isEmpty()) return false
        val statuses = downloadStatuses.value
        val anyActive = tracks.any { path ->
            val status = statuses[path]
            status != null && !status.isCompleted && status.error == null
        }
        return anyActive && !isDownloaded(book)
    }

    fun onDownloadCompleted(id: Long) {
        log("Download callback received: $id", com.omni.sync.ui.screen.LogType.SUCCESS)
        downloadManager.onDownloadComplete(id)
        // Refresh library state to update icons
        val current = _libraryState.value
        if (current is LibraryState.Success) {
            _libraryState.value = LibraryState.Success(current.books) // Trigger state update
        }
    }

    fun addToWishlist(text: String) {
        signalRClient.appendToFile("B:\\GDrive\\Books\\wishlist.txt", text)
        log("Added to wishlist: $text")
    }

    fun saveProgress(path: String, position: String) {
        progressPrefs.edit().putString(path, position).apply()
        signalRClient.saveBookProgress(path, position)
        refreshInProgressBooks()
    }

    private fun refreshInProgressBooks() {
        val all = _allBooks.value
        if (all.isEmpty()) return
        
        _inProgressBooks.value = all.filter { book ->
            val progress = progressPrefs.getString(book.path, null)
            !progress.isNullOrBlank() && progress != "0" && progress != "0|0"
        }.sortedByDescending { book ->
            // Try to sort by last modified or access time if we had it, for now just presence
            book.name 
        }
    }

    fun getProgress(path: String, onResult: (String?) -> Unit) {
        val request = signalRClient.getBookProgress(path)
        if (request == null) {
            onResult(progressPrefs.getString(path, null))
            return
        }
        request
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ progress ->
                progressPrefs.edit().putString(path, progress.position).apply()
                onResult(progress.position)
            }, {
                onResult(progressPrefs.getString(path, null))
            })
    }

    fun getCachedProgress(path: String): String? = progressPrefs.getString(path, null)

    private fun toStreamUri(path: String): Uri {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        return Uri.parse("${mainViewModel.getBaseUrl()}/api/stream?path=$encoded")
    }

    private fun resolveBookSource(book: BookItem, onResult: (BookSource?) -> Unit) {
        if (!book.isFolder) {
            val track = AudioTrackSpec(book.name, book.path)
            _resolvedAudioTracks.value = _resolvedAudioTracks.value + (book.path to listOf(track))
            log("Resolved single-track source: ${track.name} -> ${track.path}")
            onResult(BookSource.SingleTrack(track))
            return
        }

        val request = signalRClient.listDirectory(book.path)
        if (request == null) {
            log("Cannot resolve folder source: listDirectory unavailable (${book.path})", com.omni.sync.ui.screen.LogType.ERROR)
            onResult(null)
            return
        }

        request
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ entries ->
                val tracks = entries
                    .filter { !it.isDirectory && (com.omni.sync.utils.isAudioFile(it.name) || com.omni.sync.utils.isAudiobookFile(it.name)) }
                    .sortedBy { it.name.lowercase() }
                    .map { AudioTrackSpec(it.name, it.path) }
                log("Resolved source for ${book.name}: ${tracks.size} tracks")
                tracks.take(5).forEachIndexed { i, t ->
                    log("Track[$i] ${t.name} -> ${t.path}")
                }
                _resolvedAudioTracks.value = _resolvedAudioTracks.value + (book.path to tracks)
                cacheFolderTracks(book.path, tracks.map { it.path })
                onResult(BookSource.MultiTrack(tracks))
            }, {
                log("Failed resolving source for ${book.name}: ${it.message}", com.omni.sync.ui.screen.LogType.ERROR)
                onResult(null)
            })
    }

    fun resolveAudiobookMediaItems(book: BookItem, onResult: (List<MediaItem>) -> Unit) {
        resolveBookSource(book) { source ->
            when (source) {
                is BookSource.SingleTrack -> {
                    val localPath = downloadManager.getLocalPath(source.track.path)
                    val uri = if (localPath != null) Uri.fromFile(File(localPath)) else toStreamUri(source.track.path)
                    log("MediaItem single: local=${localPath != null}, uri=$uri")
                    onResult(listOf(MediaItem.fromUri(uri)))
                }
                is BookSource.MultiTrack -> {
                    val items = source.tracks.map { track ->
                        val localPath = downloadManager.getLocalPath(track.path)
                        val uri = if (localPath != null) Uri.fromFile(File(localPath)) else toStreamUri(track.path)
                        log("MediaItem track: ${track.name}, local=${localPath != null}, uri=$uri")
                        MediaItem.fromUri(uri)
                    }
                    onResult(items)
                }
                null -> onResult(emptyList())
            }
        }
    }

    fun deleteBook(book: BookItem) {
        val request = signalRClient.deleteFile(book.path)
        request?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ success ->
                if (success) {
                    log("Deleted ${book.name} from remote")
                    scanLibrary() // Refresh full list
                } else {
                    log("Failed to delete ${book.name} from remote", com.omni.sync.ui.screen.LogType.ERROR)
                }
            }, {
                log("Error deleting ${book.name}: ${it.message}", com.omni.sync.ui.screen.LogType.ERROR)
            })
    }

    fun moveBook(book: BookItem, targetDir: String) {
        val fileName = book.path.substringAfterLast('\\').substringAfterLast('/')
        val destPath = if (targetDir.endsWith('\\') || targetDir.endsWith('/')) targetDir + fileName else targetDir + File.separator + fileName
        
        val request = signalRClient.moveFile(book.path, destPath)
        request?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ success ->
                if (success) {
                    log("Moved ${book.name} to $targetDir")
                    scanLibrary()
                } else {
                    log("Failed to move ${book.name} to $targetDir", com.omni.sync.ui.screen.LogType.ERROR)
                }
            }, {
                log("Error moving ${book.name}: ${it.message}", com.omni.sync.ui.screen.LogType.ERROR)
            })
    }

    fun hideBook(book: BookItem) {
        // Simple local hide for now (not persisted on Hub yet)
        _allBooks.value = _allBooks.value.filter { it.path != book.path }
        refreshInProgressBooks()
        log("Hid ${book.name} locally")
    }

    fun scanLibrary(rootPath: String = "B:\\GDrive\\Books") {
        _libraryState.value = LibraryState.Loading

        signalRClient.scanBooks(rootPath)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ entries ->
                val books = entries.map { entry ->
                    // Extract category from path: B:\GDrive\Books\[Type]\[Category]\[Title]
                    val relPath = entry.path.removePrefix(rootPath).trimStart('\\', '/')
                    val parts = relPath.split('\\', '/')

                    val category = if (parts.size >= 2) parts[1] else "Unsorted"

                    BookItem(
                        name = entry.name,
                        path = entry.path,
                        type = if (entry.entryType == "AudiobookFolder") BookType.AUDIOBOOK else com.omni.sync.utils.getBookType(entry.name),
                        size = entry.size,
                        category = category,
                        isFolder = entry.entryType == "AudiobookFolder",
                        coverPath = entry.description
                    )
                }
                _allBooks.value = books
                _libraryState.value = LibraryState.Success(books)
                cacheLibrary(books)
                refreshInProgressBooks()
            }, { error ->
                _libraryState.value = LibraryState.Error(error.message ?: "Unknown error")
            })
    }
}

