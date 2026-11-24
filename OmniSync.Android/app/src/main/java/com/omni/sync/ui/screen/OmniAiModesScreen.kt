package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.platform.LocalDensity
import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
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
import java.util.UUID
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import java.io.ByteArrayOutputStream
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.utils.AndroidAttentionStore
import com.omni.sync.utils.PhoneAgentAttention
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Tonal surfaces, rounded session rows and unobtrusive actions follow T3 Android's hierarchy. */
/** Downscale gallery imagery for the authenticated, chunked remote T3 transport. */
private fun preparePrepfileImage(context: Context, uri: Uri): ByteArray {
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, dimensions)
    } ?: throw IllegalArgumentException("Could not open selected image")
    if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0)
        throw IllegalArgumentException("Unsupported gallery image")
    val sample = generateSequence(1) { it * 2 }
        .first { maxOf(dimensions.outWidth, dimensions.outHeight) / it <= 1400 }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val image = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: throw IllegalArgumentException("Could not decode selected image")
    val ratio = minOf(1.0, 960.0 / maxOf(image.width, image.height))
    val width = maxOf(1, (image.width * ratio).toInt())
    val height = maxOf(1, (image.height * ratio).toInt())
    val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(flattened).apply {
        drawColor(AndroidColor.WHITE)
        drawBitmap(image, null, android.graphics.Rect(0, 0, width, height), null)
    }
    image.recycle()
    var quality = 82
    var content = ByteArray(0)
    do {
        val out = ByteArrayOutputStream()
        flattened.compress(Bitmap.CompressFormat.JPEG, quality, out)
        content = out.toByteArray()
        quality -= 12
    } while (content.size > 350_000 && quality >= 34)
    if (content.size > 350_000) {
        val compact = Bitmap.createScaledBitmap(flattened,
            maxOf(1, width * 2 / 3), maxOf(1, height * 2 / 3), true)
        val out = ByteArrayOutputStream()
        compact.compress(Bitmap.CompressFormat.JPEG, 55, out)
        content = out.toByteArray()
        compact.recycle()
    }
    flattened.recycle()
    require(content.isNotEmpty() && content.size <= 600_000) {
        "Image is too complex for remote upload. Choose a smaller image."
    }
    return content
}

/** Persistent optimistic entry, never a fabricated remote session or success receipt. */
private data class SessionCreatePreview(
    val requestId: String, val title: String, val providerId: String, val kind: String, val project: String,
    val instruction: String, val status: String, val sessionKey: String? = null
)

private data class PrepfileStatusUi(
    val id: String, val request: String, val state: String,
    val outputPath: String?, val generatorSessionId: String?,
    val implementationThreadId: String?, val error: String?, val cleanup: String?
)
private fun prepfileStatus(value: JsonObject): PrepfileStatusUi? = runCatching {
    fun text(name: String) = value.get(name)?.takeUnless { it.isJsonNull }?.asString
    PrepfileStatusUi(text("id")!!, text("request").orEmpty(), text("state") ?: "unknown",
        text("outputPath"), text("generatorSessionId"), text("implementationThreadId"),
        text("error"), text("cleanup"))
}.getOrNull()

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
    (attention.isNotBlank() && !attention.equals("None", true)) ||
        // Browser sessions use explicit GMA/Return markers, not the legacy question marker.
        (kind != "t3/chatgpt" && activity.equals("WaitingOnUser", true))
/** Human-readable reason this session needs the user. */
private fun AgentSessionUi.attentionLabel(): String =
    attention.takeUnless { it.isBlank() || it.equals("None", true) }
        ?: activityText?.takeIf { it.isNotBlank() } ?: "Waiting for you"
private fun AgentSessionUi.projectName(): String =
    workspace?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() } ?: "No project"

/** Single source of truth for "a turn is running": drives the working pill, Stop and grouping. */
private fun AgentSessionUi.isRunning(): Boolean =
    activity.equals("Working", true) || activity.equals("Running", true) ||
        activity.equals("Active", true)
private fun AgentSessionUi.kindLabel(): String = when (kind) {
    "t3/thread" -> "Native T3"
    "t3/chatgpt" -> "ChatGPT"
    else -> kind
}
/** Compact relative age: 12s, 4m, 3h, 2d. Empty when unknown. */
private fun shortAge(fromMs: Long, now: Long): String {
    if (fromMs <= 0L) return ""
    val s = ((now - fromMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
/** Elapsed clock for the working pill: 45s, 3m 12s, 1h 04m. */
private fun elapsedLabel(fromMs: Long, now: Long): String {
    val s = ((now - fromMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${"%02d".format(s % 60)}s"
        else -> "${s / 3600}h ${"%02d".format((s % 3600) / 60)}m"
    }
}

/** Project name; when another project has the same name, add its parent folder ("t3code · Tools"). */
private fun projectLabel(project: NativeProjectUi, all: List<NativeProjectUi>): String {
    if (all.none { it.id != project.id && it.title.equals(project.title, true) }) return project.title
    val parent = project.workspaceRoot.trimEnd('\\', '/').split('\\', '/').dropLast(1).lastOrNull()
    return if (parent.isNullOrBlank()) project.title else project.title + " · " + parent
}

private fun AgentSessionUi.listGroup(sortMode: String): String =
    if (sortMode == "project") projectName() else groupName()

/** The provider's explicit status wins; older desktop publishers exposed this exact verified limit as Errored. */
private fun AgentSessionUi.isUsageLimited(): Boolean =
    activity.equals("UsageLimited", true) ||
        (kind == "t3/chatgpt" && activity.equals("Errored", true) &&
            activityText?.startsWith("A temporary limit", true) == true)

private fun AgentSessionUi.groupName(): String = when {
    isRunning() -> "WORKING"
    kind == "t3/chatgpt" && (borderColor != null || conversationFull) -> "HIGHLIGHTED"
    needsAttention() || unread == true -> "NEEDS YOU"
    isUsageLimited() -> "USAGE LIMITED"
    activity.equals("Errored", true) -> "ERRORS"
    else -> "CONVERSATIONS"
}
private fun AgentSessionUi.groupRank(): Int = when (groupName()) {
    "WORKING" -> 0
    "HIGHLIGHTED" -> 1
    "NEEDS YOU" -> 2
    "USAGE LIMITED" -> 3
    "CONVERSATIONS" -> 4
    else -> 5
}
/** Activity timestamps are optional in the provider protocol; unknown is always oldest. */
private fun activityMillis(iso: String?): Long =
    runCatching { java.time.Instant.parse(iso ?: "").toEpochMilli() }.getOrDefault(0L)

/** Personal colors and exhausted conversations are T3-owned, not guessed on-device. */
private fun AgentSessionUi.highlightColor(): Color? {
    if (conversationFull) return Color(0xFF8B5CF6) // Terminal: purple even if personally highlighted.
    return when (borderColor) {
        "red" -> Color(0xFFF87171)
        "orange" -> Color(0xFFFB923C)
        "yellow" -> Color(0xFFFACC15)
        "green" -> Color(0xFF34D399)
        "blue" -> Color(0xFF38BDF8)
        "purple" -> Color(0xFFA78BFA)
        "pink" -> Color(0xFFF472B6)
        else -> null
    }
}
private fun AgentSessionUi.problemLabel(): String? = when {
    conversationFull -> "Maximum conversation length · reboot required"
    isUsageLimited() ->
        activityText?.takeIf { it.isNotBlank() && it != "Failed" } ?: "Usage limit reached"
    kind == "t3/chatgpt" && activity.equals("Errored", true) ->
        activityText?.takeIf { it.isNotBlank() } ?: "ChatGPT error · desktop is checking recovery"
    kind == "t3/thread" && activity.equals("Errored", true) ->
        activityText?.takeIf { it.isNotBlank() && it != "Failed" } ?: "Native T3 error"
    else -> null
}

/** T3 GMA requests become yellow only after T3 reports the idle transition. */
private fun AgentSessionUi.readyAttention(requests: List<PhoneAgentAttention>): Boolean =
    !isRunning() && requests.any {
        it.status == "pending" && (it.sessionId == id || key.endsWith(":" + it.sessionId))
    }

private fun AgentSessionUi.dotColor(p: AiPalette, readyAttention: Boolean = false): Color {
    // T3 desktop parity: working lime, recent blue, unloaded dark grey,
    // unloaded-recent subdued blue, interruptions orange, errors rose.
    val millis = activityMillis(lastActivityAt)
    val recent = millis > 0L && System.currentTimeMillis() - millis in 0 until 7_200_000L
    val working = isRunning()
    val failed = activity.equals("Error", true) || activity.equals("Errored", true) ||
        activity.equals("Failed", true)
    val usageLimited = isUsageLimited()
    val interrupted = activity.equals("Interrupted", true) ||
        activityText?.startsWith("Connection interrupted") == true ||
        activityText?.startsWith("Message delivery timed out") == true ||
        attention.contains("interrupted", true) || attention.contains("timeout", true)
    val unloaded = kind == "t3/chatgpt" && reachability != "Reachable"
    return when {
        interrupted -> Color(0xFFF6A34A)
        usageLimited -> p.amber
        failed -> Color(0xFFE38D8D)
        unloaded && working && recent -> Color(0xFF587A5D)
        unloaded && recent -> Color(0xFF546C85)
        unloaded -> Color(0xFF52525B)
        working -> Color(0xFFA3E635)
        readyAttention -> p.amber
        needsAttention() -> p.amber
        recent -> Color(0xFF88A9C8)
        else -> p.muted
    }
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
/** Small rounded option chip with a trailing caret; opens a dropdown anchored by its parent Box. */
@Composable private fun PickerChip(icon: ImageVector, label: String, p: AiPalette,
    enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.raised)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = p.muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (enabled) p.text else p.muted, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        Icon(Icons.Default.ArrowDropDown, null, tint = p.muted, modifier = Modifier.size(16.dp))
    }
}
@Composable private fun SheetAction(label: String, p: AiPalette, tint: Color = p.text, onClick: () -> Unit) {
    Text(label, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(p.raised).clickable(onClick = onClick).padding(16.dp),
        color = tint, style = MaterialTheme.typography.bodyLarge)
}

/** Session list -> conversation, with a fixed composer and secondary tools in focused sheets. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OmniAiModesScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp),
    initialSessionId: String? = null,
    initialAttentionRequestId: String? = null
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
    var compactMode by rememberSaveable { mutableStateOf(viewPrefs.getBoolean("compactMode", false)) }
    val now = rememberNow()
    val providers by client.providers.collectAsState()
    val sessions by client.sessions.collectAsState()
    val attentionRequests by AndroidAttentionStore.entries.collectAsState()
    LaunchedEffect(context) { AndroidAttentionStore.init(context) }
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
    var busySlotsOpen by remember { mutableStateOf(false) }
    var busySlotValue by remember { mutableIntStateOf(0) }
    var busySlotsDragging by remember { mutableStateOf(false) }
    var dailyWorkOpen by remember { mutableStateOf(false) }
    var dailyWorkEnabled by remember { mutableStateOf(false) }
    var dailyWorkHours by remember { mutableIntStateOf(4) }
    var confirmIdleShutdown by remember { mutableStateOf(false) }
    // Effective desktop slider value: zero when auto-fill is disabled.
    val effectiveBusySlots = if (busyPolicy?.keepT3Busy == true)
        (busyPolicy?.maxWorking ?: 3).coerceIn(1, 5) else 0
    // Keep the selector synced to T3 unless the user is dragging it.

    LaunchedEffect(busySlotsOpen, effectiveBusySlots, busySlotsDragging, keepT3BusyUpdating) {
        if (busySlotsOpen && !busySlotsDragging && !keepT3BusyUpdating) {
            busySlotValue = effectiveBusySlots
        }
    }
    LaunchedEffect(online) {
        if (!online) {
            busyPolicy = null
        } else {
            while (true) {
                if (!keepT3BusyUpdating) {
                    try {
                        val refreshed = signalRClient.t3BusyPolicy()
                        if (!keepT3BusyUpdating) busyPolicy = refreshed
                    } catch (_: Exception) {
                        // Keep the last confirmed value during transient failures.
                    }
                }
                delay(15_000)
            }
        }
    }

    var listMode by rememberSaveable { mutableStateOf(initialSessionId == null) }
    var initialApplied by remember(initialSessionId) { mutableStateOf(false) }
    var sessionSearch by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sessionFilter by rememberSaveable { mutableStateOf("All") }
    var projectFilter by rememberSaveable { mutableStateOf("") }
    // Comma-separated persistence permits multi-color desktop-style filtering in Saveable state.
    var highlightColors by rememberSaveable { mutableStateOf("") }
    val selectedHighlightColors = highlightColors.split(',').filter { it.isNotBlank() }
    var threadKind by rememberSaveable {
        mutableStateOf(viewPrefs.getString("threadKind", "t3/chatgpt") ?: "t3/chatgpt")
    }
    var rowOptions by remember { mutableStateOf<AgentSessionUi?>(null) }
    var rowControls by remember { mutableStateOf<JsonObject?>(null) }
    var rowControlError by remember { mutableStateOf<String?>(null) }
    var rowControlBusy by remember { mutableStateOf(false) }
    var rowActionPage by remember { mutableStateOf("Session") }
    var closeConfirmation by remember { mutableStateOf<AgentSessionUi?>(null) }
    var nativeConfirmation by remember { mutableStateOf<Pair<AgentSessionUi, String>?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var deliveryMode by rememberSaveable { mutableStateOf("WhenIdle") }
    var maxActiveWorkers by rememberSaveable { mutableIntStateOf(4) }
    var createDeliveryMode by rememberSaveable { mutableStateOf("WhenIdle") }
    var loadedDraftFor by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var creationBusy by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val createPreviewPrefs = remember(context) {
        context.getSharedPreferences("ai-create-preview-v1", Context.MODE_PRIVATE)
    }
    var creationPreviews by remember(createPreviewPrefs) { mutableStateOf(
        runCatching { Gson().fromJson(createPreviewPrefs.getString("items", "[]"),
            Array<SessionCreatePreview>::class.java).orEmpty().map { entry ->
                if (entry.status == "Starting on PC…") entry.copy(
                    status = "Unconfirmed · app restarted before confirmation") else entry
            } }.getOrDefault(emptyList())
    ) }
    fun saveCreatePreviews(rows: List<SessionCreatePreview>) {
        // A committed preview survives process death while a provider request is in flight.
        check(createPreviewPrefs.edit().putString("items", Gson().toJson(rows)).commit()) {
            "Unable to preserve pending AI creation"
        }
        creationPreviews = rows
    }
    fun putCreatePreview(row: SessionCreatePreview) = saveCreatePreviews(
        creationPreviews.filterNot { it.requestId == row.requestId } + row)
    LaunchedEffect(sessions, creationPreviews) {
        val synced = creationPreviews.filterNot { it.sessionKey != null &&
            sessions.any { session -> session.key == it.sessionKey } }
        if (synced.size != creationPreviews.size) saveCreatePreviews(synced)
    }
    var addressMode by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var showT3Usage by remember { mutableStateOf(false) }
    val activeHubBaseUrl by signalRClient.currentBaseUrl.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    var humanInbox by remember { mutableStateOf(false) }
    var attentionSheet by remember { mutableStateOf(false) }
    var attentionBusy by remember { mutableStateOf<String?>(null) }
    var attentionError by remember { mutableStateOf<String?>(null) }
    // Resolved by T3 from the same desktop snooze preset configuration.
    var attentionSnoozeChoices by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var attentionSnoozeMenuFor by remember { mutableStateOf<String?>(null) }
    var attentionCustomFor by remember { mutableStateOf<String?>(null) }
    var attentionCustomMinutes by remember { mutableStateOf("90") }
    var createTab by rememberSaveable { mutableStateOf("t3/chatgpt") }
    var prepfileBusy by remember { mutableStateOf(false) }
    var prepfileError by remember { mutableStateOf<String?>(null) }
    var prepfileProgress by remember { mutableStateOf("") }
    var prepfileRequest by rememberSaveable { mutableStateOf("") }
    var prepfileDepth by rememberSaveable { mutableStateOf("medium") }
    var prepfileGenerator by rememberSaveable { mutableStateOf("chatgpt") }
    var prepfileProjectId by rememberSaveable { mutableStateOf("") }
    var prepfileModelKey by rememberSaveable { mutableStateOf("") }
    var prepfileEffort by rememberSaveable { mutableStateOf("") }
    var prepfileHandoff by rememberSaveable { mutableStateOf(true) }
    var prepfileTaskBoard by rememberSaveable { mutableStateOf(false) }
    var prepfileImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var prepfileJobs by remember { mutableStateOf<List<PrepfileStatusUi>>(emptyList()) }
    var prepfileId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var prepfileRequestSent by rememberSaveable { mutableStateOf(false) }
    val prepfilePrefs = remember(context) {
        context.getSharedPreferences("omni-prepfile-request-v1", Context.MODE_PRIVATE)
    }
    LaunchedEffect(Unit) {
        val saved = prepfilePrefs.getString("pending", null)
        if (saved != null) runCatching { Gson().fromJson(saved, JsonObject::class.java) }
            .getOrNull()?.let { snapshot ->
                fun value(name: String) = snapshot.get(name)?.takeUnless { it.isJsonNull }?.asString
                prepfileId = value("id") ?: prepfileId
                prepfileRequest = value("request").orEmpty()
                prepfileDepth = value("depth") ?: "medium"
                prepfileGenerator = value("generator") ?: "chatgpt"
                prepfileProjectId = value("projectId").orEmpty()
                prepfileModelKey = value("modelKey").orEmpty()
                prepfileEffort = value("effort").orEmpty()
                prepfileHandoff = value("handoff")?.toBoolean() ?: false
                prepfileTaskBoard = value("taskBoard")?.toBoolean() ?: false
                prepfileImages = snapshot.get("images")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.map { Uri.parse(it.asString) }.orEmpty()
                prepfileRequestSent = true
            }
    }
    val prepfileImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prepfileImages = (prepfileImages + uris).distinct().take(8)
    }
    var rename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }
    var switchProvider by remember { mutableStateOf<AgentProviderUi?>(null) }
    var create by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf("") }
    var newSessionTitle by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var nativeCatalog by remember { mutableStateOf<NativeCatalogUi?>(null) }
    var nativeCatalogLoading by remember { mutableStateOf(false) }
    var nativeCatalogError by remember { mutableStateOf<String?>(null) }
    var nativeProjectId by remember { mutableStateOf("") }
    var nativeModelKey by remember { mutableStateOf("") }
    var nativeEffort by remember { mutableStateOf("") }
    var projectMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var addProjectOpen by remember { mutableStateOf(false) }
    var addProjectName by remember { mutableStateOf("") }
    var addProjectBusy by remember { mutableStateOf(false) }
    val chosenNativeModel = nativeCatalog?.models?.firstOrNull { it.key == nativeModelKey }
    val chosenNativeProject = nativeCatalog?.projects?.firstOrNull { it.id == nativeProjectId }
    // Show a catalog and keep the current picks when still valid; otherwise fall back to the
    // last used project/model, then the project's default, then the first (favorites sort first).
    fun applyCatalog(c: NativeCatalogUi) {
        nativeCatalog = c
        if (c.projects.none { it.id == nativeProjectId })
            nativeProjectId = (c.projects.firstOrNull { it.id == viewPrefs.getString("lastNativeProject", null) }
                ?: c.projects.firstOrNull())?.id.orEmpty()
        val project = c.projects.firstOrNull { it.id == nativeProjectId }
        if (c.models.none { it.key == nativeModelKey }) {
            nativeModelKey = (c.models.firstOrNull { it.key == viewPrefs.getString("lastNativeModel", null) }
                ?: c.models.firstOrNull { it.instanceId == project?.defaultInstanceId && it.slug == project.defaultModel }
                ?: c.models.firstOrNull())?.key.orEmpty()
        }
        val model = c.models.firstOrNull { it.key == nativeModelKey }
        if (model?.efforts?.none { it.first == nativeEffort } != false)
            nativeEffort = listOfNotNull(viewPrefs.getString("lastNativeEffort", null), project?.defaultEffort, "medium")
                .firstOrNull { choice -> model?.efforts?.any { it.first == choice } == true }
                ?: model?.efforts?.firstOrNull()?.first.orEmpty()
    }
    // Cached catalog opens the sheet instantly; the live one replaces it in the background.
    LaunchedEffect(create, kind, online) {
        if (create && kind == "t3/thread") {
            (nativeCatalog ?: client.cachedNativeCatalog())?.let { applyCatalog(it) }
            if (!online) return@LaunchedEffect
            nativeCatalogLoading = true
            nativeCatalogError = null
            try { applyCatalog(client.nativeCatalog()) }
            catch (e: Exception) { nativeCatalogError = "Could not refresh T3 options: " + e.message }
            finally { nativeCatalogLoading = false }
        }
    }
    var scheduleOpen by remember { mutableStateOf(false) }
    var scheduleRevision by remember { mutableIntStateOf(0) }
    var scheduleTarget by remember { mutableStateOf<AgentSessionUi?>(null) }
    var scheduleBeginAt by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * MINUTE_MS) }
    var scheduleInitialIdle by remember { mutableStateOf(false) }
    var scheduleRequestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var scheduleBusy by remember { mutableStateOf(false) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var scheduledJobs by remember { mutableStateOf<List<OmniScheduledTask>>(emptyList()) }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var verify by remember { mutableStateOf<AddressIdentityPreview?>(null) }
    var receiptToRemove by remember { mutableStateOf<PendingAgentUi?>(null) }
    var followingLatest by remember(selectedKey, listMode) { mutableStateOf(true) }

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
    val sessionDeliveryReady = selected != null && !selected.conversationFull &&
        (!online || (selected.provider == active?.id &&
            selected.capabilities.contains("Submit") &&
            (selected.modes.contains("WhenIdle") || selected.modes.contains("Queue") ||
             selected.modes.contains("WaitForSlot") || selected.modes.contains("Steer"))))
    val canSend = loadedDraftFor == draftKey && message.isNotBlank() && (if (addressMode)
        address.trim().contains("@") && (active?.id ?: lastProvider) != null &&
            (!online || active?.capabilities?.contains("SubmitToAgent") == true)
        else sessionDeliveryReady)
    val deliveryHint = when {
        selected?.conversationFull == true && !addressMode ->
            "Maximum conversation length · reboot this T3 session"
        !online -> "Offline · draft saved; tap Send to queue"
        addressMode -> "Recipient confirmation required"
        selected == null -> "Select a conversation"
        selected.provider != active?.id -> "Activate ${selected.provider} to send"
        !sessionDeliveryReady -> "This session cannot receive messages here"
        selected.kind == "t3/thread" -> "Native T3 queue · does not interrupt the current run"
        deliveryMode == "WaitForSlot" -> "Waits for capacity; T3 queues the message"
        // GenericAgentClient.canDeliver turns WhenIdle into Steer for open ChatGPT chats.
        selected.kind == "t3/chatgpt" -> "Sends now, interrupting any reply · wakes the chat if asleep"
        deliveryMode == "Steer" -> "Immediate; may interrupt current reply"
        selected.modes.contains("Queue") -> "Native queue"
        else -> "Delivers when idle"
    }
    fun isT3Browser(s: AgentSessionUi) = s.provider == "t3" && s.kind == "t3/chatgpt"
    fun openChatGpt(target: AgentSessionUi) {
        fun launch(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage("com.openai.chatgpt")
                try { context.startActivity(intent) }
                catch (_: android.content.ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } catch (error: Exception) { note = "Open in ChatGPT failed: " + error.message }
        }
        // Browser catalog contains the exact conversation ID; no network lookup
        // needed on the normal path. This eliminates the 25s control RPC delay.
        val cachedUrl = rowControls?.takeIf { rowOptions?.key == target.key }
            ?.get("url")?.takeUnless { it.isJsonNull }?.asString
            ?.takeIf { it.startsWith("https://chatgpt.com/") }
        val conversation = target.conversation?.trim().orEmpty()
        val url = when {
            cachedUrl != null -> cachedUrl
            Regex("^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$")
                .matches(conversation) -> "https://chatgpt.com/c/" + conversation
            conversation.startsWith("https://chatgpt.com/c/") -> conversation
            else -> null
        }
        if (url != null) { launch(url); return }
        scope.launch {
            try {
                val fetched = client.sessionControls(target).get("url")
                    ?.takeUnless { it.isJsonNull }?.asString
                    ?.takeIf { it.startsWith("https://chatgpt.com/") }
                    ?: error("No ChatGPT URL is available for this session")
                launch(fetched)
            } catch (error: Exception) { note = "Open in ChatGPT failed: " + error.message }
        }
    }
    LaunchedEffect(rowOptions?.key, online) {
        rowControls = null
        rowControlError = null
        rowActionPage = "Session"
        val target = rowOptions
        if (target != null && online && (isT3Browser(target) || target.kind == "t3/thread") &&
            target.capabilities.contains("RemoteControl")) {
            try { rowControls = client.sessionControls(target) }
            catch (e: Exception) { rowControlError = "T3 control status unavailable: " + e.message }
        }
    }
    // The sheet may stay open across scheduler ticks or changes made on the PC.
    LaunchedEffect(rowOptions?.key, rowActionPage, online) {
        val target = rowOptions
        if (target == null || !online || rowActionPage != "Caffy" ||
            !isT3Browser(target) || !target.capabilities.contains("RemoteControl")) return@LaunchedEffect
        while (true) {
            delay(20_000)
            if (rowControlBusy) continue
            try {
                val latest = client.sessionControls(target)
                if (rowOptions?.key == target.key) {
                    rowControls = latest
                    rowControlError = null
                }
            } catch (e: Exception) {
                if (rowOptions?.key == target.key && rowControls == null)
                    rowControlError = "T3 control status unavailable: " + e.message
            }
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
                // Close is idempotent on the desktop: refresh the list and dismiss,
                // instead of asking the user to inspect state before retrying.
                if (command == "close" || (command in listOf("archive", "delete") && receipt == "Delivered")) {
                    runCatching { client.refresh() }
                    val gone = client.sessions.value.none { it.key == target.key }
                    note = if (gone || receipt != "Unknown" && receipt != "Pending") "${command.replaceFirstChar { it.uppercase() }} complete"
                        else "Close sent; the PC hasn't confirmed yet."
                    rowControlError = null
                    rowOptions = null
                    return@launch
                }
                if (command == "reload") {
                    rowControlError = null
                    rowOptions = null
                    note = if (receipt == "Delivered") "Reload requested"
                        else "Reload requested; PC confirmation: $receipt"
                    return@launch
                }
                if (receipt == "Unknown" || receipt == "Pending") {
                    rowControlError = "T3 has not confirmed ${command.replace('_', ' ')}. Refresh PC state before retrying."
                    runCatching { client.sessionControls(target) }
                        .onSuccess { if (rowOptions?.key == target.key) rowControls = it }
                    return@launch
                }
                if (command == "stop" && receipt != "Failed" && receipt != "Unknown") {
                    var idleConfirmed = false
                    for (waitMs in listOf(400L, 800L, 1200L, 2000L, 2400L)) {
                        delay(waitMs)
                        runCatching { client.refresh() }
                        val current = client.sessions.value.firstOrNull { it.key == target.key }
                        if (current?.activity?.equals("Idle", true) == true) {
                            idleConfirmed = true
                            break
                        }
                    }
                    note = if (idleConfirmed) "Stopped · session confirmed idle"
                        else "Stop accepted, but session has not reported Idle yet. Check again shortly."
                }
                rowControlError = null
                // Native placement changes (settle/snooze/pin/rename) reshape the list.
                if (target.kind == "t3/thread" && !command.startsWith("caffy_")) runCatching { client.refresh() }
                if (rowOptions?.key == target.key) {
                    try { rowControls = client.sessionControls(target) }
                    catch (e: Exception) {
                        rowControlError = "Action delivered, but status refresh failed: " + e.message
                    }
                }
            } catch (e: Exception) {
                rowControlError = "${command.replace('_', ' ')} failed: " + e.message
                note = rowControlError
            } finally { rowControlBusy = false }
        }
    }
    fun isHidden(s: AgentSessionUi) = if (isT3Browser(s)) s.visibilityHidden == true
        else viewPrefs.getBoolean("hidden:${s.key}", false)
    fun snoozedUntil(s: AgentSessionUi) = if (isT3Browser(s)) s.visibilitySnoozedUntil ?: 0L
        else maxOf(s.visibilitySnoozedUntil ?: 0L, viewPrefs.getLong("snooze:${s.key}", 0L))
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
    // Settled/snoozed native threads are only fetched while one of those filters is open.
    LaunchedEffect(sessionFilter, threadKind, listProvider, online) {
        val want = listProvider == "t3" && threadKind == "t3/thread" &&
            (sessionFilter == "Settled" || sessionFilter == "Snoozed")
        if (client.includeSettled != want) {
            client.includeSettled = want
            if (online) client.refresh()
        }
    }
    val availableProjects = remember(sessions, listProvider, threadKind) {
        sessions.asSequence().filter { s ->
            if (listProvider == "t3") s.provider == "t3" && s.kind == threadKind
            else s.provider == listProvider
        }.map { it.projectName() }
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).toList()
    }
    val visibleSessions = remember(sessions, sessionSearch, sessionFilter, projectFilter, highlightColors, sortMode,
        hideUnloaded, showWorkers, viewRevision, now / MINUTE_MS, threadKind, listProvider) {
        sessions.asSequence().filter { s ->
            if (listProvider == "t3") s.provider == "t3" && s.kind == threadKind
            else s.provider == listProvider
        }.filter { s ->
            projectFilter.isBlank() || s.projectName().equals(projectFilter, ignoreCase = true)
        }.filter { s ->
            highlightColors.isBlank() || (s.kind == "t3/chatgpt" && s.borderColor in selectedHighlightColors)
        }.filter { s ->
            sessionSearch.isBlank() || s.title.contains(sessionSearch, true) ||
                s.id.contains(sessionSearch, true) || s.kind.contains(sessionSearch, true) ||
                s.workspace?.contains(sessionSearch, true) == true
        }.filter { s ->
            when (sessionFilter) {
                "Hidden" -> isHidden(s)
                "Snoozed" -> !isHidden(s) && isSnoozed(s)
                "Settled" -> !isHidden(s) && s.settled
                else -> !isHidden(s) && !isSnoozed(s) && !s.settled
            }
        }.filter { s ->
            sessionFilter != "Attention" || s.needsAttention() || s.unread == true
        }.filter { s ->
            sessionFilter != "Active" || s.isRunning()
        }.filter { s ->
            sessionFilter == "Hidden" || sessionFilter == "Snoozed" || sessionFilter == "Settled" ||
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
                // Native threads: desktop order (pinned, then latest message), no status buckets.
                "Settled" -> list.sortedByDescending { it.sortAt ?: it.createdAt ?: 0L }
                else -> if (listProvider == "t3" && threadKind == "t3/thread" && sortMode != "project")
                    list.sortedWith(compareByDescending<AgentSessionUi> { it.pinned }
                        .thenByDescending { if (sortMode == "started") it.createdAt ?: 0L
                            else it.sortAt ?: it.createdAt ?: 0L }
                        .thenBy { it.key })
                else list.sortedWith(compareBy<AgentSessionUi> { if (sortMode == "project") 0 else it.groupRank() }
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
    // Keep reading the latest state inside the long-lived poll coroutine. Previously the
    // coroutine captured the opening session state (often Idle) and kept using its 8s
    // delay even after the model started replying.
    // Native Queue accepts a user turn before T3 publishes Working. Until the
    // authoritative history confirms that turn, poll with the active-chat cadence
    // rather than the idle cadence so the optimistic bubble resolves promptly.
    val nativeAwaitingHistory = selected?.kind == "t3/thread" &&
        outbox.any { item -> item.provider == selected?.provider &&
            item.target == selected?.id && item.state in listOf("InFlight", "Accepted") &&
            System.currentTimeMillis() - item.createdAt in 0L..60_000L }
    val selectedNowRunning by rememberUpdatedState(
        selected?.isRunning() == true || nativeAwaitingHistory)
    LaunchedEffect(online, listMode, selectedKey) {
        if (online && !listMode && selectedKey != null) {
            while (true) {
                // Active, visible conversation only. Live pushes remain the preferred path.
                delay(if (selectedNowRunning) 1_000L else 2_000L)
                client.select(selectedKey, reload = true)
            }
        }
    }
    BackHandler(enabled = humanInbox || !listMode) {
        if (humanInbox) humanInbox = false else listMode = true
    }
    fun schedulerCarrier(target: AgentSessionUi? = null): AgentSessionUi? =
        target ?: sessions.firstOrNull { isT3Browser(it) &&
            it.capabilities.contains("RemoteControl") }
    fun refreshAttentionRequests() {
        val carrier = schedulerCarrier() ?: return
        scope.launch {
            try {
                val details = client.sessionControls(carrier)
                val rows = details.get("attentions")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let(AndroidAttentionStore::fromStatus) }
                if (rows != null) AndroidAttentionStore.reconcile(context, rows)
                attentionSnoozeChoices = details.get("attentionSnoozePresets")
                    ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { preset ->
                        runCatching {
                            val item = preset.asJsonObject
                            val until = java.time.Instant.parse(item.get("snoozedUntil").asString).toEpochMilli()
                            if (until <= System.currentTimeMillis()) null else
                                (item.get("label").asString + " · " + item.get("whenLabel").asString) to until
                        }.getOrNull()
                    }.orEmpty()
                val jobs = details.get("prepfileJobs")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let(::prepfileStatus) }
                if (jobs != null) prepfileJobs = jobs
            } catch (error: Exception) {
                if (attentionSheet) attentionError = "Could not synchronize GMA: ${error.message}"
            }
        }
    }
    LaunchedEffect(online, t3Connected, sessions.size) {
        while (online && t3Connected) {
            refreshAttentionRequests()
            delay(20_000)
        }
    }
    fun controlAttention(request: PhoneAgentAttention, command: String, minutes: Int? = null,
                         until: Long? = null) {
        // T3 validates that a GMA action belongs to the EXACT target browser.
        // The first available browser may list all requests, but cannot acknowledge
        // another browser's notification.
        val carrier = sessions.firstOrNull { session ->
            isT3Browser(session) && session.capabilities.contains("RemoteControl") &&
                (session.id == request.sessionId ||
                    session.key.endsWith(":" + request.sessionId))
        }
        if (carrier == null || !online) {
            attentionError = "Exact requesting T3 session ${request.sessionId} is not controllable. Refresh the desktop session list before acknowledging."
            return
        }
        if (attentionBusy != null) return
        attentionBusy = request.id
        attentionError = null
        scope.launch {
            try {
                val receipt = client.sessionAction(carrier, "Control", mapOf(
                    "command" to command, "options" to mapOf(
                        "requestId" to request.id, "minutes" to minutes, "until" to until)))
                // A receipt may be unknown even after the PC applied the action.
                // Read the PC state rather than locally guessing dismissal or replaying.
                if (receipt == "Unknown" || receipt == "Pending")
                    attentionError = "Outcome uncertain. Refresh before trying again."
                val status = client.sessionControls(carrier)
                val rows = status.get("attentions")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { raw -> raw.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let(AndroidAttentionStore::fromStatus) }
                if (rows != null) AndroidAttentionStore.reconcile(context, rows)
            } catch (error: Exception) {
                attentionError = "GMA change unconfirmed: ${error.message}. Check PC status before retry."
            } finally { attentionBusy = null }
        }
    }
    fun openPrepfile() {
        create = true
        createTab = "prepfile"
        prepfileError = null
        (nativeCatalog ?: client.cachedNativeCatalog())?.let { catalog ->
            nativeCatalog = catalog
            if (catalog.models.none { it.key == prepfileModelKey }) {
                val fallback = catalog.models.firstOrNull {
                    !it.label.contains("luna", true) && !it.label.contains("haiku", true)
                } ?: catalog.models.firstOrNull()
                prepfileModelKey = fallback?.key.orEmpty()
                prepfileEffort = fallback?.efforts?.firstOrNull()?.first.orEmpty()
            }
        }
        if (online) scope.launch {
            runCatching { client.nativeCatalog() }.onSuccess { catalog ->
                nativeCatalog = catalog
                if (catalog.models.none { it.key == prepfileModelKey }) {
                    prepfileModelKey = catalog.models.firstOrNull()?.key.orEmpty()
                }
            }.onFailure { prepfileError = "Model catalog unavailable: ${it.message}" }
            refreshAttentionRequests()
        }
    }
    fun openLastAttention() {
        val current = System.currentTimeMillis()
        // Match desktop Ctrl+S: actionable requests first, then cycle through recent
        // resolved sessions without re-acknowledging them or bypassing a snooze.
        val pending = attentionRequests.filter {
            it.status in listOf("pending", "snoozed") && it.nextAt <= current
        }.maxByOrNull { it.activatedAt.takeIf { at -> at > 0 } ?: it.createdAt }
        val snoozedSessions = attentionRequests.filter {
            it.status == "snoozed" && it.nextAt > current
        }.map { it.sessionId }.toSet()
        val recent = attentionRequests.filter {
            it.status in listOf("opened", "dismissed") && it.activatedAt > 0 &&
                it.sessionId !in snoozedSessions
        }.sortedByDescending { it.activatedAt }.distinctBy { it.sessionId }
        val currentId = sessions.firstOrNull { it.key == selectedKey }?.id
        val history = recent.getOrNull(
            (recent.indexOfFirst { it.sessionId == currentId } + 1) % recent.size.coerceAtLeast(1))
        val request = pending ?: history
        if (request == null) {
            note = "No actionable or recent T3 attention requests."
            return
        }
        val session = sessions.firstOrNull {
            it.provider == "t3" && (it.id == request.sessionId ||
                it.key.endsWith(":" + request.sessionId))
        }
        if (session == null) {
            note = "Request from ${request.sessionId}: the exact T3 session is unavailable. Refresh sessions."
            return
        }
        openSession(session)
        if (pending != null) controlAttention(request, "attention_open")
    }
    fun submitPrepfile() {
        if (prepfileBusy || prepfileRequestSent) return
        val carrier = schedulerCarrier()
        if (carrier == null) {
            prepfileError = "A connected, controllable T3 ChatGPT session is required to reach the PC prepfile worker."
            return
        }
        if (prepfileRequest.isBlank()) { prepfileError = "Describe the request first."; return }
        val catalog = nativeCatalog ?: client.cachedNativeCatalog()
        val model = catalog?.models?.firstOrNull { it.key == prepfileModelKey }
        val generatorModel = if (prepfileGenerator == "chatgpt") null else
            catalog?.models?.firstOrNull { it.label.contains(prepfileGenerator, true) ||
                it.slug.contains(prepfileGenerator, true) }
        if (prepfileGenerator != "chatgpt" && generatorModel == null) {
            prepfileError = "The selected inexpensive native preparation model is unavailable."
            return
        }
        if (prepfileHandoff && model == null) {
            prepfileError = "Choose an available T3 implementation model."
            return
        }
        val snapshot = mapOf(
            "id" to prepfileId, "request" to prepfileRequest, "depth" to prepfileDepth,
            "generator" to prepfileGenerator, "projectId" to prepfileProjectId,
            "modelKey" to prepfileModelKey, "effort" to prepfileEffort,
            "handoff" to prepfileHandoff, "taskBoard" to prepfileTaskBoard,
            "images" to prepfileImages.map(Uri::toString)
        )
        if (!prepfilePrefs.edit().putString("pending", Gson().toJson(snapshot)).commit()) {
            prepfileError = "Could not durably save this prepfile request on the phone."
            return
        }
        prepfileBusy = true
        prepfileRequestSent = true // durable ID; uploads & creation are idempotent
        prepfileError = null
        prepfileProgress = "Preparing gallery images…" 
        scope.launch {
            try {
                // Reconcile before replaying any part. The desktop owns the durable job receipt.
                val pc = client.sessionControls(carrier)
                val reported = pc.get("prepfileJobs")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject
                        ?.let(::prepfileStatus) }
                if (reported != null) prepfileJobs = reported
                val prior = reported?.firstOrNull { it.id == prepfileId }
                if (prior != null) {
                    if (prior.request != prepfileRequest.trim())
                        throw IllegalStateException("The request ID belongs to a different PC job.")
                    prepfileProgress = "This request already exists on the PC: " + prior.state
                    return@launch
                }
                val imageRefs = prepfileImages.mapIndexed { index, uri ->
                    val contents = withContext(Dispatchers.IO) {
                        preparePrepfileImage(context, uri)
                    }
                    val pieces = Base64.encodeToString(contents, Base64.NO_WRAP).chunked(12_000)
                    pieces.forEachIndexed { part, piece ->
                        prepfileProgress = "Uploading image ${index + 1}/${prepfileImages.size} · ${part + 1}/${pieces.size} chunks"
                        val options = mapOf(
                            "id" to prepfileId, "index" to index, "part" to part,
                            "total" to pieces.size, "mime" to "image/jpeg",
                            "chunk" to piece
                        )
                        // Replaying the exact immutable part is safe, even after an uncertain receipt.
                        var done = false
                        var failure: Exception? = null
                        repeat(3) {
                            if (!done) {
                                try {
                                    val result = client.sessionAction(carrier, "Control",
                                        mapOf("command" to "prepfile_image_chunk", "options" to options))
                                    if (result !in listOf("Unknown", "Pending")) done = true
                                    else failure = IllegalStateException("Chunk outcome unconfirmed")
                                } catch (error: Exception) { failure = error }
                            }
                        }
                        if (!done) throw failure ?: IllegalStateException("Image chunk not confirmed")
                    }
                    mapOf("index" to index, "mime" to "image/jpeg")
                }
                val option: Map<String, Any?> = mapOf(
                    "id" to prepfileId,
                    "request" to prepfileRequest.trim(), "depth" to prepfileDepth,
                    "generator" to prepfileGenerator,
                    "projectId" to prepfileProjectId.ifBlank { null },
                    "autoHandoff" to prepfileHandoff, "createTaskboardTask" to prepfileTaskBoard,
                    "generatorModel" to generatorModel?.let { mapOf(
                        "instanceId" to it.instanceId, "model" to it.slug) },
                    "implementationModel" to (if (prepfileHandoff) model?.let { mapOf(
                        "instanceId" to it.instanceId, "model" to it.slug,
                        "effort" to prepfileEffort.takeIf(String::isNotEmpty)) } else null),
                    "imageRefs" to imageRefs
                )
                prepfileProgress = "Submitting the one-shot preparation job…"
                val receipt = client.sessionAction(carrier, "Control",
                    mapOf("command" to "prepfile_create", "options" to option))
                prepfileError = if (receipt == "Unknown" || receipt == "Pending")
                    "Creation outcome uncertain: refresh the PC journal before retrying this same request ID."
                    else null
                prepfileProgress = "PC job requested. Inspect its progress below."
                refreshAttentionRequests()
            } catch (error: Exception) {
                prepfileError = "Prepfile outcome unconfirmed: ${error.message}. Inspect this request ID on the PC before restarting."
            } finally { prepfileBusy = false; prepfileProgress = "" }
        }
    }
    var initialAttentionAcknowledged by remember(initialAttentionRequestId) { mutableStateOf(false) }
    LaunchedEffect(initialAttentionRequestId, initialSessionId,
        attentionRequests, online, t3Connected) {
        val request = attentionRequests.firstOrNull {
            it.id == initialAttentionRequestId && it.sessionId == initialSessionId &&
                it.status in listOf("pending", "snoozed") &&
                it.nextAt <= System.currentTimeMillis()
        }
        if (!initialAttentionAcknowledged && request != null && online && t3Connected) {
            // One attempt per deep link. If its receipt is uncertain the sheet can
            // reconcile it; re-composition must not enqueue another acknowledgement.
            initialAttentionAcknowledged = true
            controlAttention(request, "attention_open")
        }
    }
    fun refreshScheduledJobs() {
        val carrier = schedulerCarrier() ?: return
        scope.launch {
            try {
                val details = client.sessionControls(carrier)
                val entries = details.get("scheduledSpawns")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                scheduledJobs = entries?.mapNotNull { raw ->
                    val job = raw.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val id = job.get("id")?.takeUnless { it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                    val name = job.get("title")?.takeUnless { it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                    val whenMs = job.get("beginAt")?.takeUnless { it.isJsonNull }?.asLong
                    val idle = job.get("onIdle")?.takeUnless { it.isJsonNull }?.asBoolean == true
                    val state = job.get("status")?.takeUnless { it.isJsonNull }?.asString ?: "unknown"
                    OmniScheduledTask(id, name, whenMs, idle, state)
                } ?: emptyList()
            } catch (error: Exception) {
                scheduleError = "Could not refresh scheduled work: ${error.message}"
            }
        }
    }
    fun openSchedule(target: AgentSessionUi?, at: Long = System.currentTimeMillis() + 60 * MINUTE_MS, idle: Boolean = false) {
        if (!online || !t3Connected) {
            note = "Connect to T3 on your desktop before scheduling ChatGPT work."
            return
        }
        if (schedulerCarrier(target) == null) {
            note = "No controllable T3 ChatGPT session is available to schedule work."
            return
        }
        scheduleTarget = target
        scheduleBeginAt = at
        scheduleInitialIdle = idle
        scheduleRequestId = UUID.randomUUID().toString()
        scheduleRevision++
        scheduleError = null
        rowOptions = null
        scheduleOpen = true
        if (target == null) refreshScheduledJobs()
    }
    fun submitSchedule(title: String, prompt: String, at: Long?, onIdle: Boolean) {
        val carrier = schedulerCarrier(scheduleTarget) ?: return
        if (scheduleBusy) return
        scheduleBusy = true
        scheduleError = null
        val nativeSchedule = scheduleTarget?.kind == "t3/thread"
        val command = if (nativeSchedule) "caffy_set" else if (scheduleTarget == null) "caffy_schedule_spawn" else "caffy_begin"
        val options: Map<String, Any?> = if (nativeSchedule) mapOf(
            "mode" to "idle", "onceOnly" to true, "beginAt" to at,
            "repeatPrompt" to prompt, "respectSlots" to false
        ) else if (scheduleTarget == null) mapOf(
            "jobId" to scheduleRequestId, "title" to title, "prompt" to prompt,
            "beginAt" to at, "onIdle" to onIdle
        ) else mapOf("prompt" to prompt, "beginAt" to at, "onIdle" to onIdle)
        scope.launch {
            try {
                val receipt = client.sessionAction(carrier, "Control",
                    mapOf("command" to command, "options" to options))
                if (receipt != "Delivered") {
                    scheduleError = "Schedule not confirmed ($receipt). Inspect T3 before retrying."
                    return@launch
                }
                note = if (nativeSchedule) "Native prompt scheduled: $receipt"
                    else if (onIdle) "ChatGPT work queued ahead of TaskBoard: $receipt"
                    else "ChatGPT work scheduled: $receipt"
                scheduleOpen = false
                refreshScheduledJobs()
            } catch (error: Exception) {
                scheduleError = "Schedule unconfirmed: ${error.message}. Inspect status before retrying."
            } finally { scheduleBusy = false }
        }
    }
    fun cancelScheduledJob(jobId: String) {
        val carrier = schedulerCarrier() ?: return
        if (scheduleBusy) return
        scheduleBusy = true
        scope.launch {
            try {
                client.sessionAction(carrier, "Control", mapOf(
                    "command" to "caffy_cancel_spawn",
                    "options" to mapOf("jobId" to jobId)
                ))
                scheduledJobs = scheduledJobs.filterNot { it.id == jobId }
                note = "Scheduled new ChatGPT task cancelled."
            } catch (error: Exception) {
                scheduleError = "Cancel unconfirmed: ${error.message}"
            } finally { scheduleBusy = false }
        }
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
        workspace = ""; newSessionTitle = ""; firstMessage = ""; createDeliveryMode = "WhenIdle"; createError = null
        nativeProjectId = ""; nativeModelKey = ""; nativeEffort = ""
        createTab = kind
        create = true
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
            followingLatest = true
            client.saveDraft(draftKey, "")
            // Session sends show up in the thread right away with their delivery state,
            // so only contact messages need a separate notice.
            if (addressMode) note = if (online) "Message saved. Confirm the contact before it can be delivered."
                else "Message saved on this phone for reconnect."
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
    // Share the existing prepfile workflow with the New (+) sheet; no second modal.
    val prepfileForm: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTitle("New prepfile", "Executed by the authorized T3 PC desktop worker", p)
                if (!online || !t3Connected || schedulerCarrier() == null)
                    Text("Unavailable: connect to the desktop T3 provider and load at least one controllable ChatGPT session.",
                        color = p.amber)
                if (prepfileError != null) Text(prepfileError!!, color = p.amber,
                    style = MaterialTheme.typography.bodySmall)
                if (prepfileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (prepfileProgress.isNotBlank()) Text(prepfileProgress, color = p.muted,
                    fontSize = 12.sp)
                OutlinedTextField(prepfileRequest, { prepfileRequest = it.take(30_000) },
                    Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                    enabled = !prepfileRequestSent,
                    label = { Text("Task / request") },
                    placeholder = { Text("Describe the task, paste an issue, or specify what context to collect…") })
                Text("Research depth", color = p.muted, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("light", "medium", "heavy").forEach { option ->
                        FilterChip(prepfileDepth == option,
                            onClick = { prepfileDepth = option }, enabled = !prepfileRequestSent,
                            label = { Text(option.replaceFirstChar(Char::uppercase)) })
                    }
                }
                Text("Preparation agent", color = p.muted, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("chatgpt", "luna", "haiku").forEach { option ->
                        val available = option == "chatgpt" ||
                            nativeCatalog?.models?.any { it.label.contains(option, true) ||
                                it.slug.contains(option, true) } == true
                        FilterChip(prepfileGenerator == option, enabled = available && !prepfileRequestSent,
                            onClick = { prepfileGenerator = option },
                            label = { Text(when (option) {
                                "chatgpt" -> "ChatGPT"; "luna" -> "Luna (native)"; else -> "Haiku (native)"
                            } + if (available) "" else " · unavailable") })
                    }
                }
                var projectDropdown by remember { mutableStateOf(false) }
                Box {
                    TextButton(enabled = !prepfileRequestSent, onClick = { projectDropdown = true }) {
                        Text("Project: " + (nativeCatalog?.projects?.find { it.id == prepfileProjectId }
                            ?.title ?: "No project"))
                    }
                    DropdownMenu(expanded = projectDropdown, onDismissRequest = { projectDropdown = false }) {
                        DropdownMenuItem(text = { Text("No project") }, onClick = {
                            prepfileProjectId = ""; projectDropdown = false })
                        nativeCatalog?.projects?.forEach { project ->
                            DropdownMenuItem(text = { Text(project.title) }, onClick = {
                                prepfileProjectId = project.id; projectDropdown = false })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Create TaskBoard task", Modifier.weight(1f), color = p.text)
                    Switch(prepfileTaskBoard, onCheckedChange = { prepfileTaskBoard = it },
                        enabled = !prepfileRequestSent)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Start implementation automatically", Modifier.weight(1f), color = p.text)
                    Switch(prepfileHandoff, onCheckedChange = { prepfileHandoff = it },
                        enabled = !prepfileRequestSent)
                }
                if (prepfileHandoff) {
                    val models = nativeCatalog?.models.orEmpty()
                    val selectedModel = models.firstOrNull { it.key == prepfileModelKey }
                    var modelDropdown by remember { mutableStateOf(false) }
                    Box {
                        TextButton(enabled = !prepfileRequestSent, onClick = { modelDropdown = true }) {
                            Text("Implementation model: " + (selectedModel?.label ?: "Choose model"))
                        }
                        DropdownMenu(expanded = modelDropdown, onDismissRequest = { modelDropdown = false }) {
                            models.forEach { model ->
                                DropdownMenuItem(text = { Text(model.label + " · " + model.provider) },
                                    onClick = { prepfileModelKey = model.key
                                        prepfileEffort = model.efforts.firstOrNull()?.first.orEmpty()
                                        modelDropdown = false })
                            }
                        }
                    }
                    if (selectedModel?.efforts?.isNotEmpty() == true) {
                        Text("Implementation effort", color = p.muted,
                            style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedModel.efforts.forEach { (effort, label) ->
                                FilterChip(prepfileEffort == effort, enabled = !prepfileRequestSent,
                                    onClick = { prepfileEffort = effort }, label = { Text(label) })
                            }
                        }
                    }
                }
                TextButton(enabled = !prepfileRequestSent && prepfileImages.size < 8,
                    onClick = { prepfileImagePicker.launch(arrayOf("image/*")) }) {
                    Text("Attach gallery images (${prepfileImages.size}/8)")
                }
                prepfileImages.forEachIndexed { index, image ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(image.lastPathSegment.orEmpty().take(70), Modifier.weight(1f),
                            color = p.muted, fontSize = 12.sp)
                        TextButton(enabled = !prepfileRequestSent,
                            onClick = { prepfileImages = prepfileImages.filterIndexed { i, _ -> i != index } }) {
                            Text("Remove")
                        }
                    }
                }
                if (!prepfileRequestSent) {
                    Button(onClick = { submitPrepfile() },
                        enabled = !prepfileBusy && online && t3Connected &&
                            schedulerCarrier() != null && prepfileRequest.isNotBlank() &&
                            (!prepfileHandoff || nativeCatalog?.models?.any {
                                it.key == prepfileModelKey } == true)) {
                        Text("Start preparation")
                    }
                } else {
                    Text("Request ID: $prepfileId · Do not resubmit after an uncertain receipt.",
                        color = p.muted, fontSize = 12.sp)
                    val job = prepfileJobs.firstOrNull { it.id == prepfileId }
                    Text(job?.let { "PC state: " + it.state } ?:
                        "Awaiting PC acknowledgement. Refresh status; no automatic retry.",
                        color = if (job?.state == "needs_review") p.amber else p.accent)
                    job?.outputPath?.let { Text("Output: $it", color = p.muted, fontSize = 12.sp) }
                    job?.implementationThreadId?.let {
                        Text("Implementation thread: $it", color = p.muted, fontSize = 12.sp)
                    }
                    job?.error?.let { Text("Needs review: $it", color = p.amber) }
                    TextButton(onClick = {
                        refreshAttentionRequests()
                    }) { Text("Reconcile with PC") }
                    if (job == null) {
                        TextButton(enabled = !prepfileBusy, onClick = {
                            prepfileRequestSent = false
                            submitPrepfile()
                        }) { Text("Retry safely with the same request ID") }
                    }
                    TextButton(onClick = {
                        prepfilePrefs.edit().remove("pending").commit()
                        prepfileId = UUID.randomUUID().toString()
                        prepfileRequest = ""; prepfileImages = emptyList()
                        prepfileRequestSent = false; prepfileError = null
                    }) { Text("Start a different request") }
                }
                HorizontalDivider(color = p.border)
                Text("PC prepfile jobs", style = MaterialTheme.typography.labelLarge, color = p.text)
                TextButton(onClick = { refreshAttentionRequests() }) { Text("Refresh PC status") }
                if (prepfileJobs.isEmpty()) Text("No PC jobs reported yet", color = p.muted)
                prepfileJobs.asReversed().take(12).forEach { job ->
                    Column(Modifier.fillMaxWidth().background(p.raised, RoundedCornerShape(10.dp))
                        .padding(10.dp)) {
                        Text(job.request.ifBlank { job.id }, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = p.text)
                        Text(job.state + (job.cleanup?.let { " · cleanup: $it" } ?: ""),
                            color = if (job.state == "needs_review") p.amber else p.accent,
                            fontSize = 12.sp)
                        job.outputPath?.let { Text(it, color = p.muted, fontSize = 12.sp) }
                        job.implementationThreadId?.let { Text("Thread: $it",
                            color = p.muted, fontSize = 12.sp) }
                        job.error?.let { Text(it, color = p.amber, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
    }
    if (attentionSheet) {
        val visible = attentionRequests.filter { it.status in listOf("pending", "snoozed", "waiting") }
            .sortedByDescending { it.activatedAt.takeIf { at -> at > 0 } ?: it.createdAt }
        val readyCount = visible.count { it.status == "pending" && it.nextAt <= System.currentTimeMillis() }
        ModalBottomSheet(onDismissRequest = { attentionSheet = false },
            containerColor = p.surface, contentColor = p.text,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Needs your attention", color = p.text, fontSize = 22.sp,
                            fontWeight = FontWeight.Bold)
                        Text("$readyCount ready · ${visible.size} unresolved",
                            color = p.muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { refreshAttentionRequests() }) { Text("Refresh") }
                }
                if (attentionError != null)
                    Text(attentionError!!, color = p.amber,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (visible.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CheckCircle, null, tint = p.accent,
                            modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("All caught up", color = p.text, fontWeight = FontWeight.SemiBold)
                        Text("New requests will appear here automatically.",
                            color = p.muted, fontSize = 13.sp)
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        visible.forEach { request ->
                            val owner = sessions.firstOrNull {
                                it.provider == "t3" && (it.id == request.sessionId ||
                                    it.key.endsWith(":" + request.sessionId))
                            }
                            val waiting = request.status == "waiting"
                            val snoozed = request.status == "snoozed" &&
                                request.nextAt > System.currentTimeMillis()
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(p.raised).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(owner?.title ?: "T3 session",
                                            color = p.text, fontWeight = FontWeight.SemiBold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(when {
                                            waiting -> "WAITING FOR REPLY"
                                            snoozed -> "SNOOZED"
                                            else -> "READY FOR YOU"
                                        }, color = if (waiting || snoozed) p.muted else p.amber,
                                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = {
                                        if (owner == null)
                                            attentionError = "Session unavailable. Refresh the session list."
                                        else {
                                            openSession(owner)
                                            attentionSheet = false
                                            if (!waiting && !snoozed)
                                                controlAttention(request, "attention_open")
                                        }
                                    }) { Text(if (waiting || snoozed) "View" else "Open") }
                                }
                                if (request.message.isNotBlank())
                                    Text(request.message, color = p.text, fontSize = 14.sp)
                                if (snoozed)
                                    Text("Until " + java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(request.nextAt)),
                                        color = p.muted, fontSize = 12.sp)
                                if (waiting)
                                    Text("Will become ready after the session replies.",
                                        color = p.muted, fontSize = 12.sp)
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(enabled = attentionBusy == null && !waiting,
                                        onClick = {
                                            controlAttention(request, "attention_snooze", 10)
                                        }) { Text("10 min") }
                                    Box {
                                        TextButton(enabled = attentionBusy == null && !waiting,
                                            onClick = { attentionSnoozeMenuFor = request.id }) {
                                            Text("More snooze ▾")
                                        }
                                        DropdownMenu(expanded = attentionSnoozeMenuFor == request.id,
                                            onDismissRequest = { attentionSnoozeMenuFor = null }) {
                                            attentionSnoozeChoices.forEach { (label, until) ->
                                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                                    attentionSnoozeMenuFor = null
                                                    controlAttention(request, "attention_snooze", until = until)
                                                })
                                            }
                                            DropdownMenuItem(text = { Text("Custom duration…") },
                                                onClick = {
                                                    attentionSnoozeMenuFor = null
                                                    attentionCustomFor = request.id
                                                })
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(enabled = attentionBusy == null,
                                        onClick = { controlAttention(request, "attention_dismiss") }) {
                                        Text("Dismiss", color = p.muted)
                                    }
                                }
                                if (attentionCustomFor == request.id) {
                                    OutlinedTextField(attentionCustomMinutes,
                                        { attentionCustomMinutes = it.filter(Char::isDigit).take(5) },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("Minutes (1–10080)") })
                                    Row {
                                        TextButton(enabled = attentionBusy == null, onClick = {
                                            val minutes = attentionCustomMinutes.toIntOrNull()
                                            if (minutes == null || minutes !in 1..10080)
                                                attentionError = "Choose 1–10080 minutes."
                                            else {
                                                attentionCustomFor = null
                                                controlAttention(request, "attention_snooze", minutes)
                                            }
                                        }) { Text("Apply") }
                                        TextButton(onClick = { attentionCustomFor = null }) {
                                            Text("Cancel")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (scheduleOpen) {
        key(scheduleRevision) {
            OmniAiScheduleDialog(
                targetTitle = scheduleTarget?.title,
                initialBeginAt = scheduleBeginAt,
                initialOnIdle = scheduleInitialIdle,
                nativeThread = scheduleTarget?.kind == "t3/thread",
                jobs = scheduledJobs,
                busy = scheduleBusy,
                error = scheduleError,
                onDismiss = { scheduleOpen = false },
                onSubmit = ::submitSchedule,
                onCancelJob = ::cancelScheduledJob
            )
        }
    }
    if (busySlotsOpen) {
        AlertDialog(
            onDismissRequest = { busySlotsOpen = false },
            title = { Text("Keep T3 Busy · slots") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (busySlotValue == 0) "Automatic work: Off"
                         else "Maximum automatically working sessions: $busySlotValue", color = p.text)
                    Slider(
                        value = busySlotValue.toFloat(),
                        onValueChange = {
                            busySlotsDragging = true
                            busySlotValue = it.roundToInt().coerceIn(0, 5)
                        },
                        valueRange = 0f..5f, steps = 4,
                        enabled = online && busyPolicy != null && !keepT3BusyUpdating && busyPolicy?.dailyWorkCompleted != true,
                        onValueChangeFinished = {
                            val requested = busySlotValue
                            busySlotsDragging = false
                            if (requested != effectiveBusySlots) {
                                keepT3BusyUpdating = true
                                scope.launch {
                                    try {
                                        busyPolicy = signalRClient.setT3BusyCapacity(requested)
                                        note = if (requested == 0) "Keep T3 Busy disabled"
                                            else "Keep T3 Busy set to $requested slots"
                                    } catch (error: Exception) {
                                        note = "Capacity update failed: " + error.message
                                        try { busyPolicy = signalRClient.t3BusyPolicy() }
                                        catch (_: Exception) { /* Retain last confirmed policy. */ }
                                    } finally { keepT3BusyUpdating = false }
                                }
                            }
                        }
                    )
                    Text("0 = off · 1–5 = active work slots. Running sessions are not stopped.",
                        color = p.muted, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = p.border)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Shut down PC when idle", color = p.text)
                            Text("After 5 minutes idle; force-closes open programs.",
                                color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = busyPolicy?.shutdownWhenIdle == true,
                            enabled = online && busyPolicy != null && !keepT3BusyUpdating,
                            onCheckedChange = { checked ->
                                if (checked) { busySlotsOpen = false; confirmIdleShutdown = true }
                                else {
                                    keepT3BusyUpdating = true
                                    scope.launch {
                                        try { busyPolicy = signalRClient.t3BusyPolicy(shutdownWhenIdle = false) }
                                        catch (error: Exception) { note = "Shutdown update failed: ${error.message}" }
                                        finally { keepT3BusyUpdating = false }
                                    }
                                }
                            }
                        )
                    }
                    HorizontalDivider(color = p.border)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Daily work budget", color = p.text)
                            Text(
                                when {
                                    busyPolicy?.dailyWorkCompleted == true -> "Completed this run · restarts next T3 launch"
                                    busyPolicy?.dailyWorkMinutes != null ->
                                        "${(busyPolicy?.dailyWorkMinutes ?: 0) / 60}h each launch · then idle shutdown"
                                    else -> "Off · run for a set time on every T3 launch"
                                },
                                color = p.muted, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = {
                            dailyWorkEnabled = busyPolicy?.dailyWorkMinutes != null
                            dailyWorkHours = ((busyPolicy?.dailyWorkMinutes ?: 240) / 60).coerceIn(1, 12)
                            busySlotsOpen = false
                            dailyWorkOpen = true
                        }, enabled = online && busyPolicy != null && !keepT3BusyUpdating) {
                            Text("Configure")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { busySlotsOpen = false }) { Text("Done") } }
        )
    }
    if (confirmIdleShutdown) {
        AlertDialog(
            onDismissRequest = { confirmIdleShutdown = false },
            title = { Text("Enable automatic PC shutdown?") },
            text = { Text("T3 will force Windows to shut down after sessions have been idle for 5 minutes. Unsaved work in other apps will be lost. The arm expires after 10 hours.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmIdleShutdown = false
                    keepT3BusyUpdating = true
                    scope.launch {
                        try { busyPolicy = signalRClient.t3BusyPolicy(shutdownWhenIdle = true) }
                        catch (error: Exception) { note = "Shutdown update failed: ${error.message}" }
                        finally { keepT3BusyUpdating = false }
                    }
                }) { Text("Enable shutdown") }
            },
            dismissButton = { TextButton(onClick = { confirmIdleShutdown = false }) { Text("Cancel") } }
        )
    }
    if (dailyWorkOpen) {
        AlertDialog(
            onDismissRequest = { dailyWorkOpen = false },
            title = { Text("Daily T3 work") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable each T3 launch", Modifier.weight(1f), color = p.text)
                        Switch(checked = dailyWorkEnabled, onCheckedChange = { dailyWorkEnabled = it })
                    }
                    if (dailyWorkEnabled) {
                        Text("Work budget: $dailyWorkHours hours", color = p.text)
                        Slider(
                            value = dailyWorkHours.toFloat(),
                            onValueChange = { dailyWorkHours = it.roundToInt().coerceIn(1, 12) },
                            valueRange = 1f..12f,
                            steps = 10
                        )
                    }
                    Text("T3 resumes the last working-slot limit at launch. After the budget, new automatic work stops (0 slots); active sessions can finish. Idle shutdown is then armed. This repeats only when T3 restarts, not every 24 hours.",
                        color = p.muted, style = MaterialTheme.typography.bodySmall)
                    Text("Windows shutdown force-closes unsaved programs after T3 is idle.",
                        color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dailyWorkOpen = false
                    keepT3BusyUpdating = true
                    scope.launch {
                        try {
                            busyPolicy = if (dailyWorkEnabled)
                                signalRClient.t3BusyPolicy(dailyWorkMinutes = dailyWorkHours * 60)
                            else signalRClient.t3BusyPolicy(disableDailyWork = true)
                            note = if (dailyWorkEnabled) "Daily T3 work set to $dailyWorkHours hours per launch"
                                else "Daily T3 work disabled"
                        } catch (error: Exception) {
                            note = "Daily work update failed: ${error.message}"
                        } finally { keepT3BusyUpdating = false }
                    }
                }, enabled = online && busyPolicy != null && !keepT3BusyUpdating) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { dailyWorkOpen = false }) { Text("Cancel") } }
        )
    }
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
        // The Scaffold's bottom padding reserves space for the navigation bar, which
        // is covered by the IME. Do not leave that space between the composer and keyboard.
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(Modifier.fillMaxSize()
            .padding(top = parentPadding.calculateTopPadding(),
                bottom = if (keyboardVisible) 0.dp else parentPadding.calculateBottomPadding())
            .background(p.bg).imePadding()) {
            // Single compact toolbar; no stacked control bars or vertically wrapped title.
            Row(Modifier.fillMaxWidth().background(p.surface).height(52.dp)
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!listMode) IconButton(onClick = { listMode = true },
                    modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "All conversations", tint = p.text)
                } else Box(Modifier.size(30.dp).background(p.accentBg, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = p.accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (listMode) Text("AI", modifier = Modifier.weight(1f), color = p.text,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    style = MaterialTheme.typography.titleSmall)
                else {
                    // Thread header = title + one status line. Everything that used to
                    // live in stacked bars below (status, unloaded/retry) folds in here.
                    val unloaded = online && selected?.kind == "t3/chatgpt" &&
                        selected.reachability != "Reachable"
                    Column(Modifier.weight(1f).clickable(enabled = unloaded && !historyLoading) {
                        selected?.let { openSession(it) }
                    }) {
                        Text(selected?.title ?: "Conversation", color = p.text,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        if (selected != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(selected.dotColor(p, online && selected.readyAttention(attentionRequests)), CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text(listOfNotNull(
                                selected.kindLabel(),
                                selected.workspace?.takeIf { it.isNotBlank() }?.let { selected.projectName() },
                                when {
                                    selected.conversationFull -> "maximum length · reboot required"
                                    selected.problemLabel() != null -> selected.problemLabel()
                                    !online -> "offline · cached"
                                    historyLoading && unloaded -> "opening on PC…"
                                    unloaded -> "not loaded · tap to load"
                                    selected.needsAttention() -> selected.attentionLabel()
                                    selected.isRunning() -> null // the working pill says it
                                    else -> selected.activity.lowercase()
                                }).joinToString(" · "),
                                color = if (unloaded) p.amber else p.muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                        }
                    }
                }
                if (listMode) {
                    Dot(if (t3Connected) p.green else p.amber)
                    Spacer(Modifier.width(7.dp))
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(
                        enabled = online && busyPolicy != null && !keepT3BusyUpdating,
                        onClick = { busySlotsOpen = true }
                    ), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (effectiveBusySlots > 0) "Busy $effectiveBusySlots" else "Busy",
                            color = if (effectiveBusySlots > 0) p.green else p.muted,
                            fontSize = 11.sp, maxLines = 1)
                        Switch(checked = effectiveBusySlots > 0,
                            enabled = online && busyPolicy != null && !keepT3BusyUpdating,
                            onCheckedChange = null,
                            modifier = Modifier.size(width = 43.dp, height = 36.dp)
                                .scale(0.7f).semantics {
                                    contentDescription = "Keep T3 Busy; tap to configure 0 to 5 slots"
                                    stateDescription = if (effectiveBusySlots > 0) "On, $effectiveBusySlots slots" else "Off"
                                })
                    }
                    IconButton(onClick = {
                        if (!online) signalRClient.startConnection() else scope.launch { client.refresh() }
                    }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = p.text, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { openCreate() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Add, "New session", tint = p.text, modifier = Modifier.size(23.dp))
                    }
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = {
                        if (!listMode && selected != null) rowOptions = selected
                        else menu = true
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, "Full session actions", tint = p.text)
                    }
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
                            DropdownMenuItem(text = { Text((if (compactMode) "✓ " else "") + "Compact rows") },
                                onClick = {
                                    compactMode = !compactMode
                                    viewPrefs.edit().putBoolean("compactMode", compactMode).apply()
                                    menu = false
                                })
                DropdownMenuItem(text = { Text("Schedule ChatGPT work") },
                                enabled = t3Connected,
                                onClick = { menu = false; openSchedule(null) })
                            DropdownMenuItem(text = { Text("GMA requests (" + attentionRequests.count { it.status in listOf("waiting", "pending", "snoozed") } + ")") },
                                onClick = { menu = false; attentionSheet = true; refreshAttentionRequests() })
                            DropdownMenuItem(text = { Text("Open last requesting session · Ctrl+S") },
                                enabled = attentionRequests.any { it.status in listOf("pending", "waiting", "snoozed") },
                                onClick = { menu = false; openLastAttention() })
                DropdownMenuItem(text = { Text("Human inbox") },
                                onClick = { menu = false; humanInbox = true })
                            (listOf(60, 120, 240, 360)).forEach { minutes ->
                                DropdownMenuItem(text = { Text("Keep Busy ${minutes / 60}h then shut down") },
                                    onClick = { menu = false; pendingBusyMinutes = minutes })
                            }
                            if (busyPolicy?.busyUntil != null) DropdownMenuItem(
                                text = { Text("Cancel timed Busy") },
                                onClick = {
                                    menu = false; keepT3BusyUpdating = true
                                    scope.launch {
                                        try { busyPolicy = signalRClient.t3BusyPolicy(false) }
                                        catch (error: Exception) { note = "Cancel failed: ${error.message}" }
                                        finally { keepT3BusyUpdating = false }
                                    }
                                })
                            DropdownMenuItem(text = { Text("Snoozed sessions ($snoozedCount)") },
                                onClick = { sessionFilter = "Snoozed"; menu = false })
                            DropdownMenuItem(text = { Text("Hidden sessions ($hiddenCount)") },
                                onClick = { sessionFilter = "Hidden"; menu = false })
                            HorizontalDivider(color = p.border)
                        }
                        DropdownMenuItem(text = { Text("T3 usage") },
                            onClick = { menu = false; showT3Usage = true })
                        if (!listMode) {
                            DropdownMenuItem(text = { Text("Session actions") }, onClick = {
                                menu = false; rowOptions = selected
                            })
                            if (selected?.let { isT3Browser(it) } == true)
                                DropdownMenuItem(text = { Text("Open in ChatGPT app") }, onClick = {
                                    menu = false; selected?.let { openChatGpt(it) }
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
            note?.let { message ->
                Row(Modifier.fillMaxWidth().background(p.accentBg)
                    .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).clickable(onClickLabel = "Copy notice") {
                        clipboard.setText(AnnotatedString(message))
                    }, color = p.text, style = MaterialTheme.typography.bodySmall)
                    ActionIcon(Icons.Default.Close, "Dismiss notice", p) { note = null }
                }
            }

            if (listMode) {
                val readyRequests = attentionRequests.filter { request ->
                    request.status == "pending" && sessions.any { session ->
                        session.provider == "t3" && !session.isRunning() &&
                            (session.id == request.sessionId ||
                                session.key.endsWith(":" + request.sessionId))
                    }
                }
                if (readyRequests.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().background(p.accentBg)
                        .clickable { attentionSheet = true; refreshAttentionRequests() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.NotificationImportant, null,
                        tint = p.amber, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    val latest = readyRequests.maxByOrNull { it.createdAt }
                    val owner = sessions.firstOrNull { it.id == latest?.sessionId ||
                        it.key.endsWith(":" + latest?.sessionId) }
                    Text("GMA · " + (owner?.title ?: "Session") + " needs you" +
                        if (readyRequests.size > 1) " (+${readyRequests.size - 1})" else "",
                        color = p.text, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null, tint = p.accent)
                }
                // One header row: thread kind toggle, filter menu, search.
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (listProvider == "t3") Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.raised)
                        .padding(2.dp)) {
                        listOf("t3/chatgpt" to "ChatGPT", "t3/thread" to "Native").forEach { (value, label) ->
                            val on = threadKind == value
                            Text(label + " " + sessions.count { it.provider == "t3" && it.kind == value },
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (on) p.accentBg else Color.Transparent)
                                    .clickable {
                                        threadKind = value
                                        viewPrefs.edit().putString("threadKind", value).apply()
                                        sessionFilter = "All"
                                        projectFilter = ""
                                        highlightColors = ""
                                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = if (on) p.accent else p.muted, fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        var filterMenu by remember { mutableStateOf(false) }
                        Row(Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (sessionFilter != "All" || projectFilter.isNotBlank() || highlightColors.isNotBlank()) p.accentBg else Color.Transparent)
                            .clickable { filterMenu = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FilterList, null, tint = if (sessionFilter != "All" || projectFilter.isNotBlank() || highlightColors.isNotBlank()) p.accent else p.muted,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (highlightColors.isNotBlank()) "Colors " + selectedHighlightColors.size else if (projectFilter.isNotBlank()) projectFilter else if (sessionFilter == "All" && attention > 0) "$attention need you" else sessionFilter,
                                color = if (sessionFilter != "All" || projectFilter.isNotBlank() || highlightColors.isNotBlank() || attention > 0) p.accent else p.muted,
                                fontSize = 13.sp, maxLines = 1)
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            (listOf("All", "Active", "Attention", "Snoozed") +
                                (if (listProvider == "t3" && threadKind == "t3/thread") listOf("Settled") else emptyList()) +
                                "Hidden").forEach { value ->
                                DropdownMenuItem(text = { Text((if (sessionFilter == value) "✓ " else "") + when (value) {
                                    "Attention" -> "Needs you" + if (attention > 0) " ($attention)" else ""
                                    "Snoozed" -> "Snoozed" + if (snoozedCount > 0) " ($snoozedCount)" else ""
                                    "Hidden" -> "Hidden" + if (hiddenCount > 0) " ($hiddenCount)" else ""
                                    "Active" -> "Working"
                                    else -> value
                                }) }, onClick = { sessionFilter = value; filterMenu = false })
                            }
                            if (listProvider == "t3" && threadKind == "t3/chatgpt") {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Highlight colors · All") },
                                    onClick = { highlightColors = ""; filterMenu = false })
                                listOf("red", "orange", "yellow", "green", "blue", "purple", "pink").forEach { color ->
                                    DropdownMenuItem(text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Dot(when (color) {
                                                "red" -> Color(0xFFF87171)
                                                "orange" -> Color(0xFFFB923C)
                                                "yellow" -> Color(0xFFFACC15)
                                                "green" -> Color(0xFF34D399)
                                                "blue" -> Color(0xFF38BDF8)
                                                "purple" -> Color(0xFFA78BFA)
                                                else -> Color(0xFFF472B6)
                                            })
                                            Spacer(Modifier.width(7.dp))
                                            Text((if (color in selectedHighlightColors) "✓ " else "") +
                                                color.replaceFirstChar { it.uppercase() })
                                        }
                                    }, onClick = {
                                        highlightColors = (if (color in selectedHighlightColors)
                                            selectedHighlightColors.filterNot { it == color }
                                        else selectedHighlightColors + color).joinToString(",")
                                    })
                                }
                            }
                            if (availableProjects.isNotEmpty()) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text((if (projectFilter.isBlank()) "✓ " else "") + "All projects") },
                                    onClick = { projectFilter = ""; filterMenu = false })
                                availableProjects.forEach { project ->
                                    DropdownMenuItem(text = { Text((if (projectFilter == project) "✓ " else "") + project) },
                                        onClick = { projectFilter = project; filterMenu = false })
                                }
                            }
                        }
                    }
                    IconButton(onClick = { searchExpanded = !searchExpanded },
                        modifier = Modifier.size(36.dp)) {
                        Icon(if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                            if (searchExpanded) "Close search" else "Search conversations",
                            tint = p.text, modifier = Modifier.size(20.dp))
                    }
                }
                if (searchExpanded || sessionSearch.isNotEmpty()) {
                    OutlinedTextField(sessionSearch, { sessionSearch = it },
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        singleLine = true, placeholder = { Text("Search conversations") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (sessionSearch.isNotEmpty()) {{
                            IconButton(onClick = { sessionSearch = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }} else null,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.border))
                }
                // Local creation appears before the backend returns an ID. Once an ID
                // is confirmed we keep this card until the actual catalog catches up.
                creationPreviews.filter { preview ->
                    preview.providerId == listProvider &&
                        (listProvider != "t3" || preview.kind == threadKind) &&
                        (sessionSearch.isBlank() || preview.title.contains(sessionSearch, true))
                }.forEach { preview ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp)).background(p.accentBg)
                        .clickable {
                            if (preview.sessionKey != null) scope.launch {
                                if (client.ensureSession(preview.sessionKey)) {
                                    client.select(preview.sessionKey); listMode = false
                                } else note = "Created session is not published yet; still syncing."
                            } else {
                                kind = preview.kind
                                workspace = preview.project
                                nativeProjectId = preview.project
                                firstMessage = preview.instruction
                                createError = if (preview.status.startsWith("Unconfirmed"))
                                    "Retry with the original settings to reconcile without duplication." else null
                                create = true
                            }
                        }.padding(start = 12.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (preview.status == "Starting on PC…") CircularProgressIndicator(
                            Modifier.size(15.dp), strokeWidth = 2.dp, color = p.accent)
                        else Icon(Icons.Default.CloudSync, null, tint = p.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preview.title, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(preview.status, color = p.muted, fontSize = 11.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            saveCreatePreviews(creationPreviews.filterNot { it.requestId == preview.requestId })
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Dismiss local creation status", tint = p.muted,
                                modifier = Modifier.size(17.dp))
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(
                    start = 10.dp, end = 10.dp, top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactMode) 3.dp else 4.dp)) {
                    itemsIndexed(visibleSessions, key = { _, session -> session.key }) { index, session ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!compactMode && sessionSearch.isBlank() && sessionFilter == "All" &&
                                (sortMode == "project" || threadKind != "t3/thread" || listProvider != "t3") &&
                                (index == 0 || visibleSessions[index - 1].listGroup(sortMode) != session.listGroup(sortMode))) {
                                Text(session.listGroup(sortMode),
                                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 2.dp),
                                    color = p.muted, fontSize = 11.sp, letterSpacing = 1.1.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        val age = shortAge(maxOf(activityMillis(session.lastActivityAt),
                            viewPrefs.getLong("visited:${session.key}", 0L)), now)
                        if (compactMode) {
                            val snoozed = isSnoozed(session)
                            val persistentAttention = session.returnMarker != null
                            val caffyBadge = desktopCaffyBadge(session, now)
                            val attentionReady = session.returnMarker == "ready" && !snoozed &&
                                caffyBadge == null && !session.isRunning()
                            val cornerIndicators = snoozed || persistentAttention || caffyBadge != null
                            // Corner overlay: the badges/age are drawn over the existing 34dp-ish
                            // compact row, never measured as another line or extra row height.
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                .background(if (session.key == selectedKey) p.selected else p.surface)
                                .border(if (session.highlightColor() != null) 2.dp else 1.5.dp,
                                    session.highlightColor() ?: session.dotColor(p, online && session.readyAttention(attentionRequests)),
                                    RoundedCornerShape(9.dp))
                                .combinedClickable(onClick = { openSession(session) },
                                    onLongClick = { rowOptions = session })) {
                                Row(Modifier.fillMaxWidth()
                                    .padding(start = 11.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(session.title, modifier = Modifier.weight(1f),
                                        color = if (session.kind == "t3/chatgpt") session.dotColor(p, online && session.readyAttention(attentionRequests)) else p.text,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (session.unread == true) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp)
                                    if (session.conversationFull) {
                                        Text("FULL", color = Color(0xFFA78BFA), fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp))
                                    } else if (session.isUsageLimited()) {
                                        Text("LIMIT", color = p.amber, fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp))
                                    } else if (session.kind == "t3/chatgpt" &&
                                        session.activity.equals("Errored", true)) {
                                        Text(if (session.activityText?.startsWith("Connection interrupted") == true) "INT" else "ERR",
                                            color = session.dotColor(p), fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp))
                                    } else if (session.kind == "t3/thread" &&
                                        session.activity.equals("Errored", true)) {
                                        Text("ERR", color = session.dotColor(p), fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp))
                                    } else if (session.verificationPending) {
                                        Text("VERIFY", color = p.amber, fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 5.dp))
                                    }
                                    if (cornerIndicators) {
                                        // Reserve only horizontal space so flags never obscure title.
                                        val badgeWidth = (if (snoozed) 13 else 0) +
                                            (if (persistentAttention) 13 else 0) +
                                            (if (caffyBadge != null) 18 + caffyBadge.code.length * 6 else 0) +
                                            (listOf(snoozed, persistentAttention, caffyBadge != null).count { it } - 1).coerceAtLeast(0) * 3 + 5
                                        Spacer(Modifier.width(badgeWidth.dp))
                                    } else if (session.isRunning()) CircularProgressIndicator(Modifier.size(11.dp),
                                        strokeWidth = 1.5.dp, color = session.dotColor(p, online && session.readyAttention(attentionRequests)))
                                    else if (session.needsAttention()) Icon(Icons.Default.PriorityHigh, null,
                                        tint = p.amber, modifier = Modifier.size(14.dp))
                                    else Text(age, color = p.muted, fontSize = 11.sp)
                                }
                                if (cornerIndicators) {
                                    Box(Modifier.matchParentSize().padding(end = 9.dp, top = 2.dp, bottom = 1.dp)) {
                                        Row(Modifier.align(Alignment.TopEnd),
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            if (snoozed) T3SidebarStatusGlyph(
                                                T3SidebarGlyph.Snooze, p.muted, "Snoozed")
                                            if (persistentAttention) T3SidebarStatusGlyph(
                                                T3SidebarGlyph.PersistentAttention,
                                                if (attentionReady) Color(0xFFFACC15) else p.muted,
                                                if (attentionReady) "Persistent attention ready" else "Persistent attention armed")
                                            if (caffyBadge != null) T3CaffyCompactBadgeView(caffyBadge)
                                        }
                                        if (session.isRunning()) CircularProgressIndicator(
                                            Modifier.align(Alignment.BottomEnd).size(10.dp),
                                            strokeWidth = 1.5.dp,
                                            color = session.dotColor(p, online && session.readyAttention(attentionRequests)))
                                        else Text(age, Modifier.align(Alignment.BottomEnd),
                                            color = p.muted, fontSize = 10.sp, lineHeight = 11.sp)
                                    }
                                }
                            }
                        } else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(if (session.key == selectedKey) p.selected else p.surface)
                            .border(if (session.highlightColor() != null) 2.dp else 0.dp,
                                session.highlightColor() ?: Color.Transparent, RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = { openSession(session) },
                                onLongClick = { rowOptions = session })
                            .padding(start = 12.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(p.raised, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center) {
                                if (session.isRunning()) CircularProgressIndicator(Modifier.size(16.dp),
                                    strokeWidth = 2.dp, color = session.dotColor(p, online && session.readyAttention(attentionRequests)))
                                else Text(session.title.trim().take(1).uppercase().ifBlank { "A" },
                                    color = session.dotColor(p, online && session.readyAttention(attentionRequests)), fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(session.title, Modifier.weight(1f),
                                        color = if (session.kind == "t3/chatgpt") session.dotColor(p, online && session.readyAttention(attentionRequests)) else p.text,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (session.unread == true) FontWeight.Bold else FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyLarge)
                                    if (session.conversationFull) Text("FULL", color = Color(0xFFA78BFA),
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    else if (session.verificationPending) Text("VERIFY", color = p.amber,
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(age, color = p.muted, fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 6.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Dot(session.dotColor(p, online && session.readyAttention(attentionRequests)))
                                    Spacer(Modifier.width(6.dp))
                                    Text(listOfNotNull(
                                        when {
                                            sessionFilter == "Hidden" -> "Hidden"
                                            sessionFilter == "Snoozed" -> "Snoozed · " +
                                                ((snoozedUntil(session) - now + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1) + "m left"
                                            session.problemLabel() != null -> session.problemLabel()
                                            session.verificationPending -> "Awaiting verification"
                                            session.needsAttention() -> session.attentionLabel()
                                            session.isRunning() -> session.activityText?.takeIf { it.isNotBlank() } ?: "Working"
                                            session.kind == "t3/chatgpt" && session.reachability != "Reachable" -> "Unloaded"
                                            else -> session.activity
                                        },
                                        if (sortMode != "project" && session.workspace?.isNotBlank() == true)
                                            session.projectName() else null
                                    ).joinToString(" · "),
                                        color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (session.unread == true) Dot(p.accent)
                            IconButton(onClick = { rowOptions = session }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.MoreVert, "Session actions", tint = p.muted,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        }
                    }
                    if (visibleSessions.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Default.Forum, null, tint = p.muted, modifier = Modifier.size(35.dp))
                            Text(if (sessionSearch.isNotBlank() || sessionFilter != "All" || projectFilter.isNotBlank()) "No matches"
                                else if (online) "No sessions available" else "Connect to load sessions",
                                style = MaterialTheme.typography.titleMedium, color = p.text)
                            Text(if (sessionSearch.isNotBlank() || sessionFilter != "All" || projectFilter.isNotBlank())
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
                // Creation is available from the toolbar; no bottom action bar.
            } else {
                // Thread view, T3-mobile style: header (above) -> timeline -> floating
                // status pill -> composer. No stacked status/attention/pending bars.
                val running = selected?.isRunning() == true
                val nativeThread = selected?.kind == "t3/thread"
                // Native T3 history carries tool events, reasoning and provider
                // metadata as turn items. Keep those out of the chat transcript,
                // including copies restored from the offline cache.
                val conversationHistory = remember(history, nativeThread) {
                    if (nativeThread) history.filter { item ->
                        // Older offline caches may contain the previous JSON dump
                        // fallback for an otherwise valid user/assistant message.
                        val staleMetadata = item.text.startsWith(item.kind) &&
                            item.text.contains("\n{") &&
                            item.text.contains("\"type\":\"${item.kind}\"")
                        item.kind in listOf("user_message", "assistant_message") &&
                            !staleMetadata && (item.text.isNotBlank() || item.streaming)
                    } else history
                }
                var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(running, selected?.needsAttention()) {
                    // 1s ticker only while something on screen is counting.
                    while (running) { clock = System.currentTimeMillis(); delay(1_000) }
                    clock = System.currentTimeMillis()
                }
                // When the phone itself last saw new content; complements provider lastActivityAt.
                var lastContentAt by remember(selectedKey) { mutableLongStateOf(0L) }
                LaunchedEffect(conversationHistory.lastOrNull()?.id,
                    conversationHistory.lastOrNull()?.revision,
                    conversationHistory.lastOrNull()?.text?.length, outbox) {
                    if (conversationHistory.isNotEmpty()) {
                        lastContentAt = System.currentTimeMillis()
                        // For browser and native T3 threads, this distinguishes visible
                        // canonical messages from Hub receipt/queue acceptance.
                        if (selected?.kind == "t3/chatgpt" || selected?.kind == "t3/thread")
                            selectedKey?.let { key -> client.noteTimelineVisible(key, conversationHistory) }
                    }
                }
                val firstSeenRunning = remember(selectedKey, running) {
                    if (running) System.currentTimeMillis() else 0L
                }
                val turnStart = activityMillis(selected?.turnStartedAt).takeIf { it > 0 } ?: firstSeenRunning
                val lastUpdate = maxOf(activityMillis(selected?.lastActivityAt), lastContentAt)
                var stoppingKey by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(running) { if (!running) stoppingKey = null }
                val canStop = online && selected != null && (selected.capabilities.contains("Interrupt") ||
                    (isT3Browser(selected) && selected.capabilities.contains("RemoteControl")))
                fun stop() {
                    val s = selected ?: return
                    stoppingKey = s.key
                    // sessions.interrupt works for native and loaded ChatGPT sessions; the
                    // desktop "stop" control is only a fallback for older providers.
                    if (s.capabilities.contains("Interrupt")) operation("Interrupt")
                    else performRowControl(s, "stop")
                }
                // Messages this phone sent to this thread that history hasn't absorbed yet.
                // Failed ones stay visible here so a send can never vanish silently.
                val threadOutbox = outbox.filter { it.provider == selected?.provider &&
                    it.targetType == "session" && it.target == selected?.id && it.state != "Delivered" }
                    .sortedBy { it.createdAt }

                if (historyLoading) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = p.accent,
                    trackColor = p.bg)
                // Each opening starts at the newest message, including when revisiting the same session.
                val scroll = remember(selectedKey, listMode) { LazyListState() }
                val timelineItemCount = conversationHistory.size + threadOutbox.size +
                    (if (older != null) 1 else 0) + (if (!historyNotice.isNullOrBlank()) 1 else 0)
                LaunchedEffect(selectedKey, listMode, timelineItemCount,
                    conversationHistory.lastOrNull()?.id, conversationHistory.lastOrNull()?.revision,
                    threadOutbox.size, followingLatest) {
                    if (!listMode && timelineItemCount > 0 && followingLatest) {
                        // History often arrives after the screen opens. Wait for LazyColumn to
                        // measure the new rows rather than scrolling using the old item count.
                        snapshotFlow { scroll.layoutInfo.totalItemsCount }
                            .first { it >= timelineItemCount }
                        if (followingLatest) scroll.scrollToItem(timelineItemCount - 1)
                    }
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
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), state = scroll,
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(historyNotice!!, Modifier.weight(1f), color = p.muted,
                                    style = MaterialTheme.typography.labelSmall)
                                if (online && historyNotice!!.contains("failed", ignoreCase = true))
                                    TextButton(onClick = {
                                        selectedKey?.let { key -> scope.launch { client.select(key, reload = true) } }
                                    }) { Text("Retry") }
                            }
                        }
                        if (conversationHistory.isEmpty() && threadOutbox.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(top = 65.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.ChatBubbleOutline, null, tint = p.muted,
                                    modifier = Modifier.size(32.dp))
                                Text(if (historyLoading) "Loading conversation…"
                                    else if (online) "No messages yet"
                                    else "Nothing cached for this conversation",
                                    color = p.muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(conversationHistory, key = { it.id }) { item -> MessageRow(item, p) }
                        items(threadOutbox, key = { "local:" + it.id }) { item ->
                            LocalOutgoingRow(item, p) { sheet = "outbox" }
                        }
                    }
                    // Floating status pill: what it's doing, how long, and Stop — only while relevant.
                    val attentionNow = selected?.needsAttention() == true && !running
                    val queued = remoteQueue.size
                    if (running || attentionNow || queued > 0) Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 12.dp, end = 64.dp)
                            .clip(RoundedCornerShape(22.dp)).background(p.raised)
                            .border(1.dp, p.border, RoundedCornerShape(22.dp))
                            .height(40.dp).padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            val stale = lastUpdate > 0 && clock - lastUpdate > 120_000
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp,
                                color = if (stale) p.amber else p.accent)
                            Spacer(Modifier.width(8.dp))
                            Text(if (stoppingKey == selected?.key) "Stopping…"
                                else "Working" + (if (turnStart > 0) " · " + elapsedLabel(turnStart, clock) else ""),
                                color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            val detail = selected?.activityText?.takeIf { it.isNotBlank() }
                                ?: if (lastUpdate > 0) "updated " + shortAge(lastUpdate, clock) + " ago" else null
                            if (detail != null) Text(" · $detail", Modifier.weight(1f, fill = false),
                                color = if (stale) p.amber else p.muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (canStop && stoppingKey != selected?.key) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.width(1.dp).height(16.dp).background(p.border))
                                TextButton(onClick = { stop() }, enabled = !actionBusy && !rowControlBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Text("Stop", fontSize = 12.sp)
                                }
                            } else Spacer(Modifier.width(10.dp))
                        } else if (attentionNow) {
                            Icon(Icons.Default.PriorityHigh, null, tint = p.amber, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(selected?.attentionLabel() ?: "Needs you", Modifier.weight(1f, fill = false),
                                color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { humanInbox = true },
                                contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Inbox", fontSize = 12.sp) }
                        }
                        if (queued > 0) {
                            if (running || attentionNow) Box(Modifier.width(1.dp).height(16.dp).background(p.border))
                            TextButton(onClick = { sheet = "queue" },
                                contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text("$queued queued", fontSize = 12.sp)
                            }
                        }
                    }
                    if (!followingLatest && conversationHistory.isNotEmpty()) IconButton(onClick = {
                        followingLatest = true
                        scope.launch { scroll.animateScrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                    }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp)
                        .size(40.dp).background(p.raised, CircleShape).border(1.dp, p.border, CircleShape)) {
                        Icon(Icons.Default.ArrowDownward, "Jump to latest", tint = p.text, modifier = Modifier.size(18.dp))
                    }
                }
                // Composer: one row. Delivery mode lives behind the leading icon instead of chips + hint text.
                Column(Modifier.fillMaxWidth().background(p.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (addressMode) OutlinedTextField(address, { address = it.take(320) },
                        Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = true,
                        placeholder = { Text("Contact address (name@host)") }, shape = RoundedCornerShape(16.dp),
                        trailingIcon = { IconButton(onClick = { addressMode = false }) {
                            Icon(Icons.Default.Close, "Back to session") } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = p.text, unfocusedTextColor = p.text,
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.border))
                    if (!addressMode && !nativeThread && deliveryMode == "WaitForSlot") Row(
                        Modifier.padding(start = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Wait for slot · max working", color = p.muted, fontSize = 12.sp)
                        IconButton(onClick = { maxActiveWorkers = (maxActiveWorkers - 1).coerceAtLeast(1) },
                            modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Remove, "Fewer", tint = p.text) }
                        Text("$maxActiveWorkers", color = p.text, fontSize = 13.sp)
                        IconButton(onClick = { maxActiveWorkers = (maxActiveWorkers + 1).coerceAtMost(100) },
                            modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "More", tint = p.text) }
                    }
                    Row(verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val contactAllowed = active?.capabilities?.contains("SubmitToAgent") == true ||
                            (!online && lastProvider != null)
                        if (!nativeThread || contactAllowed) Box {
                            var modeMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { modeMenu = true }, modifier = Modifier.size(44.dp)) {
                                Icon(when {
                                    addressMode -> Icons.Default.AlternateEmail
                                    nativeThread -> Icons.Default.Schedule
                                    deliveryMode == "Steer" -> Icons.Default.Bolt
                                    deliveryMode == "WaitForSlot" -> Icons.Default.HourglassEmpty
                                    else -> Icons.Default.Schedule
                                }, "Delivery mode", tint = p.muted)
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                if (!nativeThread && selected != null) listOf(
                                    Triple("WhenIdle", "Queue", "Sends when the agent is idle"),
                                    Triple("Steer", "Steer now", "Immediate; may interrupt the reply"),
                                    Triple("WaitForSlot", "Wait for slot", "Waits for a free worker slot")
                                ).forEach { (value, label, help) ->
                                    DropdownMenuItem(
                                        text = { Column {
                                            Text((if (!addressMode && deliveryMode == value) "✓ " else "") + label)
                                            Text(help, color = p.muted, fontSize = 11.sp)
                                        } },
                                        enabled = !online || selected.modes.contains(value),
                                        onClick = { deliveryMode = value; addressMode = false; modeMenu = false })
                                }
                                if (contactAllowed) DropdownMenuItem(
                                    text = { Text(if (addressMode) "✓ Message a contact" else "Message a contact…") },
                                    onClick = { addressMode = !addressMode; modeMenu = false })
                            }
                        } else Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = if (loadedDraftFor == draftKey) message else "",
                            onValueChange = { text ->
                                if (loadedDraftFor == draftKey) {
                                    message = text.take(30_000)
                                    client.saveDraft(draftKey, message)
                                }
                            },
                            modifier = Modifier.weight(1f), minLines = 1, maxLines = 6,
                            placeholder = { Text(when {
                                !online -> "Offline · sends on reconnect"
                                addressMode -> "Message contact…"
                                selected != null && !sessionDeliveryReady -> deliveryHint
                                nativeThread -> "Message"
                                deliveryMode == "Steer" -> "Steer now…"
                                deliveryMode == "WaitForSlot" -> "Message · waits for a slot"
                                else -> "Message"
                            }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = p.raised, unfocusedContainerColor = p.raised,
                                focusedBorderColor = p.border, unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = p.text, unfocusedTextColor = p.text,
                                focusedPlaceholderColor = p.muted, unfocusedPlaceholderColor = p.muted))
                        IconButton(onClick = { submit() }, enabled = canSend,
                            modifier = Modifier.padding(bottom = 4.dp).size(46.dp).background(
                                if (canSend) p.accent else p.raised, CircleShape)) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Send message",
                                tint = if (canSend) p.bg else p.muted)
                        }
                    }
                }
            }
            // Keep transport diagnostics out of the main interface. Actionable
            // errors appear in the relevant workflow; details live under Providers.
        }
    }

    rowOptions?.let { target ->
        val remote = isT3Browser(target) && target.capabilities.contains("RemoteControl")
        // Native T3 threads get the desktop sidebar's menu: placement + Caffy.
        val native = target.kind == "t3/thread" && target.capabilities.contains("RemoteControl")
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
        // Match the list badge even while the slower detailed-controls RPC is unavailable.
        val currentSession = sessions.firstOrNull { it.key == target.key } ?: target
        fun caffyField(name: String): String? {
            val detail = caffy?.get(name)?.takeUnless { it.isJsonNull }?.let {
                runCatching { it.asString }.getOrNull()
            }
            // Native controls are authoritative after an action; a cached badge may lag.
            if (native && field("caffyAvailable") == "true") return detail
            if (name == "mode" && currentSession.caffyMode != null)
                return currentSession.caffyMode
            return detail ?: when (name) {
                "mode" -> currentSession.caffyMode
                "paused" -> currentSession.caffyPaused.toString().takeIf { currentSession.caffyMode != null }
                "onceOnly" -> currentSession.caffyOnceOnly.toString().takeIf { currentSession.caffyMode != null }
                "intervalMinutes" -> currentSession.caffyIntervalMinutes?.toString()
                "beginAt" -> currentSession.caffyBeginAt?.toString()
                else -> null
            }
        }
        var customDuration by remember(target.key) { mutableStateOf("60") }
        var customInterval by remember(target.key) { mutableStateOf("20") }
        var caffyRepeat by remember(target.key, caffyField("repeatPrompt")) {
            mutableStateOf(caffyField("repeatPrompt").orEmpty())
        }
        var caffyRebootRuns by remember(target.key) { mutableStateOf("5") }
        var caffyRebootMinutes by remember(target.key) { mutableStateOf("60") }
        var caffyRespectSlots by remember(target.key, caffyField("respectSlots")) {
            mutableStateOf(caffyField("respectSlots") == "true")
        }
        var editText by remember(target.key) { mutableStateOf("") }
        var editKind by remember(target.key) { mutableStateOf("") }
        var editBoard by remember(target.key) { mutableStateOf("") }
        var editTask by remember(target.key) { mutableStateOf("") }
        var editSeat by remember(target.key) { mutableStateOf("") }
        var editRole by remember(target.key) { mutableStateOf("personal") }
        var parentSearch by remember(target.key) { mutableStateOf("") }
        var showParentChoices by remember(target.key) { mutableStateOf(false) }
        var customSnoozeMinutes by remember(target.key) { mutableStateOf("90") }
        var rebootPrompt by remember(target.key) { mutableStateOf("") }
        var renameText by remember(target.key) { mutableStateOf(target.title) }
        var projectSettingsOpen by remember(target.key) { mutableStateOf(false) }
        var projectTitle by remember(target.key, field("projectTitle")) { mutableStateOf(field("projectTitle").orEmpty()) }
        var nativeAutoOpen by remember(target.key) { mutableStateOf(false) }
        var nativeAutoMode by remember(target.key) { mutableStateOf("idle") }
        var nativeAutoDuration by remember(target.key) { mutableStateOf("240") }
        var nativeAutoInterval by remember(target.key) { mutableStateOf("20") }
        var nativeAutoPrompt by remember(target.key) { mutableStateOf("") }
        var nativeAutoSlots by remember(target.key) { mutableStateOf(false) }
        var nativeAutoNow by remember(target.key) { mutableStateOf(false) }
        var nativeAutoDelay by remember(target.key) { mutableStateOf("") }
        fun nativePayload(command: String, options: Map<String, Any?> = emptyMap(), done: (com.google.gson.JsonObject) -> Unit) {
            if (rowControlBusy || !online) return
            rowControlBusy = true
            scope.launch {
                try { done(client.nativeControlData(target, command, options)) }
                catch (e: Exception) { rowControlError = e.message; note = e.message }
                finally { rowControlBusy = false }
            }
        }
        val pendingReboot = details?.get("pendingReboot")?.takeIf { it.isJsonObject }?.asJsonObject
        val desktopSnoozeChoices = details?.get("attentionSnoozePresets")
            ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { preset ->
                runCatching {
                    val item = preset.asJsonObject
                    val until = java.time.Instant.parse(item.get("snoozedUntil").asString).toEpochMilli()
                    if (until <= System.currentTimeMillis()) null else
                        (item.get("label").asString + " · " + item.get("whenLabel").asString) to until
                }.getOrNull()
            }.orEmpty()
        ModalBottomSheet(onDismissRequest = { rowOptions = null },
            containerColor = p.surface, contentColor = p.text,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)
                .navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetTitle(if (native) "T3 thread actions" else if (remote) "T3 session actions" else "Session visibility",
                    field("title") ?: target.title, p)
                if (rowControlBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                rowControlError?.let { error ->
                    Text(error, Modifier.fillMaxWidth().clickable(onClickLabel = "Copy error") {
                        clipboard.setText(AnnotatedString(error))
                    }, color = p.amber, style = MaterialTheme.typography.bodySmall)
                }
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
                if (native) {
                    if (details == null && rowControlError == null)
                        Text("Loading thread state…", color = p.muted, fontSize = 12.sp)
                    val settled = field("settled") == "true"
                    val snoozeUntil = field("snoozedUntil")?.toLongOrNull()?.takeIf { it > now }
                    if (target.capabilities.contains("Interrupt") && currentSession.isRunning())
                        SheetAction("Stop turn", p) {
                            scope.launch {
                                try { note = "Stop: " + client.sessionAction(target, "Interrupt") }
                                catch (e: Exception) { note = e.message }
                            }
                            rowOptions = null
                        }
                    if (!field("branch").isNullOrBlank()) SheetAction("New thread on " + field("branch"), p) {
                        nativePayload("new_on_branch", mapOf("requestId" to UUID.randomUUID().toString())) { data ->
                            val id = data.get("sessionId")?.asString
                            if (id != null) scope.launch {
                                val key = "t3:$id"
                                runCatching { client.refresh() }
                                if (client.sessions.value.any { it.key == key } || client.ensureSession(key)) {
                                    client.select(key); listMode = false; rowOptions = null
                                } else note = "Thread created; refresh the list to open it."
                            }
                        }
                    }
                    SheetAction(if (field("pinned") == "true") "Unpin thread" else "Pin thread", p) {
                        performRowControl(target, if (field("pinned") == "true") "unpin" else "pin")
                    }
                    SheetAction(if (settled) "Un-settle thread" else "Settle thread", p) {
                        performRowControl(target, if (settled) "unsettle" else "settle"); rowOptions = null
                    }
                    if (snoozeUntil != null) SheetAction("Wake thread · snoozed until " +
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(snoozeUntil)), p) {
                        performRowControl(target, "unsnooze")
                    } else {
                        Text("Snooze", color = p.muted, style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            desktopSnoozeChoices.forEach { (label, until) ->
                                FilterChip(false, onClick = {
                                    performRowControl(target, "snooze", mapOf("until" to until)); rowOptions = null
                                }, label = { Text(label) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(customSnoozeMinutes,
                                { customSnoozeMinutes = it.filter(Char::isDigit).take(6) },
                                Modifier.weight(1f), singleLine = true, label = { Text("Custom (minutes)") })
                            TextButton(onClick = {
                                val minutes = customSnoozeMinutes.toLongOrNull()
                                if (minutes == null || minutes !in 1..525_000)
                                    rowControlError = "Snooze must be 1–525000 minutes."
                                else { performRowControl(target, "snooze",
                                    mapOf("until" to System.currentTimeMillis() + minutes * MINUTE_MS)); rowOptions = null }
                            }) { Text("Snooze") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-settle", Modifier.weight(1f), color = p.text)
                        Switch(checked = field("autoSettleEnabled") != "false", enabled = details != null,
                            onCheckedChange = { performRowControl(target, "auto_settle", mapOf("value" to it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(renameText, { renameText = it.take(180) },
                            Modifier.weight(1f), singleLine = true, label = { Text("Thread title") })
                        TextButton(onClick = {
                            if (renameText.isBlank()) rowControlError = "Title can't be empty."
                            else performRowControl(target, "rename", mapOf("value" to renameText.trim()))
                        }) { Text("Rename") }
                    }
                    if (details != null && field("regeneratingTitle") != "true")
                        SheetAction("Regenerate title", p) { performRowControl(target, "regenerate_title") }
                    SheetAction("Mark unread", p) { performRowControl(target, "mark_unread") }
                    if (field("canShareGuest") == "true") SheetAction("Share with guest (create link)", p) {
                        nativeConfirmation = target to "share_guest"
                    }
                    SheetAction(if (projectFilter == target.projectName()) "Show all projects"
                        else "Filter by " + target.projectName(), p) {
                        projectFilter = if (projectFilter == target.projectName()) "" else target.projectName()
                        sessionFilter = "All"; listMode = true; rowOptions = null
                    }
                    if (field("canPromote") == "true" && !currentSession.isRunning())
                        SheetAction("Turn into project", p) { nativeConfirmation = target to "turn_into_project" }
                    Text("Copy", color = p.muted, style = MaterialTheme.typography.labelMedium)
                    SheetAction("Copy path", p) { copyValue("Path", field("workspace") ?: target.workspace) }
                    field("branch")?.takeIf { it.isNotBlank() }?.let { branch ->
                        SheetAction("Copy branch", p) { copyValue("Branch", branch) }
                    }
                    SheetAction("Copy thread", p) { copyValue("Thread", field("threadReference")) }
                    SheetAction("Copy thread ID", p) {
                        copyValue("Thread ID", java.net.URLDecoder.decode(target.id.removePrefix("thread:"), "UTF-8"))
                    }
                    if (field("projectId") != null) {
                        SheetAction("Project settings", p) { projectSettingsOpen = !projectSettingsOpen }
                        if (projectSettingsOpen) {
                            Text(field("projectWorkspace").orEmpty(), color = p.muted, fontSize = 12.sp)
                            OutlinedTextField(projectTitle, { projectTitle = it.take(180) },
                                Modifier.fillMaxWidth(), singleLine = true, label = { Text("Project title") })
                            TextButton(onClick = {
                                performRowControl(target, "rename_project", mapOf("value" to projectTitle.trim()))
                            }, enabled = projectTitle.isNotBlank() && !rowControlBusy) { Text("Save project title") }
                        }
                    }
                    if (!currentSession.isRunning()) SheetAction("Archive thread", p) {
                        nativeConfirmation = target to "archive"
                    }
                    SheetAction("Delete thread...", p, Color(0xFFE58B8B)) {
                        nativeConfirmation = target to "delete"
                    }
                    HorizontalDivider(color = p.border)
                    val caffyMode = caffyField("mode")
                    val caffyPaused = caffyField("paused") == "true"
                    Text("Caffy · " + (caffyMode ?: "Off") + (if (caffyPaused) " (paused)" else "") +
                        (caffyField("expiresAt")?.toLongOrNull()?.let { " · until " +
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: ""),
                        color = p.muted, style = MaterialTheme.typography.labelMedium)
                    if (details != null && field("caffyAvailable") != "true")
                        Text("Caffy needs the T3 desktop app running.", color = p.muted, fontSize = 12.sp)
                    else {
                        val quick = caffyMode == "idle" && !caffyPaused &&
                            caffyField("onceOnly") != "true" && caffyField("startup") != "true" &&
                            caffyField("respectSlots") != "true" && caffyField("beginAt").isNullOrBlank() &&
                            caffyField("repeatPrompt").isNullOrBlank() &&
                            caffyField("delayedPrompt").isNullOrBlank() &&
                            caffyField("autoRebootMinutes").isNullOrBlank() &&
                            caffyField("autoRebootEveryRuns").isNullOrBlank() &&
                            (caffyField("expiresAt")?.toLongOrNull()?.let { expiry ->
                                caffyField("createdAt")?.toLongOrNull()?.let { created ->
                                    expiry > now && kotlin.math.abs(expiry - created - 240 * MINUTE_MS) < 1000
                                }
                            } == true)
                        SheetAction(if (quick) "Stop 4h auto-continue" else "Keep working (4h)", p) {
                            if (quick) performRowControl(target, "caffy_stop")
                            else performRowControl(target, "caffy_set", mapOf("mode" to "idle",
                                "durationMinutes" to 240, "intervalMinutes" to 20))
                        }
                        SheetAction("Schedule prompt...", p) { openSchedule(target, idle = true) }
                        SheetAction("Auto-continue...", p) {
                            if (!nativeAutoOpen && caffyMode != null && caffyField("onceOnly") != "true") {
                                nativeAutoMode = if (caffyMode == "periodic") "periodic" else "idle"
                                nativeAutoPrompt = caffyField("repeatPrompt").orEmpty()
                                nativeAutoInterval = caffyField("intervalMinutes") ?: "20"
                                nativeAutoDuration = caffyField("expiresAt")?.toLongOrNull()?.let {
                                    ((it - now).coerceAtLeast(MINUTE_MS) / MINUTE_MS).toString()
                                }.orEmpty()
                                nativeAutoSlots = caffyField("respectSlots") == "true"
                                nativeAutoDelay = caffyField("beginAt")?.toLongOrNull()?.takeIf { it > now }
                                    ?.let { ((it - now + MINUTE_MS - 1) / MINUTE_MS).toString() }.orEmpty()
                                nativeAutoNow = false
                            }
                            nativeAutoOpen = !nativeAutoOpen
                        }
                        if (nativeAutoOpen) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("idle", "periodic").forEach { mode ->
                                    FilterChip(nativeAutoMode == mode, onClick = { nativeAutoMode = mode },
                                        label = { Text(if (mode == "idle") "On idle" else "Interval") })
                                }
                            }
                            OutlinedTextField(nativeAutoPrompt, { nativeAutoPrompt = it.take(8000) },
                                Modifier.fillMaxWidth(), minLines = 2, label = { Text("Repeat prompt (optional)") })
                            OutlinedTextField(nativeAutoDuration, { nativeAutoDuration = it.filter(Char::isDigit).take(6) },
                                Modifier.fillMaxWidth(), singleLine = true, label = { Text("Duration minutes (blank = unlimited)") })
                            OutlinedTextField(nativeAutoInterval, { nativeAutoInterval = it.filter(Char::isDigit).take(6) },
                                Modifier.fillMaxWidth(), singleLine = true, label = { Text("Interval minutes") })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Respect available slots", Modifier.weight(1f), color = p.text)
                                Switch(nativeAutoSlots, { nativeAutoSlots = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Wake now", Modifier.weight(1f), color = p.text)
                                Switch(nativeAutoNow, { nativeAutoNow = it; if (it) nativeAutoDelay = "" })
                            }
                            if (!nativeAutoNow) OutlinedTextField(nativeAutoDelay,
                                { nativeAutoDelay = it.filter(Char::isDigit).take(6) },
                                Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("Start in minutes (blank = on idle)") })
                            SheetAction("Apply auto-continue", p) {
                                val duration = nativeAutoDuration.toIntOrNull()
                                val interval = nativeAutoInterval.toIntOrNull()
                                val delayMinutes = nativeAutoDelay.toLongOrNull()
                                if ((nativeAutoDuration.isNotBlank() && (duration == null || duration !in 1..525600)) ||
                                    interval == null || interval !in 1..525600 ||
                                    (nativeAutoDelay.isNotBlank() && (delayMinutes == null || delayMinutes !in 1L..525600L)))
                                    rowControlError = "Enter valid positive minutes."
                                else performRowControl(target, "caffy_set", mapOf("mode" to nativeAutoMode,
                                    "durationMinutes" to duration, "intervalMinutes" to interval,
                                    "repeatPrompt" to nativeAutoPrompt, "respectSlots" to nativeAutoSlots,
                                    "beginAt" to delayMinutes?.let { System.currentTimeMillis() + it * MINUTE_MS },
                                    "wakeNow" to nativeAutoNow))
                            }
                        }
                        Text("Wake on idle", color = p.muted, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 2, 8).forEach { hours ->
                                FilterChip(false, onClick = {
                                    performRowControl(target, "caffy_set",
                                        mapOf("mode" to "idle", "durationMinutes" to hours * 60))
                                }, label = { Text("${hours}h") })
                            }
                            FilterChip(false, onClick = {
                                performRowControl(target, "caffy_set", mapOf("mode" to "idle"))
                            }, label = { Text("Infinite") })
                            FilterChip(false, onClick = {
                                performRowControl(target, "caffy_set", mapOf("mode" to "idle", "onceOnly" to true))
                            }, label = { Text("Once") })
                        }
                        Text("Periodic wake · every", color = p.muted, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 20, 30, 60).forEach { minutes ->
                                FilterChip(false, onClick = {
                                    performRowControl(target, "caffy_set",
                                        mapOf("mode" to "periodic", "intervalMinutes" to minutes))
                                }, label = { Text("${minutes}m") })
                            }
                        }
                        if (caffyMode != null) SheetAction(if (caffyPaused) "Resume Caffy" else "Pause Caffy", p) {
                            performRowControl(target, if (caffyPaused) "caffy_resume" else "caffy_pause")
                        }
                        SheetAction(if (caffyField("startup") == "true") "Don't resume on T3 startup"
                            else "Resume when T3 starts", p) {
                            if (caffyMode == null) performRowControl(target, "caffy_set",
                                mapOf("mode" to "startup", "startup" to true))
                            else performRowControl(target, "caffy_startup",
                                mapOf("value" to (caffyField("startup") != "true")))
                        }
                        SheetAction("Wake now", p) { performRowControl(target, "caffy_wake") }
                        if (caffyMode != null) SheetAction("Stop Caffy", p, Color(0xFFE58B8B)) {
                            performRowControl(target, "caffy_stop")
                        }
                    }
                }
                if (!native && (!remote || rowActionPage == "Session")) {
                    if (!remote && target.capabilities.contains("Interrupt"))
                        SheetAction("Stop turn", p) { operation("Interrupt"); rowOptions = null }
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
                        SheetAction("Open in ChatGPT app", p) {
                            rowOptions = null
                            openChatGpt(target)
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
                        Text("Agent reboot · same T3 slot", color = p.muted,
                            style = MaterialTheme.typography.labelMedium)
                        if (pendingReboot != null) {
                            Text("Unresolved reboot: " +
                                (pendingReboot.get("status")?.asString ?: "unknown") +
                                " · " + (pendingReboot.get("operationId")?.asString ?: ""),
                                color = p.amber, fontSize = 12.sp)
                            SheetAction("Reconcile existing reboot (do not start another)", p) {
                                performRowControl(target, "reboot_reconcile")
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(false, onClick = {
                                    performRowControl(target, "reboot", mapOf("contextMode" to "handoff"))
                                }, label = { Text("Keep context") })
                                FilterChip(false, onClick = {
                                    performRowControl(target, "reboot", mapOf("contextMode" to "fresh"))
                                }, label = { Text("Fresh start") })
                            }
                            OutlinedTextField(rebootPrompt, { rebootPrompt = it.take(8_000) },
                                Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                                label = { Text("Custom handover instruction (optional, max 8000 chars)") })
                            SheetAction("Reboot with custom handover", p) {
                                if (rebootPrompt.isBlank()) rowControlError = "Enter the custom handover instruction first."
                                else performRowControl(target, "reboot",
                                    mapOf("contextMode" to "handoff", "prompt" to rebootPrompt))
                            }
                        }
                        SheetAction("Restore ChatGPT URL", p) { performRowControl(target, "restore_url") }
                        val returnMarker = field("returnMarker")
                        SheetAction(
                            if (returnMarker == null) "Return after next reply (Ctrl+D)"
                            else if (returnMarker == "ready") "Clear ready return marker"
                            else "Remove pending return marker", p
                        ) { performRowControl(target, "return_marker") }
                        if (returnMarker != null) Text(
                            if (returnMarker == "ready" && !target.isRunning())
                                "Reply finished · return marker ready"
                            else "Armed · yellow only after the next reply finishes",
                            color = p.muted, fontSize = 12.sp
                        )
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
                        Text("Snooze · desktop presets", color = p.muted,
                            style = MaterialTheme.typography.labelMedium)
                        if (remote && desktopSnoozeChoices.isEmpty())
                            Text("Desktop snooze presets unavailable. Use custom minutes or update T3.",
                                color = p.muted, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (remote) desktopSnoozeChoices.forEach { (label, until) ->
                                FilterChip(selected = false, onClick = {
                                    setSnooze(target, until); rowOptions = null
                                }, label = { Text(label) })
                            } else listOf(1, 2, 4, 8, 24, 72, 168).forEach { hours ->
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
                    val activeMode = caffyField("mode")?.takeIf { it.isNotBlank() }
                    val paused = caffyField("paused") == "true"
                    val statusUnknown = activeMode == null &&
                        (details == null || field("caffyAvailable") == "false")
                    SheetTitle("Caffy · ${activeMode ?: if (statusUnknown) "Status unavailable" else "Off"}" +
                        (if (paused) " (paused)" else ""), "T3-owned wake control", p)
                    if (currentSession.caffyMode != null &&
                        caffy?.get("mode")?.takeUnless { it.isJsonNull }?.asString != currentSession.caffyMode)
                        Text("Caffy is enabled in the session list; detailed status is still syncing.",
                            color = p.amber, fontSize = 12.sp)
                    // PC-owned live scheduler state, never inferred from local timers.
                    fun at(name: String): String = caffyField(name)?.toLongOrNull()?.let {
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
                    } ?: "—"
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.raised)
                        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val next = when {
                            paused -> "Paused by user"
                            caffyField("pendingMessageId") != null -> "Delivery pending"
                            caffyField("beginAt")?.toLongOrNull()?.let { it > System.currentTimeMillis() } == true ->
                                "Delayed start · " + at("beginAt")
                            activeMode == "periodic" -> at("nextDueAt")
                            activeMode == "startup" -> "Next T3 startup"
                            activeMode == "idle" -> "Next verified idle transition"
                            else -> "Not scheduled"
                        }
                        Text("Next wake: $next", color = p.text, fontSize = 12.sp)
                        Text("Last wake: " + at("lastWakeAt") +
                            "   ·   Last check: " + at("lastCheckedAt"), color = p.muted, fontSize = 12.sp)
                        Text("Next scheduler check: " + at("nextCheckAt"), color = p.muted, fontSize = 12.sp)
                        Text("Expires: " + at("expiresAt") +
                            " · Every: " + (caffyField("intervalMinutes") ?: "—") + " min",
                            color = p.muted, fontSize = 12.sp)
                        listOf("lastCheckResult" to "Check", "wakePhase" to "Phase",
                            "transitionVersion" to "State revision", "pendingWake" to "Wake requested",
                            "deliveryAwaitingGuest" to "Awaiting verified guest",
                            "availability" to "Guest", "admissionReason" to "Admission",
                            "deliveryStatus" to "Delivery", "lastDeliveryResult" to "Receipt",
                            "pendingMessageId" to "Pending message ID",
                            "rebootOperationId" to "Reboot receipt",
                            "lastError" to "Error", "pausedInitiator" to "Pause reason",
                            "stopInitiator" to "Stop reason",
                            "autoRebootMinutes" to "Reboot every minutes",
                            "autoRebootEveryRuns" to "Reboot every runs",
                            "wakesSinceReboot" to "Runs since reboot",
                            "repeatPrompt" to "Repeated prompt",
                            "delayedPrompt" to "Delayed prompt").forEach { (fieldName, label) ->
                            caffyField(fieldName)?.takeIf { it.isNotBlank() }?.let { value ->
                                Text("$label: $value", color = if (fieldName == "lastError") p.amber
                                    else p.muted, fontSize = 12.sp)
                            }
                        }
                        Text("Capacity policy: " + when (caffyField("respectSlots")) {
                            "true" -> "Wait for a free T3 slot"
                            "false" -> "Independent of busy slots"
                            else -> "Not available"
                        }, color = p.muted, fontSize = 12.sp)
                        val caffyEvents = rowControls?.get("caffy")?.takeIf { it.isJsonObject }
                            ?.asJsonObject?.get("recentEvents")?.takeIf { it.isJsonArray }?.asJsonArray
                        caffyEvents?.toList()?.takeLast(5)?.forEach { event ->
                            if (event.isJsonObject) {
                                val e = event.asJsonObject
                                Text((e.get("at")?.asString ?: "") + " · " +
                                    (e.get("event")?.asString ?: "event") +
                                    " · " + (e.get("cause")?.asString ?: ""),
                                    color = p.muted, fontSize = 11.sp)
                            }
                        }
                    }
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
                            performRowControl(target, "caffy_set",
                                mapOf("mode" to "idle", "respectSlots" to true))
                        }, label = { Text("When idle · wait for slot") })
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
                    Text("Advanced auto-continue", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    if (paused) Text("Resume the existing schedule before editing its settings. Paused Caffy will not be restarted automatically.",
                        color = p.amber, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Respect Keep T3 Busy slots", Modifier.weight(1f), color = p.text)
                        Switch(checked = caffyRespectSlots, enabled = activeMode != null,
                            onCheckedChange = { desired ->
                                caffyRespectSlots = desired
                                performRowControl(target, "caffy_configure", mapOf("respectSlots" to desired))
                            })
                    }
                    OutlinedTextField(caffyRepeat, { caffyRepeat = it.take(29_800) },
                        Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                        label = { Text("Prompt repeated on every continuation") })
                    SheetAction("Save repeated prompt", p) {
                        if (activeMode == null)
                            rowControlError = "Start Caffy before modifying the repeated prompt."
                        else performRowControl(target, "caffy_configure", mapOf("repeatPrompt" to caffyRepeat))
                    }
                    OutlinedTextField(caffyRebootRuns, {
                        caffyRebootRuns = it.filter(Char::isDigit).take(5)
                    }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Auto-reboot after N completed runs") })
                    SheetAction("Set run-based auto-reboot", p) {
                        val runs = caffyRebootRuns.toIntOrNull()
                        if (runs == null || runs !in 1..10000)
                            rowControlError = "Run count must be 1–10000."
                        else performRowControl(target, "caffy_reboot", mapOf("runs" to runs))
                    }
                    SheetAction("Disable auto-reboot", p) {
                        performRowControl(target, "caffy_reboot", mapOf("value" to null))
                    }
                    HorizontalDivider(color = p.border)
                    Text("Begin / resume work", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 120, 240).forEach { minutes ->
                            FilterChip(false, onClick = {
                                openSchedule(target, System.currentTimeMillis() + minutes * MINUTE_MS)
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
                                openSchedule(target, calendar.timeInMillis)
                            }, label = { Text("Tomorrow ${hour}:00") })
                        }
                    }
                    SheetAction("Begin on idle · custom prompt", p) {
                        openSchedule(target, idle = true)
                    }
                    SheetAction("Schedule custom prompt and time…", p) {
                        openSchedule(target)
                    }
                    SheetAction("Cancel delayed start", p) {
                        performRowControl(target, "caffy_cancel_begin")
                    }
                    HorizontalDivider(color = p.border)
                    if (activeMode != null) {
                    Text("Auto-reboot after time", color = p.muted,
                        style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(caffyRebootMinutes, {
                        caffyRebootMinutes = it.filter(Char::isDigit).take(6)
                    }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Custom reboot interval (minutes)") })
                    SheetAction("Set custom time-based auto-reboot", p) {
                        val minutes = caffyRebootMinutes.toIntOrNull()
                        if (minutes == null || minutes !in 1..525600)
                            rowControlError = "Reboot interval must be 1–525600 minutes."
                        else performRowControl(target, "caffy_reboot", mapOf("value" to minutes))
                    }
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
                            FilterChip(selected = field("borderColor") == color.takeUnless { it == "none" }, onClick = {
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
    nativeConfirmation?.let { (target, command) ->
        AlertDialog(onDismissRequest = { nativeConfirmation = null },
            title = { Text(when (command) {
                "delete" -> "Delete thread permanently?"
                "archive" -> "Archive thread?"
                "share_guest" -> "Create a guest link?"
                else -> "Turn thread into project?"
            }) },
            text = { Text(when (command) {
                "delete" -> "This permanently clears the conversation history for " + target.title + "."
                "archive" -> target.title + " will leave the active list."
                "share_guest" -> "Anyone with the guest link can access the shared conversation. The link will be copied to your clipboard."
                else -> "Make the thread's existing folder into a named T3 project."
            }) },
            confirmButton = { TextButton(enabled = !rowControlBusy, onClick = {
                nativeConfirmation = null
                if (command == "share_guest") {
                    rowControlBusy = true
                    scope.launch {
                        try {
                            val url = client.nativeControlData(target, command).get("url")?.asString
                                ?: error("No guest link returned")
                            clipboard.setText(AnnotatedString(url)); note = "Guest link copied"
                        } catch (e: Exception) { note = e.message; rowControlError = e.message }
                        finally { rowControlBusy = false }
                    }
                } else performRowControl(target, command, mapOf("confirmed" to true))
            }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { nativeConfirmation = null }) { Text("Cancel") } })
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
    // New thread, T3-mobile style: prompt first, compact option chips under it, one Start button.
    // The sheet stays open until the provider confirms a session, so failures show here instead of vanishing.
    if (create && active != null) ModalBottomSheet(
        onDismissRequest = { if (!creationBusy) create = false },
        containerColor = p.surface, contentColor = p.text,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val native = kind == "t3/thread"
        val canCreate = !creationBusy && kind.isNotBlank() &&
            (active.id == "t3" || (workspace.isNotBlank() && firstMessage.isNotBlank())) &&
            (!native || (nativeCatalog?.nativeModelSelectionReady == true &&
                chosenNativeProject != null && chosenNativeModel != null &&
                (chosenNativeModel.efforts.isEmpty() ||
                 chosenNativeModel.efforts.any { it.first == nativeEffort })))
        fun start() {
            if (!canCreate) return
            val project = if (native) nativeProjectId else workspace
            val title = firstMessage.lineSequence().firstOrNull()?.trim()?.take(120)
                ?.ifBlank { "New AI session" } ?: "New AI session"
            // Reconcile an unresolved creation with the SAME underlying request;
            // do not present an extra phantom card when the user retries it.
            val existing = creationPreviews.firstOrNull {
                it.sessionKey == null && it.providerId == active.id &&
                    it.kind == kind && it.project == project &&
                    it.instruction == firstMessage
            }
            val prediction = SessionCreatePreview(existing?.requestId ?: UUID.randomUUID().toString(),
                title, active.id, kind, project, firstMessage, "Starting on PC…")
            try { putCreatePreview(prediction) }
            catch (failure: Exception) { createError = failure.message; return }
            creationBusy = true
            createError = null
            create = false
            listMode = true
            threadKind = kind
            sessionFilter = "All"
            projectFilter = ""
            sessionSearch = ""
            scope.launch {
                try {
                    val config: Map<String, Any?>? = if (native) chosenNativeModel?.let { model ->
                        val options = if (model.effortId != null && nativeEffort.isNotEmpty())
                            listOf(mapOf("id" to model.effortId, "value" to nativeEffort))
                        else emptyList()
                        mapOf("instanceId" to model.instanceId, "model" to model.slug, "options" to options)
                    } else null
                    if (native) viewPrefs.edit().putString("lastNativeProject", nativeProjectId)
                        .putString("lastNativeModel", nativeModelKey).putString("lastNativeEffort", nativeEffort).apply()
                    val result = client.createSession(active, kind, project, firstMessage,
                        createDeliveryMode, maxActiveWorkers, "", config)
                    val newKey = client.createdSessionKey.value
                    if (newKey == null) {
                        val rejected = result.contains("rejected", true) || result.contains("failed", true)
                        putCreatePreview(prediction.copy(status =
                            (if (rejected) "Failed · " else "Unconfirmed · ") + result.take(160)))
                        return@launch
                    }
                    putCreatePreview(prediction.copy(status = "Created on PC · loading session…",
                        sessionKey = newKey))
                    // The ID is confirmed. Only READ the catalog; never repeat create.
                    for (attempt in 0 until 4) {
                        if (client.sessions.value.any { it.key == newKey } || client.ensureSession(newKey)) break
                        delay(400L * (attempt + 1))
                    }
                    if (client.sessions.value.any { it.key == newKey }) {
                        viewPrefs.edit().putString("threadKind", kind).apply()
                        listMode = false
                        client.select(newKey)
                    } else note = "Session created; the local card remains until the desktop publishes it."
                } catch (e: Exception) {
                    putCreatePreview(prediction.copy(status = "Unconfirmed · " +
                        (e.message ?: "Check the original request before retrying").take(160)))
                } finally { creationBusy = false }
            }
        }
        // All creation paths live under +. Switching tabs retains the draft in each path.
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("New", color = p.text, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            val tabs = if (active.id == "t3")
                listOf("t3/chatgpt" to "ChatGPT", "t3/thread" to "Native", "prepfile" to "Prepfile")
                    .filter { it.first == "prepfile" || it.first in active.kinds }
            else active.kinds.map { it to it }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(10.dp)).background(p.raised).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                tabs.forEach { (value, label) ->
                    val selectedTab = createTab == value
                    Text(label, Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (selectedTab) p.accentBg else Color.Transparent)
                        .clickable(enabled = !creationBusy && !prepfileBusy) {
                            if (value == "prepfile") openPrepfile()
                            else { createTab = value; kind = value; createError = null }
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (selectedTab) p.accent else p.muted, fontSize = 13.sp,
                        fontWeight = if (selectedTab) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (createTab == "prepfile") {
            prepfileForm()
        } else {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(firstMessage, { firstMessage = it.take(30_000) },
                Modifier.fillMaxWidth(), minLines = 4, maxLines = 10, enabled = !creationBusy,
                placeholder = { Text(if (active.id == "t3") "What should it work on? (optional)" else "First instruction") },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = p.raised, unfocusedContainerColor = p.raised,
                    focusedBorderColor = p.border, unfocusedBorderColor = Color.Transparent))
            if (native) {
                if (nativeCatalogLoading && nativeCatalog == null)
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        PickerChip(Icons.Default.Folder, chosenNativeProject?.let { projectLabel(it, nativeCatalog!!.projects) }
                            ?: "Project", p, enabled = nativeCatalog != null) { projectMenu = true }
                        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                            nativeCatalog?.projects?.forEach { project ->
                                // Path under the name, as the desktop lists projects; tells same-named ones apart.
                                DropdownMenuItem(text = { Column {
                                    Text(project.title)
                                    if (project.workspaceRoot.isNotBlank()) Text(project.workspaceRoot,
                                        color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } }, onClick = {
                                    nativeProjectId = project.id; projectMenu = false
                                    val preferred = nativeCatalog?.models?.firstOrNull {
                                        it.instanceId == project.defaultInstanceId && it.slug == project.defaultModel
                                    }
                                    if (preferred != null) nativeModelKey = preferred.key
                                    val model = nativeCatalog?.models?.firstOrNull { it.key == nativeModelKey }
                                    nativeEffort = project.defaultEffort?.takeIf { choice ->
                                        model?.efforts?.any { it.first == choice } == true
                                    } ?: model?.efforts?.firstOrNull()?.first.orEmpty()
                                })
                            }
                            HorizontalDivider(color = p.border)
                            DropdownMenuItem(text = { Text("New project…") }, onClick = {
                                projectMenu = false; addProjectName = ""; addProjectOpen = true
                            })
                        }
                    }
                    Box {
                        PickerChip(Icons.Default.AutoAwesome, chosenNativeModel?.label ?: "Model", p,
                            enabled = nativeCatalog?.models?.isNotEmpty() == true) { modelMenu = true }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            // Favorites first (T3 desktop's shared list); the star toggles it on the desktop too.
                            nativeCatalog?.models?.forEach { model ->
                                DropdownMenuItem(text = { Text(model.provider + " · " + model.label) },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val catalog = nativeCatalog ?: return@IconButton
                                            val on = !model.favorite
                                            nativeCatalog = catalog.copy(models = catalog.models.map {
                                                if (it.key == model.key) it.copy(favorite = on) else it })
                                            scope.launch {
                                                try { client.setModelFavorite(model, on); applyCatalog(client.nativeCatalog()) }
                                                catch (e: Exception) {
                                                    nativeCatalog = catalog
                                                    nativeCatalogError = "Favorite not saved: " + e.message
                                                }
                                            }
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(if (model.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                                if (model.favorite) "Unfavorite" else "Favorite",
                                                tint = if (model.favorite) p.amber else p.muted, modifier = Modifier.size(18.dp))
                                        }
                                    }, onClick = {
                                    nativeModelKey = model.key
                                    nativeEffort = model.efforts.firstOrNull { it.first == "medium" }?.first
                                        ?: model.efforts.firstOrNull()?.first.orEmpty()
                                    modelMenu = false
                                })
                            }
                        }
                    }
                    if (chosenNativeModel?.efforts?.isNotEmpty() == true) Box {
                        PickerChip(Icons.Default.Psychology, chosenNativeModel.efforts
                            .firstOrNull { it.first == nativeEffort }?.second ?: "Effort", p) { effortMenu = true }
                        DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                            chosenNativeModel.efforts.forEach { effort ->
                                DropdownMenuItem(text = { Text(effort.second) }, onClick = {
                                    nativeEffort = effort.first; effortMenu = false
                                })
                            }
                        }
                    }
                }
                if (nativeCatalogError != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nativeCatalogError!!, Modifier.weight(1f), color = p.amber, fontSize = 12.sp)
                    TextButton(onClick = {
                        scope.launch {
                            nativeCatalogLoading = true
                            try { nativeCatalog = client.nativeCatalog(); nativeCatalogError = null }
                            catch (e: Exception) { nativeCatalogError = e.message }
                            finally { nativeCatalogLoading = false }
                        }
                    }) { Text("Retry") }
                }
                if (nativeCatalog != null && nativeCatalog?.nativeModelSelectionReady != true)
                    Text("T3 needs an update before it can create native threads from the phone.",
                        color = p.amber, fontSize = 12.sp)
            } else {
                OutlinedTextField(workspace, { workspace = it.take(4096) }, Modifier.fillMaxWidth(),
                    singleLine = true, enabled = !creationBusy, shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp)) },
                    placeholder = { Text(if (active.id == "t3") "Project (optional)" else "Working folder") })
                if (active.id == "t3") Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = createDeliveryMode == "WhenIdle",
                        onClick = { createDeliveryMode = "WhenIdle" }, label = { Text("Start now") })
                    FilterChip(selected = createDeliveryMode == "WaitForSlot",
                        onClick = { createDeliveryMode = "WaitForSlot" }, label = { Text("Wait for slot") })
                    if (createDeliveryMode == "WaitForSlot") {
                        IconButton(onClick = { maxActiveWorkers = (maxActiveWorkers - 1).coerceAtLeast(1) },
                            modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Remove, "Fewer slots") }
                        Text("max $maxActiveWorkers", fontSize = 13.sp)
                        IconButton(onClick = { maxActiveWorkers = (maxActiveWorkers + 1).coerceAtMost(100) },
                            modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "More slots") }
                    }
                }
            }
            if (createError != null) Text(createError!!, color = p.amber, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { start() }, enabled = canCreate, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)) {
                if (creationBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.bg)
                    Spacer(Modifier.width(8.dp)); Text("Starting…")
                } else Text(if (firstMessage.isBlank()) "Create empty thread" else "Start")
            }
        }
        }
    }
    if (addProjectOpen) AlertDialog(
        onDismissRequest = { if (!addProjectBusy) addProjectOpen = false },
        title = { Text("New T3 project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(addProjectName, { addProjectName = it.take(80) },
                    label = { Text("Project name") }, singleLine = true)
                Text("Creates a real named project in T3, not a phone-only folder.",
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        },
        confirmButton = {
            TextButton(enabled = !addProjectBusy && addProjectName.isNotBlank(),
                onClick = {
                    addProjectBusy = true
                    scope.launch {
                        try {
                            val project = client.createNativeProject(addProjectName)
                            // Refresh the authoritative project list, including its new ID.
                            nativeCatalog = client.nativeCatalog()
                            nativeProjectId = project.id
                            addProjectOpen = false
                            note = "T3 project created"
                        } catch (e: Exception) {
                            nativeCatalogError = "Project creation unconfirmed: " + e.message +
                                ". Verify the project list before trying again."
                        } finally { addProjectBusy = false }
                    }
                }) { Text("Create project") }
        },
        dismissButton = { TextButton(enabled = !addProjectBusy,
            onClick = { addProjectOpen = false }) { Text("Cancel") } }
    )
    if (showT3Usage) {
        T3QuotaPacingSheet(activeHubBaseUrl, mainViewModel.appConfig.value.apiKey) {
            showT3Usage = false
        }
    }
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

/**
 * One timeline entry, T3-mobile style: your messages are right-aligned bubbles,
 * agent replies are plain full-width text, tool/progress chatter is a one-line
 * collapsible work-log row.
 */
@Composable private fun MessageRow(item: AgentHistoryUi, p: AiPalette) {
    val kind = item.kind.lowercase()
    val outgoing = kind.contains("user") || kind.contains("human") || kind.contains("request")
    val technical = kind.contains("tool") || kind.contains("command") || kind.contains("progress") ||
        kind.contains("event") || kind.contains("notice")
    var expanded by remember(item.id) { mutableStateOf(false) }
    if (technical) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.streaming) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = p.accent)
                else Icon(Icons.Default.Terminal, null, tint = p.muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
                    ?: item.kind, Modifier.weight(1f), color = p.muted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Expand", tint = p.muted, modifier = Modifier.size(16.dp))
            }
            if (expanded) SelectionContainer {
                Text(item.text, color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        .background(p.raised, RoundedCornerShape(8.dp)).padding(10.dp))
            }
        }
    } else if (outgoing) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(p.accentBg).padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer {
                    Text(item.text.ifBlank { "(empty)" }, color = p.text,
                        style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            // Only label unusual speakers; plain assistant replies need no header.
            if (!kind.contains("assistant") && !kind.contains("agent") && kind.isNotBlank())
                Text(item.kind, color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
            SelectionContainer {
                Text(remember(item.text, item.streaming, item.partial, p) { aiMarkdown(
                    item.text.ifBlank { when {
                        item.streaming -> ""
                        item.partial -> "Content not available in this partial transcript."
                        else -> "(empty)"
                    } }, p
                ) }, color = p.text, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
            }
            if (item.streaming) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = p.accent)
                Spacer(Modifier.width(6.dp))
                Text(if (item.text.isBlank()) "thinking…" else "writing…", color = p.accent, fontSize = 11.sp)
            } else if (item.partial) Text("partial transcript", color = p.muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** A message sent from this phone that the provider hasn't shown in history yet. */
@Composable private fun LocalOutgoingRow(item: PendingAgentUi, p: AiPalette, onOpenOutbox: () -> Unit) {
    val failed = item.state == "Failed" || item.state == "Unknown"
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpenOutbox), horizontalAlignment = Alignment.End) {
        Box(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
            .background(p.accentBg.copy(alpha = 0.55f))
            .border(1.dp, if (failed) Color(0xFFE38D8D) else Color.Transparent,
                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(item.text, color = p.text, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
        }
        Text(when (item.state) {
            "Pending" -> "Waiting to send"
            "InFlight" -> "Sending…"
            "Accepted", "Queued" -> "Queued"
            "Blocked" -> "Blocked · tap for details"
            "Unknown" -> "Unconfirmed · check before resending"
            "Failed" -> "Failed" + (item.error?.let { " · $it" } ?: "")
            else -> item.state
        }, color = if (failed) Color(0xFFE38D8D) else p.muted, fontSize = 11.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp, end = 4.dp))
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
