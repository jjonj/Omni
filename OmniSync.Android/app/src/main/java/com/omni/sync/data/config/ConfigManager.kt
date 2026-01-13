package com.omni.sync.data.config

import android.content.Context
import com.google.gson.Gson
import com.omni.sync.data.model.NotificationAction
import java.io.File

data class AppConfig(
    var hubUrl: String = "http://10.0.0.37:5000/signalrhub",
    var wanIp: String = "85.80.233.70",
    var apiKey: String = "test_api_key",
    var videoSkipInterval: Int = 10,
    var videoPlaylistRandom: Boolean = false,
    var cortexNotificationsEnabled: Boolean = true,
    var cortexWakeTime: String = "07:00",
    var cortexTemplatesJson: String? = null,
    var globalPasswordHash: String? = null,
    var autosaveEnabled: Boolean = false,
    var notificationActions: List<NotificationAction> = emptyList(),
    var macros: List<com.omni.sync.data.model.Macro> = emptyList(),
    var maxCacheFileSize: Long = 10 * 1024 * 1024, // 10 MB default
    var cacheExclusionPatterns: List<String> = emptyList(),
    var wakeOnLanMac: String = "10FFE0379DAC",
    var subnetBroadcastIp: String = "192.168.1.255",
    var streamFps: Int = 10,
    var streamResolution: Int = 100 // Percentage
)

class ConfigManager(private val context: Context) {
    private val gson = Gson()
    private val configFile: File by lazy {
        val dir = context.getExternalFilesDir(null)
        File(dir, "app_config.json")
    }

    fun loadConfig(): AppConfig {
        val config = if (configFile.exists()) {
            try {
                gson.fromJson(configFile.readText(), AppConfig::class.java)
            } catch (e: Exception) {
                android.util.Log.e("ConfigManager", "Error loading config", e)
                AppConfig()
            }
        } else {
            val c = AppConfig()
            migrateFromPrefs(c)
            c
        }
        
        if (config.macros.isEmpty()) {
            config.macros = getDefaultMacros()
        }
        
        return config
    }

    fun getDefaultMacros(): List<com.omni.sync.data.model.Macro> {
        return listOf(
            com.omni.sync.data.model.Macro(name = "Open Downloads", script = "run explorer.exe C:\\Users\\crovea\\Downloads", iconName = "folder"),
            com.omni.sync.data.model.Macro(name = "Omni AI CLI", script = "run B:\\GDrive\\Tools\\05 Automation\\omni_ai.bat", iconName = "ai"),
            com.omni.sync.data.model.Macro(name = "Close Tab", script = "send ^w", iconName = "browser"),
            com.omni.sync.data.model.Macro(name = "Refresh Tab", script = "send {F5}", iconName = "browser")
        )
    }

    fun saveConfig(config: AppConfig) {
        try {
            configFile.writeText(gson.toJson(config))
        } catch (e: Exception) {
            android.util.Log.e("ConfigManager", "Error saving config", e)
        }
    }

    fun getPredefinedActions(): List<NotificationAction> {
        return listOf(
            NotificationAction("pre-1", "Shutdown PC", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
            NotificationAction("pre-2", "Sleep PC", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
            NotificationAction("pre-3", "Toggle TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
            NotificationAction("pre-4", "WOL PC", "", isWol = true, macAddress = "10FFE0379DAC"),
            NotificationAction("pre-tell-pc", "Tell PC", "TELL_PC", isTellPc = true),
            NotificationAction("pre-smart-ai", "Smart AI (Clipboard)", "SMART_AI"),
            NotificationAction("pre-alarm-830", "Alarm 8h30m", "ALARM:510"),
            NotificationAction("pre-alarm-845", "Alarm 8h45m", "ALARM:525"),
            NotificationAction("pre-alarm-900", "Alarm 9h", "ALARM:540"),
            NotificationAction("pre-br-yt-phone", "YT -> Phone", "BROWSER:SendLatestYouTubeToPhone"),
            NotificationAction("pre-br-yt-pc", "YT -> PC", "BROWSER:OpenLatestYouTubeOnPC"),
            NotificationAction("pre-br-phone", "Tab -> Phone", "BROWSER:OpenCurrentTabOnPhone"),
            NotificationAction("pre-nav-dash", "Go to Dashboard", "NAV:DASHBOARD"),
            NotificationAction("pre-nav-remote", "Go to Remote", "NAV:REMOTECONTROL"),
            NotificationAction("pre-nav-browser", "Go to Browser", "NAV:BROWSER"),
            NotificationAction("pre-nav-files", "Go to Files", "NAV:FILES"),
            NotificationAction("pre-nav-ai", "Go to AI Chat", "NAV:AI_CHAT"),
            NotificationAction("pre-nav-alarm", "Go to Alarm", "NAV:ALARM"),
            NotificationAction("pre-br-back", "Browser Back", "BROWSER:Back"),
            NotificationAction("pre-br-refresh", "Browser Refresh", "BROWSER:Refresh"),
            NotificationAction("pre-br-forward", "Browser Forward", "BROWSER:Forward"),
            NotificationAction("pre-br-close", "Browser Close Tab", "BROWSER:CloseTab"),
            NotificationAction("pre-br-play", "Browser Play/Pause", "BROWSER:MediaPlayPause"),
            NotificationAction("pre-br-phone", "Open Tab on Phone", "BROWSER:OpenCurrentTabOnPhone")
        )
    }

    fun getBookmarks(): List<NotificationAction> {
        val browserPrefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val bookmarksJson = browserPrefs.getString("bookmarks", null)
        if (bookmarksJson != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<com.omni.sync.viewmodel.Bookmark>>() {}.type
                val rawBookmarks: List<com.omni.sync.viewmodel.Bookmark> = gson.fromJson(bookmarksJson, type)
                return rawBookmarks.map { bookmark ->
                    NotificationAction(
                        id = "bookmark-${bookmark.url.hashCode()}",
                        label = bookmark.name,
                        command = "BOOKMARK:${bookmark.url}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ConfigManager", "Error parsing bookmarks", e)
            }
        }
        return emptyList()
    }

    private fun migrateFromPrefs(config: AppConfig) {
        val settingsPrefs = context.getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        val filesPrefs = context.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)
        
        if (settingsPrefs.contains("hub_url")) config.hubUrl = settingsPrefs.getString("hub_url", config.hubUrl)!!
        if (settingsPrefs.contains("wan_ip")) config.wanIp = settingsPrefs.getString("wan_ip", config.wanIp)!!
        if (settingsPrefs.contains("api_key")) config.apiKey = settingsPrefs.getString("api_key", config.apiKey)!!
        config.videoSkipInterval = settingsPrefs.getInt("video_skip_interval", config.videoSkipInterval)
        config.videoPlaylistRandom = settingsPrefs.getBoolean("video_playlist_random", config.videoPlaylistRandom)
        config.cortexNotificationsEnabled = settingsPrefs.getBoolean("cortex_notifications_enabled", config.cortexNotificationsEnabled)
        config.cortexWakeTime = settingsPrefs.getString("cortex_wake_time", config.cortexWakeTime)!!
        config.cortexTemplatesJson = settingsPrefs.getString("cortex_templates_json", null)
        
        config.globalPasswordHash = filesPrefs.getString("global_password_hash", null)
        config.autosaveEnabled = filesPrefs.getBoolean("autosave_enabled", false)
        config.streamFps = settingsPrefs.getInt("stream_fps", config.streamFps)
        config.streamResolution = settingsPrefs.getInt("stream_resolution", config.streamResolution)
        
        val actionsJson = settingsPrefs.getString("notification_actions", null)
        if (actionsJson != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<NotificationAction>>() {}.type
            config.notificationActions = gson.fromJson(actionsJson, type)
        }
    }
}