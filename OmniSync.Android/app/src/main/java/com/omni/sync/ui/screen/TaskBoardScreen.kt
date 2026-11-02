package com.omni.sync.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.omni.sync.data.taskboard.TaskBoardColumn
import com.omni.sync.data.taskboard.TaskBoardProject
import com.omni.sync.data.taskboard.TaskBoardQuestion
import com.omni.sync.data.taskboard.TaskBoardSnapshot
import com.omni.sync.data.taskboard.TaskBoardStore
import com.omni.sync.data.taskboard.TaskBoardTask
import com.omni.sync.data.taskboard.TaskBoardTaskDraft
import com.omni.sync.data.taskboard.TaskBoardTimeline
import com.omni.sync.data.taskboard.TaskBoardOfflineDrafts
import com.omni.sync.data.taskboard.TaskBoardPhoneClient
import com.omni.sync.data.taskboard.TaskBoardConflictException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Transient transport failure means a durable phone draft, not a failed user edit. */
private class TaskBoardQueuedException : Exception()

private fun requirePcApplied(receipt: TaskBoardPhoneClient.Submission) {
    when (receipt.status) {
        "applied" -> Unit
        "queued" -> throw TaskBoardQueuedException()
        else -> throw TaskBoardConflictException(receipt.details ?: "PC edit needs review")
    }
}

// Queue snoozes are UI-only (like desktop TaskBoard), not competing Markdown state.
private fun queueSnoozeKey(root: String): String = "taskboard.queue.snoozed:$root"

private fun readTaskQueueSnoozes(
    preferences: android.content.SharedPreferences,
    root: String
): Map<String, Long> = runCatching {
    val json = JSONObject(preferences.getString(queueSnoozeKey(root), "{}") ?: "{}")
    buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val expiry = json.optLong(id)
            if (expiry > System.currentTimeMillis()) put(id, expiry)
        }
    }
}.getOrDefault(emptyMap())

private fun saveTaskQueueSnoozes(
    preferences: android.content.SharedPreferences,
    root: String,
    snoozes: Map<String, Long>
) {
    val json = JSONObject()
    snoozes.filterValues { it > System.currentTimeMillis() }
        .forEach { (id, expiry) -> json.put(id, expiry) }
    preferences.edit().putString(queueSnoozeKey(root), json.toString()).apply()
}

private fun readCollapsedEpics(preferences: android.content.SharedPreferences, root: String): Set<String> =
    preferences.getStringSet("collapsed_epics:$root", emptySet())?.toSet() ?: emptySet()

private fun visibleTaskQueue(
    board: TaskBoardSnapshot,
    snoozes: Map<String, Long>
): List<TaskBoardTask> = board.userQueue().filter {
    (snoozes[it.id] ?: 0L) <= System.currentTimeMillis()
}

/** Phone workbench uses the same task/column concepts as desktop but keeps
 * filters and selection local. No transient UI state is written to Markdown. */
private fun mobileTaskMatches(task: TaskBoardTask, board: TaskBoardSnapshot, mode: String,
    queueModes: Map<String, String> = emptyMap()): Boolean {
    val terminal = task.completed != null || board.columns.any { it.id == task.column && it.terminal }
    val waiting = board.waitingFor(task).isNotEmpty()
    return when (mode) {
        "focus" -> !terminal && task.kind != "epic" &&
            ("priority" in task.attention || task.kind == "for_user" ||
                "needs-user" in task.attention || (task.question != null && task.question.answer.isNullOrBlank()))
        "doing" -> !terminal && (task.column == "in-progress" || task.claimedBy != null)
        "ready" -> !terminal && board.columns.any { it.id == task.column && it.aiReady }
        "queued" -> !terminal && queueModes[task.id] == "queue"
        "priority" -> !terminal && "priority" in task.attention
        "attention" -> task.attention.isNotEmpty() || task.question != null
        "user" -> task.kind == "for_user" || "needs-user" in task.attention
        "waiting" -> waiting
        "blocked" -> "blocked" in task.attention || task.column == "blocked"
        "done" -> terminal && task.kind != "epic"
        "epics" -> task.kind == "epic"
        else -> true
    }
}

private fun mobileTaskSort(mode: String): Comparator<TaskBoardTask> = when (mode) {
    "newest" -> compareByDescending<TaskBoardTask> { it.created.orEmpty() }.thenByDescending { it.id }
    "oldest" -> compareBy<TaskBoardTask> { it.created.orEmpty() }.thenBy { it.id }
    "title" -> compareBy<TaskBoardTask> { it.title.lowercase() }.thenBy { it.id }
    else -> compareBy<TaskBoardTask>({ attentionRank(it) }, { it.kind != "epic" }, { it.id })
}

private val mobileFilters = listOf(
    "all" to "All", "focus" to "Focus", "doing" to "In progress",
    "ready" to "AI ready", "queued" to "Queued", "priority" to "Priority", "user" to "For me",
    "blocked" to "Blocked", "waiting" to "Waiting", "done" to "Done",
    "epics" to "Epics"
)

private data class CreateTaskRequest(
    val column: String,
    val parent: String? = null,
    val category: String? = null,
    val kind: String = "task"
)

private fun splitPhoneDumpLine(source: String): Pair<String, String> {
    val clean = source.trim().replace(Regex("\\s+"), " ")
    if (clean.length <= 105) return clean to ""
    val breakpoint = clean.lastIndexOf(' ', 105).takeIf { it >= 45 } ?: 105
    return clean.take(breakpoint).trim() to clean.drop(breakpoint).trim()
}

@Composable
fun TaskBoardScreen(signalR: SignalRClient, parentPadding: PaddingValues = PaddingValues(0.dp)) {
    TaskBoardTheme {
        TaskBoardContents(parentPadding, signalR)
    }
}

@Composable
private fun TaskBoardContents(parentPadding: PaddingValues, signalR: SignalRClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boardPreferences = remember { context.getSharedPreferences("taskboard_mobile", android.content.Context.MODE_PRIVATE) }
    var selectedRoot by remember { mutableStateOf(boardPreferences.getString("selected_board", TaskBoardStore.defaultRoot().absolutePath) ?: TaskBoardStore.defaultRoot().absolutePath) }
    val store = remember(selectedRoot) { TaskBoardStore(java.io.File(selectedRoot)) }
    val offlineDrafts = remember { TaskBoardOfflineDrafts(context) }
    val phoneWriter = remember(signalR) { TaskBoardPhoneClient(signalR, offlineDrafts) }
    var projects by remember { mutableStateOf<List<TaskBoardProject>>(emptyList()) }
    var boardSelectorExpanded by remember { mutableStateOf(false) }
    var showCreateBoard by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var showTaskDump by remember { mutableStateOf(false) }
    var taskDumpText by remember { mutableStateOf("") }
    var taskDumpPriorities by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var compactView by remember { mutableStateOf(true) }
    var queueMode by remember { mutableStateOf(false) }
    var reviewMode by remember { mutableStateOf(false) }
    var skippedQueueIds by remember(selectedRoot) { mutableStateOf<Set<String>>(emptySet()) }
    var queueSnoozes by remember(selectedRoot) { mutableStateOf(readTaskQueueSnoozes(boardPreferences, selectedRoot)) }
    var filterMode by remember { mutableStateOf(boardPreferences.getString("mobile_filter", "all") ?: "all") }
    var sortMode by remember { mutableStateOf(boardPreferences.getString("mobile_sort", "priority") ?: "priority") }
    var queueModes by remember(selectedRoot) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hideWaiting by remember { mutableStateOf(boardPreferences.getBoolean("mobile_hide_waiting", false)) }
    var advancedOpen by remember { mutableStateOf(boardPreferences.getBoolean("mobile_tools_open", false)) }
    var focusedColumn by remember(selectedRoot) { mutableStateOf<String?>(null) }
    var collapsedEpics by remember(selectedRoot) { mutableStateOf(readCollapsedEpics(boardPreferences, selectedRoot)) }
    var selection by remember(selectedRoot) { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember(selectedRoot) { mutableStateOf(false) }
    var showBatchActions by remember { mutableStateOf(false) }
    var batchDestination by remember { mutableStateOf<String?>(null) }
    var batchArchiveConfirm by remember { mutableStateOf(false) }


    var snapshot by remember { mutableStateOf<TaskBoardSnapshot?>(null) }
    var editingTask by remember { mutableStateOf<TaskBoardTask?>(null) }
    var createTaskRequest by remember { mutableStateOf<CreateTaskRequest?>(null) }
    var showColumns by remember { mutableStateOf(false) }
    var showPendingPhoneEdits by remember { mutableStateOf(false) }
    var pendingPhoneEditsRevision by remember { mutableStateOf(0) }
    var showAddColumn by remember { mutableStateOf(false) }
    var renameColumn by remember { mutableStateOf<TaskBoardColumn?>(null) }
    var deleteColumn by remember { mutableStateOf<TaskBoardColumn?>(null) }
    var loading by remember { mutableStateOf(false) }
    var storageAccess by remember { mutableStateOf(TaskBoardStore.hasStorageAccess()) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncNotice by remember { mutableStateOf<String?>(null) }
    var archiveConfirmation by remember { mutableStateOf<TaskBoardTask?>(null) }
    var taskQuery by remember { mutableStateOf("") }
    var attentionOnly by remember { mutableStateOf(false) }

    fun refresh() {
        storageAccess = TaskBoardStore.hasStorageAccess()
        if (!storageAccess) {
            snapshot = null
            return
        }
        val currentStore = store
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    TaskBoardStore.discoverBoards() to currentStore.load()
                }
            }.onSuccess { (available, board) ->
                if (selectedRoot == currentStore.root.absolutePath) {
                    projects = available
                    // A previously selected legacy alias may now be hidden because
                    // the same-named canonical folder is present. Switch to it.
                    val canonical = available.firstOrNull { it.name.equals(board.name, ignoreCase = true) }
                    if (available.none { it.root.absolutePath == selectedRoot } && canonical != null) {
                        selectedRoot = canonical.root.absolutePath
                        boardPreferences.edit().putString("selected_board", selectedRoot).apply()
                    } else {
                        snapshot = board
                    }
                }
            }.onFailure {
                error = it.message ?: it.toString()
                projects = withContext(Dispatchers.IO) { TaskBoardStore.discoverBoards() }
            }
            loading = false
        }
    }

    fun mutate(
        action: suspend () -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    action()
                    store.load()
                }
            }.onSuccess {
                snapshot = it
                onSuccess()
            }.onFailure {
                if (it is TaskBoardQueuedException) {
                    syncNotice = "Saved on phone; syncing to PC automatically"
                    onSuccess() // Close the editor, but never claim the PC board has updated.
                } else {
                    error = it.message ?: it.toString()
                }
            }
            pendingPhoneEditsRevision++
            loading = false
        }
    }

    /** Batch edits preserve original operation IDs and stop on real conflicts. */
    fun applyBatch(mode: String, destination: String? = null) {
        val board = snapshot ?: return
        val tasks = board.tasks.filter { it.id in selection && it.kind != "epic" }
        if (tasks.isEmpty()) return
        showBatchActions = false
        batchArchiveConfirm = false
        scope.launch {
            loading = true
            error = null
            var confirmed = 0
            var queued = 0
            try {
                withContext(Dispatchers.IO) {
                    for (task in tasks) {
                        val receipt = when (mode) {
                            "archive" -> phoneWriter.submitArchive(store.root, task)
                            "priority" -> phoneWriter.submitUpdate(store.root, task,
                                task.toDraft().copy(attention = (task.attention + "priority").distinct()))
                            "unpriority" -> phoneWriter.submitUpdate(store.root, task,
                                task.toDraft().copy(attention = task.attention - "priority"))
                            "move" -> phoneWriter.submitUpdate(store.root, task,
                                task.toDraft().copy(column = requireNotNull(destination)))
                            "queue", "backlog" -> phoneWriter.submitQueue(store.root, task, mode == "queue")
                            else -> error("Unknown bulk action")
                        }
                        when (receipt.status) {
                            "applied" -> confirmed++
                            "queued" -> queued++
                            else -> throw TaskBoardConflictException(
                                receipt.details ?: "Batch edit needs review")
                        }
                    }
                }
                if (queued > 0) syncNotice = "$queued edits saved on phone; syncing automatically"
                if (mode == "queue" || mode == "backlog") {
                    runCatching { withContext(Dispatchers.IO) { phoneWriter.queueModes(store.root) } }
                        .onSuccess { queueModes = it }
                }
                selection = emptySet()
                selecting = false
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "$confirmed confirmed, $queued queued. " +
                    (failure.message ?: "Remaining edits not applied")
            } finally {
                snapshot = runCatching { withContext(Dispatchers.IO) { store.load() } }
                    .getOrNull() ?: snapshot
                pendingPhoneEditsRevision++
                loading = false
            }
        }
    }

    suspend fun saveColumnsViaPc(columns: List<TaskBoardColumn>, expectedRevision: String) {
        val result = phoneWriter.submitColumns(store.root, expectedRevision, columns)
        requirePcApplied(result)
    }

    fun generateDump() {
        val board = snapshot ?: return
        val rows = taskDumpText.lines().withIndex().filter { it.value.isNotBlank() }
        if (rows.isEmpty() || loading) return
        val priorities = taskDumpPriorities
        val currentStore = store
        scope.launch {
            loading = true
            error = null
            var completed = 0
            try {
                val nextSnapshot = withContext(Dispatchers.IO) {
                    for (row in rows) {
                        val (title, description) = splitPhoneDumpLine(row.value)
                        val result = phoneWriter.submitCreate(currentStore.root, board.boardRevision,
                            TaskBoardTaskDraft(
                                title = title,
                                description = description,
                                column = defaultCreateColumn(board),
                                attention = if (row.index in priorities) listOf("priority") else emptyList()
                            ))
                        requirePcApplied(result)
                        completed++
                    }
                    currentStore.load()
                }
                snapshot = nextSnapshot
                showTaskDump = false
                taskDumpText = ""
                taskDumpPriorities = emptySet()
            } catch (failure: Exception) {
                val pending = failure is TaskBoardQueuedException
                val rest = rows.drop(completed + if (pending) 1 else 0)
                if (pending) syncNotice = "Saved on phone; syncing automatically"
                taskDumpText = rest.joinToString("\n") { it.value }
                taskDumpPriorities = rest.mapIndexedNotNull { index, item ->
                    if (item.index in priorities) index else null
                }.toSet()
                if (!pending) error = "$completed of ${rows.size} tasks created; remaining lines preserved. ${failure.message}"
                snapshot = runCatching { withContext(Dispatchers.IO) { currentStore.load() } }.getOrNull() ?: snapshot
            } finally {
                loading = false
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh()
    }

    LaunchedEffect(selectedRoot) {
        snapshot = null
        refresh()
    }

    // No manual retry dialog for ordinary connectivity changes. Pending drafts
    // survive app restarts and replay with the original idempotent operation ID.
    LaunchedEffect(phoneWriter) {
        delay(1200) // Allow a newly submitted edit to complete its first attempt.
        while (true) {
            val hasPending = offlineDrafts.all().any { it.state == "draft" }
            if (hasPending) signalR.ensureAgentAuthentication()
            if (hasPending && signalR.hubAuthenticated.value) {
                try {
                    val delivered = withContext(Dispatchers.IO) { phoneWriter.syncPending() }
                    if (delivered > 0) refresh()
                    pendingPhoneEditsRevision++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("TaskBoardPhone", "Deferred draft sync: ${failure.message}")
                }
            }
            delay(4000)
        }
    }

    // The synced vault can change while Android TaskBoard remains open.
    LaunchedEffect(selectedRoot, reviewMode) {
        while (!reviewMode) {
            delay(7000)
            if (loading || editingTask != null || createTaskRequest != null) continue
            runCatching { withContext(Dispatchers.IO) { store.load() } }.onSuccess { fresh ->
                val previous = snapshot
                if (previous != null && (fresh.boardRevision != previous.boardRevision ||
                    fresh.tasks.size != previous.tasks.size ||
                    fresh.tasks.zip(previous.tasks).any { (a, b) ->
                        a.id != b.id || a.revision != b.revision })) {
                    snapshot = fresh
                }
            }
        }
    }

    // Queue state is server-owned, not guessed from stale synced task files.
    LaunchedEffect(selectedRoot, reviewMode) {
        while (!reviewMode) {
            if (signalR.hubAuthenticated.value) {
                runCatching { withContext(Dispatchers.IO) { phoneWriter.queueModes(store.root) } }
                    .onSuccess { queueModes = it }
            }
            delay(9000)
        }
    }

    LaunchedEffect(syncNotice) {
        if (syncNotice != null) {
            delay(4200)
            syncNotice = null
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(parentPadding),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TaskBoardColors.surface,
                    titleContentColor = TaskBoardColors.text,
                    actionIconContentColor = TaskBoardColors.blue
                ),
                title = {
                    Column {
                        Text(
                            "TASKBOARD / PROJECT",
                            style = MaterialTheme.typography.labelSmall,
                            color = TaskBoardColors.blue,
                            fontWeight = FontWeight.Bold
                        )
                        Box {
                            TextButton(
                                onClick = { boardSelectorExpanded = true },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                enabled = !loading && editingTask == null && createTaskRequest == null
                            ) {
                                Text(
                                    "${snapshot?.name ?: projects.firstOrNull { it.root.absolutePath == selectedRoot }?.name ?: "Tasks"}  ▾",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TaskBoardColors.text,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(expanded = boardSelectorExpanded, onDismissRequest = { boardSelectorExpanded = false }) {
                                projects.forEach { project ->
                                    DropdownMenuItem(text = { Text(project.name) }, onClick = {
                                        boardSelectorExpanded = false
                                        if (selectedRoot != project.root.absolutePath) {
                                            selectedRoot = project.root.absolutePath
                                            boardPreferences.edit().putString("selected_board", selectedRoot).apply()
                                            editingTask = null
                                            createTaskRequest = null
                                            showColumns = false
                                            focusedColumn = null
                                            selecting = false
                                            selection = emptySet()
                                            taskQuery = ""
                                            queueMode = false
                                        }
                                    })
                                }
                                DropdownMenuItem(text = { Text("+ New board") }, onClick = {
                                    boardSelectorExpanded = false
                                    showCreateBoard = true
                                })
                            }
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { reviewMode = !reviewMode },
                        enabled = storageAccess && editingTask == null && createTaskRequest == null) {
                        Text(if (reviewMode) "Board" else "Review", color = TaskBoardColors.blue)
                    }
                    TextButton(
                        onClick = {
                            snapshot?.let {
                                createTaskRequest = CreateTaskRequest(defaultCreateColumn(it))
                            }
                        },
                        enabled = snapshot != null && !loading
                    ) {
                        Text("+ New", fontWeight = FontWeight.Bold)
                    }
                    Box {
                        TextButton(onClick = { actionsExpanded = true }, enabled = snapshot != null && !loading) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            DropdownMenuItem(text = { Text("Task Dump") }, onClick = {
                                actionsExpanded = false
                                taskDumpText = ""
                                taskDumpPriorities = emptySet()
                                showTaskDump = true
                            })
                            DropdownMenuItem(text = { Text("Pending PC edits") }, onClick = {
                                actionsExpanded = false
                                pendingPhoneEditsRevision++
                                showPendingPhoneEdits = true
                            })
                            DropdownMenuItem(text = { Text("Manage columns") }, onClick = {
                                actionsExpanded = false
                                showColumns = true
                            })
                            DropdownMenuItem(text = { Text("Select multiple cards") }, onClick = {
                                actionsExpanded = false
                                selecting = true
                                selection = emptySet()
                            })
                            DropdownMenuItem(text = { Text(if (hideWaiting) "Show waiting tasks" else "Hide dependent tasks") }, onClick = {
                                actionsExpanded = false
                                hideWaiting = !hideWaiting
                                boardPreferences.edit().putBoolean("mobile_hide_waiting", hideWaiting).apply()
                            })
                            DropdownMenuItem(text = { Text("Refresh board") }, onClick = {
                                actionsExpanded = false
                                refresh()
                            })
                        }
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            when {
                !storageAccess -> StoragePermissionPanel(
                    path = store.root.absolutePath,
                    onGrant = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        }
                        storagePermissionLauncher.launch(intent)
                    }
                )

                reviewMode -> TaskBoardReviewScreen(onClose = { reviewMode = false })

                loading && snapshot == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                snapshot == null -> Column(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Board not available on this phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Android reads synced TaskBoard files from SharedWithPc/Obsidian/jjj. Sync the project's Tasks folder, or select another board above.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(selectedRoot, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { refresh() }) { Text("Retry discovery") }
                }

                else -> snapshot?.let {
                    Column(Modifier.fillMaxSize().background(TaskBoardColors.background)) {
                        val terminalCount = it.tasks.count { task ->
                            task.completed != null || it.columns.any { c -> c.id == task.column && c.terminal }
                        }
                        val priorityCount = it.tasks.count { task ->
                            "priority" in task.attention && task.completed == null
                        }
                        val forMeCount = it.tasks.count { task ->
                            task.kind == "for_user" || "needs-user" in task.attention
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(TaskBoardColors.surface)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Triple("all", "${it.tasks.count { task -> task.kind != "epic" }} Tasks", TaskBoardColors.blue),
                                Triple("focus", "$forMeCount For me", TaskBoardColors.amber),
                                Triple("priority", "$priorityCount Priority", TaskBoardColors.amber),
                                Triple("doing", "${it.tasks.count { task -> task.column == "in-progress" }} Doing", TaskBoardColors.green),
                                Triple("done", "$terminalCount Done", TaskBoardColors.muted)
                            ).forEach { (mode, label, color) ->
                                TextButton(onClick = { filterMode = mode; focusedColumn = null },
                                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                    Text(label, color = if (filterMode == mode) color else TaskBoardColors.text,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (filterMode == mode) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                            TextButton(
                                onClick = {
                                    queueMode = true
                                    skippedQueueIds = emptySet()
                                    editingTask = visibleTaskQueue(it, queueSnoozes).firstOrNull()
                                },
                                enabled = visibleTaskQueue(it, queueSnoozes).isNotEmpty()
                            ) { Text("User queue ${visibleTaskQueue(it, queueSnoozes).size}", color = TaskBoardColors.amber) }
                            TextButton(onClick = { compactView = !compactView }) {
                                Text(if (compactView) "Columns" else "List", color = TaskBoardColors.blue)
                            }
                        }
                        OutlinedTextField(
                            value = taskQuery,
                            onValueChange = { taskQuery = it },
                            modifier = Modifier.fillMaxWidth()
                                .background(TaskBoardColors.surface)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            placeholder = { Text("⌕  Search tasks, IDs, descriptions", color = TaskBoardColors.muted) },
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(TaskBoardColors.surface)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            mobileFilters.forEach { (id, label) ->
                                FilterChip(
                                    selected = filterMode == id,
                                    onClick = {
                                        filterMode = id
                                        focusedColumn = null
                                        boardPreferences.edit().putString("mobile_filter", id).apply()
                                    },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                    shape = RoundedCornerShape(7.dp),
                                    modifier = Modifier.height(34.dp)
                                )
                            }
                            FilterChip(
                                selected = advancedOpen,
                                onClick = {
                                    advancedOpen = !advancedOpen
                                    boardPreferences.edit().putBoolean("mobile_tools_open", advancedOpen).apply()
                                },
                                label = { Text(if (advancedOpen) "Less tools" else "Tools") },
                                modifier = Modifier.height(34.dp)
                            )
                            TextButton(onClick = { createTaskRequest = CreateTaskRequest(it.columns.firstOrNull { column -> column.id == "draft" }?.id ?: defaultCreateColumn(it), kind = "epic") }) { Text("+ Epic") }
                            if (taskQuery.isNotBlank()) TextButton(onClick = { taskQuery = "" }) { Text("Clear") }
                        }
                        if (advancedOpen) {
                        Row(
                            Modifier.fillMaxWidth().background(TaskBoardColors.surface)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box {
                                var sortsOpen by remember { mutableStateOf(false) }
                                TextButton(onClick = { sortsOpen = true }) {
                                    Text("Sort: " + when (sortMode) {
                                        "newest" -> "Newest"; "oldest" -> "Oldest"
                                        "title" -> "A–Z"; else -> "Priority"
                                    }, style = MaterialTheme.typography.labelMedium)
                                }
                                DropdownMenu(expanded = sortsOpen, onDismissRequest = { sortsOpen = false }) {
                                    listOf("priority" to "Priority", "newest" to "Newest",
                                        "oldest" to "Oldest", "title" to "Title A–Z").forEach { (id, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            sortsOpen = false; sortMode = id
                                            boardPreferences.edit().putString("mobile_sort", id).apply()
                                        })
                                    }
                                }
                            }
                            FilterChip(
                                selected = hideWaiting,
                                onClick = {
                                    hideWaiting = !hideWaiting
                                    boardPreferences.edit().putBoolean("mobile_hide_waiting", hideWaiting).apply()
                                },
                                label = { Text("Hide dependent") }, modifier = Modifier.height(32.dp)
                            )
                            TextButton(onClick = {
                                if (collapsedEpics.isEmpty()) collapsedEpics = it.tasks.filter { task -> task.kind == "epic" }.map { task -> task.id }.toSet()
                                else collapsedEpics = emptySet()
                                boardPreferences.edit().putStringSet("collapsed_epics:$selectedRoot", collapsedEpics).apply()
                            }) { Text(if (collapsedEpics.isEmpty()) "Fold epics" else "Expand epics", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick = {
                                selecting = !selecting
                                if (!selecting) selection = emptySet()
                            }) { Text(if (selecting) "Cancel select" else "Select", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick = { refresh() }) { Text("Refresh", style = MaterialTheme.typography.labelMedium) }
                        }
                        if (filterMode == "all" && !selecting) {
                            Row(Modifier.fillMaxWidth().background(TaskBoardColors.surface)
                                .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(selected = focusedColumn == null, onClick = { focusedColumn = null },
                                    label = { Text("All columns") }, modifier = Modifier.height(30.dp))
                                it.columns.filter { column -> !column.hidden }.forEach { column ->
                                    FilterChip(selected = focusedColumn == column.id,
                                        onClick = { focusedColumn = if (focusedColumn == column.id) null else column.id },
                                        label = { Text(column.name) }, modifier = Modifier.height(30.dp))
                                }
                            }
                        }
                        }
                        if (selecting) {
                            Row(Modifier.fillMaxWidth().background(TaskBoardColors.card)
                                .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${selection.size} selected", fontWeight = FontWeight.Bold, color = TaskBoardColors.text)
                                TextButton(onClick = {
                                    selection = it.tasks.filter { task ->
                                        task.kind != "epic" && mobileTaskMatches(task, it, filterMode) &&
                                            (focusedColumn == null || it.displayColumn(task) == focusedColumn)
                                    }.map { task -> task.id }.toSet()
                                }) { Text("All") }
                                TextButton(onClick = { selection = emptySet() }) { Text("None") }
                                TextButton(onClick = { showBatchActions = true }, enabled = selection.isNotEmpty()) {
                                    Text("Actions…", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            TaskBoardColumns(
                                snapshot = it,
                                query = taskQuery,
                                attentionOnly = attentionOnly,
                                filterMode = filterMode,
                                compactView = compactView,
                                sortMode = sortMode,
                                queueModes = queueModes,
                                onQueueToggle = { task ->
                                    val current = queueModes[task.id] ?: "backlog"
                                    val desired = current != "queue"
                                    mutate(action = {
                                        requirePcApplied(phoneWriter.submitQueue(store.root, task, desired))
                                    }, onSuccess = {
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { phoneWriter.queueModes(store.root) } }
                                                .onSuccess { queueModes = it }
                                        }
                                    })
                                },
                                hideWaiting = hideWaiting,
                                focusedColumn = focusedColumn,
                                collapsedEpics = collapsedEpics,
                                selection = selection,
                                selecting = selecting,
                                onSelect = { task ->
                                    selection = if (task.id in selection) selection - task.id
                                        else selection + task.id
                                },
                                onStartSelection = { task ->
                                    selecting = true
                                    selection = selection + task.id
                                },
                                onToggleEpic = { epic ->
                                    collapsedEpics = if (epic.id in collapsedEpics) collapsedEpics - epic.id
                                        else collapsedEpics + epic.id
                                    boardPreferences.edit().putStringSet(
                                        "collapsed_epics:$selectedRoot", collapsedEpics).apply()
                                },
                                onTaskClick = { task -> editingTask = task },
                                onTaskChange = { task, draft ->
                                    mutate(action = {
                                        val result = phoneWriter.submitUpdate(store.root, task, draft)
                                        requirePcApplied(result)
                                   })
                                },
                                onTaskArchive = { task -> archiveConfirmation = task },
                                onCreateChild = { epic ->
                                    createTaskRequest = CreateTaskRequest(defaultCreateColumn(it), epic.id, epic.category)
                                },
                                onAddTask = { column ->
                                    createTaskRequest = CreateTaskRequest(column)
                                }
                            )
                        }
                    }
                }
            }

            if (loading && snapshot != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }

            if (error == null) syncNotice?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    color = TaskBoardColors.card,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(message, modifier = Modifier.padding(12.dp),
                        color = TaskBoardColors.text, style = MaterialTheme.typography.bodySmall)
                }
            }

            error?.let { message ->
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { error = null }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }

    if (showPendingPhoneEdits) {
        val privateDrafts = remember(pendingPhoneEditsRevision) { offlineDrafts.all() }
        PendingPhoneTaskEditsDialog(
            drafts = privateDrafts,
            busy = loading,
            onRetryExact = { operationId ->
                scope.launch {
                    loading = true
                    error = null
                    try {
                        val result = withContext(Dispatchers.IO) { phoneWriter.sendSaved(operationId) }
                        if (result.status == "queued") syncNotice = "Saved on phone; syncing automatically"
                        else if (result.status != "applied") error = result.details ?: "PC operation needs review"
                        refresh()
                    } catch (failure: Exception) {
                        if (failure is TaskBoardQueuedException)
                            syncNotice = "Saved on phone; syncing automatically"
                        else error = failure.message ?: failure.toString()
                    } finally {
                        pendingPhoneEditsRevision++
                        loading = false
                    }
                }
            },
            onClose = { showPendingPhoneEdits = false }
        )
    }

    snapshot?.let { board ->
        createTaskRequest?.let { request ->
            TaskEditorDialog(
                original = null,
                initialColumn = request.column,
                initialParent = request.parent,
                initialCategory = request.category,
                initialKind = request.kind,
                snapshot = board,
                busy = loading,
                onDismiss = { createTaskRequest = null },
                onSave = { draft ->
                    mutate(
                        action = {
                            val result = phoneWriter.submitCreate(store.root, board.boardRevision, draft)
                            requirePcApplied(result)
                        },
                        onSuccess = { createTaskRequest = null }
                    )
                }
            )
        }

        editingTask?.let { task ->
            TaskEditorDialog(
                original = task,
                initialColumn = task.column,
                initialParent = task.parent,
                initialCategory = task.category,
                initialKind = task.kind,
                snapshot = board,
                busy = loading,
                onDismiss = { editingTask = null; queueMode = false },
                inQueue = queueMode,
                onSkip = {
                    skippedQueueIds = skippedQueueIds + task.id
                    editingTask = visibleTaskQueue(board, queueSnoozes)
                        .firstOrNull { it.id !in skippedQueueIds }
                    if (editingTask == null) queueMode = false
                },
                onSnooze = {
                    val next = queueSnoozes + (task.id to (System.currentTimeMillis() + 86_400_000L))
                    queueSnoozes = next
                    saveTaskQueueSnoozes(boardPreferences, selectedRoot, next)
                    skippedQueueIds = skippedQueueIds + task.id
                    editingTask = visibleTaskQueue(board, queueSnoozes)
                        .firstOrNull { it.id !in skippedQueueIds }
                    if (editingTask == null) queueMode = false
                },
                onSave = { draft ->
                    mutate(
                        action = {
                            val result = phoneWriter.submitUpdate(store.root, task, draft)
                            requirePcApplied(result)
                        },
                        onSuccess = {
                            if (queueMode) {
                                skippedQueueIds = skippedQueueIds + task.id
                                editingTask = snapshot?.let { visibleTaskQueue(it, queueSnoozes)
                                    .firstOrNull { next -> next.id !in skippedQueueIds } }
                                if (editingTask == null) queueMode = false
                            } else editingTask = null
                        }
                    )
                },
                onCreateSubtask = if (task.kind == "epic") {{
                    createTaskRequest = CreateTaskRequest(
                        column = defaultCreateColumn(board),
                        parent = task.id,
                        category = task.category
                    )
                    editingTask = null
                }} else null,
                onArchive = if (task.kind != "epic" || board.epicChildren(task).isEmpty()) {{
                    editingTask = null
                    archiveConfirmation = task
                }} else null
            )
        }

        if (showColumns) {
            ColumnManagerDialog(
                snapshot = board,
                busy = loading,
                onDismiss = { showColumns = false },
                onAdd = { showAddColumn = true },
                onRename = { renameColumn = it },
                onToggleHidden = { column ->
                    val changed = board.columns.map {
                        if (it.id == column.id) it.copy(hidden = !it.hidden) else it
                    }
                    mutate(action = { saveColumnsViaPc(changed, board.boardRevision) })
                },
                onToggleQueue = { column ->
                    val changed = board.columns.map {
                        if (it.id == column.id) it.copy(autoQueue = !it.autoQueue) else it
                    }
                    mutate(action = { saveColumnsViaPc(changed, board.boardRevision) })
                },
                onToggleAiReady = { column ->
                    val changed = board.columns.map {
                        if (it.id == column.id) it.copy(
                            aiReady = !it.aiReady && !it.advisory,
                            advisory = it.aiReady
                        ) else it
                    }
                    mutate(action = { saveColumnsViaPc(changed, board.boardRevision) })
                },
                onMove = { column, delta ->
                    val changed = moveColumn(board.columns, column.id, delta)
                    mutate(action = { saveColumnsViaPc(changed, board.boardRevision) })
                },
                onDelete = { deleteColumn = it }
            )
        }

        if (showAddColumn) {
            TextEntryDialog(
                title = "New column",
                label = "Column name",
                initialValue = "",
                confirmText = "Create",
                busy = loading,
                onDismiss = { showAddColumn = false },
                onConfirm = { name ->
                    mutate(
                        action = {
                            val trimmed = name.trim()
                            require(trimmed.isNotEmpty()) { "Column name cannot be empty" }
                            val base = trimmed.lowercase(java.util.Locale.US)
                                .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "column" }
                            var newId = base
                            var suffix = 2
                            while (board.columns.any { it.id == newId }) newId = "$base-${suffix++}"
                            val palette = listOf("#718f78", "#7388a8", "#8e7da8", "#a48669",
                                "#6f8679", "#8c7d72", "#6f8b91", "#927b86")
                            val used = board.columns.map { it.color }.toSet()
                            val color = palette.firstOrNull { it !in used } ?: palette.first()
                            saveColumnsViaPc(board.columns + TaskBoardColumn(
                                id = newId, name = trimmed, order = board.columns.size,
                                terminal = false, hidden = false, aiReady = false, color = color),
                                board.boardRevision)
                        },
                        onSuccess = { showAddColumn = false }
                    )
                }
            )
        }

        renameColumn?.let { column ->
            TextEntryDialog(
                title = "Rename column",
                label = "Column name",
                initialValue = column.name,
                confirmText = "Rename",
                busy = loading,
                onDismiss = { renameColumn = null },
                onConfirm = { name ->
                    val changed = board.columns.map {
                        if (it.id == column.id) it.copy(name = name.trim()) else it
                    }
                    mutate(
                        action = { saveColumnsViaPc(changed, board.boardRevision) },
                        onSuccess = { renameColumn = null }
                    )
                }
            )
        }

        deleteColumn?.let { column ->
            DeleteColumnDialog(
                column = column,
                snapshot = board,
                busy = loading,
                onDismiss = { deleteColumn = null },
                onDelete = { destination ->
                    mutate(
                        action = {
                            val remaining = board.columns.filterNot { it.id == column.id }
                            require(remaining.isNotEmpty()) { "Board needs at least one column" }
                            val occupied = board.tasks.filter { it.column == column.id }
                            if (occupied.isNotEmpty()) {
                                val target = destination ?: error("Select destination for active tasks")
                                require(remaining.any { it.id == target }) { "Unknown destination column" }
                                for (task in occupied) {
                                    val moved = phoneWriter.submitUpdate(store.root, task,
                                        task.toDraft().copy(column = target))
                                    requirePcApplied(moved)
                                }
                            }
                            saveColumnsViaPc(remaining.mapIndexed { index, item ->
                                item.copy(order = index)
                            }, board.boardRevision)
                        },
                        onSuccess = { deleteColumn = null }
                    )
                }
            )
        }
    }

    if (showBatchActions && selection.isNotEmpty()) {
        val board = snapshot
        AlertDialog(
            onDismissRequest = { showBatchActions = false },
            title = { Text("Batch actions · ${selection.size} cards") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("PC writes are sent per card. Conflicts stop the batch.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { applyBatch("priority") }) { Text("Mark priority") }
                    TextButton(onClick = { applyBatch("unpriority") }) { Text("Remove priority") }
                    if (board?.tasks?.filter { it.id in selection }?.all {
                        it.column == "ai-ready" && it.claimedBy == null && it.completed == null &&
                            it.kind == "task" && board.waitingFor(it).isEmpty()
                    } == true) {
                        TextButton(onClick = { applyBatch("queue") }) { Text("Queue selected for AI") }
                        TextButton(onClick = { applyBatch("backlog") }) { Text("Move selected to Backlog") }
                    }
                    Text("Move selected to", fontWeight = FontWeight.Bold)
                    board?.columns?.filter { !it.hidden }?.forEach { column ->
                        TextButton(onClick = { applyBatch("move", column.id) }) {
                            Text(column.name)
                        }
                    }
                    TextButton(onClick = {
                        showBatchActions = false
                        batchArchiveConfirm = true
                    }) { Text("Archive selected…", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { showBatchActions = false }) { Text("Close") } }
        )
    }
    if (batchArchiveConfirm) {
        AlertDialog(
            onDismissRequest = { batchArchiveConfirm = false },
            title = { Text("Archive ${selection.size} cards?") },
            text = { Text("Move these cards to the PC Archive?") },
            confirmButton = {
                TextButton(onClick = { applyBatch("archive") }) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { batchArchiveConfirm = false }) { Text("Cancel") }
            }
        )
    }

    archiveConfirmation?.let { task ->
        AlertDialog(
            onDismissRequest = { archiveConfirmation = null },
            title = { Text("Archive ${task.id}?") },
            text = { Text("Move this card to Archive? You can restore it later on PC.") },
            confirmButton = {
                TextButton(onClick = {
                    archiveConfirmation = null
                    mutate(action = {
                        requirePcApplied(phoneWriter.submitArchive(store.root, task))
                    })
                }) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { archiveConfirmation = null }) { Text("Cancel") }
            }
        )
    }

    if (showTaskDump) {
        TaskDumpDialog(
            text = taskDumpText,
            priorityLines = taskDumpPriorities,
            busy = loading,
            onTextChange = { taskDumpText = it },
            onPriorityChange = { index ->
                taskDumpPriorities = if (index in taskDumpPriorities) taskDumpPriorities - index
                    else taskDumpPriorities + index
            },
            onDismiss = { showTaskDump = false },
            onGenerate = { generateDump() }
        )
    }

    if (showCreateBoard) {
        TextEntryDialog(
            title = "New TaskBoard",
            label = "Board name (new synced project folder)",
            initialValue = "",
            confirmText = "Create board",
            busy = loading,
            onDismiss = { showCreateBoard = false },
            onConfirm = { name ->
                scope.launch {
                    loading = true
                    error = null
                    runCatching {
                        withContext(Dispatchers.IO) { phoneWriter.submitNewBoard(name) }
                    }.onSuccess { receipt ->
                        if (receipt.status == "applied") {
                            showCreateBoard = false
                            error = "Project created on PC. Sync SharedWithPc/Obsidian/jjj to this phone, then Refresh board before selecting it."
                        } else if (receipt.status == "queued") {
                            syncNotice = "Saved on phone; project will sync automatically"
                            showCreateBoard = false
                        } else {
                            error = receipt.details ?: "PC board creation needs review"
                        }
                        pendingPhoneEditsRevision++
                    }.onFailure { failure ->
                        error = failure.message ?: failure.toString()
                        pendingPhoneEditsRevision++
                    }
                    loading = false
                }
            }
        )
    }
}

@Composable
private fun TaskDumpDialog(
    text: String,
    priorityLines: Set<Int>,
    busy: Boolean,
    onTextChange: (String) -> Unit,
    onPriorityChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit
) {
    val rows = text.lines().withIndex().filter { it.value.isNotBlank() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Task Dump") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("One task per line. Mark Priority for urgent items.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste or type tasks") },
                    minLines = 6,
                    maxLines = 12
                )
                Text("${rows.size} tasks · ${rows.count { it.index in priorityLines }} priority",
                    style = MaterialTheme.typography.labelMedium)
                rows.forEach { row ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = row.index in priorityLines, onCheckedChange = {
                            onPriorityChange(row.index)
                        }, enabled = !busy)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.value.trim(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onGenerate, enabled = !busy && rows.isNotEmpty()) {
                Text(if (busy) "Generating…" else "Generate")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun StoragePermissionPanel(
    path: String,
    onGrant: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "TaskBoard reads and writes the phone's local SharedWithPc folder.",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) {
            Text("Grant all files access")
        }
    }
}

@Composable
private fun TaskBoardColumns(
    snapshot: TaskBoardSnapshot,
    query: String,
    attentionOnly: Boolean,
    filterMode: String,
    compactView: Boolean,
    sortMode: String,
    queueModes: Map<String, String>,
    onQueueToggle: (TaskBoardTask) -> Unit,
    hideWaiting: Boolean,
    focusedColumn: String?,
    collapsedEpics: Set<String>,
    selection: Set<String>,
    selecting: Boolean,
    onSelect: (TaskBoardTask) -> Unit,
    onStartSelection: (TaskBoardTask) -> Unit,
    onToggleEpic: (TaskBoardTask) -> Unit,
    onTaskClick: (TaskBoardTask) -> Unit,
    onTaskChange: (TaskBoardTask, TaskBoardTaskDraft) -> Unit,
    onTaskArchive: (TaskBoardTask) -> Unit,
    onCreateChild: (TaskBoardTask) -> Unit,
    onAddTask: (String) -> Unit
) {
    val filterText = query.trim()
    val filteredTasks = snapshot.tasks.filter { task ->
        val matchMode = mobileTaskMatches(task, snapshot, filterMode, queueModes)
        val folded = filterText.isBlank() && task.parent in collapsedEpics && filterMode == "all"
        matchMode && !folded &&
            (!hideWaiting || snapshot.waitingFor(task).isEmpty() || task.kind == "epic") &&
            (focusedColumn == null || snapshot.displayColumn(task) == focusedColumn) &&
            (!attentionOnly || task.attention.isNotEmpty()) && (filterText.isBlank() ||
            listOf(task.id, task.title, task.description, task.category.orEmpty(), task.parent.orEmpty(),
                task.aiNotes, task.userNotes, task.claimedBy.orEmpty(), task.kind)
                .any { it.contains(filterText, ignoreCase = true) })
    }
    val tasksByColumn = filteredTasks.groupBy { snapshot.displayColumn(it) }
    val visibleColumns = snapshot.columns.filterNot { it.hidden }
        .filter { focusedColumn == null || it.id == focusedColumn }
        .filter { (filterText.isBlank() && filterMode == "all" && !attentionOnly) ||
            tasksByColumn[it.id].orEmpty().isNotEmpty() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (snapshot.warnings.isNotEmpty()) {
            Text(
                text = snapshot.warnings.joinToString(" • "),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (visibleColumns.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isNotBlank() || attentionOnly) "No matching tasks." else "All columns are hidden. Open Columns to show one.")
            }
            return
        }

        if (compactView) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                visibleColumns.forEach { column ->
                    val columnTasks = tasksByColumn[column.id].orEmpty().sortedWith(mobileTaskSort(sortMode))
                    item(key = "heading-${column.id}") {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(top = 11.dp, bottom = 4.dp)
                                .background(TaskBoardColors.surface, RoundedCornerShape(8.dp))
                                .border(1.dp, TaskBoardColors.border, RoundedCornerShape(8.dp))
                                .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).background(parseColumnColor(column.color), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(column.name, style = MaterialTheme.typography.titleSmall,
                                color = TaskBoardColors.text, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            TaskBoardBadge("${columnTasks.size}", parseColumnColor(column.color),
                                TaskBoardColors.card)
                            TextButton(onClick = { onAddTask(column.id) }) {
                                Text("+", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    itemsIndexed(columnTasks, key = { _, task -> "${column.id}-${task.id}" }) { index, task ->
                        val epic = task.parent?.let { id -> snapshot.tasks.firstOrNull { it.id == id && it.kind == "epic" } }
                        if (epic != null && snapshot.displayColumn(epic) != column.id &&
                            columnTasks.getOrNull(index - 1)?.parent != epic.id) {
                            EpicProxy(epic, onTaskClick)
                        }
                        TaskCard(task, snapshot, onTaskClick, onTaskChange, onTaskArchive, onCreateChild,
                            selecting, task.id in selection, onSelect, onStartSelection,
                            task.id in collapsedEpics, onToggleEpic,
                            queueModes[task.id], onQueueToggle)
                    }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleColumns, key = { it.id }) { column ->
                    TaskColumn(
                        column = column,
                        tasks = tasksByColumn[column.id].orEmpty(),
                        snapshot = snapshot,
                        onTaskClick = onTaskClick,
                        onTaskChange = onTaskChange,
                        onTaskArchive = onTaskArchive,
                        onCreateChild = onCreateChild,
                        onAddTask = { onAddTask(column.id) },
                        sortMode = sortMode,
                        queueModes = queueModes,
                        onQueueToggle = onQueueToggle,
                        collapsedEpics = collapsedEpics,
                        selection = selection,
                        selecting = selecting,
                        onSelect = onSelect,
                        onStartSelection = onStartSelection,
                        onToggleEpic = onToggleEpic
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskColumn(
    column: TaskBoardColumn,
    tasks: List<TaskBoardTask>,
    snapshot: TaskBoardSnapshot,
    onTaskClick: (TaskBoardTask) -> Unit,
    onTaskChange: (TaskBoardTask, TaskBoardTaskDraft) -> Unit,
    onTaskArchive: (TaskBoardTask) -> Unit,
    onCreateChild: (TaskBoardTask) -> Unit,
    onAddTask: () -> Unit,
    sortMode: String,
    queueModes: Map<String, String>,
    onQueueToggle: (TaskBoardTask) -> Unit,
    collapsedEpics: Set<String>,
    selection: Set<String>,
    selecting: Boolean,
    onSelect: (TaskBoardTask) -> Unit,
    onStartSelection: (TaskBoardTask) -> Unit,
    onToggleEpic: (TaskBoardTask) -> Unit
) {
    val sortedTasks = remember(tasks, sortMode) { tasks.sortedWith(mobileTaskSort(sortMode)) }
    val tint = parseColumnColor(column.color)

    Card(
        modifier = Modifier
            .width(310.dp)
            .fillMaxHeight()
            .border(1.dp, TaskBoardColors.border, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = TaskBoardColors.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TaskBoardColors.card)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(9.dp).background(tint, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(column.name, fontWeight = FontWeight.Bold, color = TaskBoardColors.text)
                Text("${tasks.size} cards", style = MaterialTheme.typography.labelSmall,
                    color = TaskBoardColors.muted)
            }
            if (column.aiReady) TaskBoardBadge("AI ready", TaskBoardColors.green, TaskBoardColors.greenDark)
            if (column.advisory) TaskBoardBadge("Notice", TaskBoardColors.amber, TaskBoardColors.amberDark)
            TextButton(onClick = onAddTask) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            itemsIndexed(sortedTasks, key = { _, task -> task.id }) { index, task ->
                val epic = task.parent?.let { id -> snapshot.tasks.firstOrNull { it.id == id && it.kind == "epic" } }
                if (epic != null && snapshot.displayColumn(epic) != column.id &&
                    sortedTasks.getOrNull(index - 1)?.parent != epic.id) {
                    EpicProxy(epic, onTaskClick)
                }
                TaskCard(task, snapshot, onTaskClick, onTaskChange, onTaskArchive, onCreateChild,
                    selecting, task.id in selection, onSelect, onStartSelection,
                    task.id in collapsedEpics, onToggleEpic,
                            queueModes[task.id], onQueueToggle)
            }
        }
    }
}

@Composable
private fun EpicProxy(epic: TaskBoardTask, onTaskClick: (TaskBoardTask) -> Unit) {
    TextButton(onClick = { onTaskClick(epic) }, modifier = Modifier.fillMaxWidth()) {
        Text("◇ ${epic.id} · ${epic.title}  ↗", style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    task: TaskBoardTask,
    snapshot: TaskBoardSnapshot,
    onTaskClick: (TaskBoardTask) -> Unit,
    onTaskChange: (TaskBoardTask, TaskBoardTaskDraft) -> Unit,
    onTaskArchive: (TaskBoardTask) -> Unit,
    onCreateChild: (TaskBoardTask) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onSelect: (TaskBoardTask) -> Unit,
    onStartSelection: (TaskBoardTask) -> Unit,
    folded: Boolean,
    onToggleEpic: (TaskBoardTask) -> Unit,
    queueMode: String?,
    onQueueToggle: (TaskBoardTask) -> Unit
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box {
    Card(
        modifier = Modifier.fillMaxWidth()
            .border(
                1.dp,
                when {
                    task.kind == "for_user" || "needs-user" in task.attention -> TaskBoardColors.amber.copy(alpha = .54f)
                    "priority" in task.attention -> TaskBoardColors.amber.copy(alpha = .65f)
                    task.kind == "epic" -> TaskBoardColors.purple.copy(alpha = .53f)
                    else -> TaskBoardColors.border
                },
                RoundedCornerShape(8.dp)
            ).combinedClickable(
                onClick = { if (selecting) onSelect(task) else onTaskClick(task) },
                onLongClick = { if (selecting) onSelect(task) else menuExpanded = true }
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                task.kind == "epic" -> TaskBoardColors.purpleDark.copy(alpha = .65f)
                task.kind == "for_user" || "needs-user" in task.attention ->
                    TaskBoardColors.amberDark.copy(alpha = .82f)
                else -> TaskBoardColors.card
            }
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect(task) },
                    modifier = Modifier.size(30.dp))
                if (task.kind == "epic") TextButton(
                    onClick = { onToggleEpic(task) }, contentPadding = PaddingValues(0.dp)
                ) { Text(if (folded) "▸" else "▾", color = TaskBoardColors.purple) }
                Text(
                    text = if (task.parent != null) "↳ ${task.id}" else task.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = TaskBoardColors.blue,
                    fontWeight = FontWeight.Bold
                )
                if (task.column == "ai-ready" && task.kind == "task" &&
                    task.claimedBy == null && task.completed == null &&
                    snapshot.waitingFor(task).isEmpty() && queueMode != null) {
                    TextButton(onClick = { onQueueToggle(task) },
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)) {
                        Text(if (queueMode == "queue") "↻ Queued" else "○ Queue",
                            color = if (queueMode == "queue") TaskBoardColors.green else TaskBoardColors.muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (task.kind == "epic") {
                    TaskBoardBadge("Epic", TaskBoardColors.purple, TaskBoardColors.purpleDark)
                } else if (task.kind == "for_user" || "needs-user" in task.attention) {
                    TaskBoardBadge("For user", TaskBoardColors.amber, TaskBoardColors.amberDark)
                }
                if ("priority" in task.attention) {
                    TaskBoardBadge("Priority", TaskBoardColors.amber, TaskBoardColors.amberDark)
                }
                if ("blocked" in task.attention) {
                    TaskBoardBadge("Blocked", TaskBoardColors.red, Color(0xFF483035))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(task.title, fontWeight = FontWeight.SemiBold,
                color = TaskBoardColors.text, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (task.kind == "epic") {
                val children = snapshot.epicChildren(task)
                val done = children.count { it.completed != null }
                Text("$done/${children.size} completed · " +
                    (if (folded) "collapsed" else "expanded"),
                    style = MaterialTheme.typography.labelSmall, color = TaskBoardColors.purple)
            }
            if (task.dependsOn.isNotEmpty()) {
                val waiting = snapshot.waitingFor(task)
                Text(if (waiting.isEmpty()) "Prerequisites satisfied" else "Waiting for ${waiting.joinToString()}", style = MaterialTheme.typography.labelSmall, color = if (waiting.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            if (task.relatedTo.isNotEmpty()) {
                Text("Related: ${task.relatedTo.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val meta = listOfNotNull(task.category, task.claimedBy?.let { "claimed: $it" })
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    meta.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            task.questionPrompt?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (task.question?.answer.isNullOrBlank()) "? $it" else "Answered: ${task.question?.answer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.question?.answer.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(text = { Text("Edit task") }, onClick = {
            menuExpanded = false
            onTaskClick(task)
        })
        DropdownMenuItem(text = { Text("Select multiple") }, onClick = {
            menuExpanded = false
            onStartSelection(task)
        })
        DropdownMenuItem(text = { Text("Copy ID") }, onClick = {
            menuExpanded = false
            clipboard.setText(AnnotatedString(task.id))
        })
        if (queueMode != null && task.column == "ai-ready" && task.kind == "task" &&
            task.claimedBy == null && task.completed == null &&
            snapshot.waitingFor(task).isEmpty()) {
            DropdownMenuItem(
                text = { Text(if (queueMode == "queue") "Remove from AI queue" else "Queue for AI") },
                onClick = {
                    menuExpanded = false
                    onQueueToggle(task)
                }
            )
        }
        if (task.kind != "epic") {
            DropdownMenuItem(
                text = { Text(if ("priority" in task.attention) "Remove priority" else "Mark priority") },
                onClick = {
                    menuExpanded = false
                    val attention = if ("priority" in task.attention)
                        task.attention - "priority" else task.attention + "priority"
                    onTaskChange(task, task.toDraft().copy(attention = attention))
                }
            )
            if (task.claimedBy == null) DropdownMenuItem(
                text = { Text(if (task.kind == "for_user") "Convert to task" else "Set For user") },
                onClick = {
                    menuExpanded = false
                    onTaskChange(task, task.toDraft().copy(
                        kind = if (task.kind == "for_user") "task" else "for_user"
                    ))
                }
            )
        } else {
            DropdownMenuItem(text = { Text("Create subtask") }, onClick = {
                menuExpanded = false
                onCreateChild(task)
            })
        }

        if (task.kind != "epic" && task.completed == null) {
            snapshot.columns.firstOrNull { it.terminal }?.let { done ->
                DropdownMenuItem(text = { Text("Move to " + done.name) }, onClick = {
                    menuExpanded = false
                    onTaskChange(task, task.toDraft().copy(column = done.id))
                })
            }
        }
        snapshot.columns.filter { !it.hidden && task.kind != "epic" && it.id != task.column &&
                !(it.terminal && task.completed == null) }
            .forEach { destination ->
                DropdownMenuItem(text = {
                    Text("Move → ${destination.name}" +
                        if (destination.terminal && snapshot.columns.any { col -> col.id == task.column && col.advisory })
                            " (delete notice)" else "")
                }, onClick = {
                    menuExpanded = false
                    onTaskChange(task, task.toDraft().copy(column = destination.id))
                })
            }
        if (task.kind != "epic" || snapshot.epicChildren(task).isEmpty()) {
            DropdownMenuItem(text = { Text("Archive") }, onClick = {
                menuExpanded = false
                onTaskArchive(task)
            })
        }
    }
    }
}

@Composable
private fun TaskEditorDialog(
    original: TaskBoardTask?,
    initialColumn: String,
    initialParent: String?,
    initialCategory: String?,
    initialKind: String,
    snapshot: TaskBoardSnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskBoardTaskDraft) -> Unit,
    inQueue: Boolean = false,
    onSkip: (() -> Unit)? = null,
    onSnooze: (() -> Unit)? = null,
    onCreateSubtask: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null
) {
    val initial = original?.toDraft() ?: TaskBoardTaskDraft(
        title = "",
        column = initialColumn,
        kind = initialKind,
        parent = initialParent,
        category = initialCategory
    )

    var title by remember(original?.revision, initialColumn, initialParent) {
        mutableStateOf(initial.title)
    }
    var column by remember(original?.revision, initialColumn) {
        mutableStateOf(initial.column)
    }
    var kind by remember(original?.revision, initialKind) { mutableStateOf(initial.kind) }
    var dependsOn by remember(original?.revision) { mutableStateOf(initial.dependsOn.joinToString(", ")) }
    var relatedTo by remember(original?.revision) { mutableStateOf(initial.relatedTo.joinToString(", ")) }
    var iconName by remember(original?.revision) { mutableStateOf(initial.icon.orEmpty()) }
    var questionAnswer by remember(original?.revision) { mutableStateOf(initial.question?.answer.orEmpty()) }
    var parent by remember(original?.revision, initialParent) {
        mutableStateOf(initial.parent.orEmpty())
    }
    var category by remember(original?.revision, initialCategory) {
        mutableStateOf(initial.category.orEmpty())
    }
    var attention by remember(original?.revision) {
        mutableStateOf(initial.attention.toSet())
    }
    var questionPrompt by remember(original?.revision) {
        mutableStateOf(initial.question?.prompt.orEmpty())
    }
    var questionOptions by remember(original?.revision) {
        mutableStateOf(initial.question?.options.orEmpty().joinToString("\n"))
    }
    val timeline = remember(original?.revision) { TaskBoardTimeline.parse(initial.description) }
    var description by remember(original?.revision) {
        mutableStateOf(timeline.initial)
    }
    var descriptionUpdate by remember(original?.revision) { mutableStateOf("") }
    var acceptanceCriteria by remember(original?.revision) {
        mutableStateOf(initial.acceptanceCriteria.joinToString("\n"))
    }
    var aiNotes by remember(original?.revision) {
        mutableStateOf(initial.aiNotes)
    }
    // This is a new submission composer; legacy notes are imported to history by the store.
    var userNotes by remember(original?.revision) { mutableStateOf("") }
    var notes by remember(original?.revision) {
        mutableStateOf(initial.notes)
    }

    val columns = snapshot.columns.sortedBy { it.order }
    val selectedColumnName = columns.firstOrNull { it.id == column }?.name ?: column
    val isAdvisory = columns.any { it.id == column && it.advisory }
    val parentOptions = listOf("" to "No parent") +
        snapshot.tasks
            .filter { it.kind == "epic" && it.id != original?.id }
            .map { it.id to "${it.id} — ${it.title}" }
    val parentName = parentOptions.firstOrNull { it.first == parent }?.second ?: "No parent"
    val isQuestion = "question" in attention

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(if (original == null) "New ${when (kind) { "epic" -> "epic"; "for_user" -> "user action"; else -> "task" }}" else original.title)
                original?.let {
                    Text(
                        it.id,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 650.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                if (isAdvisory) {
                    Text("Advisory: temporary instructions shared with agents. Moving to Done deletes this notice; Draft hides it from agents.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    SelectionField(
                        label = "Column",
                        selectedText = selectedColumnName,
                        options = columns.map { it.id to it.name },
                        onSelected = { column = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Advisory content") },
                        minLines = 6
                    )
                } else {
                if (original == null) {
                    SelectionField(
                        label = "Type",
                        selectedText = when (kind) { "epic" -> "Epic · container"; "for_user" -> "For user · human action"; else -> "Task · executable" },
                        options = listOf("task" to "Task · executable", "epic" to "Epic · container", "for_user" to "For user · human action"),
                        onSelected = { kind = it; if (it == "epic") { parent = ""; attention = emptySet(); dependsOn = ""; relatedTo = "" } }
                    )
                } else {
                    Text("Type: ${when (kind) { "epic" -> "Epic"; "for_user" -> "For user"; else -> "Task" }}", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(10.dp))

                if (original != null && kind != "epic") {
                    Spacer(Modifier.height(8.dp))
                    if (original.claimedBy != null) {
                        Text("Claimed by ${original.claimedBy} · release claim before setting For user", style = MaterialTheme.typography.labelSmall)
                    } else {
                        AttentionToggle(
                            label = if (kind == "for_user") "◆ For user (tap to switch to Task)" else "Set For user",
                            active = kind == "for_user",
                            onClick = {
                                kind = if (kind == "for_user") "task" else "for_user"
                                if (kind == "task") attention = attention - "needs-user"
                            }
                        )
                    }
                }
                if (kind != "epic") SelectionField(
                    label = "Column",
                    selectedText = selectedColumnName,
                    options = columns.map { it.id to it.name },
                    onSelected = { column = it }
                )
                Spacer(Modifier.height(10.dp))

                if (kind != "epic") SelectionField(
                    label = "Parent epic",
                    selectedText = parentName,
                    options = parentOptions,
                    onSelected = { parent = it }
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Category") },
                    placeholder = { Text("Optional") },
                    singleLine = true
                )

                if (kind != "epic") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = dependsOn, onValueChange = { dependsOn = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Prerequisites (IDs, comma-separated)") }, placeholder = { Text("WT-0001, T3-0002") }, supportingText = { Text("Hard dependencies must be completed before work can start") })
                    OutlinedTextField(value = relatedTo, onValueChange = { relatedTo = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Related tasks and epics") }, supportingText = { Text("References within this board only") })
                }
                Spacer(Modifier.height(8.dp))
                if (kind != "epic") OutlinedTextField(value = iconName, onValueChange = { iconName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Task icon (optional)") }, singleLine = true)
                if (kind != "epic") {
                Spacer(Modifier.height(14.dp))
                Text("Attention", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AttentionToggle(
                        label = "? Question",
                        active = "question" in attention,
                        onClick = {
                            attention = toggleFlag(attention, "question")
                        }
                    )
                    AttentionToggle(
                        label = "◆ Needs user",
                        active = "needs-user" in attention,
                        onClick = {
                            attention = toggleFlag(attention, "needs-user")
                        }
                    )
                    AttentionToggle(
                        label = "! Priority",
                        active = "priority" in attention,
                        onClick = { attention = toggleFlag(attention, "priority") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AttentionToggle(
                        label = "× Blocked",
                        active = "blocked" in attention,
                        onClick = {
                            attention = toggleFlag(attention, "blocked")
                        }
                    )
                }

                if (isQuestion) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = questionPrompt,
                        onValueChange = { questionPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Question") },
                        minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = questionOptions,
                        onValueChange = { questionOptions = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Question options") },
                        placeholder = { Text("Optional — one per line") },
                        minLines = 2
                    )
                    OutlinedTextField(value = questionAnswer, onValueChange = { questionAnswer = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Your answer") }, minLines = 2)
                }
                }

                Spacer(Modifier.height(12.dp))
                if (original?.userNotes?.isNotBlank() == true) {
                    Text("Previous user note (will be preserved in history on save)", style = MaterialTheme.typography.labelSmall)
                    Text(original.userNotes, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = {
                        userNotes = it
                        if (it.isNotBlank() && kind == "for_user") {
                            kind = "task"
                            attention = attention - "needs-user"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note to AI") },
                    minLines = 3
                )
                Text(
                    "Changing this note marks the task for AI attention.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))
                if (kind != "epic" && aiNotes.isNotBlank()) OutlinedTextField(
                    value = aiNotes,
                    onValueChange = { aiNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Historical AI Notes") },
                    minLines = 3,
                    readOnly = true
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Original description") },
                    minLines = 4
                )
                if (timeline.updates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Description history (read-only)", style = MaterialTheme.typography.labelMedium)
                    timeline.updates.forEach { update ->
                        Text("${update.kind} · ${update.timestamp}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(update.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = descriptionUpdate,
                    onValueChange = { descriptionUpdate = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Add dated description update") },
                    minLines = 2
                )

                Spacer(Modifier.height(10.dp))
                if (kind != "epic") OutlinedTextField(
                    value = acceptanceCriteria,
                    onValueChange = { acceptanceCriteria = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Acceptance criteria") },
                    placeholder = { Text("One criterion per line") },
                    minLines = 3
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes") },
                    minLines = 3
                )
                }

                if (inQueue) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSkip?.invoke() }, enabled = !busy) { Text("Skip") }
                        OutlinedButton(onClick = { onSnooze?.invoke() }, enabled = !busy) { Text("Snooze 24h") }
                    }
                }

                if (original != null && (onCreateSubtask != null || onArchive != null)) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onCreateSubtask?.let {
                            OutlinedButton(onClick = it, enabled = !busy) {
                                Text("+ Subtask")
                            }
                        }
                        onArchive?.let {
                            TextButton(onClick = it, enabled = !busy) {
                                Text("Archive")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val retainedQuestionAnswer = original?.question?.answer
                    val draft = TaskBoardTaskDraft(
                        title = title.trim(),
                        column = column,
                        kind = kind,
                        dependsOn = if (kind == "epic") emptyList() else dependsOn.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
                        relatedTo = if (kind == "epic") emptyList() else relatedTo.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
                        icon = if (kind == "epic") null else iconName.trim().takeIf { it.isNotBlank() },
                        parent = if (kind == "epic") null else parent.takeIf { it.isNotBlank() },
                        category = category.trim().takeIf { it.isNotEmpty() },
                        attention = if (kind == "epic") emptyList() else attention.filterNot {
                            it == "question" && questionAnswer.isNotBlank()
                        },
                        question = if ((isQuestion || original?.question != null) && kind != "epic") {
                            TaskBoardQuestion(
                                prompt = questionPrompt.trim(),
                                options = questionOptions.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() },
                                answer = questionAnswer.trim().ifBlank { retainedQuestionAnswer }
                            )
                        } else null,
                        description = TaskBoardTimeline.append(
                            TaskBoardTimeline.format(description, timeline.updates),
                            listOf("update" to descriptionUpdate)
                        ),
                        acceptanceCriteria = acceptanceCriteria.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                        aiNotes = aiNotes,
                        userNotes = userNotes,
                        notes = notes
                    )
                    onSave(draft)
                },
                enabled = !busy &&
                    title.isNotBlank() &&
                    (!isQuestion || questionPrompt.isNotBlank())
            ) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SelectionField(
    label: String,
    selectedText: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedText)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttentionToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    if (active) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun ColumnManagerDialog(
    snapshot: TaskBoardSnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onRename: (TaskBoardColumn) -> Unit,
    onToggleHidden: (TaskBoardColumn) -> Unit,
    onToggleAiReady: (TaskBoardColumn) -> Unit,
    onToggleQueue: (TaskBoardColumn) -> Unit,
    onMove: (TaskBoardColumn, Int) -> Unit,
    onDelete: (TaskBoardColumn) -> Unit
) {
    val columns = snapshot.columns.sortedBy { it.order }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Columns") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                columns.forEachIndexed { index, column ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(column.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        buildList {
                                            add(column.id)
                                            if (column.terminal) add("terminal")
                                            if (column.aiReady) add("AI ready")
                                            if (column.advisory) add("Advisory")
                                            if (column.hidden) add("hidden")
                                        }.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "●",
                                    color = parseColumnColor(column.color),
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(
                                    onClick = { onMove(column, -1) },
                                    enabled = !busy && index > 0
                                ) { Text("←") }
                                TextButton(
                                    onClick = { onMove(column, 1) },
                                    enabled = !busy && index < columns.lastIndex
                                ) { Text("→") }
                                TextButton(
                                    onClick = { onRename(column) },
                                    enabled = !busy
                                ) { Text("Rename") }
                                TextButton(
                                    onClick = { onToggleHidden(column) },
                                    enabled = !busy
                                ) { Text(if (column.hidden) "Show" else "Hide") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (column.id == "ai-ready") TextButton(
                                    onClick = { onToggleQueue(column) }, enabled = !busy
                                ) { Text(if (column.autoQueue) "✓ Queue all: ON" else "Queue all: OFF") }
                                TextButton(
                                    onClick = { onToggleAiReady(column) },
                                    enabled = !busy &&
                                        column.id != "draft" &&
                                        column.id != "ai-ready" &&
                                        !column.terminal
                                ) {
                                    Text(when {
                                        column.aiReady -> "✓ AI Ready → Advisory"
                                        column.advisory -> "! Advisory → Off"
                                        else -> "Off → AI Ready"
                                    })
                                }
                                TextButton(
                                    onClick = { onDelete(column) },
                                    enabled = !busy && columns.size > 1
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAdd, enabled = !busy) {
                Text("+ Column")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmText: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value) },
                enabled = !busy && value.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DeleteColumnDialog(
    column: TaskBoardColumn,
    snapshot: TaskBoardSnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDelete: (String?) -> Unit
) {
    val occupied = snapshot.tasks.filter { it.column == column.id }
    val remaining = snapshot.columns.filterNot { it.id == column.id }.sortedBy { it.order }
    var destination by remember(column.id, snapshot.boardRevision) {
        mutableStateOf(remaining.firstOrNull()?.id.orEmpty())
    }
    val destinationName = remaining.firstOrNull { it.id == destination }?.name.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${column.name}?") },
        text = {
            Column {
                if (occupied.isEmpty()) {
                    Text("This column is empty.")
                } else {
                    Text(
                        "This column contains ${occupied.size} task(s). " +
                            "Choose where those tasks should move before deleting it."
                    )
                    Spacer(Modifier.height(12.dp))
                    SelectionField(
                        label = "Move tasks to",
                        selectedText = destinationName,
                        options = remaining.map { it.id to it.name },
                        onSelected = { destination = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(destination.takeIf { occupied.isNotEmpty() }) },
                enabled = !busy && (occupied.isEmpty() || destination.isNotBlank())
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel")
            }
        }
    )
}

private fun defaultCreateColumn(snapshot: TaskBoardSnapshot): String =
    snapshot.columns.firstOrNull { it.id == "ai-ready" }?.id
        ?: snapshot.columns.firstOrNull { it.aiReady }?.id
        ?: snapshot.columns.firstOrNull { !it.terminal && it.id != "draft" }?.id
        ?: snapshot.columns.first().id

private fun moveColumn(
    columns: List<TaskBoardColumn>,
    id: String,
    delta: Int
): List<TaskBoardColumn> {
    val ordered = columns.sortedBy { it.order }.toMutableList()
    val index = ordered.indexOfFirst { it.id == id }
    if (index < 0) return columns
    val target = (index + delta).coerceIn(0, ordered.lastIndex)
    if (target == index) return ordered
    val item = ordered.removeAt(index)
    ordered.add(target, item)
    return ordered.mapIndexed { order, column -> column.copy(order = order) }
}

private fun toggleFlag(flags: Set<String>, flag: String): Set<String> =
    if (flag in flags) flags - flag else flags + flag

private fun attentionRank(task: TaskBoardTask): Int = when {
    task.attention.any { it == "question" || it == "needs-user" || it == "user-note" || it == "priority" } || task.kind == "for_user" -> 0
    "blocked" in task.attention -> 2
    else -> 1
}

private fun attentionText(attention: List<String>): String =
    attention.joinToString(" ") {
        when (it) {
            "question" -> "?"
            "needs-user" -> "◆"
            "user-note" -> "✎"
            "blocked" -> "×"
            "priority" -> "!"
            else -> it
        }
    }

private fun parseColumnColor(raw: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(raw)) }
        .getOrDefault(Color.Gray)
