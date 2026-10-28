package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.taskboard.TaskBoardOfflineDrafts

/**
 * OW-0023: PC/phone conflicts are explicit and both versions remain available.
 * This dialog NEVER automatically merges, reapplies, or deletes an unknown edit.
 */
@Composable
fun PendingPhoneTaskEditsDialog(
    drafts: List<TaskBoardOfflineDrafts.Pending>,
    busy: Boolean,
    onRetryExact: (String) -> Unit,
    onClose: () -> Unit
) {
    var inspecting by remember { mutableStateOf<String?>(null) }
    val selected = drafts.firstOrNull { it.operationId == inspecting }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Phone TaskBoard edits · ${drafts.size} pending") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("These drafts live privately on this phone. PC TaskBoard Markdown is authoritative. An UNKNOWN operation must be resolved by its exact PC receipt; no automatic duplicate edits.",
                    style = MaterialTheme.typography.bodySmall)
                if (drafts.isEmpty()) {
                    Text("No unacknowledged edits.")
                }
                drafts.forEach { draft ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${draft.boardId} · ${draft.taskId}", fontWeight = FontWeight.SemiBold)
                        Text("State: ${draft.state} · operation ${draft.operationId}",
                            style = MaterialTheme.typography.labelSmall)
                        if (draft.explanation != null)
                            Text(draft.explanation, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                inspecting = if (inspecting == draft.operationId) null else draft.operationId
                            }) { Text(if (selected?.operationId == draft.operationId) "Hide versions" else "Compare versions") }
                            if (draft.state == "draft") {
                                TextButton(enabled = !busy, onClick = { onRetryExact(draft.operationId) }) {
                                    Text("Check/retry same ID")
                                }
                            }
                        }
                        if (selected?.operationId == draft.operationId) {
                            Text("Phone base", fontWeight = FontWeight.Medium)
                            Text(draft.baseSource.take(6000), style = MaterialTheme.typography.labelSmall)
                            if (draft.baseSource.length > 6000) Text("Base preview truncated; original retained privately.")
                            Text("Phone proposed edit", fontWeight = FontWeight.Medium)
                            Text(draft.edited.take(6000), style = MaterialTheme.typography.labelSmall)
                            if (draft.edited.length > 6000) Text("Proposed preview truncated; original retained privately.")
                            Text("Current PC version", fontWeight = FontWeight.Medium)
                            Text(draft.currentSource?.take(6000)
                                ?: "Not fetched. When PC rejects a stale base, it returns its current record here.",
                                style = MaterialTheme.typography.labelSmall)
                            Text("For a conflict, close this dialog, refresh synced TaskBoard files, and manually make a new edit. Do not use the old operation ID for a different payload.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
