package com.omni.sync.data.config

import android.content.Context
import com.google.gson.Gson
import com.omni.sync.data.model.NotificationAction
import java.io.File

data class AppConfig(
    var hubUrl: String = "http://10.0.0.37:5000/signalrhub",
    var apiKey: String = "test_api_key",
    var videoSkipInterval: Int = 10,
    var videoPlaylistRandom: Boolean = false,
    var cortexNotificationsEnabled: Boolean = true,
    var globalPasswordHash: String? = null,
    var autosaveEnabled: Boolean = false,
    var notificationActions: List<NotificationAction> = emptyList()
)

class ConfigManager(private val context: Context) {
    private val gson = Gson()
    private val configFile: File by lazy {
        val dir = context.getExternalFilesDir(null)
        File(dir, "app_config.json")
    }

    fun loadConfig(): AppConfig {
        if (configFile.exists()) {
            try {
                return gson.fromJson(configFile.readText(), AppConfig::class.java)
            } catch (e: Exception) {
                android.util.Log.e("ConfigManager", "Error loading config", e)
            }
        }
        
        // Fallback to SharedPreferences migration or defaults
        val config = AppConfig()
        migrateFromPrefs(config)
        return config
    }

    fun saveConfig(config: AppConfig) {
        try {
            configFile.writeText(gson.toJson(config))
        } catch (e: Exception) {
            android.util.Log.e("ConfigManager", "Error saving config", e)
        }
    }

    private fun migrateFromPrefs(config: AppConfig) {
        val settingsPrefs = context.getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        val filesPrefs = context.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)
        
        if (settingsPrefs.contains("hub_url")) config.hubUrl = settingsPrefs.getString("hub_url", config.hubUrl)!!
        if (settingsPrefs.contains("api_key")) config.apiKey = settingsPrefs.getString("api_key", config.apiKey)!!
        config.videoSkipInterval = settingsPrefs.getInt("video_skip_interval", config.videoSkipInterval)
        config.videoPlaylistRandom = settingsPrefs.getBoolean("video_playlist_random", config.videoPlaylistRandom)
        config.cortexNotificationsEnabled = settingsPrefs.getBoolean("cortex_notifications_enabled", config.cortexNotificationsEnabled)
        
        config.globalPasswordHash = filesPrefs.getString("global_password_hash", null)
        config.autosaveEnabled = filesPrefs.getBoolean("autosave_enabled", false)
        
        val actionsJson = settingsPrefs.getString("notification_actions", null)
        if (actionsJson != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<NotificationAction>>() {}.type
            config.notificationActions = gson.fromJson(actionsJson, type)
        }
    }
}