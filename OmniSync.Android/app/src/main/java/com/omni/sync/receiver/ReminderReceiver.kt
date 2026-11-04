package com.omni.sync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.sync.OmniSyncApplication
import com.omni.sync.utils.ReminderDelivery

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.omni.sync.REMINDER_TRIGGER") {
            ReminderDelivery.trigger(context, intent)
        } else if (intent.action == "com.omni.sync.REMINDER_ACTION") {
            val client = (context.applicationContext as OmniSyncApplication).signalRClient
            val id = intent.getStringExtra("REMINDER_ID") ?: return
            when (intent.getStringExtra("METHOD")) {
                "CompleteReminder" -> client.completeReminder(id)
                "DismissReminder" -> client.dismissReminder(id)
                "Tomorrow" -> client.postponeReminder(id, java.time.OffsetDateTime.now().plusDays(1).toString())
            }
        } else {
            com.omni.sync.utils.AlarmTrace.init(context, "receiver")
            com.omni.sync.utils.AlarmTrace.record("REBOOT_OR_TIME_BROADCAST", intent.action ?: "unknown")
            ReminderDelivery.restore(context)
            com.omni.sync.utils.AlarmScheduler.restoreEnabledAlarms(context)
            com.omni.sync.logic.assistant.AssistantScheduleStore.restore(context)
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                (context.applicationContext as OmniSyncApplication).signalRClient.startConnection()
            }
        }
    }
}
