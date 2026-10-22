package com.omni.sync.ui.screen

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CalendarItem(val title: String, val startsAt: String?, val endsAt: String?, val allDay: Boolean)
private data class CalendarPreview(val ongoing: List<CalendarItem>, val next: CalendarItem?)

private fun JSONObject.toCalendarItem(): CalendarItem = CalendarItem(
    title = optString("summary").ifBlank { "(Untitled)" },
    startsAt = optString("start").takeIf { it.isNotBlank() && it != "null" },
    endsAt = optString("end").takeIf { it.isNotBlank() && it != "null" },
    allDay = optBoolean("isAllDay")
)

private suspend fun loadCalendarPreview(gatewayUrl: String): CalendarPreview = withContext(Dispatchers.IO) {
    val connection = (URL(gatewayUrl.trimEnd('/') + "/gateway/api/sweep").openConnection() as HttpURLConnection)
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/json")
        if (connection.responseCode !in 200..299) error("Gateway HTTP ${connection.responseCode}")
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val calendar = JSONObject(response).getJSONObject("calendar")
        val ongoing = calendar.optJSONArray("ongoing")
        CalendarPreview(
            ongoing = (0 until (ongoing?.length() ?: 0)).mapNotNull { ongoing?.optJSONObject(it)?.toCalendarItem() },
            next = calendar.optJSONObject("next")?.toCalendarItem()
        )
    } finally {
        connection.disconnect()
    }
}

private fun formatCalendarTime(raw: String?, allDay: Boolean): String {
    if (raw == null) return "Time unavailable"
    if (allDay) return "All day"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            val time = java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault())
            return time.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()))
        }
    }
    // Android 7 fallback. Normalise variable RFC3339 fractional seconds before parsing.
    return runCatching {
        val normalised = raw.replace(Regex("\\.[0-9]+(?=Z|[+-][0-9]{2}:[0-9]{2}$)"), "")
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(normalised)
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(date ?: Date())
    }.getOrElse { raw.replace('T', ' ').take(16) }
}

/** Same ongoing/next calendar snapshot that OmniSweep reads from the production Rust Gateway. */
@Composable
fun CalendarDashboardCard(mainViewModel: MainViewModel, isConnected: Boolean) {
    val gatewayUrl = mainViewModel.getWebServerUrl()
    var preview by remember { mutableStateOf<CalendarPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(gatewayUrl, isConnected, refreshKey) {
        if (!isConnected) return@LaunchedEffect
        do {
            runCatching { loadCalendarPreview(gatewayUrl) }
                .onSuccess { preview = it; error = null }
                .onFailure { error = it.message ?: "Calendar unavailable" }
            delay(5 * 60 * 1000L)
        } while (true)
    }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Calendar", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { refreshKey++ }, enabled = isConnected) { Text("Refresh") }
            }
            if (!isConnected && preview == null) {
                Text("Connect to OmniHub to view calendar events.", style = MaterialTheme.typography.bodySmall)
            } else if (preview == null) {
                Text(error ?: "Loading calendar…", style = MaterialTheme.typography.bodySmall)
            } else {
                preview?.let { events ->
                    events.ongoing.forEach { event ->
                        Text("Now: ${event.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(if (event.allDay) "All day" else "Until ${formatCalendarTime(event.endsAt, false)}", style = MaterialTheme.typography.bodySmall)
                    }
                    events.next?.let { event ->
                        Text("Next: ${event.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(formatCalendarTime(event.startsAt, event.allDay), style = MaterialTheme.typography.bodySmall)
                    }
                    if (events.ongoing.isEmpty() && events.next == null) {
                        Text("No upcoming events found.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (error != null) Text("Last refresh failed: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
