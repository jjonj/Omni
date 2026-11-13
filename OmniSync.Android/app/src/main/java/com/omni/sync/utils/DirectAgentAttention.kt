package com.omni.sync.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.omni.sync.MainActivity
import com.omni.sync.R
import java.util.UUID

/** Heads-up notification that returns to the exact requesting T3 session in Omni AI. */
object DirectAgentAttention {
    private const val CHANNEL = "omni_agent_direct_attention"

    fun show(context: Context, requestId: String, sessionId: String, message: String) {
        if (runCatching { UUID.fromString(requestId); UUID.fromString(sessionId) }.isFailure) return
        if (message.isBlank()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Agent requests your attention", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Direct requests from active AI agents"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 180, 500)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        })

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("omni://agent-attention/$requestId")
            putExtra("OPEN_SCREEN", "AGENT_DETAIL")
            putExtra("AGENT_SESSION_ID", sessionId)
        }
        val open = PendingIntent.getActivity(context, requestId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Agent needs your attention")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "Open session", open)
            .build()
        try {
            manager.notify(requestId.hashCode(), notification)
        } catch (error: SecurityException) {
            android.util.Log.w("DirectAgentAttention", "Notification permission unavailable", error)
        }
    }
}
