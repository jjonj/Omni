package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

private data class T3UsageWindow(val label: String, val usedPercent: Float, val reset: String?)
private data class T3UsageAccount(
    val name: String,
    val checkedAt: String,
    val windows: List<T3UsageWindow>,
    val unavailable: String?
)

private fun parseUsage(json: String): List<T3UsageAccount> {
    val accounts = JSONObject(json).optJSONArray("accounts") ?: return emptyList()
    return (0 until accounts.length()).mapNotNull { index ->
        val account = accounts.optJSONObject(index) ?: return@mapNotNull null
        val windows = account.optJSONArray("windows")
        T3UsageAccount(
            name = account.optString("name", account.optString("driver", "Provider")),
            checkedAt = account.optString("checkedAt", ""),
            windows = (0 until (windows?.length() ?: 0)).mapNotNull { i ->
                val window = windows?.optJSONObject(i) ?: return@mapNotNull null
                T3UsageWindow(
                    label = window.optString("label", "Quota"),
                    usedPercent = window.optDouble("usedPercent", 0.0).toFloat().coerceIn(0f, 100f),
                    reset = window.optString("resetsAt").takeIf { it.isNotBlank() }
                )
            },
            unavailable = account.optJSONObject("unavailable")?.optString("message")?.ifBlank { "Usage temporarily unavailable" }
        )
    }
}

private fun relativeReset(iso: String?): String {
    if (iso.isNullOrEmpty()) return "Reset time unavailable"
    val parsed = sequenceOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        .mapNotNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(iso)?.time
            }.getOrNull()
        }.firstOrNull()
    if (parsed == null) return "Resets $iso"
    val delta = parsed - System.currentTimeMillis()
    if (delta <= 0L) return "Reset due"
    val minutes = (delta + 59_999L) / 60_000L
    return if (minutes >= 60L) "Resets in ${minutes / 60}h ${minutes % 60}m"
           else "Resets in ${minutes}m"
}

private suspend fun loadT3Usage(hubBaseUrl: String, apiKey: String, refresh: Boolean): List<T3UsageAccount> =
    withContext(Dispatchers.IO) {
        require(hubBaseUrl.isNotBlank()) { "Connect to OmniHub to see T3 usage." }
        val endpoint = hubBaseUrl.trimEnd('/') + "/api/t3/usage" + if (refresh) "?refresh=1" else ""
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 25000
            connection.setRequestProperty("x-omni-api-key", apiKey)
            if (connection.responseCode != 200) {
                error("T3 usage unavailable (${connection.responseCode}). Update T3 Code and OmniHub on the PC.")
            }
            parseUsage(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

@Composable
fun T3UsageSheet(hubBaseUrl: String, apiKey: String, onDismiss: () -> Unit) {
    val p = agentPalette()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<T3UsageAccount>>(emptyList()) }

    LaunchedEffect(hubBaseUrl, apiKey, refresh) {
        loading = true
        error = null
        try {
            accounts = loadT3Usage(hubBaseUrl, apiKey, refresh > 0)
        } catch (e: Exception) {
            error = e.message ?: "T3 usage unavailable"
        } finally {
            loading = false
        }
    }

    AgentSheet(onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("T3 usage", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text)
            Text(if (loading) "Loading…" else "Refresh", color = p.blue, fontSize = 14.sp,
                modifier = Modifier.clickable(enabled = !loading) { refresh++ })
        }
        if (loading && accounts.isEmpty()) {
            CircularProgressIndicator(color = p.blue, modifier = Modifier.padding(22.dp).size(24.dp))
        }
        error?.let {
            Text(it, color = p.yellow, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        }
        if (!loading && error == null && accounts.isEmpty()) {
            Text("No connected subscription usage accounts reported by T3.",
                color = p.muted, modifier = Modifier.padding(18.dp))
        }
        accounts.forEach { account ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                    .border(1.dp, p.line, RoundedCornerShape(14.dp))
                    .background(p.surfaceAlt, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(account.name, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                account.unavailable?.let { Text(it, color = p.yellow, fontSize = 12.sp) }
                account.windows.forEach { window ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(window.label, color = p.text, fontSize = 13.sp)
                        Text("${(100f - window.usedPercent).toInt()}% left", color = p.text,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { (100f - window.usedPercent) / 100f },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = p.blue, trackColor = p.line
                    )
                    Text(relativeReset(window.reset), color = p.muted, fontSize = 12.sp)
                }
                if (account.windows.isEmpty()) {
                    Text("No quota windows available", color = p.muted, fontSize = 12.sp)
                }
                Text("Last checked: ${account.checkedAt}", color = p.faint, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
