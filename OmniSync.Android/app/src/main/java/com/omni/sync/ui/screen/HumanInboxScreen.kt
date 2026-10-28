package com.omni.sync.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.HumanInboxClient
import com.omni.sync.data.repository.HumanInboxEntry
import com.omni.sync.data.repository.HumanInboxSnapshot
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.launch

/**
 * Read-only projection of human@kernel through the authenticated OmniHub bridge.
 * No local read/ack/approval state is persisted: T3/OpenRig remains authoritative.
 */
@Composable
fun HumanInboxScreen(signalRClient: SignalRClient, onBack: () -> Unit) {
    val client = remember(signalRClient) { HumanInboxClient(signalRClient) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authenticated by signalRClient.hubAuthenticated.collectAsState()
    var snapshot by remember { mutableStateOf<HumanInboxSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showRecentRead by remember { mutableStateOf(false) }
    var expandedKey by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        if (!authenticated) {
            error = "Connect to OmniHub to view the canonical inbox."
            snapshot = null
            return
        }
        refreshing = true
        error = null
        try {
            snapshot = client.fetchInbox()
        } catch (e: Exception) {
            error = e.message ?: "Human Inbox request failed."
            // Never present an old snapshot as current when the source is unavailable.
            snapshot = null
        } finally {
            refreshing = false
        }
    }
    LaunchedEffect(authenticated) { refresh() }

    Column(Modifier.fillMaxSize().background(Color(0xFF182B40).copy(alpha = 0.2f))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF182B40)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text("Human Inbox", color = Color(0xFF8FC7E9),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("human@kernel · T3 / OpenRig", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { scope.launch { refresh() } }, enabled = authenticated && !refreshing) {
                Text("Refresh")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !showRecentRead, onClick = { showRecentRead = false },
                label = { Text("Unread") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = showRecentRead, onClick = { showRecentRead = true },
                label = { Text("Recent read") })
            Spacer(Modifier.weight(1f))
            if (refreshing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
        }
        if (!authenticated) {
            Text("OmniHub is offline. This view does not keep a separate phone inbox.",
                Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.error) }

        val all = snapshot?.entries.orEmpty()
        val displayed = all.filter { if (showRecentRead) it.isReadInT3 == true else it.isReadInT3 != true }
        if (snapshot?.truncated == true) {
            Text("Only the latest available items are shown. Open T3 for older messages.",
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        if (snapshot != null && displayed.isEmpty()) {
            Text(if (showRecentRead) "No recent read messages." else "No unread messages.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(displayed, key = { "${it.sourceType}:${it.sourceId}" }) { item ->
                val key = "${item.sourceType}:${item.sourceId}"
                HumanInboxCard(
                    entry = item,
                    expanded = expandedKey == key,
                    onToggle = { expandedKey = if (expandedKey == key) null else key },
                    onOpen = {
                        // Only open an actual source-provided HTTP(S) destination.
                        val address = item.deepLink
                        val uri = address?.let(Uri::parse)
                        if (uri != null && (uri.scheme == "https" || uri.scheme == "http")) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            catch (_: Exception) { error = "Cannot open this source link on the phone." }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HumanInboxCard(
    entry: HumanInboxEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            RoundedCornerShape(9.dp))
        .clickable(onClick = onToggle)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.subject.ifBlank { "(No subject)" },
                Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            if (entry.requiresAction) Text("Action", color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium)
        }
        Text("${entry.sender} · ${entry.createdAt} · ${entry.queueState}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.body, maxLines = if (expanded) Int.MAX_VALUE else 3,
            style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            Text("Source: ${entry.sourceType} / ${entry.sourceId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.attentionKind.isNotBlank()) {
                Text("Attention: ${entry.attentionKind}", style = MaterialTheme.typography.labelSmall)
            }
            if (!entry.replyEligible && !entry.replyUnavailableReason.isNullOrBlank()) {
                Text("Reply unavailable: ${entry.replyUnavailableReason}",
                    style = MaterialTheme.typography.labelSmall)
            }
            val link = entry.deepLink
            if (link != null && (link.startsWith("https://") || link.startsWith("http://"))) {
                TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
                    Text("Open source")
                }
            }
        } else {
            Text("Tap to expand", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
