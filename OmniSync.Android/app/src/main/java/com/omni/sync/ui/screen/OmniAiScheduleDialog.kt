package com.omni.sync.ui.screen

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** T3 desktop remains the durable scheduler; Android only submits one explicit request. */
internal data class OmniScheduledTask(
    val id: String,
    val title: String,
    val beginAt: Long?,
    val onIdle: Boolean,
    val status: String
)

@Composable
internal fun OmniAiScheduleDialog(
    targetTitle: String?,
    initialBeginAt: Long,
    initialOnIdle: Boolean,
    nativeThread: Boolean = false,
    jobs: List<OmniScheduledTask>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String, Long?, Boolean) -> Unit,
    onCancelJob: (String) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var onIdle by remember { mutableStateOf(initialOnIdle) }
    var beginAt by remember { mutableLongStateOf(initialBeginAt) }
    val calendar = remember(beginAt) { Calendar.getInstance().apply { timeInMillis = beginAt } }
    val validTime = beginAt > System.currentTimeMillis() &&
        beginAt < System.currentTimeMillis() + 90L * 24 * 60 * 60_000
    val valid = prompt.isNotBlank() && prompt.length <= 8_000 &&
        (targetTitle != null || title.trim().isNotBlank() && title.length <= 120) &&
        (onIdle || validTime)
    fun pickDate() {
        DatePickerDialog(context, { _, year, month, day ->
            val selected = Calendar.getInstance().apply {
                timeInMillis = beginAt
                set(year, month, day)
            }
            TimePickerDialog(context, { _, hour, minute ->
                selected.set(Calendar.HOUR_OF_DAY, hour)
                selected.set(Calendar.MINUTE, minute)
                selected.set(Calendar.SECOND, 0)
                selected.set(Calendar.MILLISECOND, 0)
                beginAt = selected.timeInMillis
            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(if (targetTitle == null) Icons.Default.AddTask
            else Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (targetTitle == null) "Schedule new work" else "Begin working later",
                    style = MaterialTheme.typography.titleLarge)
                Text(targetTitle ?: "A new ChatGPT session will be created when it starts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (targetTitle == null) OutlinedTextField(
                    value = title, onValueChange = { title = it.take(120) },
                    label = { Text("New session title") }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it.take(8_000) },
                    label = { Text("Prompt to send") },
                    placeholder = { Text("Describe the work to do…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    minLines = 5, maxLines = 10, enabled = !busy,
                    shape = RoundedCornerShape(14.dp))
                Text("${prompt.length} / 8,000", modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Start condition", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onIdle, onClick = { onIdle = false },
                        enabled = !busy, label = { Text("At a time") },
                        leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(16.dp)) })
                    FilterChip(selected = onIdle, onClick = { onIdle = true },
                        enabled = !busy, label = { Text("Begin on idle") },
                        leadingIcon = { Icon(Icons.Default.Bolt, null, Modifier.size(16.dp)) })
                }
                if (onIdle) {
                    Text(if (nativeThread) "Send the prompt once when this native thread becomes idle." else "Wait for an available Keep T3 Busy slot. This request takes priority over new TaskBoard work. Keep T3 Busy must be on.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                            .padding(12.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(30L, 60L, 120L, 240L).forEach { minutes ->
                            SuggestionChip(onClick = {
                                beginAt = System.currentTimeMillis() + minutes * 60_000
                            }, enabled = !busy,
                                label = { Text(if (minutes < 60) "30m" else "${minutes / 60}h") })
                        }
                    }
                    OutlinedButton(onClick = { pickDate() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                            Locale.getDefault()).format(Date(beginAt)))
                    }
                    if (!validTime) Text("Select a future time within 90 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (targetTitle == null && jobs.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Scheduled new sessions", style = MaterialTheme.typography.titleSmall)
                    jobs.takeLast(12).forEach { job ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(job.title, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1)
                                Text(if (job.onIdle) "On idle · ${job.status}" else
                                    "${job.beginAt?.let {
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT,
                                            DateFormat.SHORT).format(Date(it))
                                    } ?: "Pending"} · ${job.status}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (job.status == "scheduled") IconButton(
                                onClick = { onCancelJob(job.id) }, enabled = !busy) {
                                Icon(Icons.Default.Close, "Cancel ${job.title}")
                            }
                        }
                    }
                }
                if (!error.isNullOrBlank()) Text(error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(title.trim(), prompt.trim(),
                if (onIdle) null else beginAt, onIdle) }, enabled = valid && !busy) {
                Text(if (busy) "Saving…" else "Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}
