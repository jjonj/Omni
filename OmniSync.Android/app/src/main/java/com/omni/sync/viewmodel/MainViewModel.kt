package com.omni.sync.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.omni.sync.ui.screen.LogEntry // We will define this or use the one from Dashboard
import com.omni.sync.ui.screen.LogType

enum class AppScreen {
    DASHBOARD,
    REMOTECONTROL,
    NOTEVIEWER,
    PROCESS,
    FILES
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val applicationContext: Context = application.applicationContext
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _currentScreen = MutableStateFlow(AppScreen.DASHBOARD)
    val currentScreen: StateFlow<AppScreen> = _currentScreen

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // --- Command Output ---
    private val _commandOutput = MutableStateFlow("")
    val commandOutput: StateFlow<String> = _commandOutput

    // --- Centralized Dashboard Logs ---
    private val _dashboardLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val dashboardLogs: StateFlow<List<LogEntry>> = _dashboardLogs

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
        if (connected) addLog("Hub Connected", LogType.SUCCESS)
        else addLog("Hub Disconnected", LogType.ERROR)
    }
    
    // Updated Clipboard logic if needed, omitted for brevity but keep your existing logic
    fun updateClipboardContent(content: String) { /* keep existing */ }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun setErrorMessage(message: String?) {
        _errorMessage.value = message
        if (message != null) addLog(message, LogType.ERROR)
    }

    fun appendCommandOutput(output: String) {
        // Append with newline
        _commandOutput.value += "\n$output"
    }

    fun clearCommandOutput() {
        _commandOutput.value = ""
    }
    
    // New function to add logs from anywhere
    fun addLog(message: String, type: LogType = LogType.INFO) {
        val newLog = LogEntry(message, type, System.currentTimeMillis())
        // Keep last 100 logs
        _dashboardLogs.value = (_dashboardLogs.value + newLog).takeLast(100)
    }

    fun clearLogs() {
        _dashboardLogs.value = emptyList()
    }
}
