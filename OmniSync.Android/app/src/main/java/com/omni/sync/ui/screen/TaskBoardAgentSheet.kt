package com.omni.sync.ui.screen

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.AgentSessionUi
import com.omni.sync.data.repository.GenericAgentClient
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.data.taskboard.TaskBoardTask
import com.omni.sync.data.taskboard.TaskBoardPhoneClient
import com.omni.sync.utils.AndroidAttentionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID

/** Delegates to Android AI's existing authenticated T3 provider; never spawns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskBoardAgentSheet(
    task: TaskBoardTask, root: File, phone: TaskBoardPhoneClient,
    signalR: SignalRClient, onOpenAgent: (String) -> Unit, onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(signalR) { GenericAgentClient(context, signalR) }
    val uuid = remember(task.claimedBy) {
        val claim = task.claimedBy.orEmpty()
        val raw = claim.removePrefix("t3-browser:").removePrefix("t3:")
        raw.takeIf { Regex("(?i)^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$").matches(it) }
    }
    val agent = remember(uuid) {
        uuid?.let { AgentSessionUi("t3", it, task.title, "t3/chatgpt",
            "Unknown", "Unknown", null, emptyList()) }
    }
    val boardPath = remember(root, phone) {
        "B:\\GDrive\\SharedWithPhone\\Obsidian\\jjj\\" + phone.boardIdentity(root).replace("/", "\\")
    }
    var details by remember(uuid) { mutableStateOf<JsonObject?>(null) }
    var liveActivity by remember(uuid) { mutableStateOf<String?>(null) }
    var error by remember(uuid) { mutableStateOf<String?>(null) }
    var notice by remember(uuid) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf("10") }
    var duration by remember { mutableStateOf("") }
    var rebootTime by remember { mutableStateOf("60") }
    var rebootRuns by remember { mutableStateOf("5") }
    var beginMinutes by remember { mutableStateOf("30") }
    var beginPrompt by remember { mutableStateOf("") }
    var prepFocus by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("medium") }
    val prefs = remember { context.getSharedPreferences("taskboard-agent-prepfile-v1", Context.MODE_PRIVATE) }
    val prepKey = remember(root, task.id) { root.absolutePath + ":" + task.id }
    var pending by remember(prepKey) { mutableStateOf(prefs.getString(prepKey, null)) }

    fun refresh() {
        val target = agent ?: return
        scope.launch {
            try {
                val current = client.sessionControls(target)
                val ids = listOf("id", "browserId").mapNotNull { key ->
                    current.get(key)?.takeUnless { it.isJsonNull }?.asString
                }
                check(uuid in ids) { "T3 returned a different agent; no controls were enabled." }
                val ref = current.get("binding")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("taskRef")?.takeIf { it.isJsonObject }?.asJsonObject
                if (ref != null) {
                    val boundId = ref.get("id")?.takeUnless { it.isJsonNull }?.asString
                    val boundBoard = ref.get("board")?.takeUnless { it.isJsonNull }?.asString
                    check(boundId == task.id && boundBoard?.replace('/', '\\')
                        ?.equals(boardPath, ignoreCase = true) == true) {
                        "This T3 session belongs to another task; controls are disabled."
                    }
                }
                details = current
                client.refresh()
                liveActivity = client.sessions.value.firstOrNull { it.provider == "t3" && it.id == target.id }
                    ?.let { it.activity + " · " + it.reachability +
                        if (it.attention != "None") " · " + it.attention else "" }
                error = null
                val rows = details?.get("attentions")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let(AndroidAttentionStore::fromStatus) }
                if (rows != null) AndroidAttentionStore.reconcile(context, rows)
            } catch (failure: Exception) {
                details = null
                liveActivity = null
                error = "T3 status unavailable: " + failure.message
            }
        }
    }
    LaunchedEffect(uuid) {
        if (agent != null) {
            refresh()
            while (true) { delay(15_000); refresh() }
        }
    }
    fun control(command: String, options: Map<String, Any?> = emptyMap(), after: (() -> Unit)? = null) {
        val target = agent ?: return
        if (details == null) {
            error = "This task's exact T3 session must be verified before sending commands."
            return
        }
        if (busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val result = client.sessionAction(target, "Control", mapOf(
                    "command" to command, "options" to options))
                notice = command + ": " + result
                after?.invoke()
                refresh()
            } catch (failure: Exception) {
                error = command + " unconfirmed: " + failure.message + ". Refresh before repeating."
            } finally { busy = false }
        }
    }
    fun positive(input: String, min: Int, max: Int): Int? =
        input.toIntOrNull()?.takeIf { input.all(Char::isDigit) && it in min..max }
    fun setMode(mode: String) {
        val mins = duration.takeIf(String::isNotBlank)?.let { positive(it,1,10_080) }
        val every = positive(interval,1,1_440)
        if ((duration.isNotBlank() && mins == null) || (mode == "periodic" && every == null)) {
            error = "Duration 1–10080 minutes or blank; interval 1–1440 minutes."; return
        }
        control("caffy_set", mapOf("mode" to mode, "durationMinutes" to mins,
            "intervalMinutes" to (if (mode == "periodic") every else null),
            "startup" to (mode == "startup")))
    }
    val caffy = details?.get("caffy")?.takeIf { it.isJsonObject }?.asJsonObject
    fun field(name: String): String? = caffy?.get(name)
        ?.takeUnless { it.isJsonNull }?.asString
    val linkedIds = setOfNotNull(uuid, details?.get("id")?.takeUnless { it.isJsonNull }?.asString,
        details?.get("browserId")?.takeUnless { it.isJsonNull }?.asString)
    val attentions = details?.get("attentions")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject
            ?.let(AndroidAttentionStore::fromStatus) }
        ?.filter { it.sessionId in linkedIds && it.status in setOf("waiting","pending","snoozed") }
        .orEmpty()
    val jobs = details?.get("prepfileJobs")?.takeIf { it.isJsonArray }?.asJsonArray
    val job = jobs?.firstOrNull {
        it.isJsonObject && it.asJsonObject.get("id")?.asString == pending
    }?.asJsonObject

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)
            .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("T3 agent · " + task.id, style = MaterialTheme.typography.titleLarge)
            if (agent == null) {
                Text("No linked T3 session. No unrelated session will be created.")
            } else {
                Text((details?.get("title")?.takeUnless { it.isJsonNull }?.asString
                    ?: task.title) + " · " + (details?.get("activity")?.takeUnless { it.isJsonNull }
                    ?.asString ?: "Agent") + " · " + (liveActivity ?: "Activity not verified"),
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = details != null, onClick = {
                        val exactId = details?.get("id")?.takeUnless { it.isJsonNull }?.asString ?: uuid!!
                        onOpenAgent(exactId); onDismiss()
                    }) { Text("Open exact agent") }
                    OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
                }
                if (details == null) {
                    Text("Agent controls are unavailable until T3 verifies this task's linked session. No unrelated session will be started.")
                } else {
                HorizontalDivider()
                Text("Caffy scheduler", style = MaterialTheme.typography.titleMedium)
                Text(if (caffy == null) "No active scheduler / status unavailable"
                    else (field("mode") ?: "active") +
                        (if (field("paused") == "true") " · paused" else " · next " +
                            (field("nextDueAt") ?: field("beginAt") ?: "idle")))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f), label = { Text("Duration min, blank=∞") }, singleLine = true)
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Interval min") }, singleLine = true)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("idle" to "Keep running", "periodic" to "Periodic",
                        "startup" to "On startup").forEach { (mode, label) ->
                        OutlinedButton(enabled = !busy, onClick = { setMode(mode) }) { Text(label) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (caffy != null) OutlinedButton(enabled = !busy,
                        onClick = { control(if (field("paused") == "true") "caffy_resume" else "caffy_pause") }) {
                        Text(if (field("paused") == "true") "Resume" else "Pause")
                    }
                    OutlinedButton(enabled = !busy, onClick = { control("caffy_wake") }) { Text("Wake") }
                    if (caffy != null) OutlinedButton(enabled = !busy,
                        onClick = { control("caffy_stop") }) { Text("Stop") }
                    OutlinedButton(enabled = !busy, onClick = {
                        control("caffy_startup", mapOf("value" to (field("startup") != "true")))
                    }) { Text("Toggle startup") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(beginMinutes, { beginMinutes = it.filter(Char::isDigit).take(6) },
                        Modifier.weight(1f), label = { Text("Start in min") }, singleLine = true)
                    OutlinedTextField(beginPrompt, { beginPrompt = it.take(8_000) },
                        Modifier.weight(2f), label = { Text("Prompt") }, maxLines = 2)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        val n = positive(beginMinutes,1,129_600)
                        if (n == null) error = "Start delay invalid."
                        else control("caffy_begin", mapOf("beginAt" to
                            System.currentTimeMillis() + n * 60_000L,
                            "prompt" to beginPrompt.ifBlank { null }))
                    }) { Text("Schedule begin") }
                    OutlinedButton(enabled = !busy,
                        onClick = { control("caffy_cancel_begin") }) { Text("Cancel begin") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(rebootTime, { rebootTime = it.filter(Char::isDigit).take(6) },
                        Modifier.weight(1f), label = { Text("Reboot min") }, singleLine = true)
                    OutlinedButton(enabled = !busy, onClick = {
                        val n = positive(rebootTime,1,525_600)
                        if(n == null) error = "Reboot minutes invalid."
                        else control("caffy_reboot", mapOf("value" to n))
                    }) { Text("Set") }
                    OutlinedTextField(rebootRuns, { rebootRuns = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(1f), label = { Text("Runs") }, singleLine = true)
                    OutlinedButton(enabled = !busy, onClick = {
                        val n = positive(rebootRuns,1,10_000)
                        if(n == null) error = "Reboot runs invalid."
                        else control("caffy_reboot", mapOf("runs" to n))
                    }) { Text("Set") }
                }
                OutlinedButton(enabled = !busy, onClick = {
                    control("caffy_reboot", mapOf("value" to null))
                }) { Text("Disable auto-reboot") }
                HorizontalDivider()
                Text("Get My Attention (" + attentions.size + ")",
                    style = MaterialTheme.typography.titleMedium)
                if (details == null) Text("GMA status unavailable until T3 responds.")
                else if (attentions.isEmpty()) Text("No pending requests for this agent.")
                attentions.forEach { req ->
                    Text(req.message)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            control("attention_open", mapOf("requestId" to req.id),
                                after = { onOpenAgent(uuid!!); onDismiss() })
                        }) { Text("Ack & open") }
                        OutlinedButton(enabled = !busy, onClick = {
                            control("attention_snooze", mapOf("requestId" to req.id,"minutes" to 15))
                        }) { Text("15m") }
                        OutlinedButton(enabled = !busy, onClick = {
                            control("attention_dismiss", mapOf("requestId" to req.id))
                        }) { Text("Dismiss") }
                    }
                }
                HorizontalDivider()
                Text("New Prepfile for " + task.id, style = MaterialTheme.typography.titleMedium)
                Text("Uses T3's existing prepfile service. Does not start a new agent or duplicate the task.")
                OutlinedTextField(prepFocus, { prepFocus = it.take(6_000) },
                    Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text("Research focus") })
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("light","medium","heavy").forEach { value ->
                        FilterChip(depth == value, onClick = { depth = value },
                            label = { Text(value) })
                    }
                }
                if (pending == null) Button(enabled = !busy && details != null && prepFocus.isNotBlank(), onClick = {
                    val id = UUID.randomUUID().toString()
                    if (!prefs.edit().putString(prepKey,id).commit())
                        error = "Could not persist request ID; nothing sent."
                    else {
                        pending = id
                        val request = "Existing TaskBoard task: " + boardPath +
                            " / " + task.id + "\nTitle: " + task.title +
                            "\nFocus: " + prepFocus.trim() +
                            "\nRead canonical criteria and write prepfile path to THIS task AI Notes." +
                            " Do not create another task."
                        control("prepfile_create", mapOf("id" to id, "request" to request,
                            "depth" to depth, "generator" to "chatgpt",
                            "taskRef" to mapOf("board" to boardPath, "id" to task.id),
                            "autoHandoff" to false, "createTaskboardTask" to false))
                    }
                }) { Text("Request task Prepfile") }
                else {
                    Text("Existing request: " + pending + ". Never create a duplicate on timeout.")
                    Text("PC job: " + (job?.get("state")?.asString ?: "unconfirmed"))
                    job?.get("outputPath")?.takeUnless { it.isJsonNull }?.asString?.let {
                        Text("Output: " + it)
                    }
                    OutlinedButton(onClick = { refresh() }) { Text("Reconcile PC status") }
                    OutlinedButton(enabled = !busy, onClick = {
                        prefs.edit().remove(prepKey).commit()
                        pending = null
                    }) { Text("New request (verify previous first)") }
                }
                }
            }
            if (notice != null) Text(notice!!, color = MaterialTheme.colorScheme.primary)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
        }
    }
}
