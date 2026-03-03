package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.omni.sync.data.repository.SignalRClient

class BooksViewModelFactory(private val signalRClient: SignalRClient) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BooksViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BooksViewModel(signalRClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
