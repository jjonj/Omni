package com.omni.sync.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.gson.Gson
import com.omni.sync.R
import com.omni.sync.data.model.NotificationAction
import com.omni.sync.service.ForegroundService

class OmniWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        for (appWidgetId in appWidgetIds) {
            prefs.edit().remove("widget_$appWidgetId").apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Keep for manual updates if needed, though we primarily use getForegroundService now
        if (intent.action == "com.omni.sync.WIDGET_CLICK") {
            val actionId = intent.getStringExtra(ForegroundService.EXTRA_ACTION_ID)
            if (actionId != null) {
                val serviceIntent = Intent(context, ForegroundService::class.java).apply {
                    action = ForegroundService.ACTION_TRIGGER_NOTIFICATION_ACTION
                    putExtra(ForegroundService.EXTRA_ACTION_ID, actionId)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("widget_$appWidgetId", null)
            val action = if (json != null) {
                try {
                    Gson().fromJson(json, NotificationAction::class.java)
                } catch (e: Exception) {
                    null
                }
            } else null

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.widget_text, action?.label ?: "Omni")

            // Smart icon selection
            val iconRes = when {
                action == null -> R.drawable.ic_notification
                action.isWol -> R.drawable.ic_shutdown
                action.label.contains("Shutdown", ignoreCase = true) -> R.drawable.ic_shutdown
                action.label.contains("Sleep", ignoreCase = true) -> R.drawable.ic_sleep
                action.label.contains("Browser", ignoreCase = true) -> R.drawable.ic_browser
                action.command.startsWith("NAV:BROWSER") -> R.drawable.ic_browser
                action.command.startsWith("NAV:FILES") -> R.drawable.ic_files
                action.command.startsWith("NAV:AI_CHAT") -> R.drawable.ic_ai
                action.command.startsWith("NAV:ALARM") -> R.drawable.ic_alarm
                action.command.startsWith("NAV:REMOTECONTROL") -> R.drawable.ic_remote
                action.command.startsWith("NAV:DASHBOARD") -> R.drawable.ic_dashboard
                action.command.startsWith("BOOKMARK:") -> R.drawable.ic_browser
                else -> R.drawable.ic_notification
            }
            views.setImageViewResource(R.id.widget_icon, iconRes)

            if (action != null) {
                // Direct call to ForegroundService for better reliability
                val serviceIntent = Intent(context, ForegroundService::class.java).apply {
                    this.action = ForegroundService.ACTION_TRIGGER_NOTIFICATION_ACTION
                    putExtra(ForegroundService.EXTRA_ACTION_ID, action.id)
                    // Use data to make intent unique for system even with same extras/action
                    data = android.net.Uri.parse("omni://widget/$appWidgetId")
                }
                
                val pendingIntent = PendingIntent.getForegroundService(
                    context, 
                    appWidgetId, 
                    serviceIntent, 
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            } else {
                // Open app if no action configured
                val mainIntent = Intent(context, com.omni.sync.MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 
                    appWidgetId, 
                    mainIntent, 
                    PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            }

            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}