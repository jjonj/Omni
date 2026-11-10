package com.omni.sync.ui.screen

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.logic.assistant.AssistantScheduleStore
import com.omni.sync.logic.assistant.ScheduledAssistantRun
import com.omni.sync.logic.assistant.AssistantFeedback
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Phone-owned Wake-on-LAN schedule editor. Hub/agent-created entries appear
 * here too; firing a schedule never depends on the desktop remaining online.
 */
@Composable
fun WakeScheduleCard(mainViewModel: MainViewModel, signalRClient: SignalRClient) {
    val context = LocalContext.current
    val config by mainViewModel.appConfig.collectAsState()
    var pending by remember { mutableStateOf(AssistantScheduleStore.pending(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmCancel by remember { mutableStateOf<ScheduledAssistantRun?>(null) }

    fun refresh() {
        pending = AssistantScheduleStore.pending(context)
        now = System.currentTimeMillis()
    }

    fun scheduled(at: Long, runId: String? = null) {
        if (at <= System.currentTimeMillis() + 10_000L) {
            Toast.makeText(context, "Choose a future time", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = pending.firstOrNull { it.runId == runId }
        // A PC-originated run remains owned by the Hub; do not change its time
        // behind the Hub's back. Cancel it and request a new schedule instead.
        if (existing?.origin == "hub") {
            Toast.makeText(context, "Cancel PC schedules before replacing them", Toast.LENGTH_LONG).show()
            return
        }
        val result = if (runId != null) {
            AssistantScheduleStore.reschedule(context, runId, at)
        } else {
            AssistantScheduleStore.add(context, "wake.pc", at, "android-ui")
        }
        if (result == null) {
            Toast.makeText(context, "Schedule no longer exists", Toast.LENGTH_SHORT).show()
            return
        }
        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
        AssistantFeedback.notify(context, "PC wake scheduled", whenText, result.runId)
        mainViewModel.addLog("[WOL] Phone alarm set for $whenText (${result.runId})", LogType.SUCCESS)
        refresh()
    }

    fun pickDateTime(run: ScheduledAssistantRun? = null) {
        val initial = Calendar.getInstance().apply {
            timeInMillis = run?.runAtMillis ?: System.currentTimeMillis() + 4 * 60 * 60 * 1000L
        }
        val picker = DatePickerDialog(context,
            { _, year, month, day ->
                val target = Calendar.getInstance().apply {
                    timeInMillis = initial.timeInMillis
                    set(year, month, day)
                }
                TimePickerDialog(context, { _, hour, minute ->
                    target.set(Calendar.HOUR_OF_DAY, hour)
                    target.set(Calendar.MINUTE, minute)
                    target.set(Calendar.SECOND, 0)
                    target.set(Calendar.MILLISECOND, 0)
                    scheduled(target.timeInMillis, run?.runId)
                }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(context)).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH))
        picker.datePicker.minDate = System.currentTimeMillis() - 60_000L
        picker.show()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000L) // Also pick up remote Hub schedules while dashboard is open.
            refresh()
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Wake PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Send now, or arm a phone alarm for when the PC is off.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    mainViewModel.sendWakeOnLan(config.wakeOnLanMac)
                    Toast.makeText(context, "Sending Wake-on-LAN", Toast.LENGTH_SHORT).show()
                }) { Text("Wake now") }
                Button(onClick = { pickDateTime() }) { Text("Pick date & time") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30L to "30m", 60L to "1h", 240L to "4h", 360L to "6h").forEach { (minutes, label) ->
                    SuggestionChip(
                        onClick = { scheduled(System.currentTimeMillis() + minutes * 60_000L) },
                        label = { Text("+$label") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Scheduled wakes (${pending.size})", fontWeight = FontWeight.SemiBold)
            if (pending.isEmpty()) {
                Text("None. Schedules from PC agents will appear here.",
                    style = MaterialTheme.typography.bodySmall)
            }
            pending.forEach { run ->
                val timeText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(run.runAtMillis))
                val source = when (run.origin) {
                    "hub" -> "From PC agent"
                    "android-voice" -> "Phone voice"
                    "one-time-request" -> "One-time request"
                    else -> "From phone"
                }
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(timeText, fontWeight = FontWeight.Medium)
                    Text("$source • ${if (run.runAtMillis > now) "Armed" else "Due"}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (run.origin != "hub") {
                            TextButton(onClick = { pickDateTime(run) }) { Text("Edit") }
                        }
                        TextButton(onClick = { confirmCancel = run }) { Text("Cancel") }
                    }
                }
            }
            Text("The phone must remain powered on with network access when the alarm fires. " +
                "A sent magic packet does not guarantee the PC powered on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    confirmCancel?.let { run ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Cancel scheduled PC wake?") },
            text = { Text(DateFormat.getDateTimeInstance().format(Date(run.runAtMillis))) },
            confirmButton = {
                TextButton(onClick = {
                    if (AssistantScheduleStore.cancel(context, run.runId)) {
                        if (run.origin == "hub") {
                            signalRClient.reportAssistantRunResult(
                                run.runId, "cancelled", "Cancelled by the user on Android"
                            )
                        }
                        mainViewModel.addLog("[WOL] Cancelled ${run.runId}", LogType.INFO)
                    }
                    refresh()
                    confirmCancel = null
                }) { Text("Cancel wake") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = null }) { Text("Keep") }
            }
        )
    }
}
