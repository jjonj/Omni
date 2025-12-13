package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.omni.sync.data.repository.SignalRClient
import android.app.Application // New import

class FilesViewModelFactory(
    private val application: Application, // New parameter
    private val signalRClient: SignalRClient,
    private val mainViewModel: MainViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FilesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FilesViewModel(application, signalRClient, mainViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
