package com.omni.sync.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.AgentSessionSummaryData
import com.omni.sync.data.repository.RemoteTerminalSession
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.AgentInboxStore
import com.omni.sync.utils.AgentLocalState
import com.omni.sync.viewmodel.AppScreen
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Inline actions a row can trigger; kept in one place so rows stay dumb. */
private class InboxActions(
    val open: (AgentSessionSummaryData) -> Unit,
    val menu: (AgentSessionSummaryData) -> Unit,
    val answer: (AgentSessionSummaryData, String) -> Unit,
    val reply: (AgentSessionSummaryData) -> Unit,
    val retry: (AgentSessionSummaryData) -> Unit,
    val stop: (AgentSessionSummaryData) -> Unit,
    val handover: (AgentSessionSummaryData) -> Unit
)

@Composable
fun AgentInboxScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val p = agentPalette()
    val sessions by signalRClient.agentSessions.collectAsState()
    val connection by signalRClient.connectionState.collectAsState()
    val local by AgentInboxStore.state.collectAsState()
    val online = connection == "Connected"
    val now = rememberNow()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var filter by rememberSaveable { mutableStateOf("All") }
    var expanded by remember { mutableStateOf(setOf<AgentSection>()) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<AgentSessionSummaryData?>(null) }
    var notifyFor by remember { mutableStateOf<AgentSessionSummaryData?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<AgentSessionSummaryData?>(null) }
    var deleteFor by remember { mutableStateOf<AgentSessionSummaryData?>(null) }
    val terminal by signalRClient.remoteTerminalClient.session.collectAsState()
    val terminalStatus by signalRClient.remoteTerminalClient.sessionStatus.collectAsState()
    val terminalCommand by signalRClient.terminalLauncher.collectAsState()
    val currentScreen by mainViewModel.currentScreen.collectAsState()

    LaunchedEffect(Unit) { AgentInboxStore.init(context) }
    LaunchedEffect(Unit) {
        while (true) {
            signalRClient.requestAgentSessions()
            delay(15_000)
        }
    }
    LaunchedEffect(Unit) {
        signalRClient.agentSendStatus.collect { status ->
            when (status.state) {
                "error" -> snackbar.showSnackbar(status.error ?: "Agent action failed")
                "starting" -> snackbar.showSnackbar("New session starting on the PC")
                "deleted" -> snackbar.showSnackbar("Deleted from the PC")
            }
        }
    }
    // The pager keeps this screen composed under the detail/terminal overlays; only handle Back when it is showing.
    BackHandler(enabled = currentScreen == AppScreen.AGENTS) { if (showHistory) showHistory = false else mainViewModel.goBack() }

    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }
    fun sent(ok: Boolean, text: String) = say(if (ok) text else "Hub offline — nothing was sent")

    fun archive(s: AgentSessionSummaryData) {
        AgentInboxStore.archive(context, s.id)
        scope.launch {
            val result = snackbar.showSnackbar("Archived ${s.title.take(40)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) AgentInboxStore.unarchive(context, s.id)
        }
    }

    val actions = InboxActions(
        open = { mainViewModel.openAgentSession(it.id) },
        menu = { menuFor = it },
        answer = { s, option -> sent(signalRClient.sendAgentMessage(s.id, option), "Answered: $option") },
        reply = { AgentUiBus.focusComposer.value = it.id; mainViewModel.openAgentSession(it.id) },
        retry = { sent(signalRClient.retryAgentTurn(it.id), "Retrying…") },
        stop = { sent(signalRClient.interruptAgentTurn(it.id), "Stopping…") },
        handover = { AgentUiBus.pendingHandover.value = it.id; mainViewModel.openAgentSession(it.id) }
    )

    Box(Modifier.fillMaxSize().padding(parentPadding)) {
        if (showHistory) {
            AgentHistory(sessions, local, now, onOpen = actions.open, onMenu = actions.menu, onBack = { showHistory = false })
        } else {
            InboxContent(
                sessions = sessions,
                local = local,
                now = now,
                online = online,
                filter = filter,
                onFilter = { filter = it },
                expanded = expanded,
                onToggle = { section -> expanded = if (section in expanded) expanded - section else expanded + section },
                actions = actions,
                onHistory = { showHistory = true },
                onNew = { showNew = true },
                searching = searching,
                onToggleSearch = { on -> searching = on; if (!on) query = "" },
                query = query,
                onQuery = { query = it },
                terminal = terminal,
                terminalStatus = terminalStatus,
                onOpenTerminal = { mainViewModel.navigateTo(AppScreen.AGENT_TERMINAL) },
                onStopTerminal = { signalRClient.remoteTerminalClient.stopSession() }
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    menuFor?.let { s ->
        val archived = local.archived[s.id]?.let { isoMillis(s.modified) <= it } == true
        AgentSheet({ menuFor = null }) {
            SheetTitle(s.title, listOf(s.provider, s.model.ifBlank { null }, s.cwd.ifBlank { null }).filterNotNull().joinToString(" · "))
            AgentMenuItem("Open") { menuFor = null; actions.open(s) }
            if (s.capabilities?.canInterrupt == true) AgentMenuItem("Stop turn", "Kills the turn Omni started") { menuFor = null; actions.stop(s) }
            if (!s.id.startsWith("new-")) AgentMenuItem("Rename…", "Only changes the name Omni shows") { menuFor = null; renameFor = s }
            AgentMenuItem("Notifications", AgentInboxStore.notifyLevel(s.id).label) { menuFor = null; notifyFor = s }
            if (s.cwd.isNotBlank() && !s.id.startsWith("new-")) AgentMenuItem("Hand over…", "Continue with another provider") { menuFor = null; actions.handover(s) }
            if (s.capabilities?.canOpenTerminal == true) AgentMenuItem("Raw terminal", "Opens ${s.terminalCommand.substringBefore(' ')} in the session folder · replaces any open remote terminal") {
                menuFor = null; signalRClient.openAgentTerminal(s.terminalCommand, s.cwd)
            }
            if (archived) AgentMenuItem("Unarchive", "Back into the inbox") { menuFor = null; AgentInboxStore.unarchive(context, s.id) }
            else AgentMenuItem("Archive", "Removes it from the inbox · provider history stays resumable", danger = true) { menuFor = null; archive(s) }
            AgentMenuItem("Delete permanently…", "Deletes the transcript from the PC · cannot be undone", danger = true) { menuFor = null; deleteFor = s }
        }
    }
    renameFor?.let { s ->
        RenameSheet(s, onSave = { title ->
            renameFor = null
            sent(signalRClient.renameAgentSession(s.id, title), if (title.isBlank()) "Original title restored" else "Renamed")
        }, onDismiss = { renameFor = null })
    }
    deleteFor?.let { s ->
        DeleteSessionSheet(s, onDelete = {
            deleteFor = null
            if (!signalRClient.deleteAgentSession(s.id)) say("Hub offline — nothing was deleted")
        }, onDismiss = { deleteFor = null })
    }
    notifyFor?.let { s ->
        NotifySheet(s, AgentInboxStore.notifyLevel(s.id), onPick = { AgentInboxStore.setNotify(context, s.id, it); notifyFor = null }, onDismiss = { notifyFor = null })
    }
    if (showNew) {
        NewSessionSheet(rememberProjects(sessions), terminalCommand, onStart = { provider, cwd, message ->
            showNew = false
            if (provider == TERMINAL_PROVIDER) {
                if (!signalRClient.startAgentTerminal(message, cwd)) say("Terminal transport offline — nothing was started")
            } else {
                sent(signalRClient.createAgentSession(provider, cwd, message), "Starting $provider in ${projectOf(cwd)}…")
            }
        }, onDismiss = { showNew = false })
    }
}

@Composable
private fun InboxContent(
    sessions: List<AgentSessionSummaryData>,
    local: AgentLocalState,
    now: Long,
    online: Boolean,
    filter: String,
    onFilter: (String) -> Unit,
    expanded: Set<AgentSection>,
    onToggle: (AgentSection) -> Unit,
    actions: InboxActions,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    searching: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    terminal: RemoteTerminalSession?,
    terminalStatus: String,
    onOpenTerminal: () -> Unit,
    onStopTerminal: () -> Unit
) {
    val match = { s: AgentSessionSummaryData ->
        query.isBlank() || listOf(s.title, s.provider, s.cwd, s.activity, s.model).any { it.contains(query, ignoreCase = true) }
    }
    val p = agentPalette()
    val placed = remember(sessions, local, now / MINUTE_MS) { sessions.mapNotNull { s -> sectionOf(s, local, now)?.let { s to it } } }
    val active = placed.filter { it.second != AgentSection.ENDED }
    val count = { section: AgentSection -> active.count { it.second == section } }

    val projects = active.groupBy { projectOf(it.first.cwd).ifBlank { it.first.provider } }
        .map { (name, items) -> Triple(name, items.size, items.any { it.second == AgentSection.NEEDS }) }
        .sortedByDescending { it.second }
    val effectiveFilter = if (filter == "All" || projects.any { it.first == filter }) filter else "All"
    val visible = placed.filter {
        (effectiveFilter == "All" || projectOf(it.first.cwd).ifBlank { it.first.provider } == effectiveFilter) && match(it.first)
    }
    val bySection = AgentSection.values().associateWith { section ->
        visible.filter { it.second == section }.map { it.first }.let { list ->
            if (section == AgentSection.NEEDS) list.sortedBy { isoMillis(it.stateSince) } else list.sortedByDescending { isoMillis(it.stateSince) }
        }
    }
    val historyCount = sessions.size - active.size

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(p.surfaceAlt).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Agents", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                val parts = listOfNotNull(
                    count(AgentSection.NEEDS).takeIf { it > 0 }?.let { "$it need you" },
                    count(AgentSection.WORKING).takeIf { it > 0 }?.let { "$it working" },
                    count(AgentSection.FINISHED).takeIf { it > 0 }?.let { "$it finished" },
                    count(AgentSection.LIMITED).takeIf { it > 0 }?.let { "$it limited" }
                )
                Text(parts.joinToString(" · ").ifBlank { "All quiet" }, fontSize = 12.sp, color = p.muted, maxLines = 1)
            }
            Row(
                Modifier
                    .background(if (online) p.greenBg else p.redBg, RoundedCornerShape(50))
                    .border(1.dp, if (online) p.greenLine else p.redLine, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(if (online) p.green else p.red, 7)
                Text(if (online) " Hub" else " Offline", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (online) p.green else p.red)
            }
            IconButton(onClick = { onToggleSearch(!searching) }) { Icon(Icons.Default.Search, "Search sessions", tint = if (searching) p.text else p.muted) }
            IconButton(onClick = onHistory) { Icon(Icons.Default.History, "History", tint = p.text) }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New session", tint = p.text) }
        }

        if (searching) {
            OutlinedTextField(
                query, onQuery,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search title, project, model, activity") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )
        }

        // Project chips
        if (projects.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().background(p.surfaceAlt).padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { FilterChip("All", active.size, count(AgentSection.NEEDS) > 0, effectiveFilter == "All") { onFilter("All") } }
                items(projects, key = { it.first }) { (name, n, alert) -> FilterChip(name, n, alert, effectiveFilter == name) { onFilter(name) } }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 80.dp)) {
            if (!online) item { AgentBanner("Hub offline · showing the last known state. Actions need the Hub.") }
            if (terminal != null) {
                item(key = "sec-terminal") { AgentSectionLabel("Terminal", 1) }
                item(key = "terminal-${terminal.sessionId}") { TerminalRow(terminal, terminalStatus, onOpenTerminal, onStopTerminal) }
            }
            if (visible.isNotEmpty() && bySection[AgentSection.NEEDS].isNullOrEmpty()) {
                item { Text("✓ Nothing needs you right now", fontSize = 12.sp, color = p.green, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
            }
            AgentSection.values().forEach { section ->
                val list = bySection[section].orEmpty()
                if (list.isEmpty()) return@forEach
                val limit = when (section) { AgentSection.IDLE -> 3; AgentSection.ENDED -> 0; else -> Int.MAX_VALUE }
                val isOpen = section in expanded
                val shown = if (isOpen) list else list.take(limit)
                item(key = "sec-${section.name}") {
                    AgentSectionLabel(section.label, list.size) {
                        if (list.size > limit) {
                            Text(
                                if (isOpen) "Show less" else if (limit == 0) "Show" else "Show all",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.muted,
                                modifier = Modifier.clickable { onToggle(section) }.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                items(shown, key = { "row-${it.id}" }) { s -> AgentRow(s, section, now, local, actions) }
                if (!isOpen && limit in 1 until list.size) {
                    item(key = "more-${section.name}") {
                        Text(
                            "+ ${list.size - limit} more",
                            fontSize = 12.sp, color = p.muted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 7.dp)
                                .border(1.dp, p.lineStrong, RoundedCornerShape(12.dp))
                                .clickable { onToggle(section) }
                                .padding(vertical = 11.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            if (sessions.isEmpty()) {
                item { Text(if (online) "No agent sessions found on the PC yet." else "Connect to the Hub to see agent sessions.", color = p.muted, modifier = Modifier.padding(24.dp)) }
            } else if (visible.isEmpty()) {
                item { Text("Nothing active in the last 24h.", color = p.muted, modifier = Modifier.padding(24.dp)) }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .heightIn(min = 46.dp)
                        .border(1.dp, p.line, RoundedCornerShape(12.dp))
                        .clickable(onClick = onHistory),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, null, tint = p.muted, modifier = Modifier.size(16.dp))
                    Text("  History · $historyCount older sessions", fontSize = 12.sp, color = p.muted)
                }
            }
        }
    }
}

@Composable
private fun TerminalRow(terminal: RemoteTerminalSession, status: String, onOpen: () -> Unit, onStop: () -> Unit) {
    val p = agentPalette()
    val running = status == "running" || status == "launching"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .background(p.surface, RoundedCornerShape(14.dp))
            .border(1.dp, if (running) p.greenLine else p.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(start = 11.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProviderMark(TERMINAL_PROVIDER)
        Spacer(Modifier.size(8.dp))
        StatusDot(if (running) p.green else p.yellow)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(terminal.command, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${projectOf(terminal.workspace)} · $status", fontSize = 12.sp, color = if (running) p.green else p.muted, maxLines = 1)
        }
        AgentButton("■ Stop", small = true, style = AgentButtonStyle.DANGER, onClick = onStop)
    }
}

@Composable
private fun FilterChip(name: String, n: Int, alert: Boolean, selected: Boolean, onClick: () -> Unit) {
    val p = agentPalette()
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .background(if (selected) p.raised else p.surface, RoundedCornerShape(50))
            .border(1.dp, if (selected) p.text.copy(alpha = 0.6f) else p.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (alert) { StatusDot(p.red, 6); Spacer(Modifier.size(6.dp)) }
        Text(name, fontSize = 12.sp, color = p.text, maxLines = 1)
        Text(" $n", fontSize = 11.sp, color = p.faint)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AgentRow(s: AgentSessionSummaryData, section: AgentSection, now: Long, local: AgentLocalState, actions: InboxActions) {
    val p = agentPalette()
    val haptics = LocalHapticFeedback.current
    val caps = s.capabilities
    val (bg, line) = when (section) {
        AgentSection.NEEDS -> p.redBg to p.redLine
        AgentSection.WORKING -> p.surface to p.greenLine
        AgentSection.FINISHED -> p.blueBg to p.blueLine
        AgentSection.LIMITED -> p.surface to p.yellowLine
        else -> p.surfaceAlt to p.line
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, line, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { actions.open(s) },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actions.menu(s) }
            )
            .padding(start = 11.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(s.provider)
            Spacer(Modifier.size(8.dp))
            StatusDot(sectionColor(section, p))
            Spacer(Modifier.size(8.dp))
            Text(
                s.title, fontSize = 14.sp, fontWeight = if (section == AgentSection.IDLE || section == AgentSection.ENDED) FontWeight.SemiBold else FontWeight.Bold,
                color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (s.state in NEEDS_STATES) { Spacer(Modifier.size(6.dp)); AgentTag(needsLabel(s.state), p.red, p.redBg, p.redLine) }
            if (!s.openIn.isNullOrBlank()) { Spacer(Modifier.size(6.dp)); AgentTag(if (s.openIn == "terminal") "TERMINAL" else "APP", p.blue, p.blueBg, p.blueLine) }
            else if (s.liveElsewhere) { Spacer(Modifier.size(6.dp)); AgentTag("PC", p.blue, p.blueBg, p.blueLine) }
            if (local.notify[s.id] == "MUTED") Icon(Icons.Default.NotificationsOff, "Muted", tint = p.faint, modifier = Modifier.padding(start = 6.dp).size(14.dp))
            Text(
                agoShort(isoMillis(s.stateSince.ifBlank { s.modified }), now),
                fontSize = 11.sp, color = if (section == AgentSection.NEEDS) p.red else p.muted,
                fontWeight = if (section == AgentSection.NEEDS) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 8.dp)
            )
            IconButton(onClick = { actions.menu(s) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreHoriz, "Session actions", tint = p.muted, modifier = Modifier.size(18.dp))
            }
        }

        val indent = Modifier.padding(start = 32.dp, end = 8.dp)
        val project = projectOf(s.cwd).ifBlank { s.provider }
        val sub = when (s.state) {
            "question" -> project
            "error" -> project
            "limited" -> "$project · ${s.activity.ifBlank { "Usage limit reached" }}"
            else -> listOf(project, s.activity.ifBlank { null }).filterNotNull().joinToString(" · ")
        } + (s.queued?.size?.takeIf { it > 0 }?.let { " · $it queued" } ?: "")
        Text(sub, fontSize = 12.sp, color = when (section) { AgentSection.WORKING -> p.green; AgentSection.LIMITED -> p.yellow; else -> p.muted }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = indent)

        when (s.state) {
            "question" -> {
                val q = s.question
                Text(q?.text ?: s.activity, fontSize = 13.sp, color = p.text, lineHeight = 18.sp, modifier = indent.padding(top = 6.dp))
                FlowRow(indent.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (caps?.canAnswerQuestions == true) q?.options.orEmpty().forEach { option ->
                        AgentButton(option, small = true) { actions.answer(s, option) }
                    }
                    AgentButton(if (caps?.canAnswerQuestions == true) "Reply…" else "Open", small = true, style = AgentButtonStyle.GHOST) { actions.reply(s) }
                }
            }
            "error" -> {
                Text(s.error ?: s.activity, fontSize = 13.sp, color = p.text, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = indent.padding(top = 6.dp))
                Row(indent.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentButton("Retry", small = true) { actions.retry(s) }
                    if (s.cwd.isNotBlank() && !s.id.startsWith("new-")) AgentButton("Hand over…", small = true, style = AgentButtonStyle.GHOST) { actions.handover(s) }
                }
            }
            "limited" -> if (s.cwd.isNotBlank()) {
                Row(indent.padding(top = 8.dp)) { AgentButton("Hand over…", small = true, style = AgentButtonStyle.WARN) { actions.handover(s) } }
            }
            "working", "starting" -> if (caps?.canInterrupt == true) {
                Row(indent.padding(top = 8.dp)) { AgentButton("■ Stop", small = true, style = AgentButtonStyle.DANGER) { actions.stop(s) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentHistory(
    sessions: List<AgentSessionSummaryData>,
    local: AgentLocalState,
    now: Long,
    onOpen: (AgentSessionSummaryData) -> Unit,
    onMenu: (AgentSessionSummaryData) -> Unit,
    onBack: () -> Unit
) {
    val p = agentPalette()
    var query by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf("All") }
    val history = remember(sessions, local) {
        sessions.filter { sectionOf(it, local, now).let { s -> s == null || s == AgentSection.ENDED } }
            .sortedByDescending { isoMillis(it.modified) }
    }
    val projects = history.map { projectOf(it.cwd) }.filter { it.isNotBlank() }.distinct()
    val shown = history.filter {
        (project == "All" || projectOf(it.cwd) == project) &&
            (query.isBlank() || "${it.title} ${it.provider} ${it.cwd}".contains(query, ignoreCase = true))
    }
    fun bucket(ms: Long): String = (now - ms).let { d ->
        when { d < DAY_MS -> "Today"; d < 2 * DAY_MS -> "Yesterday"; d < 7 * DAY_MS -> "This week"; d < 30 * DAY_MS -> "This month"; else -> "Older" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(p.surfaceAlt).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.text) }
            Column {
                Text("History", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                Text("Older and archived sessions · all resumable", fontSize = 12.sp, color = p.muted)
            }
        }
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Search sessions") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        if (projects.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip("All", history.size, false, project == "All") { project = "All" } }
                items(projects) { name -> FilterChip(name, history.count { projectOf(it.cwd) == name }, false, project == name) { project = name } }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            var last = ""
            shown.forEach { s ->
                val b = bucket(isoMillis(s.modified))
                if (b != last) {
                    last = b
                    item(key = "h-$b") { AgentSectionLabel(b, shown.count { bucket(isoMillis(it.modified)) == b }) }
                }
                item(key = s.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .combinedClickable(onClick = { onOpen(s) }, onLongClick = { onMenu(s) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProviderMark(s.provider)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(projectOf(s.cwd).ifBlank { null }, s.provider, if (local.archived.containsKey(s.id)) "archived" else null).filterNotNull().joinToString(" · "),
                                fontSize = 12.sp, color = p.muted, maxLines = 1
                            )
                        }
                        Text(agoShort(isoMillis(s.modified), now), fontSize = 11.sp, color = p.muted, fontFamily = FontFamily.Default)
                    }
                }
            }
            if (shown.isEmpty()) item { Text("No matching sessions.", color = p.muted, modifier = Modifier.padding(24.dp)) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
