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
    BROWSER,
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

    // Modifier Key States
    private val _isShiftPressed = MutableStateFlow(false)
    val isShiftPressed: StateFlow<Boolean> = _isShiftPressed

    private val _isCtrlPressed = MutableStateFlow(false)
    val isCtrlPressed: StateFlow<Boolean> = _isCtrlPressed

    private val _isAltPressed = MutableStateFlow(false)
    val isAltPressed: StateFlow<Boolean> = _isAltPressed

    // --- Command Output ---
    private val _commandOutput = MutableStateFlow("")
    val commandOutput: StateFlow<String> = _commandOutput

    // --- Centralized Dashboard Logs ---
    private val _dashboardLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val dashboardLogs: StateFlow<List<LogEntry>> = _dashboardLogs

    // Add state to hold the current video to play
    private val _currentVideoUrl = MutableStateFlow<String?>(null)
    val currentVideoUrl: StateFlow<String?> = _currentVideoUrl

    // Back Navigation Logic
    private val backStack = mutableListOf<AppScreen>()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

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
    
    fun setShiftPressed(isPressed: Boolean) {
        _isShiftPressed.value = isPressed
    }

    fun setCtrlPressed(isPressed: Boolean) {
        _isCtrlPressed.value = isPressed
    }

    fun setAltPressed(isPressed: Boolean) {
        _isAltPressed.value = isPressed
    }
    
    // Updated Clipboard logic if needed, omitted for brevity but keep your existing logic
    fun updateClipboardContent(content: String) { /* keep existing */ }

    fun navigateTo(screen: AppScreen) {
        val isMainTab = screen == AppScreen.DASHBOARD || 
                        screen == AppScreen.REMOTECONTROL || 
                        screen == AppScreen.BROWSER || 
                        screen == AppScreen.PROCESS || 
                        screen == AppScreen.FILES

        if (isMainTab) {
            backStack.clear()
        } else {
            // Push current screen before navigating away
            backStack.add(_currentScreen.value)
        }
        
        _currentScreen.value = screen
        _canGoBack.value = backStack.isNotEmpty()
    }

    fun goBack(): Boolean {
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.lastIndex)
            _currentScreen.value = previous
            _canGoBack.value = backStack.isNotEmpty()
            return true
        }
        return false
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
