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
import java.io.File

data class BookItem(
    val name: String,
    val path: String,
    val type: BookType,
    val size: Long = 0L,
    val category: String = "Unsorted",
    val isFolder: Boolean = false
)

sealed class LibraryState {
    object Idle : LibraryState()
    object Loading : LibraryState()
    data class Success(val books: List<BookItem>) : LibraryState()
    data class Error(val message: String) : LibraryState()
}

class BooksViewModel(
    private val signalRClient: SignalRClient,
    val downloadManager: com.omni.sync.logic.BookDownloadManager
) : ViewModel() {
    private val _libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
    val libraryState: StateFlow<LibraryState> = _libraryState

    private val _allBooks = MutableStateFlow<List<BookItem>>(emptyList())
    val allBooks: StateFlow<List<BookItem>> = _allBooks

    val downloadStatuses = downloadManager.downloadStatuses

    fun downloadBook(book: BookItem) {
        val entry = com.omni.sync.data.model.FileSystemEntry(
            name = book.name,
            path = book.path,
            isDirectory = book.isFolder,
            size = book.size,
            lastModified = java.util.Date(),
            entryType = if (book.isFolder) "AudiobookFolder" else "File"
        )
        downloadManager.downloadBook(entry)
    }

    fun isDownloaded(book: BookItem): Boolean {
        return downloadManager.isDownloaded(book.path)
    }

    fun scanLibrary(rootPath: String = "B:\\\\GDrive\\\\Books") {
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
                        isFolder = entry.entryType == "AudiobookFolder"
                    )
                }
                _allBooks.value = books
                _libraryState.value = LibraryState.Success(books)
            }, { error ->
                _libraryState.value = LibraryState.Error(error.message ?: "Unknown error")
            })
    }
}

