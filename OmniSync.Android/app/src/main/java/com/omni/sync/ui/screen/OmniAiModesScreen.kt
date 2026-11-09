package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.*
import com.google.gson.JsonObject
import android.content.Intent
import android.net.Uri
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tonal surfaces, rounded session rows and unobtrusive actions follow T3 Android's hierarchy. */
private data class AiPalette(
    val bg: Color, val surface: Color, val raised: Color, val selected: Color,
    val border: Color, val text: Color, val muted: Color, val accent: Color,
    val accentBg: Color, val green: Color, val amber: Color
)
@Composable private fun palette(): AiPalette = if (isSystemInDarkTheme()) AiPalette(
    Color(0xFF101319), Color(0xFF191E26), Color(0xFF222A35), Color(0xFF2B394B),
    Color(0xFF303946), Color(0xFFF1F3F6), Color(0xFF9DA8B8), Color(0xFFAFCEFF),
    Color(0xFF334862), Color(0xFF81D6A7), Color(0xFFF1BD77)
) else AiPalette(
    Color(0xFFF8F9FB), Color.White, Color(0xFFF0F3F7), Color(0xFFE2ECFA),
    Color(0xFFE0E4EA), Color(0xFF1B2533), Color(0xFF697789), Color(0xFF3262A4),
    Color(0xFFDDEAFF), Color(0xFF267751), Color(0xFF98651A)
)
private fun AgentSessionUi.needsAttention() =
    attention.isNotBlank() && !attention.equals("None", true)
private fun AgentSessionUi.projectName(): String =
    workspace?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() } ?: "No project"

private fun AgentSessionUi.listGroup(sortMode: String): String =
    if (sortMode == "project") projectName() else groupName()

private fun AgentSessionUi.groupName(): String = when {
    needsAttention() || unread == true -> "NEEDS YOU"
    activity.equals("Working", true) || activity.equals("Running", true) ||
        activity.equals("Active", true) -> "WORKING"
    else -> "CONVERSATIONS"
}
private fun AgentSessionUi.groupRank(): Int = when (groupName()) {
    "NEEDS YOU" -> 0
    "WORKING" -> 1
    else -> 2
}
/** Activity timestamps are optional in the provider protocol; unknown is always oldest. */
private fun activityMillis(iso: String?): Long =
    runCatching { java.time.Instant.parse(iso ?: "").toEpochMilli() }.getOrDefault(0L)

private fun AgentSessionUi.dotColor(p: AiPalette): Color = when {
    activity.equals("Working", true) || activity.equals("Running", true) ||
        activity.equals("Active", true) -> p.green
    activity.equals("Error", true) || activity.equals("Errored", true) ||
        activity.equals("Failed", true) -> Color(0xFFE38D8D)
    needsAttention() -> p.amber
    else -> p.muted
}
@Composable private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
@Composable private fun ActionIcon(icon: ImageVector, desc: String, p: AiPalette,
    enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = desc, tint = if (enabled) p.text else p.muted)
    }
}
@Composable private fun SheetTitle(title: String, detail: String?, p: AiPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = p.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, color = p.muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun SheetAction(label: String, p: AiPalette, tint: Color = p.text, onClick: () -> Unit) {
    Text(label, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(p.raised).clickable(onClick = onClick).padding(16.dp),
        color = tint, style = MaterialTheme.typography.bodyLarge)
}

/** Session list -> conversation, with a fixed composer and secondary tools in focused sheets. */
@Composable
fun OmniAiModesScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp),
    initialSessionId: String? = null
) {
    val client = remember(signalRClient) { signalRClient.genericAgents }
    val scope = rememberCoroutineScope()
    val p = palette()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Sorting/filter toggles are local; T3 browser hide/snooze state is desktop-owned
    // and comes from the authenticated provider snapshot.
    val viewPrefs = remember(context) { context.getSharedPreferences("ai-session-view-v1", Context.MODE_PRIVATE) }
    var viewRevision by remember { mutableIntStateOf(0) }
    var sortMode by rememberSaveable { mutableStateOf(viewPrefs.getString("sort", "activity") ?: "activity") }
    var hideUnloaded by rememberSaveable { mutableStateOf(viewPrefs.getBoolean("hideUnloaded", false)) }
    var showWorkers by rememberSaveable { mutableStateOf(viewPrefs.getBoolean("showWorkers", true)) }
    val now = rememberNow()
    val providers by client.providers.collectAsState()
    val sessions by client.sessions.collectAsState()
    val selectedKey by client.selected.collectAsState()
    val history by client.history.collectAsState()
    val older by client.olderHistoryCursor.collectAsState()
    val historyNotice by client.historyNotice.collectAsState()
    val archiveOn by client.archiveEnabled.collectAsState()
    val archiveResults by client.archiveSearch.collectAsState()
    val outbox by client.outbox.collectAsState()
    val remoteQueue by client.remoteQueue.collectAsState()
    val lastProvider by client.lastProvider.collectAsState()
    val status by client.status.collectAsState()
    val historyLoading by client.historyLoading.collectAsState()
    val createdKey by client.createdSessionKey.collectAsState()
    val online by signalRClient.hubAuthenticated.collectAsState()
    var busyPolicy by remember { mutableStateOf<T3BusyPolicyState?>(null) }
    var keepT3BusyUpdating by remember { mutableStateOf(false) }
    var pendingBusyMinutes by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(online) {
        if (!online) {
            busyPolicy = null
        } else {
            while (true) {
                if (!keepT3BusyUpdating) {
                    try { busyPolicy = signalRClient.t3BusyPolicy() }
                    catch (error: Exception) { busyPolicy = null }
                }
                delay(15_000)
            }
        }
    }

    var listMode by rememberSaveable { mutableStateOf(initialSessionId == null) }
    var initialApplied by remember(initialSessionId) { mutableStateOf(false) }
    var sessionSearch by rememberSaveable { mutableStateOf("") }
    var sessionFilter by rememberSaveable { mutableStateOf("All") }
    var threadKind by rememberSaveable {
        mutableStateOf(viewPrefs.getString("threadKind", "t3/chatgpt") ?: "t3/chatgpt")
    }
    var rowOptions by remember { mutableStateOf<AgentSessionUi?>(null) }
    var rowControls by remember { mutableStateOf<JsonObject?>(null) }
    var rowControlError by remember { mutableStateOf<String?>(null) }
    var rowControlBusy by remember { mutableStateOf(false) }
    var rowActionPage by remember { mutableStateOf("Session") }
    var closeConfirmation by remember { mutableStateOf<AgentSessionUi?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var deliveryMode by rememberSaveable { mutableStateOf("WhenIdle") }
    var maxActiveWorkers by rememberSaveable { mutableIntStateOf(4) }
    var createDeliveryMode by rememberSaveable { mutableStateOf("WhenIdle") }
    var loadedDraftFor by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var creationBusy by remember { mutableStateOf(false) }
    var addressMode by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var humanInbox by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }
    var switchProvider by remember { mutableStateOf<AgentProviderUi?>(null) }
    var create by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf("") }
    var newSessionTitle by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var verify by remember { mutableStateOf<AddressIdentityPreview?>(null) }
    var receiptToRemove by remember { mutableStateOf<PendingAgentUi?>(null) }
    var followingLatest by remember(selectedKey) { mutableStateOf(true) }

    val active = providers.firstOrNull { it.active }
    // Keep the locally cached conversation list available while Hub reconnects.
    val listProvider = active?.id ?: lastProvider ?: "t3"
    val selected = sessions.firstOrNull { it.key == selectedKey }
    val pending = outbox.count { it.state in listOf("Pending", "Blocked", "InFlight", "Accepted", "Queued") }
    val uncertain = outbox.count { it.state == "Unknown" }
    val outboxLabel = "Outbox" + when {
        pending > 0 && uncertain > 0 -> " ($pending pending · $uncertain unconfirmed)"
        pending > 0 -> " ($pending pending)"
        uncertain > 0 -> " ($uncertain unconfirmed)"
        else -> ""
    }
    val localSessionMessages = outbox.filter { it.provider == selected?.provider &&
        it.targetType == "session" && it.target == selected?.id &&
        it.state !in listOf("Delivered", "Failed") }
    val draftKey = if (addressMode) "contact-composer" else "session:" + (selectedKey ?: "")
    LaunchedEffect(draftKey) {
        loadedDraftFor = null
        message = client.getDraft(draftKey)
        loadedDraftFor = draftKey
    }
    // Selecting another session always returns to direct session messaging.
    // Otherwise the old contact address can silently follow the user between
    // conversations and turn a normal Send tap into a contact message.
    LaunchedEffect(selectedKey) { addressMode = false }
    val attention = sessions.count { it.needsAttention() || it.unread == true }
    // Hub connectivity alone does not prove the T3 provider is responding.
    val t3Connected = online && providers.any {
        it.id == "t3" && it.active && it.capabilities.contains("Submit")
    }
    val sessionDeliveryReady = selected != null &&
        (!online || (selected.provider == active?.id &&
            selected.capabilities.contains("Submit") &&
            (selected.modes.contains("WhenIdle") || selected.modes.contains("Queue") ||
             selected.modes.contains("WaitForSlot") || selected.modes.contains("Steer"))))
    val canSend = loadedDraftFor == draftKey && message.isNotBlank() && (if (addressMode)
        address.trim().contains("@") && (active?.id ?: lastProvider) != null &&
            (!online || active?.capabilities?.contains("SubmitToAgent") == true)
        else sessionDeliveryReady)
    val deliveryHint = when {
        !online -> "Offline · draft saved; tap Send to queue"
        addressMode -> "Recipient confirmation required"
        selected == null -> "Select a conversation"
        selected.provider != active?.id -> "Activate ${selected.provider} to send"
        !sessionDeliveryReady -> "This session cannot receive messages here"
        selected.kind == "t3/thread" -> "Native T3 queue · does not interrupt the current run"
        deliveryMode == "WaitForSlot" -> "Waits for capacity; T3 queues the message"
        deliveryMode == "Steer" -> "Immediate; may interrupt current reply"
        selected.modes.contains("Queue") -> "Native queue"
        else -> "Delivers when idle"
    }
    fun isT3Browser(s: AgentSessionUi) = s.provider == "t3" && s.kind == "t3/chatgpt"
    LaunchedEffect(rowOptions?.key, online) {
        rowControls = null
        rowControlError = null
        rowActionPage = "Session"
        val target = rowOptions
        if (target != null && online && isT3Browser(target) &&
            target.capabilities.contains("RemoteControl")) {
            try { rowControls = client.sessionControls(target) }
            catch (e: Exception) { rowControlError = "T3 control status unavailable: " + e.message }
        }
    }
    fun performRowControl(target: AgentSessionUi, command: String,
                          options: Map<String, Any?> = emptyMap()) {
        if (!online || !target.capabilities.contains("RemoteControl")) {
            rowControlError = "T3 session controls require the connected desktop provider."
            return
        }
        if (rowControlBusy) return
        rowControlBusy = true
        scope.launch {
            try {
                val receipt = client.sessionAction(target, "Control",
                    mapOf("command" to command, "options" to options))
                note = "${command.replace('_', ' ')}: $receipt"
                rowControlError = null
                if (command == "close") rowOptions = null
                else if (rowOptions?.key == target.key) {
                    try { rowControls = client.sessionControls(target) }
                    catch (e: Exception) {
                        rowControlError = "Action delivered, but status refresh failed: " + e.message
                    }
                }
            } catch (e: Exception) {
                rowControlError = "${command.replace('_', ' ')} failed: " + e.message +
                    ". If the operation timed out, inspect its state before retrying."
            } finally { rowControlBusy = false }
        }
    }
    fun isHidden(s: AgentSessionUi) = if (isT3Browser(s)) s.visibilityHidden == true
        else viewPrefs.getBoolean("hidden:${s.key}", false)
    fun snoozedUntil(s: AgentSessionUi) = if (isT3Browser(s)) s.visibilitySnoozedUntil ?: 0L
        else viewPrefs.getLong("snooze:${s.key}", 0L)
    fun isSnoozed(s: AgentSessionUi) = snoozedUntil(s) > now
    fun setHidden(s: AgentSessionUi, hidden: Boolean) {
        if (isT3Browser(s)) {
            if (!online || !s.capabilities.contains("ManageVisibility")) {
                note = "T3 visibility controls unavailable. Reconnect and refresh."; return
            }
            scope.launch {
                try {
                    client.sessionAction(s, "Visibility", mapOf("hidden" to hidden))
                    delay(400) // Wait for the desktop's catalog-change publication.
                    client.refresh()
                } catch (e: Exception) { note = "T3 hide failed: " + e.message }
            }
        } else {
            viewPrefs.edit().putBoolean("hidden:${s.key}", hidden).apply()
            viewRevision++
        }
    }
    fun setSnooze(s: AgentSessionUi, until: Long) {
        if (isT3Browser(s)) {
            if (!online || !s.capabilities.contains("ManageVisibility")) {
                note = "T3 snooze controls unavailable. Reconnect and refresh."; return
            }
            scope.launch {
                try {
                    client.sessionAction(s, "Visibility",
                        mapOf("snoozedUntil" to until.takeIf { it > 0 }))
                    delay(400)
                    client.refresh()
                } catch (e: Exception) { note = "T3 snooze failed: " + e.message }
            }
        } else {
            viewPrefs.edit().putLong("snooze:${s.key}", until).apply()
            viewRevision++
        }
    }
    fun openSession(s: AgentSessionUi) {
        viewPrefs.edit().putLong("visited:${s.key}", System.currentTimeMillis()).apply()
        if (s.provider == "t3" && s.kind in listOf("t3/chatgpt", "t3/thread")) {
            threadKind = s.kind
            viewPrefs.edit().putString("threadKind", threadKind).apply()
        }
        viewRevision++
        listMode = false
        // Browser wake, unhide, and conversation readiness now share one path.
        // Never require a second Load-on-PC tap after selecting a row.
        if (!isT3Browser(s)) {
            if (isSnoozed(s)) setSnooze(s, 0L)
            if (isHidden(s)) setHidden(s, false)
        }
        scope.launch { client.select(s.key) }
    }
    val hiddenCount = remember(sessions, viewRevision) { sessions.count { isHidden(it) } }
    val snoozedCount = remember(sessions, viewRevision, now / MINUTE_MS) {
        sessions.count { !isHidden(it) && isSnoozed(it) }
    }
    val visibleSessions = remember(sessions, sessionSearch, sessionFilter, sortMode,
        hideUnloaded, showWorkers, viewRevision, now / MINUTE_MS, threadKind, listProvider) {
        sessions.asSequence().filter { s ->
            if (listProvider == "t3") s.provider == "t3" && s.kind == threadKind
            else s.provider == listProvider
        }.filter { s ->
            sessionSearch.isBlank() || s.title.contains(sessionSearch, true) ||
                s.id.contains(sessionSearch, true) || s.kind.contains(sessionSearch, true) ||
                s.workspace?.contains(sessionSearch, true) == true
        }.filter { s ->
            when (sessionFilter) {
                "Hidden" -> isHidden(s)
                "Snoozed" -> !isHidden(s) && isSnoozed(s)
                else -> !isHidden(s) && !isSnoozed(s)
            }
        }.filter { s ->
            sessionFilter != "Attention" || s.needsAttention() || s.unread == true
        }.filter { s ->
            sessionFilter != "Active" || s.activity.equals("Working", true) ||
                s.activity.equals("Running", true) || s.activity.equals("Active", true)
        }.filter { s ->
            sessionFilter == "Hidden" || sessionFilter == "Snoozed" ||
                (showWorkers || s.parentId == null) &&
                (!hideUnloaded || s.kind != "t3/chatgpt" ||
                    s.reachability == "Reachable" ||
                    // Recently active sessions remain visible, as in T3.
                    maxOf(activityMillis(s.lastActivityAt),
                        viewPrefs.getLong("visited:${s.key}", 0L)) > now - 2 * HOUR_MS)
        }.toList().let { list ->
            when (sessionFilter) {
                "Hidden" -> list.sortedByDescending { if (isT3Browser(it)) it.visibilityHiddenAt ?: 0L
                    else viewPrefs.getLong("hiddenAt:${it.key}", 0L) }
                "Snoozed" -> list.sortedByDescending { if (isT3Browser(it)) it.visibilitySnoozedAt ?: 0L
                    else viewPrefs.getLong("snoozedAt:${it.key}", 0L) }
                else -> list.sortedWith(compareBy<AgentSessionUi> { if (sortMode == "project") 0 else it.groupRank() }
                    .thenBy { if (sortMode == "project") it.projectName().lowercase() else "" }
                    .thenByDescending { it.needsAttention() }
                    .thenByDescending { it.unread == true }
                    .thenByDescending { if (sortMode == "started") it.createdAt ?: it.visibilityCreatedAt ?: 0L
                        else maxOf(activityMillis(it.lastActivityAt), viewPrefs.getLong("visited:${it.key}", 0L)) }
                    .thenBy { it.key })
            }
        }
    }

    LaunchedEffect(initialSessionId, sessions) {
        if (!initialApplied && initialSessionId != null) {
            sessions.firstOrNull { it.id == initialSessionId || it.key == initialSessionId }?.let {
                initialApplied = true
                listMode = false
                client.select(it.key)
            }
        }
    }
    // Opening a row already starts its full history read. Do not issue a second
    // simultaneous read merely because the conversation screen became visible.
    var wasConnected by remember { mutableStateOf(online) }
    LaunchedEffect(online) {
        if (online && !wasConnected && selectedKey != null) client.select(selectedKey)
        wasConnected = online
    }
    LaunchedEffect(online, listMode, selectedKey) {
        if (online && !listMode && selectedKey != null) {
            while (true) {
                delay(8_000)
                client.select(selectedKey, reload = true)
            }
        }
    }
    BackHandler(enabled = humanInbox || !listMode) {
        if (humanInbox) humanInbox = false else listMode = true
    }
    fun openCreate() {
        val provider = active ?: run {
            note = "Connect and activate the T3 provider before creating a session."
            return
        }
        kind = if (provider.id == "t3") threadKind else provider.kinds.firstOrNull().orEmpty()
        if (kind !in provider.kinds) {
            note = "Selected thread type is unavailable from ${provider.name}."
            return
        }
        if (!online || !provider.capabilities.contains("CreateSession")) {
            note = "New sessions require a connected ${provider.name} creator."
            return
        }
        workspace = ""; newSessionTitle = ""; firstMessage = ""; createDeliveryMode = "WhenIdle"; create = true
    }
    fun submit() {
        if (!canSend) return
        try {
            val contactReceipt = if (addressMode) client.enqueueAddress(address.trim(), message.trim(),
                active?.id ?: lastProvider ?: error("No provider selected"))
                else null
            if (!addressMode) client.enqueueSession(selected ?: error("Select a session"), message.trim(),
                deliveryMode, if (deliveryMode=="WaitForSlot") maxActiveWorkers else null)
            message = ""
            client.saveDraft(draftKey, "")
            note = when {
                addressMode && online -> "Message saved. Confirm the contact before it can be delivered."
                online -> if (deliveryMode == "WaitForSlot")
                    "Message queued for an available worker slot. See Outbox for receipts."
                    else "Message queued. See its delivery receipt in Outbox."
                else -> "Message saved on this phone for reconnect."
            }
            if (contactReceipt != null && online) scope.launch {
                try { verify = client.previewAddressRecipient(contactReceipt) }
                catch (e: Exception) {
                    note = "Message saved. Verify the contact from Outbox: " + e.message
                }
            }
        } catch (e: Exception) { note = "Unable to queue: " + e.message }
    }
    fun operation(action: String, extras: Map<String, Any?> = emptyMap()) {
        val target = selected ?: return
        if (actionBusy) return
        actionBusy = true
        scope.launch {
            try {
                val receipt = client.sessionAction(target, action, extras)
                note = "$action: $receipt"
            } catch (e: Exception) { note = action + " failed: " + e.message }
            finally { actionBusy = false }
        }
    }

    // The app-wide disconnected theme uses white-on-burgundy text. Scope a
    // consistent tonal Material palette to every AI popup, sheet and field.
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = p.accent, onPrimary = p.bg,
        secondary = p.accent, onSecondary = p.bg,
        tertiary = p.accent, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.muted,
        outline = p.border, surfaceTint = p.accent
    )) {
    if (pendingBusyMinutes != null) {
        AlertDialog(
            onDismissRequest = { pendingBusyMinutes = null },
            title = { Text("Timed T3 work") },
            text = { Text("Keep assigning work for ${pendingBusyMinutes!! / 60} hour(s), then let running sessions finish. Once all T3 sessions are idle, Windows will force shutdown, closing other apps without saving.") },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = pendingBusyMinutes ?: return@TextButton
                    pendingBusyMinutes = null
                    keepT3BusyUpdating = true
                    scope.launch {
                        try { busyPolicy = signalRClient.t3BusyPolicy(scheduleMinutes = minutes) }
                        catch (error: Exception) { note = "Timed run failed: " + error.message }
                        finally { keepT3BusyUpdating = false }
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { pendingBusyMinutes = null }) { Text("Cancel") } }
        )
    }
    if (humanInbox) {
        Box(Modifier.fillMaxSize().padding(parentPadding)) {
            HumanInboxScreen(signalRClient, onBack = { humanInbox = false })
        }
    } else {
        Column(Modifier.fillMaxSize().padding(parentPadding).background(p.bg).imePadding()) {
            // Dense, predictable toolbar; everything else lives in an overflow menu.
            Row(Modifier.fillMaxWidth().background(p.surface).heightIn(min = 60.dp)
                .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!listMode) ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, "All sessions", p) { listMode = true }
                else Box(Modifier.padding(horizontal = 10.dp).size(34.dp)
                    .background(p.accentBg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = p.accent, modifier = Modifier.size(20.dp))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (listMode) "AI" else selected?.title ?: "Conversation",
                        color = p.text, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(9.dp))
                    Dot(if (t3Connected) p.green else p.amber)
                    if (listMode) Text(if (t3Connected) "  T3" else "  T3 offline",
                        color = if (t3Connected) p.green else p.amber,
                        style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(busyPolicy?.busyUntil?.let {
                        "Busy · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
                    } ?: "Busy", color = if (busyPolicy?.keepT3Busy == true) p.green else p.muted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = busyPolicy?.keepT3Busy == true,
                        enabled = online && busyPolicy != null && !keepT3BusyUpdating,
                        onCheckedChange = { enabled ->
                            keepT3BusyUpdating = true
                            scope.launch {
                                try { busyPolicy = signalRClient.t3BusyPolicy(enabled) }
                                catch (error: Exception) {
                                    busyPolicy = null
                                    note = "Keep T3 Busy failed: " + error.message
                                } finally { keepT3BusyUpdating = false }
                            }
                        },
                        modifier = Modifier.size(width = 48.dp, height = 40.dp)
                            .scale(0.75f).semantics {
                                contentDescription = "Keep T3 Busy"
                                stateDescription = when (busyPolicy?.keepT3Busy) {
                                    true -> "On"
                                    false -> "Off"
                                    null -> "Unavailable"
                                }
                            }
                    )
                    Box {
                        var timingMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { timingMenu = true },
                            enabled = online && busyPolicy != null && !keepT3BusyUpdating,
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Timer, "Schedule Busy and shutdown", tint = p.accent,
                                modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = timingMenu, onDismissRequest = { timingMenu = false }) {
                            listOf(60, 120, 240, 360).forEach { minutes ->
                                DropdownMenuItem(text = { Text("Work ${minutes / 60}h, then shut down when idle") },
                                    onClick = { timingMenu = false; pendingBusyMinutes = minutes })
                            }
                            if (busyPolicy?.busyUntil != null) DropdownMenuItem(
                                text = { Text("Cancel timed work and shutdown") },
                                onClick = {
                                    timingMenu = false
                                    keepT3BusyUpdating = true
                                    scope.launch {
                                        try { busyPolicy = signalRClient.t3BusyPolicy(false) }
                                        catch (error: Exception) { note = "Cancel failed: " + error.message }
                                        finally { keepT3BusyUpdating = false }
                                    }
                                })
                        }
                    }
                }
                ActionIcon(Icons.Default.Refresh, if (online) "Refresh" else "Reconnect", p) {
                    if (!online) {
                        signalRClient.startConnection()
                        note = "Connecting to OmniHub…"
                    } else scope.launch {
                        client.refresh()
                        if (!listMode && selectedKey != null) client.select(selectedKey)
                    }
                }
                if (listMode) {
                    ActionIcon(Icons.Default.Add, "New ${if (threadKind == "t3/thread") "native T3 thread" else "ChatGPT session"}", p) { openCreate() }
                    ActionIcon(Icons.Default.MailOutline, "Human inbox", p) { humanInbox = true }
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    ActionIcon(Icons.Default.MoreVert, "More options", p) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (listMode) {
                            listOf("activity" to "Recent activity", "project" to "Project",
                                "started" to "Creation date").forEach { (value, label) ->
                                DropdownMenuItem(text = { Text((if (sortMode == value) "✓ " else "") + "Sort: " + label) },
                                    onClick = {
                                        sortMode = value
                                        viewPrefs.edit().putString("sort", value).apply()
                                        menu = false
                                    })
                            }
                            DropdownMenuItem(text = { Text((if (showWorkers) "✓ " else "") + "Show workers") },
                                onClick = {
                                    showWorkers = !showWorkers
                                    viewPrefs.edit().putBoolean("showWorkers", showWorkers).apply()
                                    menu = false
                                })
                            DropdownMenuItem(text = { Text((if (hideUnloaded) "✓ " else "") + "Hide unloaded") },
                                onClick = {
                                    hideUnloaded = !hideUnloaded
                                    viewPrefs.edit().putBoolean("hideUnloaded", hideUnloaded).apply()
                                    menu = false
                                })
                            DropdownMenuItem(text = { Text("Snoozed sessions ($snoozedCount)") },
                                onClick = { sessionFilter = "Snoozed"; menu = false })
                            DropdownMenuItem(text = { Text("Hidden sessions ($hiddenCount)") },
                                onClick = { sessionFilter = "Hidden"; menu = false })
                            HorizontalDivider(color = p.border)
                        }
                        if (!listMode) {
                            DropdownMenuItem(text = { Text("Session actions") }, onClick = {
                                menu = false; sheet = "actions"
                            })
                            DropdownMenuItem(text = { Text("Native queue") }, onClick = {
                                menu = false; sheet = "queue"
                                selected?.let { scope.launch { client.fetchQueue(it) } }
                            }, enabled = selected?.capabilities?.contains("NativeQueue") == true)
                        }
                        DropdownMenuItem(text = { Text(outboxLabel) },
                            onClick = { menu = false; sheet = "outbox" })
                        DropdownMenuItem(text = { Text("Offline history search") },
                            onClick = { menu = false; sheet = "archive" })
                        if (online && active?.id == "t3") DropdownMenuItem(
                            text = { Text("Check last AI creation") },
                            onClick = {
                                menu = false
                                scope.launch {
                                    try { note = client.checkLastWork() }
                                    catch (e: Exception) { note = "Creation check failed: " + e.message }
                                }
                            })
                        if (createdKey != null) DropdownMenuItem(
                            text = { Text("Open last created session") },
                            onClick = {
                                menu = false
                                if (sessions.any { it.key == createdKey }) {
                                    listMode = false
                                    scope.launch { client.select(createdKey) }
                                } else scope.launch {
                                    if (client.ensureSession(createdKey!!)) {
                                        listMode = false
                                        client.select(createdKey)
                                    } else note = "Created session not available yet. Retry opening it."
                                }
                            })
                        DropdownMenuItem(text = { Text("AI providers") },
                            onClick = { menu = false; sheet = "providers" })
                        if (!listMode) DropdownMenuItem(text = { Text("Human inbox") },
                            onClick = { menu = false; humanInbox = true })
                    }
                }
            }
            if (note != null) Row(Modifier.fillMaxWidth().background(p.accentBg)
                .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note!!, Modifier.weight(1f), color = p.text, style = MaterialTheme.typography.bodySmall)
                ActionIcon(Icons.Default.Close, "Dismiss notice", p) { note = null }
            }

            if (listMode) {
                if (active?.id == "t3") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("t3/chatgpt" to "ChatGPT", "t3/thread" to "Native T3").forEach { (value, label) ->
                        FilterChip(selected = threadKind == value, onClick = {
                            threadKind = value
                            viewPrefs.edit().putString("threadKind", value).apply()
                            sessionFilter = "All"
                        }, label = { Text(label + " (" + sessions.count { it.provider == "t3" && it.kind == value } + ")") },
                            modifier = Modifier.weight(1f))
                    }
                }
                OutlinedTextField(sessionSearch, { sessionSearch = it }, Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp), singleLine = true,
                    placeholder = { Text("Search conversations") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (sessionSearch.isNotEmpty()) {{
                        IconButton(onClick = { sessionSearch = "" }) { Icon(Icons.Default.Close, "Clear search") }
                    }} else null,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
                        focusedBorderColor = p.accent, unfocusedBorderColor = p.border,
                        focusedTextColor = p.text, unfocusedTextColor = p.text,
                        focusedPlaceholderColor = p.muted, unfocusedPlaceholderColor = p.muted,
                        focusedLeadingIconColor = p.muted, unfocusedLeadingIconColor = p.muted,
                        focusedTrailingIconColor = p.muted, unfocusedTrailingIconColor = p.muted))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Active", "Attention", "Snoozed", "Hidden").forEach { value ->
                        FilterChip(selected = sessionFilter == value, onClick = { sessionFilter = value },
                            label = { Text(when (value) {
                                "Attention" -> if (attention > 0) "Attention $attention" else value
                                "Snoozed" -> if (snoozedCount > 0) "Snoozed $snoozedCount" else value
                                "Hidden" -> if (hiddenCount > 0) "Hidden $hiddenCount" else value
                                else -> value
                            }) },
                            shape = RoundedCornerShape(13.dp),
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = p.raised,
                                selectedContainerColor = p.accentBg, selectedLabelColor = p.accent,
                                labelColor = p.muted))
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sortMode == "activity", onClick = {
                        sortMode = "activity"; viewPrefs.edit().putString("sort", sortMode).apply()
                    }, label = { Text("Recent") })
                    FilterChip(selected = sortMode == "project", onClick = {
                        sortMode = "project"; viewPrefs.edit().putString("sort", sortMode).apply()
                    }, label = { Text("By project") })
                    FilterChip(selected = hideUnloaded, onClick = {
                        hideUnloaded = !hideUnloaded
                        viewPrefs.edit().putBoolean("hideUnloaded", hideUnloaded).apply()
                    }, label = { Text("Hide unloaded") })
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(
                    start = 10.dp, end = 10.dp, top = 6.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(visibleSessions, key = { _, session -> session.key }) { index, session ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (sessionSearch.isBlank() && sessionFilter == "All" &&
                                (index == 0 || visibleSessions[index - 1].listGroup(sortMode) != session.listGroup(sortMode))) {
                                Text(session.listGroup(sortMode),
                                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 14.dp, bottom = 5.dp),
                                    color = p.muted, fontSize = 11.sp, letterSpacing = 1.1.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(if (session.key == selectedKey) p.selected else p.surface)
                            .clickable { openSession(session) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).background(p.raised, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(session.title.trim().take(1).uppercase().ifBlank { "A" },
                                    color = p.accent, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(session.title, color = p.text, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (session.unread == true) FontWeight.Bold else FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyLarge)
                                Text((if (session.kind == "t3/thread") "T3 native agent" else if (session.kind == "t3/chatgpt") "ChatGPT" else session.kind) +
                                    (if (sortMode != "project" && session.workspace?.isNotBlank() == true)
                                        " � " + session.projectName() else ""),
                                    color = p.muted, style = MaterialTheme.typography.labelSmall)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Dot(session.dotColor(p))
                                    Text(when {
                                        sessionFilter == "Hidden" -> "Hidden on this phone"
                                        sessionFilter == "Snoozed" -> "Snoozed · " +
                                            ((snoozedUntil(session) - now + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1) + "m left"
                                        session.needsAttention() -> session.attention
                                        session.kind == "t3/chatgpt" && session.reachability != "Reachable" -> "Unloaded"
                                        else -> session.activity
                                    },
                                        color = session.dotColor(p),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (session.unread == true) Dot(p.accent)
                            IconButton(onClick = { rowOptions = session }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.MoreVert, "Session actions", tint = p.muted,
                                    modifier = Modifier.size(19.dp))
                            }
                        }
                        }
                    }
                    if (visibleSessions.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Default.Forum, null, tint = p.muted, modifier = Modifier.size(35.dp))
                            Text(if (sessionSearch.isNotBlank() || sessionFilter != "All") "No matches"
                                else if (online) "No sessions available" else "Connect to load sessions",
                                style = MaterialTheme.typography.titleMedium, color = p.text)
                            Text(if (sessionSearch.isNotBlank() || sessionFilter != "All")
                                "Try another search or filter"
                                else if (online && status.startsWith("Provider refresh failed"))
                                "Couldn't load conversations. Check the connection and retry."
                                else if (online) "Try another filter or create a session"
                                else "Cached conversations will appear here when available",
                                color = p.muted, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                if (online) scope.launch { client.refresh() }
                                else signalRClient.startConnection()
                            }) { Text(if (online) "Refresh" else "Reconnect") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(15.dp),
                    horizontalArrangement = Arrangement.End) {
                    Button(onClick = { openCreate() }, shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = p.accentBg, contentColor = p.accent)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (active?.id == "t3" && threadKind == "t3/thread") "New native thread" else "New session")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Dot(selected?.dotColor(p) ?: p.muted)
                    Spacer(Modifier.width(8.dp))
                    Text(if (selected == null) "Select a session"
                        else if (!online) "Last known: " + selected.activity + " · cached"
                        else if (historyLoading) "Opening / reading on PC…"
                        else if (selected.needsAttention()) selected.attention
                        else selected.activity + " · " + selected.confidence,
                        modifier = Modifier.weight(1f), color = p.muted, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall)
                    if (online && !actionBusy && selected?.capabilities?.contains("Interrupt") == true)
                        TextButton(onClick = { operation("Interrupt") }) {
                            Icon(Icons.Default.StopCircle, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(3.dp)); Text("Stop")
                        }
                }
                if (selected?.needsAttention() == true) {
                    Row(Modifier.fillMaxWidth().background(p.accentBg)
                        .padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PriorityHigh, null, tint = p.amber,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Needs your attention", color = p.text,
                                style = MaterialTheme.typography.labelLarge)
                            Text("${selected.attention} · Check the conversation or human inbox for a required response.",
                                color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { humanInbox = true }) { Text("Inbox") }
                    }
                }
                if (selected?.kind == "t3/chatgpt" &&
                    selected.reachability != "Reachable" && online) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (historyLoading) "Opening browser on PC automatically…" else "Browser not ready on PC", color = p.muted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        if (!historyLoading) TextButton(onClick = { selected.let { openSession(it) } }) {
                            Text("Retry PC load")
                        }
                    }
                }
                HorizontalDivider(color = p.border)
                if (historyLoading) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = p.accent,
                    trackColor = p.bg)
                val scroll = rememberLazyListState()
                LaunchedEffect(selectedKey, history.lastOrNull()?.id, history.lastOrNull()?.revision) {
                    if (history.isNotEmpty() && followingLatest) scroll.scrollToItem(
                        (if (older != null) 1 else 0) +
                        (if (!historyNotice.isNullOrBlank()) 1 else 0) + history.size - 1)
                }
                LaunchedEffect(scroll, selectedKey) {
                    snapshotFlow {
                        scroll.isScrollInProgress to (
                            (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                                scroll.layoutInfo.totalItemsCount - 2)
                    }.collect { (scrolling, atBottom) ->
                        if (scrolling) followingLatest = atBottom
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (older != null) item {
                        TextButton(onClick = {
                            followingLatest = false
                            scope.launch { client.loadOlderHistory() }
                        }, enabled = online, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Load earlier messages")
                        }
                    }
                    if (!historyNotice.isNullOrBlank()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(historyNotice!!, color = p.muted,
                                style = MaterialTheme.typography.labelSmall)
                            if (online && historyNotice!!.contains("failed", ignoreCase = true)) {
                                TextButton(onClick = {
                                    selectedKey?.let { key -> scope.launch { client.select(key, reload = true) } }
                                }) { Text("Retry loading conversation") }
                            }
                        }
                    }
                    if (history.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 65.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ChatBubbleOutline, null, tint = p.muted,
                                modifier = Modifier.size(35.dp))
                            Text("Conversation history", color = p.text,
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (historyLoading) "Retrieving the conversation from your PC…"
                                else if (online) "No messages yet, or no history exposed by provider"
                                else "Cached history is available offline",
                                color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(history, key = { it.id }) { item -> MessageRow(item, p) }
                    item { if (online && selected != null)
                        Text("LATEST", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center,
                            color = p.muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!followingLatest && history.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            followingLatest = true
                            scope.launch {
                                client.select(selectedKey, reload = true)
                                scroll.scrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                            }
                        }) {
                            Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp)); Text("Latest messages")
                        }
                    }
                }
                if (localSessionMessages.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().background(p.accentBg)
                        .clickable { sheet = "outbox" }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, tint = p.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(localSessionMessages.size.toString() + " messages awaiting delivery",
                            color = p.text, style = MaterialTheme.typography.labelMedium)
                        Text(localSessionMessages.first().text, color = p.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = p.muted)
                }
                if ((pending > 0 || remoteQueue.isNotEmpty()) && localSessionMessages.isEmpty()) Row(
                    Modifier.fillMaxWidth().background(p.raised)
                        .clickable { sheet = if (pending == 0) "queue" else "outbox" }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, tint = p.amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text((if (pending > 0) pending.toString() + " pending on device" else "Provider queue") +
                        (if (remoteQueue.isNotEmpty()) " · " + remoteQueue.size + " provider queued" else ""),
                        Modifier.weight(1f), color = p.text, style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.Default.ChevronRight, null, tint = p.muted, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.fillMaxWidth().background(p.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!addressMode && selected != null) {
                        if (selected.kind == "t3/thread") {
                            FilterChip(selected = true, onClick = {}, enabled = false,
                                label = { Text("Native queue", style = MaterialTheme.typography.labelSmall) })
                        } else Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("Steer" to "Steer now", "WhenIdle" to "Queue",
                                "WaitForSlot" to "Wait for slot").forEach { (value,label) ->
                                FilterChip(selected = deliveryMode==value,
                                    onClick = { deliveryMode=value },
                                    label = {Text(label, style=MaterialTheme.typography.labelSmall)},
                                    enabled = !online || selected.modes.contains(value))
                            }
                        }
                        if (selected.kind != "t3/thread" && deliveryMode == "WaitForSlot") Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Max working agents",color=p.muted,style=MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(value=maxActiveWorkers.toString(),
                                onValueChange={it.toIntOrNull()?.takeIf{n->n in 1..100}?.let{n->maxActiveWorkers=n}},
                                modifier=Modifier.width(74.dp),singleLine=true)
                        }
                    }
                    if (addressMode) OutlinedTextField(address, { address = it.take(320) },
                        Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true,
                        label = { Text("Contact address") }, shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = p.text, unfocusedTextColor = p.text,
                            focusedLabelColor = p.muted, unfocusedLabelColor = p.muted,
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.border))
                    Row(verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (loadedDraftFor == draftKey) message else "",
                            onValueChange = { text ->
                                if (loadedDraftFor == draftKey) {
                                    message = text.take(30_000)
                                    client.saveDraft(draftKey, message)
                                }
                            },
                            modifier = Modifier.weight(1f), minLines = 1, maxLines = 5,
                            placeholder = { Text(if (addressMode) "Message contact…" else "Message agent…") },
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = p.raised, unfocusedContainerColor = p.raised,
                                focusedBorderColor = p.accent, unfocusedBorderColor = p.border,
                                focusedTextColor = p.text, unfocusedTextColor = p.text,
                                focusedPlaceholderColor = p.muted, unfocusedPlaceholderColor = p.muted))
                        IconButton(onClick = { submit() }, enabled = canSend,
                            modifier = Modifier.size(50.dp).background(
                                if (canSend) p.accent else p.raised, CircleShape)) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Send message",
                                tint = if (canSend) p.bg else p.muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { addressMode = !addressMode },
                            enabled = active?.capabilities?.contains("SubmitToAgent") == true ||
                                (!online && lastProvider != null),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Icon(if (addressMode) Icons.Default.AlternateEmail
                                else Icons.Default.ChatBubbleOutline,
                                null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (addressMode) "To contact" else "To session",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(deliveryHint, maxLines = 2,
                            style = MaterialTheme.typography.labelSmall, color = p.muted)
                    }
                }
            }
            // Keep transport diagnostics out of the main interface. Actionable
            // errors appear in the relevant workflow; details live under Providers.
        }
    }

    rowOptions?.let { target ->
        val remote = isT3Browser(target) && target.capabilities.contains("RemoteControl")
        val details = rowControls
        fun field(name: String): String? =
            details?.get(name)?.takeUnless { it.isJsonNull }?.let {
                runCatching { it.asString }.getOrNull()
            }
        fun copyValue(label: String, value: String?) {
            if (value.isNullOrBlank()) { rowControlError = "$label is unavailable"; return }
            clipboard.setText(AnnotatedString(value))
            note = "$label copied"
        }
        val caffy = details?.get("caffy")?.takeIf { it.isJsonObject }?.asJsonObject
        fun caffyField(name: String): String? =
            caffy?.get(name)?.takeUnless { it.isJsonNull }?.let {
                runCatching { it.asString }.getOrNull()
            }
        var customDuration by remember(target.key) { mutableStateOf("60") }
        var customInterval by remember(target.key) { mutableStateOf("20") }
        var editText by remember(target.key) { mutableStateOf("") }
        var editKind by remember(target.key) { mutableStateOf("") }
        var editBoard by remember(target.key) { mutableStateOf("") }
        var editTask by remember(target.key) { mutableStateOf("") }
        var editSeat by remember(target.key) { mutableStateOf("") }
        var editRole by remember(target.key) { mutableStateOf("personal") }
        var parentSearch by remember(target.key) { mutableStateOf("") }
        var showParentChoices by remember(target.key) { mutableStateOf(false) }
        var customSnoozeMinutes by remember(target.key) { mutableStateOf("90") }
        ModalBottomSheet(onDismissRequest = { rowOptions = null },
            containerColor = p.surface, contentColor = p.text,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)
                .navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTitle(if (remote) "T3 session actions" else "Session visibility", field("title") ?: target.title, p)
                if (rowControlBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (rowControlError != null)
                    Text(rowControlError!!, color = p.amber, style = MaterialTheme.typography.bodySmall)
                if (remote) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Session", "Caffy", "Organize").forEach { page ->
                            FilterChip(selected = rowActionPage == page,
                                onClick = { rowActionPage = page },
                                label = { Text(page) })
                        }
                    }
                }
                if (!remote || rowActionPage == "Session") {
                    if (remote) {
                        Text("Copy", color = p.muted, style = MaterialTheme.typography.labelMedium)
                        SheetAction("T3 Session ID", p) {
                            copyValue("T3 Session ID", field("id"))
                        }
                        SheetAction("ChatGPT Conversation ID", p) {
                            copyValue("Conversation ID", field("conversationId"))
                        }
                        SheetAction("ChatGPT URL", p) {
                            copyValue("ChatGPT URL", field("url"))
                        }
                        SheetAction("Session title", p) { copyValue("Title", field("title") ?: target.title) }
                        SheetAction("Parent Session ID", p) {
                            copyValue("Parent ID", field("parentId"))
                        }
                        HorizontalDivider(color = p.border)
                        Text("Session", color = p.muted, style = MaterialTheme.typography.labelMedium)
                        SheetAction("Stop generation", p) { performRowControl(target, "stop") }
                        SheetAction("Reload session", p) { performRowControl(target, "reload") }
                        SheetAction(if (field("keepLoaded") == "true") "Disable Keep loaded" else "Keep loaded", p) {
                            performRowControl(target, "keep_loaded",
                                mapOf("value" to (field("keepLoaded") != "true")))
                        }
                        SheetAction("Reboot agent (same T3 slot)", p) { performRowControl(target, "reboot") }
                        SheetAction("Restore ChatGPT URL", p) { performRowControl(target, "restore_url") }
                        SheetAction(if (field("verificationPending") == "true") "Clear verification"
                            else "Mark for verification", p) {
                            performRowControl(target, "verification",
                                mapOf("value" to (field("verificationPending") != "true")))
                        }
                        SheetAction("Unload session", p) { performRowControl(target, "unload") }
                    }
                    HorizontalDivider(color = p.border)
                    if (isHidden(target)) SheetAction("Unhide session", p) {
                        setHidden(target, false); rowOptions = null
                    } else SheetAction("Hide session", p) {
                        viewPrefs.edit().putLong("hiddenAt:${target.key}", now).apply()
                        setHidden(target, true); rowOptions = null
                    }
                    if (isSnoozed(target)) SheetAction("Unsnooze", p) {
                        setSnooze(target, 0L); rowOptions = null
                    } else {
                        Text("Snooze for", color = p.muted, style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 4, 8, 24, 72, 168).forEach { hours ->
                                FilterChip(selected = false, onClick = {
                                    viewPrefs.edit().putLong("snoozedAt:${target.key}", now).apply()
                                    setSnooze(target, now + hours * HOUR_MS); rowOptions = null
                                }, label = { Text(if (hours >= 72) "${hours / 24}d" else "${hours}h") })
                            }
                        }
                    }
                    if (remote) {
                        OutlinedTextField(customSnoozeMinutes,
                            { customSnoozeMinutes = it.filter(Char::isDigit).take(5) },
                            Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Custom snooze (minutes)") })
                        SheetAction("Apply custom snooze", p) {
                            val minutes = customSnoozeMinutes.toLongOrNull()
                            if (minutes == null || minutes !in 1..10080)
                                rowControlError = "Snooze duration must be 1–10080 minutes."
                            else {
                                setSnooze(target, now + minutes * MINUTE_MS)
                                rowOptions = null
                            }
                        }
                        HorizontalDivider(color = p.border)
                        SheetAction("Close T3 session permanently…", p, Color(0xFFE58B8B)) {
                            closeConfirmation = target
                        }
                    }
                }
                if (remote && rowActionPage == "Caffy") {
                    val activeMode = caffyField("mode")
                    val paused = caffyField("paused") == "true"
                    SheetTitle("Caffy · ${activeMode ?: "Off"}" +
                        (if (paused) " (paused)" else ""), "T3-owned wake control", p)
                    Text("Wake on idle · duration", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 4, 8).forEach { hours ->
                            FilterChip(false, onClick = {
                                performRowControl(target, "caffy_set",
                                    mapOf("mode" to "idle", "durationMinutes" to hours * 60))
                            }, label = { Text("${hours}h") })
                        }
                        FilterChip(false, onClick = {
                            performRowControl(target, "caffy_set", mapOf("mode" to "idle"))
                        }, label = { Text("Infinite") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(false, onClick = {
                            performRowControl(target, "caffy_set",
                                mapOf("mode" to "idle", "onceOnly" to true))
                        }, label = { Text("Once only") })
                        FilterChip(false, onClick = {
                            performRowControl(target, "caffy_set", mapOf("mode" to "queued"))
                        }, label = { Text("Queued idle · Infinite") })
                    }
                    Text("Periodic wake · every", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 20, 30, 60).forEach { minutes ->
                            FilterChip(false, onClick = {
                                performRowControl(target, "caffy_set",
                                    mapOf("mode" to "periodic", "intervalMinutes" to minutes))
                            }, label = { Text("${minutes}m") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(customInterval, { customInterval = it.filter(Char::isDigit).take(4) },
                            Modifier.weight(1f), singleLine = true, label = { Text("Interval min") })
                        OutlinedTextField(customDuration, { customDuration = it.filter(Char::isDigit).take(5) },
                            Modifier.weight(1f), singleLine = true, label = { Text("Duration min") })
                    }
                    SheetAction("Set custom periodic schedule", p) {
                        val interval = customInterval.toIntOrNull()
                        val duration = customDuration.toIntOrNull()
                        if (interval == null || interval !in 1..1440 ||
                            (customDuration.isNotEmpty() && (duration == null || duration !in 1..10080)))
                            rowControlError = "Use a 1–1440 minute interval and 1–10080 minute duration, or blank for infinite."
                        else performRowControl(target, "caffy_set",
                            mapOf("mode" to "periodic", "intervalMinutes" to interval,
                                "durationMinutes" to duration))
                    }
                    HorizontalDivider(color = p.border)
                    Text("Begin / resume work", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 120, 240).forEach { minutes ->
                            FilterChip(false, onClick = {
                                performRowControl(target, "caffy_begin",
                                    mapOf("beginAt" to System.currentTimeMillis() + minutes * MINUTE_MS))
                            }, label = { Text("${minutes}m") })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(9, 13, 18).forEach { hour ->
                            FilterChip(false, onClick = {
                                val calendar = java.util.Calendar.getInstance().apply {
                                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                performRowControl(target, "caffy_begin", mapOf("beginAt" to calendar.timeInMillis))
                            }, label = { Text("Tomorrow ${hour}:00") })
                        }
                    }
                    SheetAction("Cancel delayed start", p) {
                        performRowControl(target, "caffy_begin", mapOf("beginAt" to null))
                    }
                    HorizontalDivider(color = p.border)
                    if (activeMode == "idle" || activeMode == "queued") {
                    Text("Auto-reboot (idle / queued only)", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 120).forEach { minutes ->
                            FilterChip(false, onClick = {
                                performRowControl(target, "caffy_reboot", mapOf("value" to minutes))
                            }, label = { Text("${minutes}m") })
                        }
                        FilterChip(false, onClick = {
                            performRowControl(target, "caffy_reboot", mapOf("value" to null))
                        }, label = { Text("Off") })
                    }
                    }
                    if (activeMode != null) SheetAction(if (paused) "Resume Caffy" else "Pause Caffy", p) {
                        performRowControl(target, if (paused) "caffy_resume" else "caffy_pause")
                    }
                    SheetAction(if (caffyField("startup") == "true") "Disable resume on startup"
                        else "Resume on T3 startup", p) {
                        if (activeMode == null)
                            performRowControl(target, "caffy_set",
                                mapOf("mode" to "startup", "startup" to true))
                        else performRowControl(target, "caffy_startup",
                            mapOf("value" to (caffyField("startup") != "true")))
                    }
                    SheetAction("Wake this agent now", p) {
                        performRowControl(target, "caffy_wake")
                    }
                    if (activeMode != null) SheetAction("Stop Caffy", p, Color(0xFFE58B8B)) {
                        performRowControl(target, "caffy_stop")
                    }
                }
                if (remote && rowActionPage == "Organize") {
                    SheetTitle("Organization", "T3 desktop sidebar state", p)
                    if (field("role") != "manager") SheetAction(if (field("role") == "worker") "Mark as personal"
                        else "Mark as worker", p) {
                        performRowControl(target, "worker", mapOf("value" to (field("role") != "worker")))
                    }
                    SheetAction("Edit Omniwork binding…", p) {
                        val binding = details?.get("binding")?.takeIf { it.isJsonObject }?.asJsonObject
                        val taskRef = binding?.get("taskRef")?.takeIf { it.isJsonObject }?.asJsonObject
                        editKind = "binding"
                        editRole = field("role")?.takeUnless { it == "manager" } ?: "personal"
                        editSeat = binding?.get("seatId")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                        editBoard = taskRef?.get("board")?.asString.orEmpty()
                        editTask = taskRef?.get("id")?.asString.orEmpty()
                    }
                    if (details?.get("binding")?.isJsonObject == true) {
                        SheetAction("Clear Omniwork binding", p) {
                            performRowControl(target, "binding_clear")
                        }
                    }
                    Text("Move to project", color = p.muted, style = MaterialTheme.typography.labelMedium)
                    val projects = details?.get("projects")?.takeIf { it.isJsonArray }?.asJsonArray
                    projects?.forEach { item ->
                        val name = item.asString
                        SheetAction((if (field("project") == name) "✓ " else "") + name, p) {
                            performRowControl(target, "project", mapOf("value" to name))
                        }
                    }
                    SheetAction("No project", p) {
                        performRowControl(target, "project", mapOf("value" to null))
                    }
                    SheetAction("Add / move to project…", p) {
                        editKind = "project"; editText = field("project") ?: ""
                    }
                    Text("Parent session", color = p.muted, style = MaterialTheme.typography.labelMedium)
                    SheetAction("Detach from parent", p) {
                        performRowControl(target, "parent", mapOf("value" to null))
                    }
                    val others = details?.get("otherSessions")?.takeIf { it.isJsonArray }?.asJsonArray
                    SheetAction(if (showParentChoices) "Hide parent choices" else "Choose parent…", p) {
                        showParentChoices = !showParentChoices
                    }
                    if (showParentChoices) OutlinedTextField(parentSearch, { parentSearch = it },
                        Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Search parent by title or T3 ID") })
                    (if (showParentChoices) others?.filter { item ->
                        item.isJsonObject && (parentSearch.isBlank() ||
                            item.asJsonObject.get("title")?.asString.orEmpty().contains(parentSearch, true) ||
                            item.asJsonObject.get("id")?.asString.orEmpty().contains(parentSearch, true))
                    }?.take(18) else null)?.forEach { item ->
                        if (item.isJsonObject) {
                            val id = item.asJsonObject.get("id")?.asString.orEmpty()
                            val name = item.asJsonObject.get("title")?.asString.orEmpty()
                            SheetAction("Parent: $name", p) {
                                performRowControl(target, "parent", mapOf("value" to id))
                            }
                        }
                    }
                    Text("Highlight", color = p.muted, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "none").forEach { color ->
                            FilterChip(selected = field("borderColor") == color, onClick = {
                                performRowControl(target, "highlight",
                                    mapOf("value" to color.takeUnless { it == "none" }))
                            }, label = { Text(color.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                    SheetAction("Rename session…", p) {
                        editKind = "rename"; editText = target.title
                    }
                    val task = details?.get("binding")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("taskRef")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (task != null) SheetAction("Open TaskBoard task", p) {
                        val board = task.get("board")?.asString.orEmpty()
                        val id = task.get("id")?.asString.orEmpty()
                        try {
                            val file = "$board/Tasks/Active/$id"
                            val uri = Uri.parse("obsidian://open?vault=jjj&file=" + Uri.encode(file))
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) { rowControlError = "Cannot open Obsidian: " + e.message }
                    }
                    if (editKind == "binding") {
                        Text("Omniwork role", color = p.muted,
                            style = MaterialTheme.typography.labelMedium)
                        if (field("role") == "manager") Text("Manager role is preserved.",
                            color = p.muted, style = MaterialTheme.typography.labelSmall)
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("personal", "worker").forEach { role ->
                                FilterChip(selected = editRole == role,
                                    onClick = { editRole = role },
                                    label = { Text(role.replaceFirstChar { it.uppercase() }) })
                            }
                        }
                        OutlinedTextField(editSeat, { editSeat = it.take(160) },
                            Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Seat ID (optional)") })
                        OutlinedTextField(editBoard, { editBoard = it.take(200) },
                            Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("TaskBoard board (optional)") })
                        OutlinedTextField(editTask, { editTask = it.take(120) },
                            Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("TaskBoard task ID (optional)") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editKind = "" }) { Text("Cancel") }
                            TextButton(onClick = {
                                performRowControl(target, "binding_update",
                                    mapOf("role" to editRole, "seatId" to editSeat,
                                        "taskBoard" to editBoard, "taskId" to editTask))
                                editKind = ""
                            }, enabled = (editBoard.isBlank() == editTask.isBlank())) { Text("Save binding") }
                        }
                    }
                    if (editKind == "project" || editKind == "rename") {
                        OutlinedTextField(editText, { editText = it.take(180) },
                            Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (editKind == "project") "Project name" else "Session title") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editKind = "" }) { Text("Cancel") }
                            TextButton(onClick = {
                                performRowControl(target, editKind, mapOf("value" to editText.trim()))
                                editKind = ""
                            }, enabled = editText.isNotBlank()) { Text("Save") }
                        }
                    }
                }
                Text(if (remote) "Actions are executed by the T3 desktop. No control is sent while offline."
                    else "Visibility applies only on this phone for this provider.",
                    color = p.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    closeConfirmation?.let { target ->
        AlertDialog(onDismissRequest = { closeConfirmation = null },
            title = { Text("Close T3 session?") },
            text = { Text("Permanently removes this local T3 browser session. This does not delete the ChatGPT conversation in the cloud. Any unfinished draft must be cleared first.") },
            confirmButton = {
                TextButton(onClick = {
                    closeConfirmation = null
                    performRowControl(target, "close")
                }) { Text("Close session") }
            },
            dismissButton = { TextButton(onClick = { closeConfirmation = null }) { Text("Cancel") } })
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null },
        containerColor = p.surface, contentColor = p.text,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (sheet) {
                "providers" -> {
                    SheetTitle("AI providers", "Choose the active backend", p)
                    if (status.isNotBlank()) Text(status, color = p.muted,
                        style = MaterialTheme.typography.bodySmall)
                    providers.forEach { provider ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(p.raised).clickable(enabled = online && !provider.active) {
                                switchProvider = provider; sheet = null
                            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (provider.active) p.green else p.muted)
                            Spacer(Modifier.width(12.dp))
                            Text(provider.name, Modifier.weight(1f), color = p.text)
                            Text(if (provider.active) "Active" else "Switch", color = p.accent)
                        }
                    }
                    if (providers.isEmpty()) Text("Connect to discover providers", color = p.muted)
                }
                "actions" -> {
                    SheetTitle("Session options", selected?.title, p)
                    if (selected?.capabilities?.contains("RenameSession") == true)
                        SheetAction("Rename session", p) {
                            renameText = selected.title; rename = true; sheet = null
                        }
                    if (selected?.capabilities?.contains("NativeQueue") == true)
                        SheetAction("View native queue", p) {
                            sheet = "queue"; scope.launch { client.fetchQueue(selected) }
                        }
                    if (selected?.capabilities?.contains("Interrupt") == true)
                        SheetAction("Stop turn", p) { operation("Interrupt"); sheet = null }
                    if (active?.id == "t3") SheetAction("Check last AI creation", p) {
                        scope.launch { try { note = client.checkLastWork() }
                            catch (e: Exception) { note = e.message } }
                        sheet = null
                    }
                    if (selected?.capabilities?.contains("DeleteSession") == true)
                        SheetAction("Delete session", p, p.amber) {
                            deleteConfirm = true; sheet = null
                        }
                }
                "queue" -> {
                    SheetTitle("Provider queue", selected?.title, p)
                    if (remoteQueue.isEmpty()) Text("No queued messages", color = p.muted)
                    remoteQueue.forEach { item ->
                        Row(Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(14.dp))
                            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.text, Modifier.weight(1f), color = p.text,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (selected?.capabilities?.contains("EditQueue") == true)
                                TextButton(onClick = { operation("QueueEdit", mapOf(
                                    "edit" to mapOf("id" to item.id, "action" to "Cancel"))) }) {
                                    Text("Cancel")
                                }
                        }
                    }
                }
                "outbox" -> {
                    SheetTitle("Delivery outbox", "Messages and delivery receipts", p)
                    if (outbox.isEmpty()) Text("Outbox is empty", color = p.muted)
                    if (outbox.any { it.state == "Unknown" }) Text(
                        "A message has an unconfirmed outcome. Check its destination before sending the same text again.",
                        color = p.amber, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(outbox.take(50), key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(15.dp))
                                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.target + " · " + item.state, color = p.accent,
                                    style = MaterialTheme.typography.labelMedium)
                                Text(item.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = p.text, style = MaterialTheme.typography.bodyMedium)
                                if (item.error != null) Text(item.error, color = p.amber,
                                    style = MaterialTheme.typography.labelSmall)
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        clipboard.setText(AnnotatedString(item.text))
                                        note = "Message copied"
                                    }) { Text("Copy") }
                                    if (item.needsVerification && online &&
                                        item.state in listOf("Pending", "Blocked"))
                                        TextButton(onClick = {
                                            scope.launch {
                                                try { verify = client.previewAddressRecipient(item.id) }
                                                catch (e: Exception) { note = "Verification failed: " + e.message }
                                            }
                                        }) { Text("Verify recipient") }
                                    if (item.state in listOf("Pending", "Blocked"))
                                        TextButton(onClick = { receiptToRemove = item }) {
                                            Text("Cancel pending")
                                        }
                                    if (item.state in listOf("Delivered", "Failed", "Unknown"))
                                        TextButton(onClick = { receiptToRemove = item }) {
                                            Text("Remove receipt")
                                        }
                                }
                            }
                        }
                    }
                }
                "archive" -> {
                    SheetTitle("Offline history", "Locally searchable when enabled", p)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Save observed history", Modifier.weight(1f), color = p.text)
                        Switch(checked = archiveOn, onCheckedChange = { client.setArchiveEnabled(it) })
                    }
                    OutlinedTextField(archiveQuery, {
                        archiveQuery = it.take(100)
                        scope.launch { client.searchArchive(archiveQuery) }
                    }, Modifier.fillMaxWidth(), singleLine = true, enabled = archiveOn,
                        label = { Text("Search archived messages") },
                        leadingIcon = { Icon(Icons.Default.Search, null) })
                    if (archiveOn && archiveQuery.isNotBlank()) {
                        if (archiveResults.isEmpty()) Text("No saved matches", color = p.muted)
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(archiveResults.take(30)) { (key, item) ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    sheet = null; listMode = false
                                    scope.launch { client.select(key) }
                                }.padding(10.dp)) {
                                    Text(key.substringAfter(':'), color = p.accent,
                                        maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                    Text(item.text, color = p.text, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(color = p.border)
                            }
                        }
                    }
                }
            }
        }
    }
    receiptToRemove?.let { candidate ->
        val pendingCancel = candidate.state in listOf("Pending", "Blocked")
        val uncertain = candidate.state == "Unknown"
        AlertDialog(onDismissRequest = { receiptToRemove = null },
            title = { Text(if (pendingCancel) "Cancel queued message?"
                else if (uncertain) "Discard unconfirmed receipt?" else "Remove receipt?") },
            text = { Text(when {
                pendingCancel -> "This message is still on your phone and has not been submitted. Remove it from the outbox?"
                uncertain -> "Delivery might already have happened. Check the destination first. This only removes your local receipt; it never retracts or resends the message."
                else -> "Remove this completed delivery receipt from your phone?"
            }) },
            confirmButton = { TextButton(onClick = {
                receiptToRemove = null
                try {
                    if (pendingCancel) client.cancelPending(candidate.id)
                    else client.dismissReceipt(candidate.id)
                    note = if (pendingCancel) "Pending message cancelled" else "Receipt removed"
                } catch (e: Exception) { note = "Unable to remove message: " + e.message }
            }) { Text(if (pendingCancel) "Cancel message" else "Remove receipt") } },
            dismissButton = { TextButton(onClick = { receiptToRemove = null }) { Text("Keep") } })
    }
    verify?.let { candidate ->
        AlertDialog(onDismissRequest = { verify = null }, title = { Text("Confirm recipient identity") },
            text = { Text("Message will only reach this verified recipient:\n\n" + candidate.summary) },
            confirmButton = { TextButton(onClick = {
                verify = null
                try { client.confirmAddressRecipient(candidate); note = "Recipient verified" }
                catch (e: Exception) { note = "Verification failed: " + e.message }
            }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { verify = null }) { Text("Cancel") } })
    }
    if (rename && selected != null) AlertDialog(onDismissRequest = { rename = false },
        title = { Text("Rename session") },
        text = { OutlinedTextField(renameText, { renameText = it.take(256) },
            label = { Text("Session title") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            rename = false; operation("Rename", mapOf("title" to renameText))
        }, enabled = renameText.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    if (deleteConfirm && selected != null) AlertDialog(onDismissRequest = { deleteConfirm = false },
        title = { Text("Permanently delete session?") },
        text = { Text("This deletes the provider's local transcript and cannot be undone.") },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = {
            deleteConfirm = false
            actionBusy = true
            scope.launch {
                try {
                    val result = client.sessionAction(selected, "Delete", mapOf("scope" to "transcript"))
                    if (result == "Accepted" || result == "Delivered") {
                        client.select(null); listMode = true; note = "Deletion accepted"
                    } else note = "Deletion $result — verify state before retrying"
                } catch (e: Exception) { note = "Delete failed: " + e.message }
                finally { actionBusy = false }
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } })
    if (create && active != null) AlertDialog(onDismissRequest = { create = false },
        title = { Text(when (kind) {
            "t3/thread" -> "New native T3 thread"
            "t3/chatgpt" -> "New ChatGPT thread"
            else -> "New AI session"
        }) },
        text = { Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active.id != "t3") Text("Execution kind", style = MaterialTheme.typography.labelMedium)
            active.kinds.filter { active.id != "t3" }.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { kind = option },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == option, onClick = { kind = option })
                    Text(option, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(newSessionTitle, { newSessionTitle = it.take(180) },
                label = { Text("Title (optional)") }, singleLine = true)
            OutlinedTextField(workspace, { workspace = it.take(4096) },
                label = { Text(if (kind == "t3/thread") "Existing native project / folder (optional)"
                    else if (active.id == "t3") "Project / folder (optional)" else "Working folder") }, singleLine = true)
            if (kind == "t3/thread") Text(
                "Leave project empty to reuse the most recently active native project's settings. " +
                    "New native projects must first be opened in T3 on the PC.",
                style = MaterialTheme.typography.bodySmall, color = p.muted)
            OutlinedTextField(firstMessage, { firstMessage = it.take(30_000) },
                label = { Text(if (active.id == "t3") "First message (optional)" else "First instruction") }, maxLines = 4)
            if (kind == "t3/thread") Text("Native T3 uses its own durable message queue.",
                style = MaterialTheme.typography.bodySmall, color = p.muted)
            if (kind != "t3/thread") Text("When to start",style=MaterialTheme.typography.labelMedium)
            if (kind != "t3/thread") Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=createDeliveryMode=="WhenIdle",onClick={createDeliveryMode="WhenIdle"},
                    label={Text("Start now")})
                FilterChip(selected=createDeliveryMode=="WaitForSlot",onClick={createDeliveryMode="WaitForSlot"},
                    label={Text("Wait for slot")},enabled=active.id=="t3")
            }
            if(kind != "t3/thread" && createDeliveryMode=="WaitForSlot") Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Max working agents",style=MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(value=maxActiveWorkers.toString(),
                    onValueChange={it.toIntOrNull()?.takeIf{n->n in 1..100}?.let{n->maxActiveWorkers=n}},
                    modifier=Modifier.width(74.dp),singleLine=true)
            }
        }},
        confirmButton = { TextButton(enabled = !creationBusy && kind.isNotBlank() &&
            (active.id == "t3" || (workspace.isNotBlank() && firstMessage.isNotBlank())), onClick = {
            if (creationBusy) return@TextButton
            creationBusy = true
            create = false
            scope.launch {
                try {
                    note = client.createSession(active, kind, workspace, firstMessage,
                        createDeliveryMode, maxActiveWorkers, newSessionTitle)
                    val newKey = client.createdSessionKey.value
                    if (newKey != null) {
                        // New T3 browser tabs may appear in the provider snapshot shortly
                        // after creation. Retry reads, never the create mutation itself.
                        repeat(3) { attempt ->
                            if (client.sessions.value.any { it.key == newKey }) return@repeat
                            delay(350L * (attempt + 1))
                            client.refresh()
                        }
                        if (client.sessions.value.any { it.key == newKey }) {
                            listMode = false
                            client.select(newKey)
                        } else note = "Session created but not listed yet. Refresh to open it."
                    }
                } catch (e: Exception) { note = "Create failed: " + e.message }
                finally { creationBusy = false }
            }
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { create = false }) { Text("Cancel") } })
    switchProvider?.let { provider ->
        AlertDialog(onDismissRequest = { switchProvider = null },
            title = { Text("Activate " + provider.name + "?") },
            text = { Text("The previous provider will be deactivated. Messages already queued for that provider stay tied to it and will wait until it becomes active again.") },
            confirmButton = { TextButton(onClick = {
                switchProvider = null
                scope.launch {
                    try { client.activate(provider); listMode = true
                        note = "Activated " + provider.name
                    } catch (e: Exception) { note = "Switch failed: " + e.message }
                }
            }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { switchProvider = null }) { Text("Cancel") } })
    }
    }
}

@Composable private fun MessageRow(item: AgentHistoryUi, p: AiPalette) {
    val kind = item.kind.lowercase()
    val outgoing = kind.contains("user") || kind.contains("human") || kind.contains("request")
    val technical = kind.contains("tool") || kind.contains("command") || kind.contains("progress") ||
        kind.contains("event") || kind.contains("notice")
    var expanded by remember(item.id) { mutableStateOf(false) }
    if (technical) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(p.raised).clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, null, tint = p.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.kind + (if (item.partial) " · partial" else ""),
                    Modifier.weight(1f), color = p.muted,
                    style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Expand", tint = p.muted, modifier = Modifier.size(18.dp))
            }
            SelectionContainer {
                Text(if (expanded || item.text.length <= 90) item.text
                    else item.text.lineSequence().firstOrNull().orEmpty().take(90),
                    color = if (expanded) p.text else p.muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment =
            if (outgoing) Alignment.End else Alignment.Start) {
            Text(if (outgoing) "YOU" else item.kind.uppercase(),
                color = p.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            Box(Modifier.widthIn(max = if (outgoing) 310.dp else 500.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (outgoing) p.accentBg else p.surface)
                .padding(horizontal = 15.dp, vertical = 12.dp)) {
                SelectionContainer {
                    Text(remember(item.text, p) { aiMarkdown(
                        item.text.ifBlank { if (item.partial) "Waiting for content…" else "(empty)" }, p
                    ) }, color = p.text, style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp)
                }
            }
            if (item.partial) Text("Partial history", color = p.muted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(7.dp))
        }
    }
}


private val aiInlineMd = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)""")
private val aiHeadingMd = Regex("""^#{1,6}\s+""")
private val aiBulletMd = Regex("""^(\s*)[-*]\s+""")

/** Compact, selectable Markdown reading experience; tool output remains monospace. */
private fun aiMarkdown(text: String, p: AiPalette) = buildAnnotatedString {
    var inCode = false
    text.lines().forEachIndexed { index, raw ->
        if (index > 0) append("\n")
        if (raw.trimStart().startsWith("```")) {
            inCode = !inCode
            return@forEachIndexed
        }
        if (inCode) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = p.muted)) { append(raw) }
            return@forEachIndexed
        }
        val heading = aiHeadingMd.find(raw)
        var line = if (heading != null) raw.substring(heading.range.last + 1) else raw
        val bullet = aiBulletMd.find(line)
        if (bullet != null) {
            append(bullet.groupValues[1] + "• ")
            line = line.substring(bullet.range.last + 1)
        }
        val start = length
        var cursor = 0
        aiInlineMd.findAll(line).forEach { match ->
            append(line.substring(cursor, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                match.groupValues[2].isNotEmpty() ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, background = p.raised)) { append(match.groupValues[2]) }
                else -> withStyle(SpanStyle(color = p.accent)) { append(match.groupValues[3]) }
            }
            cursor = match.range.last + 1
        }
        append(line.substring(cursor))
        if (heading != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}
