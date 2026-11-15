package com.omni.sync.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-alert a snoozed request only if its durable phone status still permits it. */
class AndroidAttentionSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidAttentionStore.init(context)
        val id = intent.getStringExtra("requestId") ?: return
        val row = AndroidAttentionStore.entries.value.firstOrNull { it.id == id } ?: return
        if (row.status != "snoozed" || row.nextAt > System.currentTimeMillis()) return
        DirectAgentAttention.show(context, row.id, row.sessionId, row.message,
            remindExisting = true)
    }
}
