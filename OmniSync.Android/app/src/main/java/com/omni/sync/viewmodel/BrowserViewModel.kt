package com.omni.sync.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Bookmark(val name: String, val url: String)

class BrowserViewModel(
    application: Application,
    private val signalRClient: SignalRClient
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _openInNewTab = MutableStateFlow(false)
    val openInNewTab: StateFlow<Boolean> = _openInNewTab
    
    val customCleanupPatterns: StateFlow<List<String>> = signalRClient.cleanupPatterns

    init {
        loadBookmarks()
        // Load toggle preference
        _openInNewTab.value = prefs.getBoolean("open_in_new_tab", false)
        // Request cleanup patterns from Chrome extension
        requestCleanupPatterns()
    }
    
    fun requestCleanupPatterns() {
        signalRClient.sendBrowserCommand("GetCleanupPatterns", "", false)
    }
    
    fun addCurrentTabToCleanup() {
        signalRClient.sendBrowserCommand("AddCurrentTabToCleanup", "", false)
    }
    
    fun removeCleanupPattern(pattern: String) {
        signalRClient.sendBrowserCommand("RemoveCleanupPattern", pattern, false)
    }

    fun onUrlChanged(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun toggleNewTab(enabled: Boolean) {
        _openInNewTab.value = enabled
        prefs.edit().putBoolean("open_in_new_tab", enabled).apply()
    }

    fun navigate(url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://$finalUrl"
        }
        signalRClient.sendBrowserCommand("Navigate", finalUrl, _openInNewTab.value)
    }

    fun sendCommand(command: String) {
        signalRClient.sendBrowserCommand(command, "", false)
    }
    
    fun sendSpacebar() {
        // VK_SPACE = 0x20 (32)
        signalRClient.sendKeyEvent("InputKeyPress", 0x20u)
    }

    fun addBookmark() {
        val url = _urlInput.value
        if (url.isNotBlank()) {
            val currentList = _bookmarks.value.toMutableList()
            // Simple name generation, ideally user sets name
            val name = if (url.contains("://")) url.split("://")[1].take(20) else url.take(20)
            
            currentList.add(0, Bookmark(name, url)) // Add to top
            _bookmarks.value = currentList
            saveBookmarks()
        }
    }
    
    fun removeBookmark(bookmark: Bookmark) {
        val currentList = _bookmarks.value.toMutableList()
        currentList.remove(bookmark)
        _bookmarks.value = currentList
        saveBookmarks()
    }
    
    fun loadUrlFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            _urlInput.value = text
            navigate(text)
        }
    }

    private fun loadBookmarks() {
        val json = prefs.getString("bookmarks", null)
        if (json != null) {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            _bookmarks.value = gson.fromJson(json, type)
        } else {
            // Default bookmarks
            _bookmarks.value = listOf(
                Bookmark("Google", "https://google.com"),
                Bookmark("YouTube", "https://youtube.com"),
                Bookmark("ChatGPT", "https://chatgpt.com")
            )
        }
    }

    private fun saveBookmarks() {
        val json = gson.toJson(_bookmarks.value)
        prefs.edit().putString("bookmarks", json).apply()
    }
}

class BrowserViewModelFactory(
    private val application: Application,
    private val signalRClient: SignalRClient
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BrowserViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BrowserViewModel(application, signalRClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
