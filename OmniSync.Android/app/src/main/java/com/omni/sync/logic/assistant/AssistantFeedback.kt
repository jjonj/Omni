package com.omni.sync.logic.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.omni.sync.R

object AssistantFeedback {
    private const val CHANNEL = "assistant_commands"

    fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun notify(context: Context, title: String, message: String, id: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Assistant commands",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Scheduled and completed Jarvis commands"
                }
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            manager.notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }
}
