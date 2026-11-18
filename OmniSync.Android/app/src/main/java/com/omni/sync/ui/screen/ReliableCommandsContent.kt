package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.ReliableCommandRecord
import com.omni.sync.data.repository.SignalRClient

@Composable
internal fun ReliableCommandsContent(
    unstableMode: Boolean,
    hubAuthenticated: Boolean,
    connectionMessage: String,
    records: List<ReliableCommandRecord>,
    client: SignalRClient
) {
    var showAll by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("Command delivery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (hubAuthenticated) "Hub authenticated" else "Hub unavailable — $connectionMessage",
                style = MaterialTheme.typography.bodySmall,
                color = if (hubAuthenticated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            if (unstableMode) {
                Text("Unstable mode • SignalR over TCP (HTTPS when configured). Only Hub receipts confirm commands.",
                    style = MaterialTheme.typography.bodySmall)
            }
            val pendingCount = records.count { it.state == "pending" }
            if (pendingCount > 0) {
                Text("$pendingCount pending command(s)", style = MaterialTheme.typography.bodySmall)
            }
            if (records.isEmpty()) {
                Text("No recent commands", style = MaterialTheme.typography.bodySmall)
            } else {
                (if (showAll) records else records.take(8)).forEach { record ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    val stateLabel = when (record.state) {
                        "pending" -> when (record.phase) {
                            "held" -> "Held — confirm after reconnect"
                            "sending" -> "Sending"
                            "delivered" -> "Delivered — awaiting result"
                            "retrying" -> "Retrying / checking receipt"
                            else -> if (record.attempts == 0) "Queued" else "Awaiting confirmation"
                        }
                        "succeeded" -> "Confirmed"
                        "failed" -> "Failed"
                        "cancelled" -> "Cancelled"
                        else -> "Uncertain — verify on PC"
                    }
                    Text("${record.method} • $stateLabel", style = MaterialTheme.typography.bodyMedium)
                    record.error?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.state == "pending") {
                            TextButton(onClick = { client.retryQueuedCommand(record.id) },
                                enabled = hubAuthenticated) {
                                Text(if (record.attempts == 0) "Send now" else "Recheck")
                            }
                        }
                        if (record.state == "pending" && record.attempts == 0) {
                            TextButton(onClick = { client.cancelQueuedCommand(record.id) }) {
                                Text("Cancel")
                            }
                        }
                        if (record.attempts > 0 && record.state in setOf("unknown", "failed")) {
                            TextButton(onClick = { client.checkCommandStatus(record.id) },
                                enabled = hubAuthenticated) {
                                Text("Check Hub receipt")
                            }
                        }
                    }
                }
                if (records.size > 8) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show fewer" else "Show all ${records.size} commands")
                    }
                }
            }
    }
}

