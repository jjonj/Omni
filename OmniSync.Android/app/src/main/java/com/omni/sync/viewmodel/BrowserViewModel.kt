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
    val tabList: StateFlow<List<Map<String, Any>>> = signalRClient.tabListReceived

    init {
        loadBookmarks()
        // Load toggle preference
        _openInNewTab.value = prefs.getBoolean("open_in_new_tab", false)
        // Request cleanup patterns from Chrome extension
        requestCleanupPatterns()

        // Listen for tab info to add bookmarks automatically when requested
        viewModelScope.launch {
            signalRClient.tabInfoReceived.collect { (title, url) ->
                addBookmark(title, url)
            }
        }
    }
    
    fun requestCleanupPatterns() {
        signalRClient.sendBrowserCommand("GetCleanupPatterns", "", false)
    }

    fun requestTabList() {
        signalRClient.sendBrowserCommand("ListTabs", "", false)
    }

    fun closeSpecificTab(tabId: Any) {
        // Use dedicated hub method for closing specific tab
        if (tabId is Number) {
            signalRClient.sendCommand("CloseSpecificTab", tabId.toInt())
        } else {
            signalRClient.sendBrowserCommand("CloseTab", tabId.toString(), false)
        }
    }
    
    fun addCurrentTabToCleanup() {
        signalRClient.sendBrowserCommand("AddCurrentTabToCleanup", "", false)
    }

    fun addCleanupPattern(pattern: String) {
        if (pattern.isNotBlank()) {
            // Send to hub, which will forward to extension
            signalRClient.sendPayload("AddCleanupPattern", pattern)
        }
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

    fun toggleMedia() {
        signalRClient.sendBrowserCommand("MediaPlayPause", "", false)
    }
    
    fun sendSpacebar() {
        // VK_SPACE = 0x20 (32)
        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", 0x20u)
    }

    fun bookmarkCurrentTab() {
        signalRClient.sendBrowserCommand("GetTabInfo", "", false)
    }

    fun openCurrentTabOnPhone() {
        signalRClient.sendBrowserCommand("OpenCurrentTabOnPhone", "", false)
    }

    fun sendLatestYouTubeToPhone() {
        signalRClient.sendBrowserCommand("SendLatestYouTubeToPhone", "", false)
    }

    fun openLatestYouTubeOnPC() {
        signalRClient.sendBrowserCommand("OpenLatestYouTubeOnPC", "", _openInNewTab.value)
    }

    fun addBookmark(name: String? = null, url: String? = null) {
        val finalUrl = url ?: _urlInput.value
        if (finalUrl.isNotBlank()) {
            val currentList = _bookmarks.value.toMutableList()
            
            val finalName = if (!name.isNullOrBlank()) {
                name
            } else {
                if (finalUrl.contains("://")) finalUrl.split("://")[1].take(20) else finalUrl.take(20)
            }
            
            // Avoid duplicates
            if (currentList.none { it.url == finalUrl }) {
                currentList.add(0, Bookmark(finalName, finalUrl)) // Add to top
                _bookmarks.value = currentList
                saveBookmarks()
            }
        }
    }
    
    fun removeBookmark(bookmark: Bookmark) {
        val currentList = _bookmarks.value.toMutableList()
        currentList.remove(bookmark)
        _bookmarks.value = currentList
        saveBookmarks()
    }

    fun moveBookmarkUp(bookmark: Bookmark) {
        val currentList = _bookmarks.value.toMutableList()
        val index = currentList.indexOf(bookmark)
        if (index > 0) {
            currentList.removeAt(index)
            currentList.add(index - 1, bookmark)
            _bookmarks.value = currentList
            saveBookmarks()
        }
    }

    fun moveBookmarkDown(bookmark: Bookmark) {
        val currentList = _bookmarks.value.toMutableList()
        val index = currentList.indexOf(bookmark)
        if (index != -1 && index < currentList.size - 1) {
            currentList.removeAt(index)
            currentList.add(index + 1, bookmark)
            _bookmarks.value = currentList
            saveBookmarks()
        }
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

    private fun getBookmarksFile(): java.io.File {
        val dir = getApplication<Application>().getExternalFilesDir(null)
        return java.io.File(dir, "bookmarks.json")
    }

    private fun loadBookmarks() {
        val file = getBookmarksFile()
        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<Bookmark>>() {}.type
                _bookmarks.value = gson.fromJson(json, type)
                return
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Error reading bookmarks file", e)
            }
        }

        // Fallback/Migration from SharedPreferences
        val json = prefs.getString("bookmarks", null)
        if (json != null) {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            _bookmarks.value = gson.fromJson(json, type)
            // Migrate to file
            saveBookmarks()
        } else {
            // Default bookmarks
            _bookmarks.value = listOf(
                Bookmark("Google", "https://google.com"),
                Bookmark("YouTube", "https://youtube.com"),
                Bookmark("ChatGPT", "https://chatgpt.com")
            )
            saveBookmarks()
        }
    }

    private fun saveBookmarks() {
        val json = gson.toJson(_bookmarks.value)
        // Save to SharedPreferences (as backup/standard)
        prefs.edit().putString("bookmarks", json).apply()
        
        // Save to external file for persistence between reinstalls
        try {
            val file = getBookmarksFile()
            file.writeText(json)
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Error saving bookmarks to file", e)
        }
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
