package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.logic.BookDownloadManager

class BooksViewModelFactory(
    private val mainViewModel: MainViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BooksViewModel::class.java)) {
            val context = mainViewModel.getApplication<android.app.Application>()
            val downloadManager = BookDownloadManager(context, mainViewModel)
            @Suppress("UNCHECKED_CAST")
            return BooksViewModel(mainViewModel.signalRClient, downloadManager, mainViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
