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

        val actions = getSavedActions()

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
                            items(actions) { action ->
                                ListItem(
                                    headlineContent = { Text(text = action.label) },
                                    supportingContent = { Text(text = if (action.isWol) "WOL" else action.command.takeLast(20)) },
                                    modifier = Modifier.clickable {
                                        saveWidgetAction(appWidgetId, action)
                                        finishWithSuccess()
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getSavedActions(): List<NotificationAction> {
        val prefs = getSharedPreferences("omni_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("notification_actions", null)
        if (json == null) {
            return listOf(
                NotificationAction("1", "Shutdown", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
                NotificationAction("2", "Sleep", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
                NotificationAction("3", "TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
                NotificationAction("4", "WOL", "", isWol = true, macAddress = "10FFE0379DAC")
            )
        }
        val type = object : TypeToken<List<NotificationAction>>() {}.type
        return Gson().fromJson(json, type)
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
