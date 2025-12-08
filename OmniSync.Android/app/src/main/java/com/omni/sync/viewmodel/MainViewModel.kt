package com.omni.sync.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppScreen {
    DASHBOARD,
    TRACKPAD,
    NOTEVIEWER,
    PROCESS
}

class MainViewModel : ViewModel() {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _currentClipboardContent = MutableStateFlow("")
    val currentClipboardContent: StateFlow<String> = _currentClipboardContent

    private val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _commandOutput = MutableStateFlow("")
    val commandOutput: StateFlow<String> = _commandOutput

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }

    fun updateClipboardContent(content: String) {
        _currentClipboardContent.value = content
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun setErrorMessage(message: String?) {
        _errorMessage.value = message
    }

    fun appendCommandOutput(output: String) {
        _commandOutput.value += output
    }

    fun clearCommandOutput() {
        _commandOutput.value = ""
    }
}
