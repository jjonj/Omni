package com.omni.sync

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.omni.sync.logic.SelectedTextActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single Android selection-toolbar entry with two actions powered by the
 * existing remote-control and Chrome-extension SignalR commands.
 */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_PROCESS_TEXT) {
            finish()
            return
        }
        val selection = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (selection.isBlank()) {
            finish()
            return
        }

        val url = SelectedTextActions.webUrlOrNull(selection)
        val labels = if (url == null) {
            arrayOf("Paste on PC")
        } else {
            arrayOf("Paste on PC", "Open on PC")
        }

        AlertDialog.Builder(this)
            .setTitle("Omni")
            .setItems(labels) { _, which ->
                dispatch(selection, if (which == 1) url else null)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun dispatch(selection: String, url: String?) {
        val client = (application as OmniSyncApplication).signalRClient
        val progress = AlertDialog.Builder(this)
            .setMessage("Connecting to PC…")
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        progress.show()

        lifecycleScope.launch {
            val state = client.connectionState.value
            if (state != "Connected" &&
                !state.contains("Connecting", ignoreCase = true) &&
                !state.contains("Reconnecting", ignoreCase = true)) {
                client.startConnection()
            }

            val connected = withTimeoutOrNull(15_000) {
                client.connectionState.first { it == "Connected" }
                // The connection is established before the Hub's Authenticate RPC completes.
                // Do not silently lose the first action on a cold app start.
                client.hubAuthenticated.first { it }
                true
            } ?: false
            progress.dismiss()
            if (!connected) {
                Toast.makeText(this@ProcessTextActivity, "Omni: PC offline or not authenticated", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            if (url == null) {
                // The same INPUT_TEXT command used by Remote Control's Clipboard button.
                client.sendText(selection)
                Toast.makeText(this@ProcessTextActivity, "Text sent to PC", Toast.LENGTH_SHORT).show()
            } else {
                // Existing browser route to the Omni Chrome/Vivaldi extension.
                client.sendBrowserCommand("Navigate", url, true)
                Toast.makeText(this@ProcessTextActivity, "URL sent to PC browser", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
