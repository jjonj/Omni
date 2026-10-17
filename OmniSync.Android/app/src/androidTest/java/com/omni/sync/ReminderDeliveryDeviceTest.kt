package com.omni.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.omni.sync.data.repository.ReminderData
import com.omni.sync.utils.ReminderDelivery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryDeviceTest {
    @Test fun scheduledTaskPostsANotificationAndNewerTerminalStateCancelsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = UUID.randomUUID().toString()
        val task = ReminderData(id = id, title = "Omni delivery regression check", dueAt = OffsetDateTime.now().plusSeconds(3).toString(), revision = 1)
        try {
            ReminderDelivery.merge(context, listOf(task))
            val deadline = System.currentTimeMillis() + 15_000
            while (nm.activeNotifications.none { it.tag == id } && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertTrue("Scheduled task did not produce a notification", nm.activeNotifications.any { it.tag == id })
            ReminderDelivery.merge(context, listOf(task.copy(state = 3, revision = 2)))
            assertFalse("Terminal revision did not cancel the notification", nm.activeNotifications.any { it.tag == id })
            ReminderDelivery.merge(context, listOf(task))
            assertEquals("Stale revision resurrected a completed task", 3, ReminderDelivery.load(context).first { it.id == id }.state)
        } finally {
            synchronized(ReminderDelivery) {
                ReminderDelivery.stopPresentation(context, id)
                val prefs = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
                prefs.edit().putString("items", Gson().toJson(ReminderDelivery.load(context).filterNot { it.id == id }))
                    .remove("scheduled_$id").remove("shown_$id").remove("request_$id").commit()
            }
        }
    }
}
