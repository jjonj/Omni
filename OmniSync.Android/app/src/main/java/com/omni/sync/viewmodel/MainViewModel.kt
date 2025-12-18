package com.omni.sync.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.omni.sync.ui.screen.LogEntry // We will define this or use the one from Dashboard
import com.omni.sync.ui.screen.LogType
import java.lang.Exception
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.content.Intent
import android.net.Uri

enum class AppScreen {
    DASHBOARD,
    REMOTECONTROL,
    BROWSER,
    PROCESS,
    FILES,
    VIDEOPLAYER,
    EDITOR,
    SETTINGS
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

    private val _scheduledShutdownTime = MutableStateFlow<String?>(null)
    val scheduledShutdownTime: StateFlow<String?> = _scheduledShutdownTime

    // --- Command Output ---
    private val _commandOutput = MutableStateFlow("")
    val commandOutput: StateFlow<String> = _commandOutput

    // --- Centralized Dashboard Logs ---
    private val _dashboardLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val dashboardLogs: StateFlow<List<LogEntry>> = _dashboardLogs

    // Add state to hold the current video to play and playlist
    private val _currentVideoUrl = MutableStateFlow<String?>(null)
    val currentVideoUrl: StateFlow<String?> = _currentVideoUrl
    
    private val _videoPlaylist = MutableStateFlow<List<String>>(emptyList())
    val videoPlaylist: StateFlow<List<String>> = _videoPlaylist
    
    private val _currentVideoIndex = MutableStateFlow(0)
    val currentVideoIndex: StateFlow<Int> = _currentVideoIndex

    // Back Navigation Logic
    private val backStack = mutableListOf<AppScreen>()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack
    
    private var lastDashboardBackPressTime = 0L

    // Helper to extract the base URL (http://10.0.0.37:5000) from the specific Hub URL
    fun getBaseUrl(): String {
        return "http://10.0.0.37:5000" 
    }

    fun playVideo(remotePath: String, playlist: List<String> = emptyList()) {
        val prefs = applicationContext.getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        val isRandom = prefs.getBoolean("video_playlist_random", false)
        
        val baseUrl = getBaseUrl()
        
        var finalPlaylist = playlist
        if (isRandom && finalPlaylist.isNotEmpty()) {
            val otherVideos = finalPlaylist.filter { it != remotePath }.shuffled()
            finalPlaylist = listOf(remotePath) + otherVideos
        }

        val playlistUrls = finalPlaylist.map { path ->
            val encoded = java.net.URLEncoder.encode(path, "UTF-8")
            "$baseUrl/api/stream?path=$encoded"
        }
        
        val encodedPath = java.net.URLEncoder.encode(remotePath, "UTF-8")
        val currentUrl = "$baseUrl/api/stream?path=$encodedPath"
        
        _videoPlaylist.value = playlistUrls
        _currentVideoIndex.value = if (playlistUrls.contains(currentUrl)) playlistUrls.indexOf(currentUrl) else 0
        _currentVideoUrl.value = currentUrl
        
        navigateTo(AppScreen.VIDEOPLAYER)
    }

    fun openUrlOnPhone(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            addLog("Opened URL: $url", LogType.SUCCESS)
        } catch (e: Exception) {
            addLog("Failed to open URL: ${e.message}", LogType.ERROR)
        }
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

    fun setScheduledShutdownTime(time: String?) {
        _scheduledShutdownTime.value = time
    }
    
    // Updated Clipboard logic if needed, omitted for brevity but keep your existing logic
    fun updateClipboardContent(content: String) { /* keep existing */ }

    fun navigateTo(screen: AppScreen) {
        if (_currentScreen.value == screen) return

        // Push current screen to backstack
        backStack.add(_currentScreen.value)
        
        // Keep only the last 3 screens in history
        if (backStack.size > 3) {
            backStack.removeAt(0)
        }
        
        _currentScreen.value = screen
        _canGoBack.value = true // We can always try to go back now
    }

    fun handleBackPress(exitApp: () -> Unit) {
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.lastIndex)
            _currentScreen.value = previous
            _canGoBack.value = true // Still true because we can always go back to dashboard eventually
        } else if (_currentScreen.value != AppScreen.DASHBOARD) {
            // If no history and not on dashboard, go to dashboard
            _currentScreen.value = AppScreen.DASHBOARD
            _canGoBack.value = true
        } else {
            // On dashboard with no history, check for double press to exit
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastDashboardBackPressTime < 2000) {
                exitApp()
            } else {
                lastDashboardBackPressTime = currentTime
                addLog("Press back again to exit", LogType.INFO)
            }
        }
    }

    fun goBack(): Boolean {
        // This is still used by some UI components to navigate back without exiting the app
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.lastIndex)
            _currentScreen.value = previous
            return true
        } else if (_currentScreen.value != AppScreen.DASHBOARD) {
            _currentScreen.value = AppScreen.DASHBOARD
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

    fun sendWakeOnLan(macAddress: String, broadcastIp: String = "10.0.0.255", port: Int = 9) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("Sending WOL to $macAddress...", LogType.INFO)
                val macBytes = getMacBytes(macAddress)
                val bytes = ByteArray(6 + 16 * macBytes.size)
                for (i in 0 until 6) {
                    bytes[i] = 0xff.toByte()
                }
                for (i in 6 until bytes.size step macBytes.size) {
                    System.arraycopy(macBytes, 0, bytes, i, macBytes.size)
                }

                val address = InetAddress.getByName(broadcastIp)
                val packet = DatagramPacket(bytes, bytes.size, address, port)
                val socket = DatagramSocket()
                socket.send(packet)
                socket.close()
                addLog("WOL packet sent!", LogType.SUCCESS)
            } catch (e: Exception) {
                addLog("Failed to send WOL: ${e.message}", LogType.ERROR)
            }
        }
    }

    private fun getMacBytes(macString: String): ByteArray {
        val bytes = ByteArray(6)
        val hex = macString.replace("[:\\-]".toRegex(), "")
        for (i in 0 until 6) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
