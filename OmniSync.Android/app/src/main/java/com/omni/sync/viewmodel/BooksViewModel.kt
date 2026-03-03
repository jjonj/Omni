package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.BookType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BookItem(
    val name: String,
    val path: String,
    val type: BookType,
    val size: Long = 0L,
    val category: String = "Unsorted"
)

sealed class LibraryState {
    object Idle : LibraryState()
    object Loading : LibraryState()
    data class Success(val books: List<BookItem>) : LibraryState()
    data class Error(val message: String) : LibraryState()
}

class BooksViewModel(private val signalRClient: SignalRClient) : ViewModel() {
    private val _libraryState = MutableStateFlow<LibraryState>(LibraryState.Idle)
    val libraryState: StateFlow<LibraryState> = _libraryState

    private val _allBooks = MutableStateFlow<List<BookItem>>(emptyList())

    fun scanLibrary(rootPath: String = "B:\\\\GDrive\\\\Books") {

        viewModelScope.launch {
            _libraryState.value = LibraryState.Loading
            try {
                // In a real implementation, we'd use a dedicated Hub method for recursive scanning.
                // For now, we'll simulate or use the existing listDirectory if adapted.
                // Since I added ScanBooksRecursive to Hub, I'll need to call it via SignalR.
                // Assuming signalRClient.scanBooks(rootPath) exists or will be added.
                
                // Mocking for the "Red" phase test
                _libraryState.value = LibraryState.Error("Not implemented yet")
            } catch (e: Exception) {
                _libraryState.value = LibraryState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
