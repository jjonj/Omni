package com.omni.sync.data.taskboard

import android.os.Build
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlin.random.Random

data class TaskBoardProject(val name: String, val folderName: String, val root: File)

data class TaskBoardColumn(
    val id: String,
    val name: String,
    val order: Int,
    val terminal: Boolean,
    val hidden: Boolean,
    val aiReady: Boolean,
    val color: String,
    val advisory: Boolean = false,
    val autoQueue: Boolean = false,
    val snoozedUntil: String? = null,
    val awakeUntil: String? = null
)

data class TaskBoardQuestion(
    val prompt: String,
    val options: List<String> = emptyList(),
    val answer: String? = null
)

data class TaskBoardTask(
    val id: String,
    val title: String,
    val column: String,
    val kind: String,
    val dependsOn: List<String>,
    val relatedTo: List<String>,
    val icon: String?,
    val parent: String?,
    val category: String?,
    val claimedBy: String?,
    val attention: List<String>,
    val question: TaskBoardQuestion?,
    val created: String?,
    val completed: String?,
    val completedAt: String?,
    val description: String,
    val acceptanceCriteria: List<String>,
    val aiNotes: String,
    val userNotes: String,
    val notes: String,
    val file: File,
    val revision: String
) {
    val questionPrompt: String?
        get() = question?.prompt

    fun toDraft(): TaskBoardTaskDraft = TaskBoardTaskDraft(
        title = title,
        column = column,
        kind = kind,
        dependsOn = dependsOn,
        relatedTo = relatedTo,
        icon = icon,
        parent = parent,
        category = category,
        attention = attention,
        question = question,
        description = description,
        acceptanceCriteria = acceptanceCriteria,
        aiNotes = aiNotes,
        userNotes = userNotes,
        notes = notes
    )
}

data class TaskBoardTaskDraft(
    val title: String,
    val column: String,
    val kind: String = "task",
    val dependsOn: List<String> = emptyList(),
    val relatedTo: List<String> = emptyList(),
    val icon: String? = null,
    val parent: String? = null,
    val category: String? = null,
    val attention: List<String> = emptyList(),
    val question: TaskBoardQuestion? = null,
    val description: String = "",
    val acceptanceCriteria: List<String> = emptyList(),
    val aiNotes: String = "",
    val userNotes: String = "",
    val notes: String = ""
)

data class TaskBoardSnapshot(
    val name: String,
    val schema: Int,
    val taskPrefix: String,
    val epicPrefix: String,
    val columns: List<TaskBoardColumn>,
    val tasks: List<TaskBoardTask>,
    val warnings: List<String>,
    val root: File,
    val boardRevision: String,
    val archivedIds: Set<String>
) {
    fun waitingFor(task: TaskBoardTask): List<String> = task.dependsOn.filter { id ->
        if (id in archivedIds) return@filter false
        val prerequisite = tasks.firstOrNull { it.id == id }
        prerequisite == null || (
            prerequisite.completed.isNullOrBlank() &&
            columns.none { it.id == prerequisite.column && it.terminal }
        )
    }

    fun epicChildren(epic: TaskBoardTask): List<TaskBoardTask> = tasks.filter { it.parent == epic.id }

    fun displayColumn(task: TaskBoardTask): String {
        if (task.kind != "epic") return task.column
        val children = epicChildren(task)
        if (children.isEmpty()) return columns.firstOrNull { it.id == "draft" }?.id ?: task.column
        val inProgress = columns.firstOrNull { it.id == "in-progress" }?.id
        if (inProgress != null && children.any { it.column == inProgress }) return inProgress
        if (children.all { child -> columns.any { it.id == child.column && it.terminal } }) {
            return columns.firstOrNull { it.terminal }?.id ?: task.column
        }
        if (children.all { it.column == "blocked" || waitingFor(it).isNotEmpty() }) {
            return columns.firstOrNull { it.id == "blocked" }?.id ?: task.column
        }
        return children.firstOrNull { child ->
            columns.any { it.id == child.column && !it.terminal && it.id != "draft" }
        }?.column ?: children.first().column
    }
    fun userQueue(): List<TaskBoardTask> = tasks.filter {
        it.completed == null &&
            columns.none { column -> column.id == it.column && column.terminal } &&
            (it.kind == "for_user" || (it.kind == "task" && "needs-user" in it.attention)) &&
            waitingFor(it).isEmpty()
    }.sortedWith(compareByDescending<TaskBoardTask> { it.created.orEmpty() }.thenByDescending { it.id })
}

class TaskBoardConflictException(message: String) : Exception(message)

class TaskBoardStore(
    val root: File = defaultRoot()
) {
    companion object {
        private val taskFileName = Regex("^[A-Za-z][A-Za-z0-9]{1,11}-[0-9]+\\.md$", RegexOption.IGNORE_CASE)
        private val prefixFormat = Regex("^[A-Z][A-Z0-9]{1,11}$")
        private val columnPalette = listOf(
            "#718f78",
            "#7388a8",
            "#8e7da8",
            "#a48669",
            "#6f8679",
            "#8c7d72",
            "#6f8b91",
            "#927b86"
        )

        fun projectsRoot(): File = File(Environment.getExternalStorageDirectory(), "SharedWithPc/Obsidian/jjj")

        fun defaultRoot(): File = File(projectsRoot(), "Wartribes/Tasks")

        fun discoverBoards(): List<TaskBoardProject> {
            val boards = projectsRoot().listFiles().orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { folder ->
                    val board = File(folder, "Tasks/_board.md")
                    if (!board.isFile) return@mapNotNull null
                    val name = runCatching {
                        board.useLines { lines -> lines.firstOrNull { it.startsWith("name: ") }
                            ?.substringAfter(':')?.trim()?.trim('"', '\'') }
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: folder.name
                    TaskBoardProject(name, folder.name, requireNotNull(board.parentFile))
                }
            return preferCanonicalNamedBoards(boards)
                .sortedWith(compareBy<TaskBoardProject> { it.folderName != "Wartribes" }.thenBy { it.name.lowercase(Locale.ROOT) })
        }

        /** Desktop hides the legacy OmniHub/Tasks tab while keeping its files.
         * Mirror that ONE known alias when Omni/Tasks exists. Never silently
         * suppress other same-named boards; their contents might differ.
         */
        internal fun preferCanonicalNamedBoards(boards: List<TaskBoardProject>): List<TaskBoardProject> {
            val hasCanonicalOmni = boards.any {
                it.name.equals("Omni", ignoreCase = true) && it.folderName.equals("Omni", ignoreCase = true)
            }
            return boards.filterNot {
                hasCanonicalOmni && it.name.equals("Omni", ignoreCase = true) &&
                    it.folderName.equals("OmniHub", ignoreCase = true)
            }
        }

        fun hasStorageAccess(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        /** Create a native Markdown board under the same synced projects root. */
        fun createProject(name: String): TaskBoardProject {
            require(hasStorageAccess()) { "Grant All files access before creating a board" }
            val cleanName = name.trim()
            require(cleanName.length in 2..60) { "Board name must contain 2–60 characters" }
            require(!Regex("""[<>:"/\\|?*\\x00-\\x1F]""").containsMatchIn(cleanName) &&
                cleanName !in setOf(".", "..")) { "Board name contains invalid folder characters" }
            val project = File(projectsRoot(), cleanName)
            require(!project.exists()) { "Board or folder already exists: $cleanName" }
            val tasks = File(project, "Tasks")
            check(tasks.mkdirs()) { "Could not create board folder" }

            val letters = cleanName.filter { it.isLetterOrDigit() && it.code < 128 }
                .uppercase(Locale.ROOT).take(10)
            val taskPrefix = (if (letters.firstOrNull()?.isLetter() == true) letters else "TB" + letters)
                .take(10).padEnd(2, 'T')
            val epicPrefix = "${taskPrefix.take(10)}E"
            val board = buildString {
                appendLine("---")
                appendLine("schema: 1")
                appendLine("name: ${JSONObject.quote(cleanName)}")
                appendLine("task_prefix: $taskPrefix")
                appendLine("epic_prefix: $epicPrefix")
                appendLine("columns:")
                listOf(
                    listOf("draft", "Draft", "false", "false", "#8e7da8"),
                    listOf("ai-ready", "AI Ready", "false", "true", "#718f78"),
                    listOf("in-progress", "In Progress", "false", "true", "#7388a8"),
                    listOf("blocked", "Blocked", "false", "false", "#a48669"),
                    listOf("done", "Done", "true", "false", "#6f8679")
                ).forEachIndexed { index, data ->
                    appendLine("- id: ${data[0]}")
                    appendLine("  name: ${JSONObject.quote(data[1])}")
                    appendLine("  order: $index")
                    appendLine("  terminal: ${data[2]}")
                    appendLine("  hidden: false")
                    appendLine("  ai_ready: ${data[3]}")
                    appendLine("  color: '${data[4]}'")
                }
                appendLine("---")
                appendLine()
                appendLine("# $cleanName Task Board")
            }
            try {
                File(tasks, "_board.md").writeText(board)
                File(tasks, "Active").mkdirs()
                File(tasks, "Archive").mkdirs()
                return TaskBoardProject(cleanName, cleanName, tasks)
            } catch (error: Exception) {
                if (tasks.listFiles().isNullOrEmpty()) {
                    tasks.delete()
                    project.delete()
                }
                throw error
            }
        }
    }

    fun load(): TaskBoardSnapshot {
        requireStorageAccess()
        val boardFile = File(root, "_board.md")
        require(boardFile.isFile) {
            "TaskBoard not found at ${boardFile.absolutePath}"
        }

        val boardText = boardFile.readText()
        val (boardFrontmatter, _) = splitFrontmatter(boardText)
        val boardFields = parseTopLevelScalars(boardFrontmatter)
        val columns = parseColumns(boardFrontmatter)
        require(columns.isNotEmpty()) { "TaskBoard has no columns" }

        val warnings = mutableListOf<String>()
        val activeDir = File(root, "Active")
        val tasks = if (!activeDir.isDirectory) {
            warnings += "Active task folder is missing: ${activeDir.absolutePath}"
            emptyList()
        } else {
            activeDir.walkTopDown()
                .filter { it.isFile && taskFileName.matches(it.name) }
                .mapNotNull { file ->
                    runCatching { parseTask(file, file.readText()) }
                        .onFailure { warnings += "${file.name}: ${it.message}" }
                        .getOrNull()
                }
                .sortedBy { it.id }
                .toList()
        }

        return TaskBoardSnapshot(
            name = boardFields["name"] ?: "TaskBoard",
            schema = boardFields["schema"]?.toIntOrNull() ?: 1,
            taskPrefix = boardFields["task_prefix"]?.takeIf { prefixFormat.matches(it) } ?: "WT",
            epicPrefix = boardFields["epic_prefix"]?.takeIf { prefixFormat.matches(it) } ?: "WTE",
            columns = columns.sortedBy { it.order },
            tasks = tasks,
            warnings = warnings,
            root = root,
            boardRevision = revision(boardText),
            archivedIds = File(root, "Archive").takeIf { it.isDirectory }
                ?.walkTopDown()?.filter { it.isFile && taskFileName.matches(it.name) }
                ?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        )
    }

    fun createTask(draft: TaskBoardTaskDraft): TaskBoardTask = withBoardLock {
        val snapshot = load()
        validateDraft(snapshot, draft)

        val prefix = if (draft.kind == "epic") snapshot.epicPrefix else snapshot.taskPrefix
        val next = allTaskFiles()
            .mapNotNull { it.nameWithoutExtension.takeIf { name -> name.startsWith("$prefix-") }
                ?.substringAfterLast('-')?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1
        val id = "%s-%04d".format(Locale.US, prefix, next)
        val activeDir = File(root, "Active").apply { mkdirs() }
        val file = File(activeDir, "$id.md")
        if (file.exists()) throw TaskBoardConflictException("$id already exists")

        val normalizedAttention = normalizeAttention(
            draft.attention,
            oldUserNotes = "",
            newUserNotes = draft.userNotes,
            isNew = true
        )
        val terminal = snapshot.columns.first { it.id == draft.column }.terminal
        val created = today()
        val text = renderNewTask(
            schema = snapshot.schema,
            id = id,
            draft = draft.copy(
                attention = normalizedAttention,
                description = if (draft.kind == "epic") draft.description else TaskBoardTimeline.append(
                    draft.description, listOf("user-note" to draft.userNotes, "legacy-ai" to draft.aiNotes)
                ),
                userNotes = if (draft.kind == "epic") draft.userNotes else "",
                aiNotes = ""
            ),
            created = created,
            completed = if (terminal) created else null
        )

        if (!file.createNewFile()) {
            throw TaskBoardConflictException("$id was created by another writer")
        }
        try {
            file.writeText(text)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        parseTask(file, text)
    }

    fun updateTask(task: TaskBoardTask, draft: TaskBoardTaskDraft): TaskBoardTask = withBoardLock {
        val currentText = task.file.readText()
        if (revision(currentText) != task.revision) {
            throw TaskBoardConflictException(
                "${task.id} changed on disk. Refresh before saving."
            )
        }

        val snapshot = load()
        validateDraft(snapshot, draft, task.id)
        val targetColumn = snapshot.columns.first { it.id == draft.column }
        val currentTask = parseTask(task.file, currentText)
        // Desktop-equivalent advisory lifecycle: Done removes a temporary
        // notice rather than leaving it as an actionable task in the archive.
        if (targetColumn.terminal && snapshot.columns.any { it.id == currentTask.column && it.advisory }) {
            check(task.file.delete()) { "Could not delete completed advisory ${task.id}" }
            return@withBoardLock currentTask.copy(
                column = targetColumn.id,
                completed = today()
            )
        }
        require(currentTask.kind != "epic" || draft.column == currentTask.column) {
            "Epic status is derived from subtasks; it cannot be moved directly"
        }
        require(draft.kind != "for_user" || currentTask.claimedBy == null) {
            "Release the agent claim before converting this task to For user"
        }

        var frontmatter = splitFrontmatter(currentText).first
        frontmatter = replaceTopLevelField(
            frontmatter,
            "title",
            listOf("title: ${yamlQuote(draft.title.trim())}")
        )
        frontmatter = replaceTopLevelField(
            frontmatter,
            "column",
            listOf("column: ${yamlQuote(draft.column)}")
        )
        val effectiveKind = if (draft.kind == "for_user" && targetColumn.terminal) "task" else draft.kind
        frontmatter = replaceTopLevelField(frontmatter, "kind", listOf("kind: $effectiveKind"))
        frontmatter = replaceTopLevelField(frontmatter, "depends_on", renderStringListField("depends_on", draft.dependsOn))
        frontmatter = replaceTopLevelField(frontmatter, "related_to", renderStringListField("related_to", draft.relatedTo))
        frontmatter = replaceTopLevelField(frontmatter, "icon", draft.icon?.let { listOf("icon: ${yamlQuote(it)}") })
        frontmatter = replaceTopLevelField(
            frontmatter,
            "parent",
            draft.parent?.takeIf { it.isNotBlank() }?.let { listOf("parent: ${yamlQuote(it)}") }
        )
        frontmatter = replaceTopLevelField(
            frontmatter,
            "category",
            draft.category?.trim()?.takeIf { it.isNotEmpty() }?.let {
                listOf("category: ${yamlQuote(it)}")
            }
        )

        val completed = when {
            draft.kind == "epic" -> currentTask.completed
            targetColumn.terminal && currentTask.completed.isNullOrBlank() -> today()
            targetColumn.terminal -> currentTask.completed
            else -> null
        }
        frontmatter = replaceTopLevelField(
            frontmatter,
            "completed",
            completed?.let { listOf("completed: $it") }
        )
        frontmatter = replaceTopLevelField(
            frontmatter, "completed_at",
            if (completed == null) null else if (currentTask.completedAt != null) {
                listOf("completed_at: ${yamlQuote(currentTask.completedAt)}")
            } else listOf("completed_at: ${yamlQuote(java.time.Instant.now().toString())}")
        )

        val attention = normalizeAttention(
            draft.attention,
            oldUserNotes = currentTask.userNotes,
            newUserNotes = draft.userNotes,
            isNew = false
        ).filterNot { effectiveKind == "task" && draft.kind == "for_user" && it == "needs-user" }
        frontmatter = replaceTopLevelField(
            frontmatter,
            "attention",
            renderStringListField("attention", attention)
        )

        val question = draft.question
            ?.takeIf { it.prompt.isNotBlank() && ("question" in attention || !it.answer.isNullOrBlank()) }
        val effectiveAttention = if (!question?.answer.isNullOrBlank()) attention - "question" else attention
        frontmatter = replaceTopLevelField(
            frontmatter, "attention", renderStringListField("attention", effectiveAttention)
        )
        frontmatter = replaceTopLevelField(
            frontmatter,
            "question",
            question?.let(::renderQuestionField)
        )

        var body = splitFrontmatter(currentText).second
        // The desktop app stores immutable dated updates inside Description, never in a
        // second competing user/AI notes history. Import legacy notes once on save.
        val additions = buildList {
            if (currentTask.aiNotes.isNotBlank() && draft.kind != "epic") add("legacy-ai" to currentTask.aiNotes)
            if (currentTask.userNotes.isNotBlank()) add("user-note" to currentTask.userNotes)
            if (draft.userNotes.isNotBlank()) add("user-note" to draft.userNotes)
        }
        body = replaceMarkdownSection(body, "## Description", TaskBoardTimeline.append(draft.description, additions))
        if (draft.kind != "epic") {
            body = replaceMarkdownSection(
                body,
                "## Acceptance Criteria",
                draft.acceptanceCriteria
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n") { "- [ ] $it" }
            )
            body = replaceMarkdownSection(body, "## AI Notes", "")
        }
        body = replaceMarkdownSection(body, "## User Notes", "")
        body = replaceMarkdownSection(body, "## Notes", draft.notes.trim())

        val updatedText = "---\n${frontmatter.trimEnd()}\n---\n$body"
        // Check again just before replace in case a non-cooperating sync writer
        // arrived during formatting (the local lock covers Android writers only).
        if (revision(task.file.readText()) != task.revision) {
            throw TaskBoardConflictException("${task.id} changed during save. Refresh before retrying.")
        }
        writeSafely(task.file, updatedText)
        parseTask(task.file, updatedText)
    }

    fun updateTask(
        task: TaskBoardTask,
        newColumn: String,
        newUserNotes: String
    ): TaskBoardTask = updateTask(
        task,
        task.toDraft().copy(column = newColumn, userNotes = newUserNotes)
    )

    fun archiveTask(task: TaskBoardTask) = withBoardLock {
        val currentText = task.file.readText()
        if (revision(currentText) != task.revision) {
            throw TaskBoardConflictException(
                "${task.id} changed on disk. Refresh before archiving."
            )
        }

        val snapshot = load()
        require(snapshot.tasks.none { it.parent == task.id }) {
            "Archive or reassign the epic's active subtasks first"
        }
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val archiveDir = File(root, "Archive/$year").apply { mkdirs() }
        val destination = File(archiveDir, "${task.id}.md")
        if (destination.exists()) {
            throw TaskBoardConflictException("Archive already contains ${task.id}")
        }
        if (!task.file.renameTo(destination)) {
            task.file.copyTo(destination, overwrite = false)
            if (!task.file.delete()) {
                destination.delete()
                error("Could not remove active task after archiving")
            }
        }
    }

    fun addColumn(name: String, expectedRevision: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Column name cannot be empty" }
        val snapshot = load()
        requireBoardRevision(snapshot, expectedRevision)

        val base = trimmed.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "column" }
        var id = base
        var suffix = 2
        val ids = snapshot.columns.map { it.id }.toSet()
        while (id in ids) id = "$base-${suffix++}"

        val usedColors = snapshot.columns.map { it.color }
        val available = columnPalette.filterNot { it in usedColors }
        val color = (available.ifEmpty { columnPalette }).random(Random.Default)
        saveColumns(
            snapshot.columns + TaskBoardColumn(
                id = id,
                name = trimmed,
                order = snapshot.columns.size,
                terminal = false,
                hidden = false,
                aiReady = false,
                color = color
            ),
            expectedRevision
        )
    }

    fun saveColumns(columns: List<TaskBoardColumn>, expectedRevision: String) = withBoardLock {
        require(columns.isNotEmpty()) { "Board needs at least one column" }
        validateColumns(columns)

        val boardFile = File(root, "_board.md")
        val currentText = boardFile.readText()
        if (revision(currentText) != expectedRevision) {
            throw TaskBoardConflictException(
                "Board columns changed on disk. Refresh before saving."
            )
        }

        val normalized = columns
            .sortedBy { it.order }
            .mapIndexed { index, column -> column.copy(order = index) }

        var frontmatter = splitFrontmatter(currentText).first
        val replacement = mutableListOf("columns:")
        normalized.forEach { column ->
            replacement += "- id: ${yamlQuote(column.id)}"
            replacement += "  name: ${yamlQuote(column.name.trim())}"
            replacement += "  order: ${column.order}"
            replacement += "  terminal: ${column.terminal}"
            replacement += "  hidden: ${column.hidden}"
            column.snoozedUntil?.let { replacement += "  snoozed_until: ${yamlQuote(it)}" }
            column.awakeUntil?.let { replacement += "  awake_until: ${yamlQuote(it)}" }
            replacement += "  ai_ready: ${column.aiReady}"
            if (column.advisory) replacement += "  advisory: true"
            if (column.autoQueue) replacement += "  auto_queue: true"
            replacement += "  color: ${yamlQuote(column.color)}"
        }
        frontmatter = replaceTopLevelField(frontmatter, "columns", replacement)
        val body = splitFrontmatter(currentText).second
        writeSafely(boardFile, "---\n${frontmatter.trimEnd()}\n---\n$body")
    }

    fun deleteColumn(
        columnId: String,
        destinationId: String?,
        expectedRevision: String
    ) {
        val snapshot = load()
        requireBoardRevision(snapshot, expectedRevision)
        require(snapshot.columns.size > 1) { "Board needs at least one column" }
        require(snapshot.columns.any { it.id == columnId }) { "Unknown column: $columnId" }

        val remaining = snapshot.columns.filterNot { it.id == columnId }
        val occupied = snapshot.tasks.filter { it.column == columnId }
        if (occupied.isNotEmpty()) {
            val destination = destinationId
                ?: error("Choose a destination for the ${occupied.size} task(s) in this column")
            require(remaining.any { it.id == destination }) {
                "Unknown destination column: $destination"
            }
            occupied.forEach { task ->
                updateTask(task, task.toDraft().copy(column = destination))
            }
        }

        saveColumns(
            remaining.mapIndexed { index, column -> column.copy(order = index) },
            expectedRevision
        )
    }

    private fun requireStorageAccess() {
        if (!hasStorageAccess()) {
            error("Omni needs All files access to read ${root.absolutePath}")
        }
    }

    private fun requireBoardRevision(snapshot: TaskBoardSnapshot, expectedRevision: String) {
        if (snapshot.boardRevision != expectedRevision) {
            throw TaskBoardConflictException(
                "Board columns changed on disk. Refresh before saving."
            )
        }
    }

    private fun validateColumns(columns: List<TaskBoardColumn>) {
        val ids = mutableSetOf<String>()
        columns.forEach { column ->
            require(column.id.isNotBlank() && column.name.isNotBlank()) {
                "Column id/name cannot be empty"
            }
            require(ids.add(column.id)) { "Duplicate column ${column.id}" }
            if (column.id == "ai-ready") {
                require(column.aiReady) { "Canonical AI Ready column must remain AI ready" }
            }
            if (column.aiReady) {
                require(column.id != "draft" && !column.terminal) {
                    "Column ${column.id} cannot be AI ready"
                }
            }
            if (column.advisory) {
                require(!column.aiReady && column.id !in setOf("draft", "ai-ready") && !column.terminal) {
                    "Column ${column.id} cannot be Advisory"
                }
            }
        }
    }

    private fun validateDraft(
        snapshot: TaskBoardSnapshot,
        draft: TaskBoardTaskDraft,
        editingId: String? = null
    ) {
        require(draft.title.trim().isNotEmpty()) { "Task title cannot be empty" }
        require(draft.kind in setOf("task", "epic", "for_user")) { "Unsupported task kind" }
        require(draft.dependsOn.distinct().size == draft.dependsOn.size) { "Duplicate dependencies" }
        require(draft.relatedTo.distinct().size == draft.relatedTo.size) { "Duplicate relationships" }
        if (draft.kind == "epic") {
            require(draft.parent == null && draft.dependsOn.isEmpty() && draft.relatedTo.isEmpty()) {
                "Epics cannot have parents or dependencies"
            }
            require(draft.attention.isEmpty() && draft.question == null) { "Epics cannot have attention or questions" }
        }
        draft.dependsOn.forEach { id ->
            require(id != editingId && (id in snapshot.archivedIds || snapshot.tasks.any { it.id == id && it.kind != "epic" })) {
                "Dependency must reference an executable task in this board: $id"
            }
            if (editingId != null) {
                require(!dependsTransitively(snapshot, id, editingId)) {
                    "Hard dependency cycle: $editingId -> $id -> ... -> $editingId"
                }
            }
        }
        require(draft.kind != "epic" || draft.icon == null) { "Epics cannot have icons" }
        draft.relatedTo.forEach { id ->
            require(id != editingId && (id in snapshot.archivedIds || snapshot.tasks.any { it.id == id })) { "Unknown related task: $id" }
        }
        require(snapshot.columns.any { it.id == draft.column }) {
            "Unknown column: ${draft.column}"
        }
        draft.parent?.takeIf { it.isNotBlank() }?.let { parent ->
            require(parent != editingId) { "A task cannot be its own parent" }
            val existing = editingId?.let { id -> snapshot.tasks.firstOrNull { it.id == id } }
            require(existing?.parent == parent || snapshot.tasks.any { it.id == parent && it.kind == "epic" }) {
                "Parent must be an epic in this board: $parent"
            }
            if (editingId != null) {
                require(!wouldCreateParentCycle(snapshot, editingId, parent)) {
                    "Parent selection would create a task cycle"
                }
            }
        }
        val priorColumn = editingId?.let { id -> snapshot.tasks.firstOrNull { it.id == id }?.column }
        val movingIntoWork = draft.kind != "epic" && priorColumn != draft.column &&
            (draft.column == "in-progress" || snapshot.columns.any { it.id == draft.column && it.terminal })
        if (movingIntoWork) {
            val waiting = draft.dependsOn.filter { id ->
                id !in snapshot.archivedIds && snapshot.tasks.firstOrNull { it.id == id }?.completed.isNullOrBlank()
            }
            require(waiting.isEmpty()) { "Blocked by: ${waiting.joinToString()}" }
        }
        if ("question" in draft.attention) {
            require(!draft.question?.prompt.isNullOrBlank()) {
                "Question attention requires a question prompt"
            }
        }
    }

    private fun dependsTransitively(snapshot: TaskBoardSnapshot, start: String, target: String): Boolean {
        val byId = snapshot.tasks.associateBy { it.id }
        val pending = ArrayDeque<String>().apply { add(start) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current == target) return true
            if (visited.add(current)) byId[current]?.dependsOn?.forEach(pending::add)
        }
        return false
    }

    private fun wouldCreateParentCycle(
        snapshot: TaskBoardSnapshot,
        taskId: String,
        parentId: String
    ): Boolean {
        val parents = snapshot.tasks.associate { it.id to it.parent }
        var current: String? = parentId
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current == taskId) return true
            current = parents[current]
        }
        return false
    }

    private fun normalizeAttention(
        attention: List<String>,
        oldUserNotes: String,
        newUserNotes: String,
        isNew: Boolean
    ): List<String> {
        val normalized = attention
            .filter { it in setOf("question", "needs-user", "user-note", "blocked", "priority") }
            .distinct()
            .toMutableList()

        if (isNew || oldUserNotes != newUserNotes) {
            normalized.removeAll { it == "user-note" }
            if (newUserNotes.isNotBlank() || oldUserNotes.isNotBlank()) normalized += "user-note"
        }
        return normalized
    }

    private fun allTaskFiles(): Sequence<File> = sequence {
        listOf(File(root, "Active"), File(root, "Archive")).forEach { directory ->
            if (directory.isDirectory) {
                yieldAll(
                    directory.walkTopDown()
                        .filter {
                            it.isFile &&
                                taskFileName.matches(it.name)
                        }
                )
            }
        }
    }

    private fun parseColumns(frontmatter: String): List<TaskBoardColumn> {
        val result = mutableListOf<TaskBoardColumn>()
        var inColumns = false
        var current: MutableMap<String, String?>? = null

        fun flush() {
            val map = current ?: return
            val id = map["id"] ?: return
            result += TaskBoardColumn(
                id = id,
                name = map["name"] ?: id,
                order = map["order"]?.toIntOrNull() ?: result.size,
                terminal = map["terminal"]?.toBoolean() ?: false,
                hidden = map["hidden"]?.toBoolean() ?: false,
                aiReady = map["ai_ready"]?.toBoolean() ?: false,
                advisory = map["advisory"]?.toBoolean() ?: false,
                autoQueue = map["auto_queue"]?.toBoolean() ?: false,
                color = map["color"] ?: "#777777",
                snoozedUntil = map["snoozed_until"],
                awakeUntil = map["awake_until"]
            )
        }

        for (line in frontmatter.lines()) {
            when {
                line == "columns:" -> {
                    inColumns = true
                    continue
                }
                inColumns && line.startsWith("- ") -> {
                    flush()
                    current = mutableMapOf()
                    parseKeyValue(line.removePrefix("- "))?.let { (key, value) ->
                        current?.set(key, value)
                    }
                }
                inColumns && line.startsWith("  ") -> {
                    parseKeyValue(line.trimStart())?.let { (key, value) ->
                        current?.set(key, value)
                    }
                }
                inColumns && line.isNotBlank() && !line.first().isWhitespace() -> {
                    flush()
                    current = null
                    inColumns = false
                }
            }
        }
        flush()
        return result
    }

    private fun parseTask(file: File, text: String): TaskBoardTask {
        val (frontmatter, body) = splitFrontmatter(text)
        val fields = parseTopLevelScalars(frontmatter)
        val id = fields["id"] ?: error("missing id")
        val title = fields["title"] ?: error("missing title")
        val sections = parseSections(body)
        val questionPrompt = parseNestedScalar(frontmatter, "question", "prompt")

        return TaskBoardTask(
            id = id,
            title = title,
            column = fields["column"] ?: error("missing column"),
            kind = fields["kind"] ?: "task",
            dependsOn = parseStringList(frontmatter, "depends_on"),
            relatedTo = parseStringList(frontmatter, "related_to"),
            icon = fields["icon"],
            parent = fields["parent"],
            category = fields["category"],
            claimedBy = fields["claimed_by"],
            attention = parseStringList(frontmatter, "attention"),
            question = questionPrompt?.let {
                TaskBoardQuestion(
                    prompt = it,
                    options = parseNestedStringList(frontmatter, "question", "options"),
                    answer = parseNestedScalar(frontmatter, "question", "answer")
                )
            },
            created = fields["created"],
            completed = fields["completed"],
            completedAt = fields["completed_at"],
            description = sections["Description"].orEmpty(),
            acceptanceCriteria = parseCriteria(sections["Acceptance Criteria"].orEmpty()),
            aiNotes = sections["AI Notes"].orEmpty(),
            userNotes = sections["User Notes"].orEmpty(),
            notes = sections["Notes"].orEmpty(),
            file = file,
            revision = revision(text)
        )
    }

    private fun renderNewTask(
        schema: Int,
        id: String,
        draft: TaskBoardTaskDraft,
        created: String,
        completed: String?
    ): String {
        val lines = mutableListOf(
            "---",
            "schema: $schema",
            "id: ${yamlQuote(id)}",
            "title: ${yamlQuote(draft.title.trim())}",
            "column: ${yamlQuote(draft.column)}",
            "kind: ${draft.kind}"
        )
        renderStringListField("depends_on", draft.dependsOn)?.let(lines::addAll)
        renderStringListField("related_to", draft.relatedTo)?.let(lines::addAll)
        draft.icon?.let { lines += "icon: ${yamlQuote(it)}" }
        draft.parent?.takeIf { it.isNotBlank() }?.let {
            lines += "parent: ${yamlQuote(it)}"
        }
        draft.category?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lines += "category: ${yamlQuote(it)}"
        }
        renderStringListField("attention", draft.attention)?.let(lines::addAll)
        draft.question
            ?.takeIf { "question" in draft.attention && it.prompt.isNotBlank() }
            ?.let { lines += renderQuestionField(it) }
        lines += "created: $created"
        completed?.let {
            lines += "completed: $it"
            lines += "completed_at: ${yamlQuote(java.time.Instant.now().toString())}"
        }
        lines += "---"
        lines += ""
        lines += "## Description"
        lines += ""
        if (draft.description.isNotBlank()) lines += draft.description.trim().lines()
        lines += ""
        lines += "## Acceptance Criteria"
        lines += ""
        draft.acceptanceCriteria.takeIf { draft.kind != "epic" }.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { lines += "- [ ] $it" }
        lines += ""
        lines += "## AI Notes"
        lines += ""
        if (draft.aiNotes.isNotBlank()) lines += draft.aiNotes.trim().lines()
        lines += ""
        lines += "## User Notes"
        lines += ""
        if (draft.userNotes.isNotBlank()) lines += draft.userNotes.trim().lines()
        lines += ""
        lines += "## Notes"
        lines += ""
        if (draft.notes.isNotBlank()) lines += draft.notes.trim().lines()
        lines += ""
        return lines.joinToString("\n")
    }

    private fun renderStringListField(key: String, values: List<String>): List<String>? =
        if (values.isEmpty()) null
        else listOf("$key:") + values.map { "- ${yamlQuote(it)}" }

    private fun renderQuestionField(question: TaskBoardQuestion): List<String> {
        val lines = mutableListOf(
            "question:",
            "  prompt: ${yamlQuote(question.prompt.trim())}"
        )
        if (question.options.isNotEmpty()) {
            lines += "  options:"
            question.options
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { lines += "  - ${yamlQuote(it)}" }
        }
        question.answer?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lines += "  answer: ${yamlQuote(it)}"
        }
        return lines
    }

    private fun splitFrontmatter(text: String): Pair<String, String> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "missing YAML frontmatter" }
        val rest = normalized.substring(4)
        val marker = "\n---\n"
        val end = rest.indexOf(marker)
        require(end >= 0) { "unterminated YAML frontmatter" }
        return rest.substring(0, end) to rest.substring(end + marker.length)
    }

    private fun parseTopLevelScalars(frontmatter: String): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        frontmatter.lines().forEach { line ->
            if (line.isBlank() || line.first().isWhitespace() || line.startsWith("- ")) return@forEach
            parseKeyValue(line)?.let { (key, value) -> result[key] = value }
        }
        return result
    }

    private fun parseKeyValue(line: String): Pair<String, String?>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val key = line.substring(0, colon).trim()
        val value = yamlScalar(line.substring(colon + 1).trim())
        return key to value
    }

    private fun yamlScalar(raw: String): String? {
        if (raw.isBlank() || raw == "null" || raw == "~") return null
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            return raw.substring(1, raw.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
        }
        return raw
    }

    private fun yamlQuote(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

    private fun parseStringList(frontmatter: String, key: String): List<String> {
        val lines = frontmatter.lines()
        val start = lines.indexOfFirst { it == "$key:" }
        if (start < 0) return emptyList()
        val result = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (isTopLevelKey(line)) break
            if (line.startsWith("- ")) {
                yamlScalar(line.removePrefix("- ").trim())?.let(result::add)
            }
        }
        return result
    }

    private fun parseNestedStringList(
        frontmatter: String,
        key: String,
        nestedKey: String
    ): List<String> {
        val lines = frontmatter.lines()
        val start = lines.indexOfFirst { it == "$key:" }
        if (start < 0) return emptyList()
        var inList = false
        val result = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (isTopLevelKey(line)) break
            val trimmed = line.trimStart()
            if (trimmed == "$nestedKey:") {
                inList = true
                continue
            }
            if (inList) {
                if (trimmed.startsWith("- ")) {
                    yamlScalar(trimmed.removePrefix("- ").trim())?.let(result::add)
                } else if (trimmed.isNotBlank() && ':' in trimmed) {
                    break
                }
            }
        }
        return result
    }

    private fun parseNestedScalar(frontmatter: String, key: String, nestedKey: String): String? {
        val lines = frontmatter.lines()
        val start = lines.indexOfFirst { it == "$key:" }
        if (start < 0) return null
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (isTopLevelKey(line)) break
            val trimmed = line.trimStart()
            if (trimmed.startsWith("$nestedKey:")) {
                return yamlScalar(trimmed.substringAfter(':').trim())
            }
        }
        return null
    }

    private fun parseSections(body: String): Map<String, String> {
        val known = setOf("Description", "Acceptance Criteria", "AI Notes", "User Notes", "Notes")
        val buffers = known.associateWith { mutableListOf<String>() }
        var section: String? = null
        for (line in body.replace("\r\n", "\n").lines()) {
            if (line.startsWith("## ")) {
                val heading = line.removePrefix("## ").trim()
                section = heading.takeIf { it in known }
                continue
            }
            section?.let { buffers.getValue(it).add(line) }
        }
        return buffers.mapValues { (_, lines) -> lines.joinToString("\n").trim() }
    }

    private fun parseCriteria(text: String): List<String> =
        text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) null
            else trimmed
                .removePrefix("- [ ] ")
                .removePrefix("- [x] ")
                .removePrefix("- [X] ")
                .removePrefix("- ")
        }

    private fun replaceTopLevelField(
        frontmatter: String,
        key: String,
        replacement: List<String>?
    ): String {
        val lines = frontmatter.lines().toMutableList()
        val start = lines.indexOfFirst {
            isTopLevelKey(it) && it.substringBefore(':').trim() == key
        }
        if (start >= 0) {
            var end = start + 1
            while (end < lines.size && !isTopLevelKey(lines[end])) end++
            repeat(end - start) { lines.removeAt(start) }
            replacement?.let { lines.addAll(start, it) }
        } else if (replacement != null) {
            lines.addAll(replacement)
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun isTopLevelKey(line: String): Boolean =
        line.isNotBlank() &&
            !line.first().isWhitespace() &&
            !line.startsWith("- ") &&
            ':' in line

    private fun replaceMarkdownSection(body: String, heading: String, content: String): String {
        val lines = body.replace("\r\n", "\n").lines().toMutableList()
        val start = lines.indexOfFirst { it.trim() == heading }
        if (start >= 0) {
            var end = start + 1
            while (end < lines.size && !lines[end].trimStart().startsWith("## ")) end++
            val replacement = mutableListOf(heading, "")
            if (content.isNotBlank()) replacement += content.lines()
            replacement += ""
            repeat(end - start) { lines.removeAt(start) }
            lines.addAll(start, replacement)
        } else {
            while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) lines += ""
            lines += heading
            lines += ""
            if (content.isNotBlank()) lines += content.lines()
            lines += ""
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    // Serialize Android writers against other Android processes. The desktop works
    // on a DIFFERENT synced path, so this does not replace revision checks or
    // guarantee a distributed transaction against simultaneous desktop sync.
    private fun <T> withBoardLock(action: () -> T): T {
        val lockPath = File(root, ".omni-android-taskboard.lock")
        RandomAccessFile(lockPath, "rw").use { handle ->
            val lock = handle.channel.lock()
            try { return action() } finally { lock.release() }
        }
    }

    private fun writeSafely(file: File, text: String) {
        val temp = File(file.parentFile, ".${file.name}.omni.tmp")
        temp.writeText(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                return
            } catch (_: Exception) {
                // Fall through to normal replacement for filesystems without atomic moves.
            }
        }
        temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    private fun revision(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
