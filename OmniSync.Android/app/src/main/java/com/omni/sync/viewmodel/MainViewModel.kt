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
    FILES,
    VIDEOPLAYER
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

    // Add state to hold the current video to play
    private val _currentVideoUrl = MutableStateFlow<String?>(null)
    val currentVideoUrl: StateFlow<String?> = _currentVideoUrl

    // Helper to extract the base URL (http://10.0.0.37:5000) from the specific Hub URL
    fun getBaseUrl(): String {
        return "http://10.0.0.37:5000" 
    }

    fun playVideo(remotePath: String) {
        // Construct a direct HTTP URL. 
        // We must encode the path to ensure spaces and slashes are handled correctly in the query string.
        val encodedPath = java.net.URLEncoder.encode(remotePath, "UTF-8")
        val url = "${getBaseUrl()}/api/stream?path=$encodedPath"
        
        _currentVideoUrl.value = url
        navigateTo(AppScreen.VIDEOPLAYER)
    }

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
