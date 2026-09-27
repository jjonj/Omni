package com.omni.sync.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.AgentEventData
import com.omni.sync.data.repository.AgentSessionDetailData
import com.omni.sync.data.repository.AgentSessionSummaryData
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val PROVIDER_ORDER = listOf("Codex", "Claude", "Antigravity", "OpenCode")

private val PROVIDER_COMMANDS: Map<String, List<Pair<String, String>>> = mapOf(
    "Codex" to listOf(
        "/init" to "Create project instructions",
        "/status" to "Show session configuration",
        "/permissions" to "Set approval and sandbox boundaries",
        "/model" to "Choose model and reasoning effort",
        "/review" to "Review local changes and find issues"
    ),
    "Claude" to listOf(
        "/clear" to "Start a fresh conversation",
        "/compact" to "Summarize history to free context",
        "/context" to "Inspect context usage",
        "/config" to "Open configuration",
        "/help" to "Show interactive help",
        "/resume" to "Resume a saved session"
    ),
    "Antigravity" to listOf(
        "/resume" to "Open the conversation picker",
        "/rename" to "Rename the active conversation",
        "/agents" to "Open the agent manager",
        "/permissions" to "Choose agent autonomy",
        "/diff" to "Review changed files",
        "/skills" to "Browse reusable workflows",
        "/mcp" to "Manage MCP servers",
        "/usage" to "Open the CLI help manual"
    ),
    "OpenCode" to listOf(
        "/help" to "Show help",
        "/new" to "Start a session",
        "/sessions" to "Switch sessions",
        "/compact" to "Summarize context",
        "/models" to "Choose a model",
        "/undo" to "Undo the last message",
        "/redo" to "Redo the last message"
    )
)

private fun providerAccent(provider: String): Color = when (provider.lowercase()) {
    "codex" -> Color(0xFF10A37F)
    "claude" -> Color(0xFFD97757)
    "antigravity" -> Color(0xFF4285F4)
    "opencode" -> Color(0xFF8B5CF6)
    else -> Color(0xFF6B7280)
}

private fun relativeTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val clean = iso.substringBefore('.').ifBlank { iso }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val time = format.parse(clean)?.time ?: return ""
        val seconds = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0)
        when {
            seconds < 60 -> "Now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h"
            seconds < 31536000 -> "${seconds / 86400}d"
            else -> "${seconds / 31536000}y"
        }
    } catch (e: Exception) {
        ""
    }
}

private fun eventTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val clean = iso.substringBefore('.')
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val out = SimpleDateFormat("MMM d HH:mm", Locale.US)
        out.format(format.parse(clean) ?: return "")
    } catch (e: Exception) {
        ""
    }
}

@Composable
fun AgentInboxScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val sessions by signalRClient.agentSessions.collectAsState()
    var collapsedProviders by remember { mutableStateOf(setOf<String>()) }

    BackHandler { mainViewModel.goBack() }

    LaunchedEffect(Unit) {
        while (true) {
            signalRClient.requestAgentSessions()
            delay(20_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(parentPadding)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("AGENTS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${sessions.size} local transcripts · read-only",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (sessions.isEmpty()) {
            Text(
                "No local transcripts found. Open an agent chat on the PC first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val grouped = PROVIDER_ORDER.mapNotNull { provider ->
                val items = sessions.filter { it.provider == provider }
                if (items.isEmpty()) null else provider to items
            } + sessions.map { it.provider }.distinct()
                .filter { provider -> PROVIDER_ORDER.none { it.equals(provider, ignoreCase = true) } }
                .mapNotNull { provider ->
                    val items = sessions.filter { it.provider == provider }
                    if (items.isEmpty()) null else provider to items
                }

            grouped.forEach { (provider, items) ->
                val collapsed = provider in collapsedProviders
                item(key = "header-$provider") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                collapsedProviders = if (collapsed) collapsedProviders - provider
                                else collapsedProviders + provider
                            }
                            .padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .background(providerAccent(provider), RoundedCornerShape(2.dp))
                                    .height(14.dp)
                                    .widthIn(min = 4.dp)
                            )
                            Text(
                                "RECENT ${provider.uppercase()}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${items.size} ${if (collapsed) "›" else "⌄"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!collapsed) {
                    items(items, key = { it.id }) { session ->
                        AgentSessionRow(session) { mainViewModel.openAgentSession(session.id) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AgentSessionRow(session: AgentSessionSummaryData, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (session.cwd.isNotBlank()) {
                    Text(
                        session.cwd,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Text(
                relativeTime(session.modified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AgentSessionDetailScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val sessionId by mainViewModel.selectedAgentSessionId.collectAsState()
    var detail by remember { mutableStateOf<AgentSessionDetailData?>(null) }
    var technicals by remember { mutableStateOf(true) }
    var commandMenuOpen by remember { mutableStateOf(false) }
    var loadMoreState by remember { mutableStateOf<String?>(null) } // null idle, "loading", "error"
    var composerText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    BackHandler { onBack() }

    LaunchedEffect(sessionId) {
        val id = sessionId ?: return@LaunchedEffect
        signalRClient.requestAgentSessionDetail(id)
        while (true) {
            delay(10_000)
            // Background refresh only when not paging back in history.
            signalRClient.requestAgentSessionDetail(id, before = 0, clear = false)
        }
    }

    LaunchedEffect(Unit) {
        signalRClient.agentSessionDetail.collect { received ->
            val newDetail = received ?: return@collect
            // Keep an older loaded page when a background refresh re-paged to newest.
            val previous = detail
            val keepOlderPage = previous != null && previous.id == newDetail.id &&
                newDetail.nextBefore > 0 && newDetail.events.size < previous.events.size
            if (!keepOlderPage) {
                detail = newDetail
            }
        }
    }

    // Auto-scroll to bottom on new messages when already near the bottom.
    LaunchedEffect(detail?.events?.size, detail?.events?.lastOrNull()?.text?.take(50)) {
        val currentDetail = detail ?: return@LaunchedEffect
        if (currentDetail.nextBefore == 0 && currentDetail.events.isNotEmpty()) {
            listState.animateScrollToItem(currentDetail.events.size - 1)
        }
    }

    val current = detail
    val accent = current?.provider?.let { providerAccent(it) } ?: MaterialTheme.colorScheme.primary
    val visibleEvents = current?.events?.filter { technicals || it.kind == "user" || it.kind == "assistant" } ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(parentPadding)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    current?.title ?: "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    listOfNotNull(
                        current?.provider,
                        current?.model?.takeIf { it.isNotBlank() },
                        current?.cwd?.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accent.copy(alpha = 0.6f))
        )

        // Toolbar: Technicals toggle + /Commands (mirrors the web prototype)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            androidx.compose.material3.FilterChip(
                selected = technicals,
                onClick = { technicals = !technicals },
                label = { Text("⌘ Technicals ${if (technicals) "ON" else "OFF"}", style = MaterialTheme.typography.labelSmall) }
            )
            Box {
                androidx.compose.material3.FilterChip(
                    selected = commandMenuOpen,
                    onClick = { commandMenuOpen = !commandMenuOpen },
                    label = { Text("/ Commands", style = MaterialTheme.typography.labelSmall) }
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = commandMenuOpen,
                    onDismissRequest = { commandMenuOpen = false }
                ) {
                    val commands = current?.provider?.let { PROVIDER_COMMANDS[it] } ?: emptyList()
                    if (commands.isEmpty()) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("No commands for ${current?.provider ?: "this provider"}") },
                            onClick = { commandMenuOpen = false }
                        )
                    } else {
                        commands.forEach { (command, hint) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(command, fontWeight = FontWeight.SemiBold)
                                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    commandMenuOpen = false
                                    if (composerText.isBlank() || composerText.endsWith(" ")) {
                                        composerText += command
                                    } else {
                                        composerText += " $command"
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        val loading = current == null
        if (loading) {
            Text(
                "Loading transcript…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }

        // History controls (Load more / jump info), like the web prototype's chat controls
        if (current!!.hasMoreBefore || current!!.events.size < current!!.totalEvents) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (current!!.hasMoreBefore) {
                    androidx.compose.material3.TextButton(
                        enabled = loadMoreState != "loading",
                        onClick = {
                            val id = sessionId ?: return@TextButton
                            loadMoreState = "loading"
                            signalRClient.requestAgentSessionDetail(id, before = current!!.nextBefore, clear = false)
                        }
                    ) {
                        Text(if (loadMoreState == "loading") "Loading…" else "Load more")
                    }
                }
                Text(
                    "${current!!.events.size} / ${current!!.totalEvents} events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Conversation
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(visibleEvents) { event ->
                AgentEventCard(event, current!!.provider, accent)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Composer (same pattern as the AI chat screen input)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = composerText,
                onValueChange = { composerText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message ${current!!.provider}…") },
                minLines = 1,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.IconButton(
                onClick = { },
                enabled = false
            ) {
                Text("➤", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AgentEventCard(event: AgentEventData, provider: String, accent: Color) {
    var expanded by remember(event.text) { mutableStateOf(false) }
    val body = event.text.trim()
    val isUser = event.kind == "user"
    val isAssistant = event.kind == "assistant"
    val hasDetail = event.result?.isNotBlank() == true || (body.length > 160 && (event.kind == "command" || event.kind == "tool" || event.kind == "output"))

    when {
        isUser -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        isAssistant -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(provider.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        event.kind == "command" || event.kind == "tool" -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetail) { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (event.kind == "command" && event.resultError == true) "✗" else "▸",
                        color = if (event.kind == "command" && event.resultError == true) MaterialTheme.colorScheme.error else accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        event.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    if (hasDetail) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (body.isNotBlank() && (expanded || body.length <= 160)) {
                    Text(
                        if (body.length > 2000 && !expanded) body.take(2000) + "…" else body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (expanded && !event.result.isNullOrBlank()) {
                    Text(
                        "RESULT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (event.resultError == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        event.result,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (event.resultError == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        else -> Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            if (event.kind != "output") {
                Text(
                    event.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
    }
    if (event.time.isNotBlank() && (isUser || isAssistant)) {
        Text(
            eventTime(event.time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}
