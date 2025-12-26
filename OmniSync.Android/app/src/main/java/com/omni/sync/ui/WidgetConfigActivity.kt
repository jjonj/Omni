package com.omni.sync.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.data.model.NotificationAction
import com.omni.sync.ui.theme.OmniSyncTheme
import com.omni.sync.receiver.OmniWidgetProvider

class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the result to CANCELED. This will cause the widget host to cancel
        // out of the widget placement if the user presses the back button.
        setResult(RESULT_CANCELED)

        // Find the widget id from the intent.
        val intent = intent
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        // If this activity was started with an invalid widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val configManager = com.omni.sync.data.config.ConfigManager(this)
        val config = configManager.loadConfig()
        val customActions = config.notificationActions
        val predefinedActions = configManager.getPredefinedActions()
        val bookmarkActions = configManager.getBookmarks()

        setContent {
            OmniSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Pick an action for this widget:",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        LazyColumn {
                            if (customActions.isNotEmpty()) {
                                item {
                                    Text("Custom Actions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                                }
                                items(customActions) { action ->
                                    ActionItem(action) {
                                        saveWidgetAction(appWidgetId, action)
                                        finishWithSuccess()
                                    }
                                }
                            }

                            item {
                                Text("Predefined Actions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                            }
                            items(predefinedActions) { action ->
                                ActionItem(action) {
                                    saveWidgetAction(appWidgetId, action)
                                    finishWithSuccess()
                                }
                            }

                            if (bookmarkActions.isNotEmpty()) {
                                item {
                                    Text("Bookmarks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                                }
                                items(bookmarkActions) { action ->
                                    ActionItem(action) {
                                        saveWidgetAction(appWidgetId, action)
                                        finishWithSuccess()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionItem(action: NotificationAction, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(text = action.label) },
            supportingContent = { Text(text = if (action.isWol) "WOL" else action.command.takeLast(30)) },
            modifier = Modifier.clickable { onClick() }
        )
        HorizontalDivider()
    }

    private fun saveWidgetAction(widgetId: Int, action: NotificationAction) {
        val prefs = getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("widget_$widgetId", Gson().toJson(action)).apply()
    }

    private fun finishWithSuccess() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        OmniWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)

        val resultValue = Intent()
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
