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
import com.omni.sync.data.repository.SignalRClient
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TextEditorScreen(
    filesViewModel: FilesViewModel,
    signalRClient: SignalRClient,
    onBack: () -> Unit
) {
    val editingFile by filesViewModel.editingFile.collectAsState()
    val editingContent by filesViewModel.editingContent.collectAsState()
    val openFiles by filesViewModel.openFiles.collectAsState()
    val openFileContents by filesViewModel.openFileContents.collectAsState()
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
    var showOpenFiles by remember { mutableStateOf(false) }
    var showCloseAllDialog by remember { mutableStateOf(false) }
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var goToLineInput by remember { mutableStateOf("") }
    
    // Editor features state
    var wordWrap by remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var forceMarkdown by remember { mutableStateOf(false) }
    
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val isMarkdown = (editingFile?.name?.endsWith(".md") == true) || forceMarkdown
    val visualTransformation = remember(colorScheme, isMarkdown, searchQuery) { 
        EditorVisualTransformation(colorScheme, isMarkdown, searchQuery) 
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // --- Swiping between files ---
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { openFiles.size })
    
    // Sync pager with editingFile
    LaunchedEffect(editingFile) {
        val index = openFiles.indexOfFirst { it.path == editingFile?.path }
        if (index != -1 && pagerState.currentPage != index) {
            pagerState.scrollToPage(index)
        }
    }
    
    // Sync editingFile with pager
    LaunchedEffect(pagerState.currentPage) {
        if (openFiles.isNotEmpty() && pagerState.currentPage < openFiles.size) {
            filesViewModel.switchToFile(openFiles[pagerState.currentPage])
        }
    }

    fun scrollToSelection(index: Int) {
        val text = textFieldValue.text
        val lineIndex = text.substring(0, index).count { it == '\n' }
        val lineHeightPx = with(density) { (fontSize * 1.4f).sp.toPx() }
        coroutineScope.launch {
            verticalScrollState.animateScrollTo((lineIndex * lineHeightPx).toInt())
        }
    }

    LaunchedEffect(Unit) {
        filesViewModel.remoteChangeDetected.collect { fileName: String ->
            showRemoteChangeDialog = fileName
        }
    }

    val exitHandler = {
        onBack() // Back button doesn't close files anymore, just goes back to explorer
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        exitHandler()
    }

    // --- Helper Functions for Line Operations ---
    fun getSelectedLinesRange(): Pair<Int, Int> {
        val text = textFieldValue.text
        if (text.isEmpty()) return Pair(0, 0)
        
        val selection = textFieldValue.selection
        val startPos = selection.start.coerceIn(0, text.length)
        val endPos = selection.end.coerceIn(0, text.length)
        
        val actualStart = startPos.coerceAtMost(endPos)
        val actualEnd = endPos.coerceAtLeast(startPos)

        var start = text.lastIndexOf('\n', (actualStart - 1).coerceAtLeast(0))
        start = if (start == -1) 0 else start + 1
        
        var end = text.indexOf('\n', actualEnd)
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
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showOpenFiles = true }) {
                                Text(
                                    text = displayName, 
                                    maxLines = 1, 
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = showOpenFiles, onDismissRequest = { showOpenFiles = false }) {
                                openFiles.forEach { file ->
                                    DropdownMenuItem(
                                        text = { Text(file.name) },
                                        onClick = {
                                            filesViewModel.switchToFile(file)
                                            showOpenFiles = false
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { filesViewModel.closeFile(file) }) {
                                                Icon(Icons.Default.Close, "Close")
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Recent Files...") },
                                    onClick = { showRecentFiles = true; showOpenFiles = false }
                                )
                            }
                            
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
                            Row {
                                IconButton(onClick = { filesViewModel.saveEditingContent() }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Save, contentDescription = "Save")
                                        if (autoSaveEnabled) {
                                            Text("A", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
                                        }
                                    }
                                }
                                IconButton(onClick = { editingFile?.let { filesViewModel.closeFile(it) } }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close File")
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
                                    text = { Text("Go to Line") },
                                    onClick = {
                                        showGoToLineDialog = true
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.TransitEnterexit, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (wordWrap) "Disable Word Wrap" else "Enable Word Wrap") },
                                    onClick = { wordWrap = !wordWrap; showMenu = false },
                                    leadingIcon = { Icon(if (wordWrap) Icons.Default.WrapText else Icons.Default.FormatAlignLeft, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (forceMarkdown) "Disable Markdown" else "Enable Markdown") },
                                    onClick = { forceMarkdown = !forceMarkdown; showMenu = false },
                                    leadingIcon = { Icon(Icons.Default.MenuBook, null) }
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
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Uppercase Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val newText = text.replaceRange(sel.start, sel.end, text.substring(sel.start, sel.end).uppercase())
                                        filesViewModel.updateEditingContent(newText)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Lowercase Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val newText = text.replaceRange(sel.start, sel.end, text.substring(sel.start, sel.end).lowercase())
                                        filesViewModel.updateEditingContent(newText)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Trim Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val newText = text.replaceRange(sel.start, sel.end, text.substring(sel.start, sel.end).trim())
                                        filesViewModel.updateEditingContent(newText)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCut, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort Lines (Selection)") },
                                    onClick = {
                                        modifyLines { lines -> lines.sorted() }
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.SortByAlpha, null) }
                                )
                                HorizontalDivider()
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
                                
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        val text = textFieldValue.text
                                        val currentEnd = textFieldValue.selection.end
                                        var index = text.indexOf(searchQuery, currentEnd, ignoreCase = true)
                                        if (index == -1) index = text.indexOf(searchQuery, 0, ignoreCase = true)
                                        if (index != -1) {
                                            textFieldValue = textFieldValue.copy(selection = TextRange(index, index + searchQuery.length))
                                            scrollToSelection(index)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowDown, "Next")
                                }
                                
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        val text = textFieldValue.text
                                        val currentStart = textFieldValue.selection.start
                                        var index = text.lastIndexOf(searchQuery, currentStart - 1, ignoreCase = true)
                                        if (index == -1) index = text.lastIndexOf(searchQuery, text.length, ignoreCase = true)
                                        if (index != -1) {
                                            textFieldValue = textFieldValue.copy(selection = TextRange(index, index + searchQuery.length))
                                            scrollToSelection(index)
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, "Previous")
                                }

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
                        try {
                            modifyLines { lines -> lines.flatMap { listOf(it, it) } }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.LibraryAdd, "Duplicate Line", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        try {
                            modifyLines { emptyList() }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.Delete, "Delete Line", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        try {
                            val text = textFieldValue.text
                            val range = getSelectedLinesRange()
                            if (range.first > 0) {
                                val lineStartBefore = (text.lastIndexOf('\n', (range.first - 2).coerceAtLeast(0))).let { if (it == -1) 0 else it + 1 }
                                if (lineStartBefore >= 0 && lineStartBefore <= range.first && range.second <= text.length) {
                                    val above = text.substring(lineStartBefore, range.first)
                                    val selected = text.substring(range.first, range.second)
                                    val before = text.substring(0, lineStartBefore)
                                    val after = text.substring(range.second)
                                    val newText = before + selected + (if (selected.endsWith("\n")) "" else "\n") + above.trimEnd('\n') + "\n" + after.trimStart('\n')
                                    filesViewModel.updateEditingContent(newText)
                                    textFieldValue = textFieldValue.copy(
                                        text = newText,
                                        selection = TextRange(lineStartBefore, lineStartBefore + (range.second - range.first))
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.ArrowUpward, "Move Up", modifier = iconModifier)
                    }
                    IconButton(onClick = {
                        try {
                            val text = textFieldValue.text
                            val range = getSelectedLinesRange()
                            if (range.second < text.length) {
                                val nextLineEnd = text.indexOf('\n', (range.second + 1).coerceAtMost(text.length))
                                val endOfNext = if (nextLineEnd == -1) text.length else nextLineEnd
                                
                                if (range.second + 1 <= text.length && endOfNext <= text.length) {
                                    val below = text.substring((range.second + 1).coerceAtMost(text.length), endOfNext)
                                    val selected = text.substring(range.first, range.second)
                                    val before = text.substring(0, range.first)
                                    val after = text.substring(endOfNext)
                                    val newText = before + below + "\n" + selected.trimEnd('\n') + after
                                    filesViewModel.updateEditingContent(newText)
                                    val newStart = range.first + below.length + 1
                                    textFieldValue = textFieldValue.copy(
                                        text = newText,
                                        selection = TextRange(newStart, (newStart + (range.second - range.first)).coerceAtMost(newText.length))
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
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
                    
                    IconButton(onClick = {
                        val text = textFieldValue.text
                        val sel = textFieldValue.selection
                        val selectedText = text.substring(sel.start, sel.end)
                        val newText = text.replaceRange(sel.start, sel.end, "**$selectedText**")
                        filesViewModel.updateEditingContent(newText)
                        textFieldValue = textFieldValue.copy(text = newText, selection = TextRange(sel.start, sel.end + 4))
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.FormatBold, "Bold", modifier = iconModifier)
                    }
                    
                    IconButton(onClick = {
                        val text = textFieldValue.text
                        val sel = textFieldValue.selection
                        val selectedText = text.substring(sel.start, sel.end)
                        val newText = text.replaceRange(sel.start, sel.end, "_${selectedText}_")
                        filesViewModel.updateEditingContent(newText)
                        textFieldValue = textFieldValue.copy(text = newText, selection = TextRange(sel.start, sel.end + 2))
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.FormatItalic, "Italic", modifier = iconModifier)
                    }
                    
                    IconButton(onClick = {
                        val text = textFieldValue.text
                        val sel = textFieldValue.selection
                        val selectedText = text.substring(sel.start, sel.end)
                        val newText = text.replaceRange(sel.start, sel.end, "~~$selectedText~~")
                        filesViewModel.updateEditingContent(newText)
                        textFieldValue = textFieldValue.copy(text = newText, selection = TextRange(sel.start, sel.end + 4))
                    }, modifier = btnModifier) {
                        Icon(Icons.Default.FormatStrikethrough, "Strikethrough", modifier = iconModifier)
                    }
                }
            }
        }
    ) { paddingValues ->
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            val fileAtPage = openFiles[page]
            val contentAtPage = openFileContents[fileAtPage.path] ?: ""
            
            // Note: Each page has its own scroll state and text field value for now
            // To make it fully robust, we'd need to store scroll state and TextFieldValue per-file in ViewModel
            
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            fontSize = (fontSize * zoom).coerceIn(8f, 40f)
                        }
                    }
            ) {
                // Line numbers column
                val lines = contentAtPage.split("\n")
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

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 16.dp)
                        .verticalScroll(verticalScrollState)
                        .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState))
                ) {
                    val currentTFV = if (editingFile?.path == fileAtPage.path) textFieldValue else TextFieldValue(contentAtPage)
                    BasicTextField(
                        value = currentTFV,
                        onValueChange = { newValue -> 
                            if (editingFile?.path == fileAtPage.path) {
                                textFieldValue = newValue
                                if (newValue.text != editingContent) {
                                    filesViewModel.updateEditingContent(newValue.text)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.4).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        visualTransformation = visualTransformation,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }

    if (showGoToLineDialog) {
        AlertDialog(
            onDismissRequest = { showGoToLineDialog = false; goToLineInput = "" },
            title = { Text("Go to Line") },
            text = {
                OutlinedTextField(
                    value = goToLineInput,
                    onValueChange = { input -> 
                        if (input.all { it.isDigit() }) {
                            goToLineInput = input
                        }
                    },
                    label = { Text("Line Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                Button(onClick = {
                    val lineNum = goToLineInput.toIntOrNull() ?: 0
                    if (lineNum > 0) {
                        val text = textFieldValue.text
                        val lines = text.split("\n")
                        if (lineNum <= lines.size) {
                            var charIndex = 0
                            for (i in 0 until (lineNum - 1)) {
                                charIndex += lines[i].length + 1
                            }
                            textFieldValue = textFieldValue.copy(selection = TextRange(charIndex, charIndex))
                            scrollToSelection(charIndex)
                        }
                    }
                    showGoToLineDialog = false
                    goToLineInput = ""
                }) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoToLineDialog = false; goToLineInput = "" }) {
                    Text("Cancel")
                }
            }
        )
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
