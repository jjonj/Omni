package com.omni.sync.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.omni.sync.ui.screen.LogEntry
 // We will define this or use the one from Dashboard
import com.omni.sync.ui.screen.LogType
import java.lang.Exception
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.omni.sync.R
import com.omni.sync.logic.SleepTracker

enum class AppScreen {
    DASHBOARD,
    REMOTECONTROL,
    BROWSER,
    PROCESS,
    FILES,
    VIDEOPLAYER,
    EDITOR,
    SETTINGS,
    AI_CHAT,
    DOWNLOADED_VIDEOS,
    ALARM,
    IMAGE_VIEWER,
    MACRO_MANAGER,
    WEB_SERVER,
    BOOKS,
    PDF_VIEWER,
    EPUB_VIEWER
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val applicationContext: Context = application.applicationContext
    val configManager = com.omni.sync.data.config.ConfigManager(applicationContext)
    private val _appConfig = MutableStateFlow(configManager.loadConfig())
    val appConfig: StateFlow<com.omni.sync.data.config.AppConfig> = _appConfig
    
    // Injected manually from Application onCreate
    lateinit var signalRClient: com.omni.sync.data.repository.SignalRClient

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

    private val _isWinPressed = MutableStateFlow(false)
    val isWinPressed: StateFlow<Boolean> = _isWinPressed

    private val _scheduledShutdownTime = MutableStateFlow<String?>(null)
    val scheduledShutdownTime: StateFlow<String?> = _scheduledShutdownTime

    private val _shutdownMode = MutableStateFlow("Shutdown")
    val shutdownMode: StateFlow<String> = _shutdownMode

    // --- Command Output ---
    private val _commandOutput = MutableStateFlow("")
    val commandOutput: StateFlow<String> = _commandOutput

    // --- Centralized Dashboard Logs ---
    private val _dashboardLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val dashboardLogs: StateFlow<List<LogEntry>> = _dashboardLogs

    // --- Hub Logs ---
    private val _hubLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val hubLogs: StateFlow<List<LogEntry>> = _hubLogs
    
    private val _isShowingHubLogs = MutableStateFlow(false)
    val isShowingHubLogs: StateFlow<Boolean> = _isShowingHubLogs

    fun toggleLogSource() {
        _isShowingHubLogs.value = !_isShowingHubLogs.value
    }

    fun updateHubLogs(logs: List<String>) {
         // Hub logs already come formatted with timestamps, so we just treat them as INFO
         // In a real scenario, we could parse the timestamp from the string
         _hubLogs.value = logs.map { LogEntry(it, LogType.INFO, System.currentTimeMillis()) }
    }

    fun fetchHubLogs(signalRClient: com.omni.sync.data.repository.SignalRClient) {
        viewModelScope.launch {
            try {
                addLog("Fetching logs from Hub...", LogType.INFO)
                signalRClient.getHubLog()
                    ?.subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                    ?.observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                    ?.subscribe({ logs ->
                        updateHubLogs(logs)
                        addLog("Fetched ${logs.size} log entries from Hub.", LogType.SUCCESS)
                    }, { error ->
                        addLog("Failed to fetch Hub logs: ${error.message}", LogType.ERROR)
                    })
            } catch (e: Exception) {
                addLog("Error initiating log fetch: ${e.message}", LogType.ERROR)
            }
        }
    }

    private val _activeBaseUrl = MutableStateFlow("")
    val activeBaseUrl: StateFlow<String> = _activeBaseUrl

    val sleepTracker = SleepTracker(application)
    private val _sleepDuration = MutableStateFlow(sleepTracker.getFormattedSleepDuration())
    val sleepDuration: StateFlow<String> = _sleepDuration
    
    private val _isSleeping = MutableStateFlow(sleepTracker.isSleeping())
    val isSleeping: StateFlow<Boolean> = _isSleeping

    init {
        viewModelScope.launch {
            while (true) {
                _sleepDuration.value = sleepTracker.getFormattedSleepDuration()
                _isSleeping.value = sleepTracker.isSleeping()
                delay(60000) // Update every minute
            }
        }
    }

    fun recordActivity() {
        sleepTracker.recordActivity()
        // We no longer automatically reset sleep on every activity record.
        // Sleep is only reset via resetSleep() which is called by the "Woke up" button
        // or potentially a manual connection to the Hub if we want that.
    }

    fun recordUserActivity() {
        sleepTracker.recordActivity()
        if (sleepTracker.isSleeping()) {
            // Only reset if we are sure it's a manual user action
            resetSleep()
        }
    }

    fun startSleep() {
        sleepTracker.recordPotentialSleepStart()
        _isSleeping.value = true
        _sleepDuration.value = sleepTracker.getFormattedSleepDuration()
    }

    fun resetSleep() {
        sleepTracker.resetSleep()
        _isSleeping.value = false
        _sleepDuration.value = sleepTracker.getFormattedSleepDuration()
    }

    fun setActiveBaseUrl(url: String) {
        _activeBaseUrl.value = url
    }

    // Add state to hold the current video to play and playlist
    private val _currentVideoUrl = MutableStateFlow<String?>(null)
    val currentVideoUrl: StateFlow<String?> = _currentVideoUrl
    
    private val _videoPlaylist = MutableStateFlow<List<String>>(emptyList())
    val videoPlaylist: StateFlow<List<String>> = _videoPlaylist
    
    private val _currentVideoIndex = MutableStateFlow(0)
    val currentVideoIndex: StateFlow<Int> = _currentVideoIndex

    // --- Image Viewer State ---
    private val _currentImageUrl = MutableStateFlow<String?>(null)
    val currentImageUrl: StateFlow<String?> = _currentImageUrl
    
    private val _imagePlaylist = MutableStateFlow<List<String>>(emptyList())
    val imagePlaylist: StateFlow<List<String>> = _imagePlaylist
    
    private val _currentImageIndex = MutableStateFlow(0)
    val currentImageIndex: StateFlow<Int> = _currentImageIndex

    // Back Navigation Logic
    private val backStack = mutableListOf<AppScreen>()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack
    
    private var lastDashboardBackPressTime = 0L

    private val _pendingNavigationPath = MutableStateFlow<String?>(null)
    val pendingNavigationPath: StateFlow<String?> = _pendingNavigationPath

    private val _lastFilesScreen = MutableStateFlow(AppScreen.FILES)
    val lastFilesScreen: StateFlow<AppScreen> = _lastFilesScreen

    fun setPendingNavigationPath(path: String?) {
        _pendingNavigationPath.value = path
    }

    // Helper to extract the base URL (http://10.0.0.37:5000) from the specific Hub URL
    fun getBaseUrl(): String {
        return if (_activeBaseUrl.value.isNotEmpty()) _activeBaseUrl.value 
               else _appConfig.value.hubUrl.substringBefore("/signalrhub")
    }

    fun getWebServerUrl(): String {
        val baseUrl = getBaseUrl()
        return if (baseUrl.contains(":5000")) {
            baseUrl.replace(":5000", ":3333")
        } else {
            // Fallback or if port not specified, append 3333
            val uri = android.net.Uri.parse(baseUrl)
            val configUri = android.net.Uri.parse(_appConfig.value.hubUrl)
            val host = uri.host ?: configUri.host ?: "192.168.0.37"
            "http://$host:3333"
        }
    }

    fun saveAppConfig() {
        configManager.saveConfig(_appConfig.value)
        // Force emission of new state if object reference hasn't changed but content has
        _appConfig.value = _appConfig.value.copy() 
    }

    fun updateMacros(macros: List<com.omni.sync.data.model.Macro>) {
        val current = _appConfig.value
        _appConfig.value = current.copy(macros = macros)
        saveAppConfig()
        
        // Sync to Hub
        if (isConnected.value) {
            macros.forEach { signalRClient.saveMacro(it) }
        }
    }

    fun deleteMacro(macro: com.omni.sync.data.model.Macro) {
        val currentMacros = _appConfig.value.macros.filter { it.id != macro.id }
        updateMacros(currentMacros)
        if (isConnected.value) {
            signalRClient.deleteMacro(macro.id)
        }
    }

    fun fetchMacros() {
        if (!isConnected.value) return
        
        viewModelScope.launch {
            try {
                signalRClient.getMacros()
                    ?.subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                    ?.observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                    ?.subscribe({ hubMacros ->
                        // Merge logic: Hub is source of truth for overlapping IDs
                        val localMacros = _appConfig.value.macros
                        val merged = localMacros.toMutableList()
                        
                        hubMacros.forEach { hm ->
                            val existingIndex = merged.indexOfFirst { it.id == hm.id }
                            if (existingIndex != -1) {
                                merged[existingIndex] = hm
                            } else {
                                merged.add(hm)
                            }
                        }
                        
                        updateMacros(merged)
                        addLog("Synced ${hubMacros.size} macros from Hub.", LogType.SUCCESS)
                    }, { error ->
                        addLog("Failed to fetch macros: ${error.message}", LogType.ERROR)
                    })
            } catch (e: Exception) {
                addLog("Error syncing macros: ${e.message}", LogType.ERROR)
            }
        }
    }

    fun updateConfig(update: (com.omni.sync.data.config.AppConfig) -> com.omni.sync.data.config.AppConfig) {
        _appConfig.value = update(_appConfig.value)
        saveAppConfig()
    }

    fun playVideo(remotePath: String, playlist: List<String> = emptyList()) {
        val isLocal = remotePath.startsWith("/") || remotePath.startsWith("file://") || remotePath.startsWith("content://")
        
        if (isLocal) {
            val fixedPath = if (remotePath.startsWith("/")) "file://$remotePath" else remotePath
            val fixedPlaylist = playlist.map { if (it.startsWith("/")) "file://$it" else it }
            
            _videoPlaylist.value = fixedPlaylist
            _currentVideoIndex.value = if (fixedPlaylist.contains(fixedPath)) fixedPlaylist.indexOf(fixedPath) else 0
            _currentVideoUrl.value = fixedPath
            navigateTo(AppScreen.VIDEOPLAYER)
            return
        }

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

    fun viewImages(remotePath: String, playlist: List<String> = emptyList()) {
        val prefs = applicationContext.getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        val isRandom = prefs.getBoolean("image_slideshow_random", false)
        
        val baseUrl = getBaseUrl()
        
        var finalPlaylist = playlist
        if (isRandom && finalPlaylist.isNotEmpty()) {
            val otherImages = finalPlaylist.filter { it != remotePath }.shuffled()
            finalPlaylist = listOf(remotePath) + otherImages
        }

        val playlistUrls = finalPlaylist.map { path ->
            val encoded = java.net.URLEncoder.encode(path, "UTF-8")
            "$baseUrl/api/stream?path=$encoded"
        }
        
        val encodedPath = java.net.URLEncoder.encode(remotePath, "UTF-8")
        val currentUrl = "$baseUrl/api/stream?path=$encodedPath"
        
        _imagePlaylist.value = playlistUrls
        _currentImageIndex.value = if (playlistUrls.contains(currentUrl)) playlistUrls.indexOf(currentUrl) else 0
        _currentImageUrl.value = currentUrl
        
        navigateTo(AppScreen.IMAGE_VIEWER)
    }

    fun handleOpenFile(remotePath: String) {
        val extension = remotePath.substringAfterLast(".", "").lowercase()
        when (extension) {
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts" -> playVideo(remotePath)
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg" -> viewImages(remotePath)
            "mp3", "m4a", "wav", "flac", "ogg", "aac" -> {
                // For now, treat audio as video (it will play in the video player)
                playVideo(remotePath)
            }
            else -> openEditor(remotePath)
        }
    }

    fun handleOpenFolder(remotePath: String) {
        _pendingNavigationPath.value = remotePath
        navigateTo(AppScreen.FILES)
    }

    fun openEditor(remotePath: String) {
        // Assume it's a text file
        _pendingNavigationPath.value = remotePath
        navigateTo(AppScreen.EDITOR)
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

    fun onCortexActivityChanged(name: String, type: String) {
        addLog("Cortex: $name ($type)", LogType.INFO)
        
        val prefs = applicationContext.getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("cortex_notifications_enabled", true)) return

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "OmniSyncForegroundServiceChannel" // Reuse existing channel for now
        
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("New Cortex Activity")
            .setContentText(name)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(2, notification) // ID 2 for activity changes
    }

    private val _toastMessage = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    fun showToast(message: String) {
        viewModelScope.launch {
            _toastMessage.emit(message)
        }
    }
fun setConnected(connected: Boolean) {
    val wasConnected = _isConnected.value
    _isConnected.value = connected

    if (connected == wasConnected) return // Avoid spamming effects if state hasn't changed

    if (connected) {
        _errorMessage.value = null
        addLog("Hub Connected", LogType.SUCCESS)
        showToast("Hub Connected")
        resetSleep()
        fetchMacros()
    } else {
        addLog("Hub Disconnected", LogType.ERROR)
        showToast("Hub Disconnected")
        _isSleeping.value = sleepTracker.isSleeping()
    }
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

    fun setWinPressed(isPressed: Boolean) {
        _isWinPressed.value = isPressed
    }

    fun setScheduledShutdownTime(time: String?) {
        _scheduledShutdownTime.value = time
    }

    fun setShutdownMode(mode: String) {
        _shutdownMode.value = mode
    }
    
    // Updated Clipboard logic if needed, omitted for brevity but keep your existing logic
    fun updateClipboardContent(content: String) { /* keep existing */ }

    fun navigateTo(screen: AppScreen) {
        if (_currentScreen.value == screen) return

        if (screen == AppScreen.FILES || screen == AppScreen.EDITOR) {
            _lastFilesScreen.value = screen
        }

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
        if (_currentScreen.value == AppScreen.IMAGE_VIEWER) {
            _currentScreen.value = AppScreen.FILES
            _canGoBack.value = true
            return
        }
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
        // Also add to dashboard logs so it's visible
        addLog(output, LogType.INFO)
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

    fun sendWakeOnLan(macAddress: String, port: Int = 9) {
        val wanIp = _appConfig.value.wanIp
        val broadcastIp = _appConfig.value.subnetBroadcastIp
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("Sending WOL to $macAddress (Local & Remote)...", LogType.INFO)
                val macBytes = getMacBytes(macAddress)
                val bytes = ByteArray(6 + 16 * macBytes.size)
                for (i in 0 until 6) {
                    bytes[i] = 0xff.toByte()
                }
                for (i in 6 until bytes.size step macBytes.size) {
                    System.arraycopy(macBytes, 0, bytes, i, macBytes.size)
                }

                val socket = DatagramSocket()
                
                // 1. Send Local Broadcast (to the configured subnet)
                try {
                    val localAddress = InetAddress.getByName(broadcastIp)
                    val localPacket = DatagramPacket(bytes, bytes.size, localAddress, port)
                    socket.send(localPacket)
                    addLog("Subnet WOL sent to $broadcastIp", LogType.SUCCESS)
                } catch (e: Exception) {
                    addLog("Subnet WOL Failed: ${e.message}", LogType.WARNING)
                }

                // 1b. Send Global Local Broadcast (255.255.255.255)
                try {
                    val globalAddress = InetAddress.getByName("255.255.255.255")
                    val globalPacket = DatagramPacket(bytes, bytes.size, globalAddress, port)
                    socket.setBroadcast(true)
                    socket.send(globalPacket)
                    addLog("Global WOL sent to 255.255.255.255", LogType.SUCCESS)
                } catch (e: Exception) {
                    addLog("Global WOL Failed: ${e.message}", LogType.WARNING)
                }
                
                // 2. Send Remote Unicast (target WAN IP, router should forward to broadcast)
                try {
                    val remoteAddress = InetAddress.getByName(wanIp)
                    val remotePacket = DatagramPacket(bytes, bytes.size, remoteAddress, port)
                    socket.send(remotePacket)
                    addLog("Remote WOL sent to $wanIp", LogType.SUCCESS)
                } catch (e: Exception) {
                    addLog("Remote WOL Failed: ${e.message}", LogType.WARNING)
                }

                socket.close()
                addLog("All WOL packets processed.", LogType.INFO)
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
