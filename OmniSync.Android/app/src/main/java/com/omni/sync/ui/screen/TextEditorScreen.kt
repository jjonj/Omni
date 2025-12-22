package com.omni.sync.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.viewmodel.FilesViewModel
import com.omni.sync.data.model.FileSystemEntry
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.omni.sync.utils.isAudioFile
import com.omni.sync.utils.isImageFile
import com.omni.sync.utils.isPdfFile
import com.omni.sync.utils.isVideoFile

class EditorVisualTransformation(
    private val colorScheme: ColorScheme, 
    private val isMarkdown: Boolean,
    private val searchQuery: String = ""
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            highlightContent(text.text),
            OffsetMapping.Identity
        )
    }

    private fun highlightContent(text: String): AnnotatedString {
        return buildAnnotatedString {
            if (isMarkdown) {
                val lines = text.split("\n")
                lines.forEachIndexed { index, line ->
                    when {
                        line.startsWith("#") -> {
                            withStyle(SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.Bold)) {
                                append(line)
                            }
                        }
                        line.startsWith(">") -> {
                            withStyle(SpanStyle(color = colorScheme.tertiary, fontStyle = FontStyle.Italic)) {
                                append(line)
                            }
                        }
                        line.contains("`") -> {
                            val parts = line.split("`")
                            parts.forEachIndexed { pIndex, part ->
                                if (pIndex % 2 == 1) {
                                    withStyle(SpanStyle(background = colorScheme.surfaceVariant, color = colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)) {
                                        append(part)
                                    }
                                } else {
                                    append(part)
                                }
                            }
                        }
                        else -> append(line)
                    }
                    if (index < lines.size - 1) append("\n")
                }
            } else {
                append(text)
            }

            // Bracket Highlighting
            val brackets = setOf('(', ')', '[', ']', '{', '}')
            text.forEachIndexed { index, char ->
                if (char in brackets) {
                    addStyle(SpanStyle(color = colorScheme.error, fontWeight = FontWeight.ExtraBold), index, index + 1)
                }
            }

            // Apply search highlighting on top
            if (searchQuery.isNotBlank()) {
                var startIndex = 0
                while (startIndex < text.length) {
                    val index = text.indexOf(searchQuery, startIndex, ignoreCase = true)
                    if (index == -1) break
                    addStyle(
                        SpanStyle(background = Color.Yellow.copy(alpha = 0.5f), color = Color.Black),
                        index,
                        index + searchQuery.length
                    )
                    startIndex = index + searchQuery.length
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val editingFile by filesViewModel.editingFile.collectAsState()
    val editingContent by filesViewModel.editingContent.collectAsState()
    val isSaving by filesViewModel.isSaving.collectAsState()
    val hasUnsavedChanges by filesViewModel.hasUnsavedChanges.collectAsState()
    val autoSaveEnabled by filesViewModel.autoSaveEnabled.collectAsState()
    val recentFiles by filesViewModel.recentFiles.collectAsState()
    
    var textFieldValue by remember { mutableStateOf(TextFieldValue(editingContent)) }
    
    // Sync external changes to internal state
    LaunchedEffect(editingContent) {
        if (editingContent != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = editingContent)
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var lastContent by remember { mutableStateOf(editingContent) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showRemoteChangeDialog by remember { mutableStateOf<String?>(null) }
    var showSaveCopyDialog by remember { mutableStateOf(false) }
    var copyFileName by remember { mutableStateOf("") }
    var showRecentFiles by remember { mutableStateOf(false) }
    var showCloseAllDialog by remember { mutableStateOf(false) }
    
    // Editor features state
    var wordWrap by remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(14f) }
    
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val isMarkdown = editingFile?.name?.endsWith(".md") == true
    val visualTransformation = remember(colorScheme, isMarkdown, searchQuery) { 
        EditorVisualTransformation(colorScheme, isMarkdown, searchQuery) 
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        filesViewModel.remoteChangeDetected.collect { fileName: String ->
            showRemoteChangeDialog = fileName
        }
    }

    val exitHandler = {
        if (hasUnsavedChanges) {
            if (autoSaveEnabled) {
                filesViewModel.saveEditingContent()
            } else {
                showUnsavedDialog = true
            }
        } else {
            onBack()
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        exitHandler()
    }

    // --- Helper Functions for Line Operations ---
    fun getSelectedLinesRange(): Pair<Int, Int> {
        val text = textFieldValue.text
        val selection = textFieldValue.selection
        if (text.isEmpty()) return Pair(0, 0)
        var start = text.lastIndexOf('\n', selection.start.coerceAtMost(text.length - 1).coerceAtLeast(0))
        start = if (start == -1) 0 else start + 1
        var end = text.indexOf('\n', selection.end.coerceAtMost(text.length - 1).coerceAtLeast(0))
        end = if (end == -1) text.length else end
        return Pair(start, end)
    }

    fun modifyLines(transform: (List<String>) -> List<String>) {
        val text = textFieldValue.text
        val range = getSelectedLinesRange()
        val before = text.substring(0, range.first)
        val selected = text.substring(range.first, range.second)
        val after = text.substring(range.second)
        
        val lines = selected.split('\n')
        val newLines = transform(lines).joinToString("\n")
        
        val newText = before + newLines + after
        filesViewModel.updateEditingContent(newText)
        textFieldValue = textFieldValue.copy(
            text = newText,
            selection = TextRange(range.first, range.first + newLines.length)
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Box {
                            val fileName = editingFile?.name ?: "No file"
                            val displayName = if (hasUnsavedChanges) "$fileName *" else fileName
                            Text(
                                text = displayName, 
                                maxLines = 1, 
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { showRecentFiles = true }
                            )
                            DropdownMenu(expanded = showRecentFiles, onDismissRequest = { showRecentFiles = false }) {
                                if (recentFiles.isEmpty()) {
                                    DropdownMenuItem(text = { Text("No recent files") }, onClick = { showRecentFiles = false }, enabled = false)
                                }
                                recentFiles.forEach { file ->
                                    DropdownMenuItem(
                                        text = { Text(file.name) },
                                        onClick = {
                                            filesViewModel.openForEditing(file)
                                            showRecentFiles = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.FileOpen, null) }
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = exitHandler) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Box {
                                IconButton(onClick = { filesViewModel.saveEditingContent() }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Save, contentDescription = "Save")
                                        if (autoSaveEnabled) {
                                            Text("A", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Undo (Simple)") },
                                    onClick = {
                                        val temp = editingContent
                                        filesViewModel.updateEditingContent(lastContent)
                                        lastContent = temp
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Undo, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save as Copy") },
                                    onClick = {
                                        copyFileName = editingFile?.name ?: "copy.txt"
                                        showSaveCopyDialog = true
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (wordWrap) "Disable Word Wrap" else "Enable Word Wrap") },
                                    onClick = { wordWrap = !wordWrap; showMenu = false },
                                    leadingIcon = { Icon(if (wordWrap) Icons.Default.WrapText else Icons.Default.FormatAlignLeft, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Insert Link") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val newText = text.replaceRange(sel.start, sel.end, "[title](url)")
                                        filesViewModel.updateEditingContent(newText)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Link, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Toggle AutoSave") },
                                    onClick = { 
                                        filesViewModel.setAutoSaveEnabled(!autoSaveEnabled)
                                        showMenu = false 
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close All") },
                                    onClick = {
                                        showCloseAllDialog = true
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Close, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear All") },
                                    onClick = {
                                        lastContent = editingContent
                                        filesViewModel.updateEditingContent("")
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.DeleteForever, null) }
                                )
                            }
                        }
                    }
                )
                
                if (showSearch) {
                    Surface(tonalElevation = 2.dp) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("Find") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Default.Clear, null)
                                            }
                                        }
                                    }
                                )
                                IconButton(onClick = { showSearch = false }) {
                                    Icon(Icons.Default.Close, null)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    label = { Text("Replace with") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                TextButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        val newText = editingContent.replace(searchQuery, replaceQuery, ignoreCase = true)
                                        filesViewModel.updateEditingContent(newText)
                                    }
                                }) {
                                    Text("Replace All")
                                }
                            }
                        }
                    }
                }

                // Advanced Toolbar
                ScrollableTabRow(
                    selectedTabIndex = -1,
                    divider = {},
                    edgePadding = 8.dp,
                    indicator = {},
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.height(48.dp)
                ) {
                    val iconModifier = Modifier.size(20.dp)
                    val btnModifier = Modifier.padding(horizontal = 4.dp)
                    
                    IconButton(onClick = {
                        modifyLines { lines -> lines.flatMap { listOf(it, it) } }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.LibraryAdd, "Duplicate Line", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        modifyLines { emptyList() }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.Delete, "Delete Line", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        val text = textFieldValue.text
                        val range = getSelectedLinesRange()
                        if (range.first > 0) {
                            val lineStartBefore = text.lastIndexOf('\n', range.first - 2) + 1
                            val above = text.substring(lineStartBefore, range.first)
                            val selected = text.substring(range.first, range.second)
                            val before = text.substring(0, lineStartBefore)
                            val after = text.substring(range.second)
                            val newText = before + selected + "\n" + above.trimEnd('\n') + after
                            filesViewModel.updateEditingContent(newText)
                            textFieldValue = textFieldValue.copy(
                                text = newText,
                                selection = TextRange(lineStartBefore, lineStartBefore + (range.second - range.first))
                            )
                        }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.ArrowUpward, "Move Up", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        val text = textFieldValue.text
                        val range = getSelectedLinesRange()
                        val nextLineEnd = text.indexOf('\n', range.second + 1)
                        val endOfNext = if (nextLineEnd == -1) text.length else nextLineEnd
                        
                        if (range.second < text.length) {
                            val below = text.substring(range.second + 1, endOfNext)
                            val selected = text.substring(range.first, range.second)
                            val before = text.substring(0, range.first)
                            val after = text.substring(endOfNext)
                            val newText = before + below + "\n" + selected + after
                            filesViewModel.updateEditingContent(newText)
                            val newStart = range.first + below.length + 1
                            textFieldValue = textFieldValue.copy(
                                text = newText,
                                selection = TextRange(newStart, newStart + (range.second - range.first))
                            )
                        }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.ArrowDownward, "Move Down", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        val range = getSelectedLinesRange()
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OmniLines", textFieldValue.text.substring(range.first, range.second)))
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.ContentCopy, "Copy Line", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.text?.let { clipText ->
                            val text = textFieldValue.text
                            val sel = textFieldValue.selection
                            filesViewModel.updateEditingContent(text.replaceRange(sel.start, sel.end, clipText.toString()))
                        }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.ContentPaste, "Paste", modifier = iconModifier)
                    }
                }
            }
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .imePadding()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        fontSize = (fontSize * zoom).coerceIn(8f, 40f)
                    }
                }
        ) {
            // Line numbers column
            val lines = editingContent.split("\n")
            val lineCount = lines.size
            val lineNumbers = (1..lineCount).joinToString("\n")
            
            Text(
                text = lineNumbers,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 4.dp)
                    .verticalScroll(verticalScrollState),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.End
                ),
                lineHeight = (fontSize * 1.4).sp
            )

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TextField(
                    value = textFieldValue,
                    onValueChange = { 
                        textFieldValue = it
                        if (it.text != editingContent) {
                            filesViewModel.updateEditingContent(it.text)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState))
                        .verticalScroll(verticalScrollState),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.4).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    visualTransformation = visualTransformation,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    )
                )
            }
        }
    }

    if (showSaveCopyDialog) {
        AlertDialog(
            onDismissRequest = { showSaveCopyDialog = false },
            title = { Text("Save Copy As") },
            text = {
                OutlinedTextField(
                    value = copyFileName,
                    onValueChange = { copyFileName = it },
                    label = { Text("Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (copyFileName.isNotBlank()) {
                        filesViewModel.saveEditingContentAsCopy(copyFileName)
                        showSaveCopyDialog = false
                    }
                }) {
                    Text("Save Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCopyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCloseAllDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllDialog = false },
            title = { Text("Close All Files") },
            text = { Text("Close all recently opened files? Unsaved changes will be lost.") },
            confirmButton = {
                Button(onClick = {
                    filesViewModel.closeAllFiles()
                    showCloseAllDialog = false
                    onBack()
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Close All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. What would you like to do?") },
            confirmButton = {
                Button(onClick = {
                    showUnsavedDialog = false
                    filesViewModel.saveEditingContent()
                }) {
                    Text("Save & Exit")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        filesViewModel.markSaved()
                        onBack()
                    }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showRemoteChangeDialog != null) {
        AlertDialog(
            onDismissRequest = { showRemoteChangeDialog = null },
            title = { Text("Remote Change Detected") },
            text = { Text("The file '${showRemoteChangeDialog}' was modified on the Hub. Your local changes may conflict.") },
            confirmButton = {
                Button(onClick = { showRemoteChangeDialog = null }) {
                    Text("OK")
                }
            }
        )
    }
}
