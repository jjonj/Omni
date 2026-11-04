package com.omni.sync.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.omni.sync.data.config.ConfigManager
import com.omni.sync.data.model.NotificationAction
import com.omni.sync.service.ForegroundService
import com.omni.sync.service.QuickActionDispatcher
import com.omni.sync.ui.theme.OmniSyncTheme

/**
 * Android accessibility shortcuts resolve to manifest-declared activities, not dynamic app
 * shortcuts. The named aliases are individually selectable in Android's Accessibility settings.
 * The picker additionally exposes user-defined actions, macros and bookmarks at invocation time.
 *
 * This activity never needs Omni's existing AccessibilityService to be enabled.
 */
class AccessibilityQuickActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configManager = ConfigManager(this)
        val alias = intent.component?.className?.substringAfterLast('.')?.removePrefix("access_")
        val configuredActions = configManager.loadConfig().notificationActions
            .ifEmpty { configManager.getDefaultNotificationActions() }
        val predefined = configManager.getPredefinedActions().distinctBy { it.id }

        val action = when {
            alias == null || alias == "choose" -> null
            alias.startsWith("favorite_") ->
                alias.removePrefix("favorite_").toIntOrNull()
                    ?.let { configuredActions.getOrNull(it - 1) }
            else -> predefined.find { it.id == alias.replace('_', '-') }
        }

        if (action != null) {
            runAction(action)
            return
        }

        // Also serves as a fallback when a configured slot has been removed.
        val config = configManager.loadConfig()
        val macros = config.macros.map {
            NotificationAction("macro-${it.name}", it.name, "MACRO:${it.script}")
        }
        setContent {
            OmniSyncTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                        Text(
                            "Omni Quick Actions",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                        Text(
                            "Choose an action to run now. Built-in actions and six quick-action slots " +
                                "can also be assigned directly in Android Accessibility settings.",
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        LazyColumn {
                            if (configuredActions.isNotEmpty()) {
                                item { SectionHeading("Your notification quick actions") }
                                items(configuredActions, key = { "saved:${it.id}" }) { item ->
                                    ActionRow(item) { runAction(item) }
                                }
                            }
                            if (macros.isNotEmpty()) {
                                item { SectionHeading("Macros") }
                                items(macros, key = { "macro:${it.id}" }) { item ->
                                    ActionRow(item) { runAction(item) }
                                }
                            }
                            item { SectionHeading("Built-in actions") }
                            items(predefined, key = { "builtin:${it.id}" }) { item ->
                                ActionRow(item) { runAction(item) }
                            }
                            val bookmarks = configManager.getBookmarks().distinctBy { it.id }
                            if (bookmarks.isNotEmpty()) {
                                item { SectionHeading("Bookmarks") }
                                items(bookmarks, key = { "bookmark:${it.id}" }) { item ->
                                    ActionRow(item) { runAction(item) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionHeading(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )
    }

    @Composable
    private fun ActionRow(action: NotificationAction, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(action.label) },
            modifier = Modifier.clickable(onClick = onClick)
        )
        HorizontalDivider()
    }

    /** PC commands need an established SignalR session; cold-starting a shortcut must not drop them. */
    private fun runAction(action: NotificationAction) {
        val localAction = action.isWol || action.command == "PC_KEYBOARD" ||
            action.command == "STILL_AWAKE" ||
            action.command.startsWith("ALARM:") ||
            action.command.startsWith("NAV:") ||
            action.command.startsWith("NAV_FILE:")

        if (localAction || (application as com.omni.sync.OmniSyncApplication)
                .signalRClient.connectionState.value == "Connected") {
            dispatch(action)
            finish()
            return
        }

        // An accessibility target is a user-initiated Activity, so it can bring the persistent
        // service up before dispatching. Never report remote success while disconnected.
        try {
            ContextCompat.startForegroundService(this, Intent(this, ForegroundService::class.java))
        } catch (error: Exception) {
            Log.e("OmniAccessibilityAction", "Cannot start Hub connection", error)
        }
        lifecycleScope.launch {
            val connected = withTimeoutOrNull(12_000) {
                (application as com.omni.sync.OmniSyncApplication).signalRClient.connectionState
                    .first { it == "Connected" }
            } != null
            if (connected) {
                dispatch(action)
            } else {
                Log.w("OmniAccessibilityAction", "Hub unavailable for ${action.label}")
                Toast.makeText(
                    this@AccessibilityQuickActionActivity,
                    "Omni Hub disconnected: ${action.label}",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    private fun dispatch(action: NotificationAction) {
        try {
            // The existing widget treats this action specially; reuse its actual clipboard path.
            if (action.command == "SMART_AI") {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ForegroundService::class.java)
                        .setAction(ForegroundService.ACTION_TRIGGER_SMART_AI)
                )
            } else {
                QuickActionDispatcher.execute(this, action)
            }
            Log.i("OmniAccessibilityAction", "Triggered: ${action.label} (${action.id})")
        } catch (error: Exception) {
            Log.e("OmniAccessibilityAction", "Failed: ${action.label}", error)
            Toast.makeText(this, "Omni action failed: ${action.label}", Toast.LENGTH_LONG).show()
        }
    }
}
