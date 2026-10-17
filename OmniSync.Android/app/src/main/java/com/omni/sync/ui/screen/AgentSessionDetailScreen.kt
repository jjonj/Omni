package com.omni.sync.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.AgentEventData
import com.omni.sync.data.repository.AgentSessionDetailData
import com.omni.sync.data.repository.AgentSessionState
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.AgentInboxStore
import com.omni.sync.utils.AgentNotifyLevel
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PROVIDER_COMMANDS: Map<String, List<Pair<String, String>>> = mapOf(
    "Codex" to listOf(
        "/init" to "Create project instructions",
        "/status" to "Show session configuration",
        "/review" to "Review local changes and find issues",
        "/compact" to "Summarize history to free context"
    ),
    "Claude" to listOf(
        "/compact" to "Summarize history to free context",
        "/context" to "Inspect context usage",
        "/review" to "Review local changes",
        "/clear" to "Start a fresh conversation"
    ),
    "Antigravity" to listOf(
        "/diff" to "Review changed files",
        "/agents" to "Open the agent manager",
        "/skills" to "Browse reusable workflows",
        "/usage" to "Open the CLI help manual"
    ),
    "OpenCode" to listOf(
        "/compact" to "Summarize context",
        "/undo" to "Undo the last message",
        "/models" to "Choose a model"
    )
)

private data class PendingSend(val text: String, val at: Long)

@Composable
fun AgentSessionDetailScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val p = agentPalette()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sessionId by mainViewModel.selectedAgentSessionId.collectAsState()
    val sessions by signalRClient.agentSessions.collectAsState()
    val connection by signalRClient.connectionState.collectAsState()
    val local by AgentInboxStore.state.collectAsState()
    val online = connection == "Connected"
    val now = rememberNow(15_000)

    var detail by remember { mutableStateOf<AgentSessionDetailData?>(null) }
    var older by remember(sessionId) { mutableStateOf(listOf<AgentEventData>()) }
    var olderHasMore by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    var requestedOlder by remember(sessionId) { mutableStateOf(false) }
    var pending by remember(sessionId) { mutableStateOf(listOf<PendingSend>()) }
    var draft by remember(sessionId) { mutableStateOf(sessionId?.let { AgentUiBus.drafts[it] } ?: "") }
    var menuOpen by remember { mutableStateOf(false) }
    var sendMode by remember(sessionId) { mutableStateOf<String?>(null) }
    var commandsOpen by remember { mutableStateOf(false) }
    var showNotify by remember { mutableStateOf(false) }
    var showCaps by remember { mutableStateOf(false) }
    var showHandover by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val expandedGroups = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    val expandedTools = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    val current = detail?.takeIf { it.id == sessionId }
    val summary = sessions.firstOrNull { it.id == sessionId }
    // The detail carries events; whichever of detail/summary was derived later carries the fresher state.
    val st: AgentSessionState? = when {
        current == null -> summary
        summary == null -> current
        isoMillis(summary.modified) > isoMillis(current.modified) || summary.state != current.state && summary.stateSince > current.stateSince -> summary
        else -> current
    }
    val working = st?.state == "working" || st?.state == "starting"
    val workingNow by rememberUpdatedState(working)
    val caps = st?.capabilities

    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }

    BackHandler { onBack() }

    DisposableEffect(sessionId) {
        AgentInboxStore.viewingSessionId = sessionId
        onDispose { if (AgentInboxStore.viewingSessionId == sessionId) AgentInboxStore.viewingSessionId = null }
    }
    DisposableEffect(sessionId) {
        onDispose { sessionId?.let { AgentUiBus.drafts[it] = draft } }
    }

    // Load and keep the newest page fresh; faster while a turn is running.
    LaunchedEffect(sessionId) {
        val id = sessionId ?: return@LaunchedEffect
        signalRClient.requestAgentSessionDetail(id)
        while (true) {
            delay(if (workingNow) 4_000 else 10_000)
            signalRClient.requestAgentSessionDetail(id, before = 0, clear = false)
        }
    }
    LaunchedEffect(sessionId) {
        signalRClient.agentSessionsChanged.collect { sessionId?.let { signalRClient.requestAgentSessionDetail(it, before = 0, clear = false) } }
    }
    LaunchedEffect(sessionId) {
        signalRClient.agentSessionDetail.collect { received ->
            val incoming = received ?: return@collect
            if (incoming.id != sessionId) return@collect
            if (requestedOlder) {
                // An older page: prepend instead of replacing what is on screen.
                older = incoming.events + older
                olderHasMore = incoming.hasMoreBefore
                requestedOlder = false
            } else {
                detail = incoming
            }
        }
    }
    LaunchedEffect(sessionId) {
        signalRClient.agentSendStatus.collect { status ->
            if (status.id != sessionId) return@collect
            when (status.state) {
                "error" -> {
                    pending.lastOrNull()?.let { failed -> if (draft.isBlank()) draft = failed.text }
                    pending = emptyList()
                    say(status.error ?: "The turn failed")
                }
                "interrupted" -> say("Turn stopped")
                "deleted" -> onBack()
                "queued" -> {
                    pending = pending.dropLast(1)
                    say("Queued — sends when the running turn ends")
                }
                "delivered" -> {
                    pending = pending.dropLast(1)
                    say("Typed into the open session on the PC")
                }
                "delivered-pending" -> {
                    pending = pending.dropLast(1)
                    say("Waiting for the open session on the PC to pick it up")
                }
            }
        }
    }
    // Drop optimistic bubbles once the transcript shows the message.
    LaunchedEffect(current?.events?.size, current?.modified) {
        val events = current?.events ?: return@LaunchedEffect
        pending = pending.filter { sent -> events.none { it.kind == "user" && it.text.trim().contains(sent.text.trim().take(200)) } }
    }
    LaunchedEffect(st?.modified, st?.id) {
        st?.let { AgentInboxStore.markSeen(context, it.id, isoMillis(it.modified)) }
    }
    val handoverRequest by AgentUiBus.pendingHandover.collectAsState()
    LaunchedEffect(handoverRequest, sessionId) {
        if (handoverRequest != null && handoverRequest == sessionId) {
            showHandover = true
            AgentUiBus.pendingHandover.value = null
        }
    }
    val focusRequest by AgentUiBus.focusComposer.collectAsState()
    LaunchedEffect(focusRequest, sessionId) {
        if (focusRequest != null && focusRequest == sessionId) {
            delay(250)
            runCatching { focus.requestFocus() }
            AgentUiBus.focusComposer.value = null
        }
    }

    val events = older + current?.events.orEmpty()
    val timeline = remember(events, working, sessionId) { buildTimeline(events, working, sessionId ?: "s") }
    val hasMore = olderHasMore ?: (current?.hasMoreBefore == true)

    // Follow the conversation while the user is at the bottom.
    LaunchedEffect(timeline.size, pending.size, working) {
        val total = listState.layoutInfo.totalItemsCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        // A large offset lands on the end of the last item, not its top (long answers).
        if (total > 0 && (lastVisible >= total - 3 || older.isEmpty())) listState.scrollToItem(total - 1, 100_000)
    }

    fun send(text: String) {
        val id = sessionId ?: return
        val message = text.trim()
        if (message.isEmpty()) return
        val options = st?.sendOptions.orEmpty()
        val mode = sendMode?.takeIf { m -> options.any { it.mode == m } } ?: "auto"
        if (!signalRClient.sendAgentMessage(id, message, mode)) {
            say("Hub offline — not sent")
            return
        }
        pending = pending + PendingSend(message, System.currentTimeMillis())
        if (message == draft.trim()) draft = ""
    }

    Box(Modifier.fillMaxSize().padding(parentPadding).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(Modifier.fillMaxWidth().background(p.surfaceAlt).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.text) }
                Column(Modifier.weight(1f)) {
                    Text(st?.title ?: "Loading…", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(st?.provider, st?.model?.ifBlank { null }, st?.cwd?.let { projectOf(it) }?.ifBlank { null }).joinToString(" · "),
                        fontSize = 12.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                val level = sessionId?.let { local.notify[it] } ?: AgentNotifyLevel.DEFAULT.name
                IconButton(onClick = { showNotify = true }) {
                    Icon(if (level == AgentNotifyLevel.MUTED.name) Icons.Default.NotificationsOff else Icons.Default.Notifications, "Notifications", tint = if (level == AgentNotifyLevel.MUTED.name) p.faint else p.text)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More", tint = p.text) }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        val s = st
                        if (s != null && !s.id.startsWith("new-")) {
                            DropdownMenuItem(text = { MenuText("Rename…", "Only changes the name Omni shows") }, onClick = { menuOpen = false; showRename = true })
                        }
                        DropdownMenuItem(text = { MenuText("Notifications", sessionId?.let { AgentInboxStore.notifyLevel(it).label }) }, onClick = { menuOpen = false; showNotify = true })
                        if (s != null && s.cwd.isNotBlank() && !s.id.startsWith("new-")) {
                            DropdownMenuItem(text = { MenuText("Hand over…", "Continue with another provider") }, onClick = { menuOpen = false; showHandover = true })
                        }
                        if (s?.capabilities?.canOpenTerminal == true) {
                            DropdownMenuItem(text = { MenuText("Raw terminal", "Replaces any open remote terminal") }, onClick = {
                                menuOpen = false
                                signalRClient.openAgentTerminal(s.terminalCommand, s.cwd)
                            })
                        }
                        DropdownMenuItem(text = { MenuText("Capabilities", s?.capabilities?.backend) }, onClick = { menuOpen = false; showCaps = true })
                        if (s != null && !s.id.startsWith("new-")) {
                            val archived = local.archived[s.id]?.let { isoMillis(s.modified) <= it } == true
                            DropdownMenuItem(
                                text = { MenuText(if (archived) "Unarchive" else "Archive", if (archived) "Back into the inbox" else "Removes it from the inbox · stays resumable", danger = !archived) },
                                onClick = {
                                    menuOpen = false
                                    if (archived) AgentInboxStore.unarchive(context, s.id) else { AgentInboxStore.archive(context, s.id); onBack() }
                                }
                            )
                        }
                        if (s != null) {
                            DropdownMenuItem(
                                text = { MenuText("Delete permanently…", "Deletes the transcript from the PC", danger = true) },
                                onClick = { menuOpen = false; showDelete = true }
                            )
                        }
                    }
                }
            }

            if (st != null) StatusStrip(
                st, now,
                onStop = { if (!signalRClient.interruptAgentTurn(st.id)) say("Hub offline") },
                onHandover = { showHandover = true }
            )
            val openIn = st?.openIn.orEmpty()
            if (openIn.isNotBlank() || st?.liveElsewhere == true) {
                Text(
                    when {
                        openIn.isNotBlank() && st?.state == "working" -> "Open in the $openIn on the PC and working. Messages go into that same conversation."
                        openIn.isNotBlank() -> "Open in the $openIn on the PC. Messages go into that same conversation."
                        else -> "Running in another client on the PC. Messages queue until its turn ends, or can run as a separate turn."
                    },
                    fontSize = 11.sp, lineHeight = 15.sp, color = p.blue,
                    modifier = Modifier.fillMaxWidth().background(p.blueBg).padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!online) item { AgentBanner("Hub offline · showing the last loaded transcript.") }
                if (current == null && st == null) item { Text("Loading transcript…", color = p.muted, modifier = Modifier.padding(16.dp)) }
                if (current?.title == "Session unavailable") item {
                    Text("This session is no longer available here. A newly started session moves to its own entry in the inbox once the PC writes its transcript.", color = p.muted, fontSize = 13.sp)
                }
                if (hasMore) item(key = "load-more") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        AgentButton(if (requestedOlder) "Loading…" else "Load earlier", small = true, style = AgentButtonStyle.GHOST, enabled = !requestedOlder) {
                            val id = sessionId ?: return@AgentButton
                            requestedOlder = true
                            signalRClient.requestAgentSessionDetail(id, before = current?.events.orEmpty().size + older.size, clear = false)
                        }
                        Text("  ${events.size} / ${current?.totalEvents ?: events.size} events", fontSize = 11.sp, color = p.faint)
                    }
                }
                items(timeline, key = { it.key }) { item ->
                    when (item) {
                        is TimelineItem.User -> UserBubble(item.event.text, if (item.event.title == "phone") "Sent from your phone · delivered into the open PC session" else null)
                        is TimelineItem.Assistant -> AgentBubble(st?.provider ?: "Agent", item.event.text)
                        is TimelineItem.System -> Text(item.text, fontSize = 11.sp, color = p.faint, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        is TimelineItem.Tools -> ToolGroupCard(
                            item,
                            open = expandedGroups[item.key] == true,
                            onToggle = { expandedGroups[item.key] = expandedGroups[item.key] != true },
                            isToolOpen = { i -> expandedTools["${item.key}/$i"] == true },
                            onToolToggle = { i -> expandedTools["${item.key}/$i"] = expandedTools["${item.key}/$i"] != true }
                        )
                    }
                }
                if (st?.state == "question") item(key = "question") {
                    QuestionCard(st, onChoose = { send(it) }, onWrite = { runCatching { focus.requestFocus() } })
                }
                if (st?.state == "error") item(key = "error") {
                    ErrorCard(st, onRetry = { if (!signalRClient.retryAgentTurn(st.id)) say("Hub offline") }, onHandover = { showHandover = true })
                }
                items(st?.queued.orEmpty(), key = { "queued-${it.id}" }) { q ->
                    val tag = if (q.delivery == "pc-pickup") "Waiting for the session open on the PC" else "Queued · sends when the running turn ends"
                    UserBubble(q.text, tag, actionLabel = "Cancel") {
                        if (!signalRClient.cancelQueuedAgentMessage(st!!.id, q.id)) say("Hub offline")
                    }
                }
                items(pending, key = { "pending-${it.at}" }) { sent -> UserBubble(sent.text, if (working) "Sent · the agent is on it" else "Sending…") }
                if (working) item(key = "typing") { TypingBubble(st?.provider ?: "Agent", st?.activity ?: "") }
            }

            if (st != null) Composer(
                session = st,
                online = online,
                draft = draft,
                onDraft = { draft = it },
                focus = focus,
                commandsOpen = commandsOpen,
                onCommands = { commandsOpen = it },
                onCommand = { cmd -> draft = if (draft.isBlank() || draft.endsWith(" ")) draft + cmd else "$draft $cmd" },
                sendMode = sendMode,
                onSendMode = { sendMode = it },
                onSend = { send(draft) }
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    val s = st
    if (s != null && showNotify) {
        NotifySheet(s, AgentInboxStore.notifyLevel(s.id), onPick = { AgentInboxStore.setNotify(context, s.id, it); showNotify = false }, onDismiss = { showNotify = false })
    }
    if (s != null && showCaps) CapabilitiesSheet(s) { showCaps = false }
    if (s != null && showRename) {
        RenameSheet(s, onSave = { title ->
            showRename = false
            if (signalRClient.renameAgentSession(s.id, title)) say(if (title.isBlank()) "Original title restored" else "Renamed")
            else say("Hub offline — nothing was sent")
        }, onDismiss = { showRename = false })
    }
    if (s != null && showDelete) {
        DeleteSessionSheet(s, onDelete = {
            showDelete = false
            if (!signalRClient.deleteAgentSession(s.id)) say("Hub offline — nothing was deleted")
        }, onDismiss = { showDelete = false })
    }
    if (s != null && showHandover) {
        HandoverSheet(s, current, onStart = { provider, text, archive ->
            showHandover = false
            if (signalRClient.createAgentSession(provider, s.cwd, text)) {
                if (archive) AgentInboxStore.archive(context, s.id)
                onBack()
            } else say("Hub offline — nothing was sent")
        }, onDismiss = { showHandover = false })
    }
}

@Composable
private fun MenuText(label: String, hint: String?, danger: Boolean = false) {
    val p = agentPalette()
    Column {
        Text(label, color = if (danger) p.red else p.text, fontSize = 14.sp)
        if (!hint.isNullOrBlank()) Text(hint, fontSize = 11.sp, color = p.muted, maxLines = 1)
    }
}

@Composable
private fun StatusStrip(s: AgentSessionState, now: Long, onStop: () -> Unit, onHandover: () -> Unit) {
    val p = agentPalette()
    val since = isoMillis(s.stateSince.ifBlank { s.modified })
    data class Look(val bg: Color, val fg: Color, val dot: Color, val title: String, val sub: String)
    val look = when (s.state) {
        "question" -> Look(p.redBg, p.red, p.red, "Needs your answer", "Waiting for you · ${agoShort(since, now)}")
        "permission" -> Look(p.redBg, p.red, p.red, "Needs your permission", "Waiting for you · ${agoShort(since, now)}")
        "error" -> Look(p.redBg, p.red, p.red, "Turn failed", "Waiting for you · ${agoShort(since, now)}")
        "working" -> Look(p.greenBg, p.green, p.green, "Working · ${agoShort(since, now)}", s.activity.ifBlank { "Working" })
        "starting" -> Look(p.greenBg, p.green, p.green, "Starting new session · ${agoShort(since, now)}", s.cwd)
        "limited" -> Look(p.yellowBg, p.yellow, p.yellow, "Usage limited", s.activity.ifBlank { "The provider reported a usage limit" })
        else -> Look(p.surfaceAlt, p.text, p.grey, "Ready for instructions", "Last activity ${agoLong(isoMillis(s.modified), now)}")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).background(look.bg).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (s.state == "working" || s.state == "starting") PulsingDot(look.dot) else StatusDot(look.dot)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(look.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = look.fg)
            Text(look.sub, fontSize = 11.sp, color = look.fg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.capabilities?.canInterrupt == true) AgentButton("■ Stop", small = true, style = AgentButtonStyle.DANGER, onClick = onStop)
        if (s.state == "limited" && s.cwd.isNotBlank()) AgentButton("Hand over", small = true, style = AgentButtonStyle.WARN, onClick = onHandover)
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.alpha(alpha)) { StatusDot(color) }
}

@Composable
private fun UserBubble(text: String, tag: String?, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val p = agentPalette()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(p.raised, RoundedCornerShape(15.dp, 15.dp, 4.dp, 15.dp))
                .border(1.dp, p.lineStrong, RoundedCornerShape(15.dp, 15.dp, 4.dp, 15.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            SelectionContainer { Text(text.trim(), fontSize = 14.sp, lineHeight = 20.sp, color = p.text) }
            if (tag != null || actionLabel != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tag != null) Text(tag, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = p.yellow, modifier = Modifier.weight(1f, fill = false))
                if (actionLabel != null) Text(
                    actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.blue,
                    modifier = Modifier.clickable(onClick = onAction).padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AgentBubble(provider: String, text: String) {
    val p = agentPalette()
    Column(
        Modifier
            .widthIn(max = 340.dp)
            .background(p.surface, RoundedCornerShape(15.dp, 15.dp, 15.dp, 4.dp))
            .border(1.dp, p.line, RoundedCornerShape(15.dp, 15.dp, 15.dp, 4.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(provider.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = p.faint)
        val styled = remember(text, p) { markdownLite(text.trim(), p) }
        SelectionContainer { Text(styled, fontSize = 14.sp, lineHeight = 20.sp, color = p.text, modifier = Modifier.padding(top = 3.dp)) }
    }
}

private val INLINE_MD = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)""")

/** Just enough Markdown for agent answers: headings, bullets, bold, inline code, links (label only). */
private fun markdownLite(text: String, p: AgentPalette) = buildAnnotatedString {
    var inFence = false
    text.lines().forEachIndexed { index, raw ->
        if (index > 0) append("\n")
        if (raw.trimStart().startsWith("```")) { inFence = !inFence; return@forEachIndexed }
        if (inFence) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = p.muted)) { append(raw) }
            return@forEachIndexed
        }
        val heading = Regex("^#{1,6}\\s+").find(raw)
        var line = if (heading != null) raw.substring(heading.range.last + 1) else raw
        val bullet = Regex("^(\\s*)[-*]\\s+").find(line)
        if (bullet != null) {
            append(bullet.groupValues[1] + "• ")
            line = line.substring(bullet.range.last + 1)
        }
        val start = length
        var cursor = 0
        INLINE_MD.findAll(line).forEach { m ->
            append(line.substring(cursor, m.range.first))
            when {
                m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
                m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, background = p.raised)) { append(m.groupValues[2]) }
                else -> withStyle(SpanStyle(color = p.blue)) { append(m.groupValues[3]) }
            }
            cursor = m.range.last + 1
        }
        append(line.substring(cursor))
        if (heading != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}

@Composable
private fun TypingBubble(provider: String, activity: String) {
    val p = agentPalette()
    val transition = rememberInfiniteTransition(label = "typing")
    Column(
        Modifier
            .background(p.surface, RoundedCornerShape(15.dp, 15.dp, 15.dp, 4.dp))
            .border(1.dp, p.line, RoundedCornerShape(15.dp, 15.dp, 15.dp, 4.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(provider.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = p.faint)
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val a by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "dot$i")
                Box(Modifier.alpha(a)) { StatusDot(p.muted, 6) }
            }
        }
        if (activity.isNotBlank()) Text(activity, fontSize = 11.sp, color = p.green, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolGroupCard(
    group: TimelineItem.Tools,
    open: Boolean,
    onToggle: () -> Unit,
    isToolOpen: (Int) -> Boolean,
    onToolToggle: (Int) -> Unit
) {
    val p = agentPalette()
    val items = group.items
    val plus = items.sumOf { it.plus }
    val minus = items.sumOf { it.minus }
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.surfaceAlt, RoundedCornerShape(12.dp))
            .border(1.dp, p.line, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable(onClick = onToggle).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ChevronRight, null, tint = p.faint, modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f))
            Text(
                buildAnnotatedString {
                    append(toolSummary(items))
                    if (plus + minus > 0) {
                        append("  ")
                        withStyle(SpanStyle(color = p.green)) { append("+$plus") }
                        append(" ")
                        withStyle(SpanStyle(color = p.red)) { append("−$minus") }
                    }
                },
                fontSize = 12.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
            if (items.any { it.failed }) AgentTag("FAILED", p.red, p.redBg, p.redLine)
            if (items.any { it.running }) Box(Modifier.padding(start = 6.dp)) { PulsingDot(p.green) }
        }
        if (open) items.forEachIndexed { i, tool ->
            Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).background(p.raised, RoundedCornerShape(6.dp)).border(1.dp, p.line, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text(tool.kind.glyph, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = p.muted) }
                    Text(
                        if (tool.target.isBlank()) tool.name else "${tool.name} · ${tool.target}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    when {
                        tool.running -> PulsingDot(p.green)
                        tool.failed -> Text("✕", color = p.red, fontWeight = FontWeight.Bold)
                        tool.plus + tool.minus > 0 -> Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = p.green)) { append("+${tool.plus}") }
                                append(" ")
                                withStyle(SpanStyle(color = p.red)) { append("−${tool.minus}") }
                            }, fontSize = 11.sp
                        )
                    }
                    if (tool.diff != null || !tool.output.isNullOrBlank()) {
                        Text(
                            if (isToolOpen(i)) "Hide" else if (tool.diff != null) "Diff" else "Output",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.blue,
                            modifier = Modifier.clickable { onToolToggle(i) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }
                }
                if (isToolOpen(i)) {
                    val diff = tool.diff
                    if (diff != null) DiffBlock(diff) else MonoBlock(tool.output.orEmpty().take(6000))
                }
            }
        }
    }
}

@Composable
private fun DiffBlock(lines: List<Pair<String, String>>) {
    val p = agentPalette()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(p.surfaceAlt, RoundedCornerShape(8.dp))
            .border(1.dp, p.line, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            buildAnnotatedString {
                lines.take(300).forEachIndexed { i, (kind, text) ->
                    if (i > 0) append("\n")
                    val (prefix, color) = when (kind) {
                        "ADD" -> "+ " to p.green
                        "DEL" -> "- " to p.red
                        "HEADER" -> "" to p.faint
                        else -> "  " to p.muted
                    }
                    withStyle(SpanStyle(color = color)) { append(prefix + text) }
                }
            },
            fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 15.sp, softWrap = false
        )
    }
}

@Composable
private fun QuestionCard(s: AgentSessionState, onChoose: (String) -> Unit, onWrite: () -> Unit) {
    val p = agentPalette()
    val canAnswer = s.capabilities?.canAnswerQuestions == true
    Column(
        Modifier.fillMaxWidth().background(p.redBg, RoundedCornerShape(14.dp)).border(1.dp, p.redLine, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Text("QUESTION", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = p.red)
        Text(s.question?.text ?: s.activity, fontSize = 14.sp, lineHeight = 20.sp, color = p.text, modifier = Modifier.padding(top = 5.dp))
        if (canAnswer) {
            s.question?.options.orEmpty().forEach { option ->
                AgentButton(option, Modifier.fillMaxWidth().padding(top = 6.dp)) { onChoose(option) }
            }
            Text(
                "Write a different answer…", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.blue,
                modifier = Modifier.clickable(onClick = onWrite).padding(top = 10.dp, bottom = 2.dp)
            )
        } else {
            Text(s.capabilities?.note?.ifBlank { null } ?: "Answer it in the client that asked (or the raw terminal).", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ErrorCard(s: AgentSessionState, onRetry: () -> Unit, onHandover: () -> Unit) {
    val p = agentPalette()
    Column(
        Modifier.fillMaxWidth().background(p.redBg, RoundedCornerShape(14.dp)).border(1.dp, p.redLine, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Text("TURN FAILED", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = p.red)
        SelectionContainer { Text(s.error ?: s.activity, fontSize = 13.sp, lineHeight = 18.sp, color = p.text, modifier = Modifier.padding(top = 5.dp)) }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentButton("Retry", Modifier.weight(1f), AgentButtonStyle.PRIMARY, onClick = onRetry)
            if (s.cwd.isNotBlank() && !s.id.startsWith("new-")) AgentButton("Hand over…", Modifier.weight(1f), onClick = onHandover)
        }
    }
}

@Composable
private fun Composer(
    session: AgentSessionState,
    online: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    focus: FocusRequester,
    commandsOpen: Boolean,
    onCommands: (Boolean) -> Unit,
    onCommand: (String) -> Unit,
    sendMode: String?,
    onSendMode: (String) -> Unit,
    onSend: () -> Unit
) {
    val p = agentPalette()
    val caps = session.capabilities
    val canSend = online && caps?.canSend == true
    data class Mode(val placeholder: String, val hint: String?, val hintColor: Color)
    val options = session.sendOptions.orEmpty()
    val selected = options.firstOrNull { it.mode == sendMode } ?: options.firstOrNull()
    val mode = when {
        !online -> Mode("Continue this session…", "Hub offline — reconnect to send", p.yellow)
        session.state == "starting" -> Mode("Starting…", "The session is starting on the PC.", p.muted)
        selected != null -> Mode(
            when (selected.mode) { "queue" -> "Queue a message…"; "type", "inbox" -> "Message the session open on the PC…"; else -> "Continue this session…" },
            selected.hint, if (selected.mode == "now") p.yellow else p.muted
        )
        caps?.canSend != true -> Mode("Read-only here", caps?.note?.ifBlank { null } ?: "This session cannot be continued from Omni.", p.muted)
        session.state == "question" -> Mode("Type your answer…", "Sending answers the question", p.muted)
        session.state == "error" -> Mode("Message…", "Sending starts a new turn with your message", p.muted)
        session.state == "limited" -> Mode("Continue this session…", "The provider reported a usage limit; this may fail until it resets", p.yellow)
        else -> Mode("Continue this session…", null, p.muted)
    }
    Column(Modifier.fillMaxWidth().background(p.surfaceAlt).padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 10.dp)) {
        if (online && options.size > 1) {
            Row(Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option -> ModeChip(option.label, option == selected) { onSendMode(option.mode) } }
            }
        }
        if (mode.hint != null) Text(mode.hint, fontSize = 11.sp, color = mode.hintColor, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.surface, RoundedCornerShape(16.dp))
                .border(1.dp, p.lineStrong, RoundedCornerShape(16.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box {
                Box(
                    Modifier.size(42.dp).clickable(enabled = canSend) { onCommands(true) },
                    contentAlignment = Alignment.Center
                ) { Text("/", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (canSend) p.muted else p.faint) }
                DropdownMenu(commandsOpen, { onCommands(false) }) {
                    val commands = PROVIDER_COMMANDS[session.provider].orEmpty()
                    if (commands.isEmpty()) DropdownMenuItem(text = { Text("No commands for ${session.provider}") }, onClick = { onCommands(false) })
                    commands.forEach { (cmd, hint) ->
                        DropdownMenuItem(text = { MenuText(cmd, hint) }, onClick = { onCommands(false); onCommand(cmd) })
                    }
                    DropdownMenuItem(text = { MenuText("About commands", "Headless turns may ignore interactive-only commands; use the raw terminal for those") }, onClick = { onCommands(false) })
                }
            }
            Box(Modifier.weight(1f).heightIn(min = 42.dp).padding(horizontal = 4.dp, vertical = 10.dp)) {
                if (draft.isEmpty()) Text(mode.placeholder, fontSize = 14.sp, color = p.faint)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    enabled = canSend || draft.isNotEmpty(),
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = p.text),
                    cursorBrush = SolidColor(p.text),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            val sendable = canSend && draft.isNotBlank()
            Box(
                Modifier
                    .size(42.dp)
                    .background(if (sendable) p.text else p.raised, RoundedCornerShape(12.dp))
                    .clickable(enabled = sendable, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (sendable) p.surface else p.faint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = agentPalette()
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = if (selected) p.surface else p.text,
        modifier = Modifier
            .background(if (selected) p.text else p.surface, RoundedCornerShape(50))
            .border(1.dp, if (selected) p.text else p.lineStrong, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
