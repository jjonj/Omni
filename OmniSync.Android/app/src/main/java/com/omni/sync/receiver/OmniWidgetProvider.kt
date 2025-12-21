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
            val action = if (json != null) Gson().fromJson(json, NotificationAction::class.java) else null

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.widget_text, action?.label ?: "Omni")

            if (action != null) {
                // Send broadcast to self
                val intent = Intent(context, OmniWidgetProvider::class.java).apply {
                    this.action = "com.omni.sync.WIDGET_CLICK"
                    putExtra(ForegroundService.EXTRA_ACTION_ID, action.id)
                }
                // Need unique request code per widget to avoid intent collision
                val pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            }

            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}