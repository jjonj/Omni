package com.omni.sync.service

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.omni.sync.data.repository.SignalRClient

class ClipboardWatcher(private val context: Context, private val signalRClient: SignalRClient) : ClipboardManager.OnPrimaryClipChangedListener {

    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun startWatching() {
        clipboardManager.addPrimaryClipChangedListener(this)
        Log.i("ClipboardWatcher", "Clipboard watcher started.")
    }

    fun stopWatching() {
        clipboardManager.removePrimaryClipChangedListener(this)
        Log.i("ClipboardWatcher", "Clipboard watcher stopped.")
    }

    override fun onPrimaryClipChanged() {
        if (signalRClient.isUpdatingClipboardInternally) {
            Log.d("ClipboardWatcher", "Ignoring internal clipboard update.")
            return
        }

        val clipData = clipboardManager.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).coerceToText(context).toString()
            if (text.isNotEmpty()) {
                signalRClient.sendClipboardUpdate(text)
                Log.d("ClipboardWatcher", "Clipboard content changed: $text")
            }
        }
    }
}
