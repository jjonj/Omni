package com.omni.sync.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.taskboard.TaskBoardColumn
import com.omni.sync.data.taskboard.TaskBoardProject
import com.omni.sync.data.taskboard.TaskBoardQuestion
import com.omni.sync.data.taskboard.TaskBoardSnapshot
import com.omni.sync.data.taskboard.TaskBoardStore
import com.omni.sync.data.taskboard.TaskBoardTask
import com.omni.sync.data.taskboard.TaskBoardTaskDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CreateTaskRequest(
    val column: String,
    val parent: String? = null,
    val category: String? = null,
    val kind: String = "task"
)

@Composable
fun TaskBoardScreen(parentPadding: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boardPreferences = remember { context.getSharedPreferences("taskboard_mobile", android.content.Context.MODE_PRIVATE) }
    var selectedRoot by remember { mutableStateOf(boardPreferences.getString("selected_board", TaskBoardStore.defaultRoot().absolutePath) ?: TaskBoardStore.defaultRoot().absolutePath) }
    val store = remember(selectedRoot) { TaskBoardStore(java.io.File(selectedRoot)) }
    var projects by remember { mutableStateOf<List<TaskBoardProject>>(emptyList()) }
    var boardSelectorExpanded by remember { mutableStateOf(false) }
    var compactView by remember { mutableStateOf(true) }
    var queueMode by remember { mutableStateOf(false) }
    var filterMode by remember { mutableStateOf("all") }

    var snapshot by remember { mutableStateOf<TaskBoardSnapshot?>(null) }
    var editingTask by remember { mutableStateOf<TaskBoardTask?>(null) }
    var createTaskRequest by remember { mutableStateOf<CreateTaskRequest?>(null) }
    var showColumns by remember { mutableStateOf(false) }
    var showAddColumn by remember { mutableStateOf(false) }
    var renameColumn by remember { mutableStateOf<TaskBoardColumn?>(null) }
    var deleteColumn by remember { mutableStateOf<TaskBoardColumn?>(null) }
    var loading by remember { mutableStateOf(false) }
    var storageAccess by remember { mutableStateOf(TaskBoardStore.hasStorageAccess()) }
    var error by remember { mutableStateOf<String?>(null) }
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
                error = it.message ?: it.toString()
            }
            loading = false
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

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(parentPadding),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Box {
                            TextButton(onClick = { boardSelectorExpanded = true }, enabled = !loading && editingTask == null && createTaskRequest == null) {
                                Text("${snapshot?.name ?: projects.firstOrNull { it.root.absolutePath == selectedRoot }?.name ?: "Tasks"} ▾", style = MaterialTheme.typography.titleMedium)
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
                                            filterMode = "all"
                                            taskQuery = ""
                                            queueMode = false
                                        }
                                    })
                                }
                            }
                        }
                        Text(
                            text = "Phone · SharedWithPc · ${projects.firstOrNull { it.root.absolutePath == selectedRoot }?.folderName ?: "Tasks"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            snapshot?.let {
                                createTaskRequest = CreateTaskRequest(defaultCreateColumn(it))
                            }
                        },
                        enabled = snapshot != null && !loading
                    ) {
                        Text("New")
                    }
                    TextButton(
                        onClick = { showColumns = true },
                        enabled = snapshot != null && !loading
                    ) {
                        Text("Columns")
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh tasks")
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
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${it.tasks.count { task -> task.kind != "epic" }} tasks · ${it.tasks.count { task -> task.kind == "epic" }} epics", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { queueMode = true; editingTask = it.userQueue().firstOrNull() }, enabled = it.userQueue().isNotEmpty()) {
                                Text("User queue (${it.userQueue().size})")
                            }
                            TextButton(onClick = { compactView = !compactView }) { Text(if (compactView) "Board view" else "List view") }
                        }
                        OutlinedTextField(
                            value = taskQuery,
                            onValueChange = { taskQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            label = { Text("Search tasks by title, ID or description") },
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("all" to "All", "attention" to "Attention", "user" to "For user", "waiting" to "Waiting", "epics" to "Epics").forEach { (id, label) ->
                                FilterChip(selected = filterMode == id, onClick = { filterMode = id }, label = { Text(label) })
                            }
                            TextButton(onClick = { createTaskRequest = CreateTaskRequest(it.columns.firstOrNull { column -> column.id == "draft" }?.id ?: defaultCreateColumn(it), kind = "epic") }) { Text("+ Epic") }
                            if (taskQuery.isNotBlank()) TextButton(onClick = { taskQuery = "" }) { Text("Clear") }
                        }
                        Box(Modifier.weight(1f)) {
                            TaskBoardColumns(
                                snapshot = it,
                                query = taskQuery,
                                attentionOnly = attentionOnly,
                                filterMode = filterMode,
                                compactView = compactView,
                                onTaskClick = { task -> editingTask = task },
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
                        action = { store.createTask(draft) },
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
                onSave = { draft ->
                    mutate(
                        action = { store.updateTask(task, draft) },
                        onSuccess = {
                            editingTask = if (queueMode) board.userQueue().firstOrNull { it.id != task.id } else null
                            if (editingTask == null) queueMode = false
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
                onArchive = if (task.kind == "task" && task.completed != null) {{
                    mutate(
                        action = { store.archiveTask(task) },
                        onSuccess = { editingTask = null }
                    )
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
                    mutate(action = { store.saveColumns(changed, board.boardRevision) })
                },
                onToggleAiReady = { column ->
                    val changed = board.columns.map {
                        if (it.id == column.id) it.copy(aiReady = !it.aiReady) else it
                    }
                    mutate(action = { store.saveColumns(changed, board.boardRevision) })
                },
                onMove = { column, delta ->
                    val changed = moveColumn(board.columns, column.id, delta)
                    mutate(action = { store.saveColumns(changed, board.boardRevision) })
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
                        action = { store.addColumn(name, board.boardRevision) },
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
                        action = { store.saveColumns(changed, board.boardRevision) },
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
                            store.deleteColumn(
                                column.id,
                                destination,
                                board.boardRevision
                            )
                        },
                        onSuccess = { deleteColumn = null }
                    )
                }
            )
        }
    }
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
    onTaskClick: (TaskBoardTask) -> Unit,
    onAddTask: (String) -> Unit
) {
    val filterText = query.trim()
    val filteredTasks = snapshot.tasks.filter { task ->
        val matchMode = when (filterMode) {
            "attention" -> task.attention.isNotEmpty() || task.question != null
            "user" -> task.kind == "for_user" || "needs-user" in task.attention
            "waiting" -> snapshot.waitingFor(task).isNotEmpty() || "blocked" in task.attention
            "epics" -> task.kind == "epic"
            else -> true
        }
        matchMode && (!attentionOnly || task.attention.isNotEmpty()) && (filterText.isBlank() ||
            listOf(task.id, task.title, task.description, task.category.orEmpty(), task.parent.orEmpty(), task.kind)
                .any { it.contains(filterText, ignoreCase = true) })
    }
    val tasksByColumn = filteredTasks.groupBy { snapshot.displayColumn(it) }
    val visibleColumns = snapshot.columns.filterNot { it.hidden }
        .filter { (filterText.isBlank() && filterMode == "all" && !attentionOnly) || tasksByColumn[it.id].orEmpty().isNotEmpty() }

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
                    val columnTasks = tasksByColumn[column.id].orEmpty().sortedWith(
                        compareBy<TaskBoardTask>({ attentionRank(it) }, { it.kind != "epic" }, { it.id })
                    )
                    item(key = "heading-${column.id}") {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(column.name, style = MaterialTheme.typography.titleSmall, color = parseColumnColor(column.color), modifier = Modifier.weight(1f))
                            Text("${columnTasks.size}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { onAddTask(column.id) }) { Text("+ Task") }
                        }
                    }
                    items(columnTasks, key = { task -> "${column.id}-${task.id}" }) { task ->
                        TaskCard(task, snapshot, onTaskClick)
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
                        onAddTask = { onAddTask(column.id) }
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
    onAddTask: () -> Unit
) {
    val sortedTasks = remember(tasks) {
        tasks.sortedWith(
            compareBy<TaskBoardTask>(
                { attentionRank(it) },
                { if (it.parent == null) 0 else 1 },
                { it.id }
            )
        )
    }
    val tint = parseColumnColor(column.color)

    Card(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = tint.copy(alpha = 0.14f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(column.name, fontWeight = FontWeight.Bold)
                Text(
                    "${tasks.size} tasks" + if (column.aiReady) " • AI ready" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onAddTask) {
                Text("+ Task")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(sortedTasks, key = { it.id }) { task ->
                TaskCard(task, snapshot, onTaskClick)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskBoardTask,
    snapshot: TaskBoardSnapshot,
    onTaskClick: (TaskBoardTask) -> Unit
) {
    Card(
        onClick = { onTaskClick(task) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (task.kind) {
                "epic" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                "for_user" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (task.parent != null) "↳ ${task.id}" else task.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (task.kind != "task") {
                    Spacer(Modifier.width(8.dp))
                    Text(if (task.kind == "epic") "EPIC" else "FOR USER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                if (task.attention.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        attentionText(task.attention),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(task.title, fontWeight = FontWeight.SemiBold)
            if (task.kind == "epic") {
                val children = snapshot.epicChildren(task)
                val done = children.count { it.completed != null }
                Text("$done/${children.size} completed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    "? $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
    var description by remember(original?.revision) {
        mutableStateOf(initial.description)
    }
    var acceptanceCriteria by remember(original?.revision) {
        mutableStateOf(initial.acceptanceCriteria.joinToString("\n"))
    }
    var aiNotes by remember(original?.revision) {
        mutableStateOf(initial.aiNotes)
    }
    var userNotes by remember(original?.revision) {
        mutableStateOf(initial.userNotes)
    }
    var notes by remember(original?.revision) {
        mutableStateOf(initial.notes)
    }

    val columns = snapshot.columns.sortedBy { it.order }
    val selectedColumnName = columns.firstOrNull { it.id == column }?.name ?: column
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
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = { userNotes = it },
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
                    label = { Text("Description") },
                    minLines = 4
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
                        attention = if (kind == "epic") emptyList() else attention.toList(),
                        question = if (isQuestion && kind != "epic") {
                            TaskBoardQuestion(
                                prompt = questionPrompt.trim(),
                                options = questionOptions.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() },
                                answer = questionAnswer.trim().ifBlank { retainedQuestionAnswer }
                            )
                        } else null,
                        description = description,
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
                                TextButton(
                                    onClick = { onToggleAiReady(column) },
                                    enabled = !busy &&
                                        column.id != "draft" &&
                                        column.id != "ai-ready" &&
                                        !column.terminal
                                ) {
                                    Text(if (column.aiReady) "AI off" else "AI ready")
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
    task.attention.any { it == "question" || it == "needs-user" || it == "user-note" } -> 0
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
            else -> it
        }
    }

private fun parseColumnColor(raw: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(raw)) }
        .getOrDefault(Color.Gray)
