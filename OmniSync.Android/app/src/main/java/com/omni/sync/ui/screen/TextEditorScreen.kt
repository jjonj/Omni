package com.omni.sync.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

class EditorVisualTransformation(

    private val colorScheme: ColorScheme, 

    private val isMarkdown: Boolean,

    private val fontSize: Float,

    private val searchQuery: String = "",

    private val currentSelection: TextRange = TextRange.Zero

) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {

        val highlighted = highlightContent(text.text)

        return TransformedText(

            highlighted,

            OffsetMapping.Identity

        )

    }



    private fun highlightContent(text: String): AnnotatedString {

        return buildAnnotatedString {

            append(text)

            

            if (isMarkdown) {

                // Find current cursor line

                val cursorLineStart = if (currentSelection.start <= text.length) {

                    text.lastIndexOf('\n', currentSelection.start - 1).let { if (it == -1) 0 else it + 1 }

                } else 0

                val cursorLineEnd = text.indexOf('\n', cursorLineStart).let { if (it == -1) text.length else it }



                // Headers (#, ##, ###, ...)

                val headerRegex = Regex("^(#+)(\\s*)", RegexOption.MULTILINE)

                headerRegex.findAll(text).forEach { result ->

                    val start = result.range.first

                    val end = result.range.last + 1

                    

                    if (start < cursorLineStart || start > cursorLineEnd) {

                        // Hide markers if not on cursor line

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), start, end)

                    } else {

                        // Highlight markers on cursor line

                        val level = result.groupValues[1].length

                        val color = when (level) {

                            1 -> colorScheme.primary

                            2 -> colorScheme.secondary

                            3 -> colorScheme.tertiary

                            else -> colorScheme.outline

                        }

                        addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), start, end)

                    }

                }



                // Bold (**bold** or __bold__)

                val boldRegex = Regex("(\\*\\*|__)(.*?)\\1")

                boldRegex.findAll(text).forEach { result ->

                    val markerStart1 = result.range.first

                    val markerEnd1 = result.range.first + 2

                    val markerStart2 = result.range.last - 1

                    val markerEnd2 = result.range.last + 1

                    

                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), result.range.first, result.range.last + 1)

                    

                    if (markerStart1 < cursorLineStart || markerStart1 > cursorLineEnd) {

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), markerStart1, markerEnd1)

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), markerStart2, markerEnd2)

                    }

                }



                // Italic (*italic* or _italic_)

                val italicRegex = Regex("(?<![\\*_])(\\*|_)(?![\\*_])(.*?)\\1(?![\\*_])")

                italicRegex.findAll(text).forEach { result ->

                    val markerStart = result.range.first

                    val markerEnd = result.range.first + 1

                    val markerStart2 = result.range.last

                    val markerEnd2 = result.range.last + 1

                    

                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), result.range.first, result.range.last + 1)

                    

                    if (markerStart < cursorLineStart || markerStart > cursorLineEnd) {

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), markerStart, markerEnd)

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), markerStart2, markerEnd2)

                    }

                }



                // Strikethrough (~~strike~~)

                val strikeRegex = Regex("~~(.*?)~~")

                strikeRegex.findAll(text).forEach { result ->

                    val markerStart1 = result.range.first

                    val markerEnd1 = result.range.first + 2

                    val markerStart2 = result.range.last - 1

                    val markerEnd2 = result.range.last + 1

                    

                    addStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), result.range.first, result.range.last + 1)

                    

                    if (markerStart1 < cursorLineStart || markerStart1 > cursorLineEnd) {

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), markerStart1, markerEnd1)

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), markerStart2, markerEnd2)

                    }

                }

                

                // Header text size/color (separate from markers)

                val headerContentRegex = Regex("^(#+)(\\s*)(.*)$", RegexOption.MULTILINE)

                headerContentRegex.findAll(text).forEach { result ->

                    val level = result.groupValues[1].length

                    val color = when (level) {

                        1 -> colorScheme.primary

                        2 -> colorScheme.secondary

                        3 -> colorScheme.tertiary

                        else -> colorScheme.outline

                    }

                    val contentStart = result.groups[3]?.range?.first ?: return@forEach

                    val contentEnd = result.groups[3]?.range?.last?.plus(1) ?: return@forEach

                    

                    addStyle(

                        SpanStyle(color = color, fontWeight = FontWeight.Bold, fontSize = if (level == 1) (fontSize * 1.2).sp else fontSize.sp),

                        contentStart,

                        contentEnd

                    )

                }



                // Links ([title](url))

                val linkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")

                linkRegex.findAll(text).forEach { result ->

                    addStyle(SpanStyle(color = Color(0xFF64B5F6), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), result.range.first, result.range.last + 1)

                    

                    if (result.range.first < cursorLineStart || result.range.first > cursorLineEnd) {

                        // Hide [ ] and (url)

                        val titleStart = result.groups[1]?.range?.first ?: return@forEach

                        val titleEnd = result.groups[1]?.range?.last?.plus(1) ?: return@forEach

                        val urlStart = result.groups[2]?.range?.first ?: return@forEach

                        val urlEnd = result.groups[2]?.range?.last?.plus(1) ?: return@forEach

                        

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), result.range.first, titleStart)

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), titleEnd, result.range.last + 1)

                    }

                }



                // Lists (-, *, 1.)

                val listRegex = Regex("^(\\s*[-*+]|\\s*\\d+\\.)\\s", RegexOption.MULTILINE)

                listRegex.findAll(text).forEach { result ->

                    addStyle(SpanStyle(color = colorScheme.tertiary, fontWeight = FontWeight.Bold), result.range.first, result.range.last + 1)

                }



                // Blockquotes (>)

                val quoteRegex = Regex("^>.*$", RegexOption.MULTILINE)

                quoteRegex.findAll(text).forEach { result ->

                    addStyle(SpanStyle(color = colorScheme.outline, fontStyle = FontStyle.Italic), result.range.first, result.range.last + 1)

                }



                // Code Blocks (```)

                val codeBlockRegex = Regex("```[\\s\\S]*?```")

                codeBlockRegex.findAll(text).forEach { result ->

                    addStyle(SpanStyle(background = colorScheme.surfaceVariant.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace), result.range.first, result.range.last + 1)

                }



                // Horizontal Rule (---)

                val hrRegex = Regex("^---$", RegexOption.MULTILINE)

                hrRegex.findAll(text).forEach { result ->

                    addStyle(SpanStyle(color = colorScheme.outline, fontWeight = FontWeight.Bold), result.range.first, result.range.last + 1)

                }



                // Inline Code (`)

                val codeRegex = Regex("`(.*?)`")

                codeRegex.findAll(text).forEach { result ->

                    addStyle(SpanStyle(background = colorScheme.surfaceVariant, fontFamily = FontFamily.Monospace), result.range.first, result.range.last + 1)

                    

                    if (result.range.first < cursorLineStart || result.range.first > cursorLineEnd) {

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), result.range.first, result.range.first + 1)

                        addStyle(SpanStyle(fontSize = 0.sp, color = Color.Transparent), result.range.last, result.range.last + 1)

                    }

                }

            }

 else {
                // Non-markdown: just append was already done
            }

            // Bracket Highlighting (always, even if not markdown)
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
                    
                    val isCurrentMatch = index == currentSelection.start && (index + searchQuery.length) == currentSelection.end
                    val highlightColor = if (isCurrentMatch) Color.Cyan else Color.Yellow.copy(alpha = 0.5f)
                    
                    addStyle(
                        SpanStyle(background = highlightColor, color = Color.Black),
                        index,
                        index + searchQuery.length
                    )
                    startIndex = index + searchQuery.length
                }
            }
        }
    }
}

class CustomTextToolbar(
    val onBold: () -> Unit,
    val onItalic: () -> Unit,
    val onEmoji: (String) -> Unit,
    val onHide: () -> Unit = {}
) : TextToolbar {
    var currentRect by mutableStateOf<Rect?>(null)
    var onCopy by mutableStateOf<(() -> Unit)?>(null)
    var onPaste by mutableStateOf<(() -> Unit)?>(null)
    var onCut by mutableStateOf<(() -> Unit)?>(null)
    var onSelectAll by mutableStateOf<(() -> Unit)?>(null)
    var visible by mutableStateOf(false)

    override val status: TextToolbarStatus get() = if (visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopy: (() -> Unit)?,
        onPaste: (() -> Unit)?,
        onCut: (() -> Unit)?,
        onSelectAll: (() -> Unit)?
    ) {
        this.currentRect = rect
        this.onCopy = onCopy
        this.onPaste = onPaste
        this.onCut = onCut
        this.onSelectAll = onSelectAll
        this.visible = true
    }

    override fun hide() {
        if (visible) {
            visible = false
            onHide()
        }
    }
}

@Composable
fun CustomTextSelectionMenu(toolbar: CustomTextToolbar) {
    if (toolbar.visible && toolbar.currentRect != null) {
        val rect = toolbar.currentRect!!
        
        // Calculate position
        // The rect provided by BasicTextField is usually local to its bounds.
        // If we render the Popup inside the same container as BasicTextField,
        // the offset should match.
        
        val showBelow = rect.top < 150 // If too close to top, show below
        val offsetX = rect.center.x - 150 // Try to center horizontally (approx width 300)
        val offsetY = if (showBelow) rect.bottom + 10 else rect.top - 120 
        
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(offsetX.roundToInt().coerceAtLeast(0), offsetY.roundToInt()),
            onDismissRequest = { toolbar.hide() },
            properties = PopupProperties(
                focusable = false, 
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                excludeFromSystemGesture = true
            )
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    toolbar.onCut?.let {
                        IconButton(onClick = { it(); toolbar.hide() }) {
                            Icon(Icons.Default.ContentCut, "Cut", modifier = Modifier.size(20.dp))
                        }
                    }
                    toolbar.onCopy?.let {
                        IconButton(onClick = { it(); toolbar.hide() }) {
                            Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(20.dp))
                        }
                    }
                    toolbar.onPaste?.let {
                        IconButton(onClick = { it(); toolbar.hide() }) {
                            Icon(Icons.Default.ContentPaste, "Paste", modifier = Modifier.size(20.dp))
                        }
                    }
                    toolbar.onSelectAll?.let {
                        IconButton(onClick = { it(); toolbar.hide() }) {
                            Icon(Icons.Default.SelectAll, "Select All", modifier = Modifier.size(20.dp))
                        }
                    }
                    
                    VerticalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    IconButton(onClick = { toolbar.onBold.invoke(); toolbar.hide() }) {
                        Icon(Icons.Default.FormatBold, "Bold", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { toolbar.onItalic.invoke(); toolbar.hide() }) {
                        Icon(Icons.Default.FormatItalic, "Italic", modifier = Modifier.size(20.dp))
                    }

                    var showEmojiMenu by remember { mutableStateOf(false) }
                    val commonEmojis = listOf(
                        "✅", "❌", "⚠️", "ℹ️", "🚩", "🚧", "📋", "📌", "📎", "🔗", 
                        "🔒", "🔓", "🔑", "🔧", "⚙️", "🛠️", "💻", "📱", "🌐", "📡", 
                        "⬆️", "⬇️", "⬅️", "➡️", "↗️", "↘️", "🔄", "⏳", "⏰", "📅", 
                        "🔴", "🟢", "🔵", "🟡", "⚪", "⚫", "⏹️", "⏺️", "▶️", "⏸️",
                        "➕", "➖", "🔢", "🆔", "🆗", "🆙", "🆕", "🆓"
                    )

                    Box {
                        IconButton(onClick = { showEmojiMenu = true }) {
                            Icon(Icons.Default.EmojiEmotions, "Emojis", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showEmojiMenu,
                            onDismissRequest = { showEmojiMenu = false },
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            val chunks = commonEmojis.chunked(4)
                            chunks.forEach { chunk ->
                                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                                    chunk.forEach { emoji ->
                                        Text(
                                            text = emoji,
                                            modifier = Modifier
                                                .clickable { 
                                                    toolbar.onEmoji(emoji)
                                                    showEmojiMenu = false
                                                    toolbar.hide()
                                                }
                                                .padding(8.dp),
                                            fontSize = 20.sp
                                        )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TextEditorScreen(
    filesViewModel: FilesViewModel,
    signalRClient: SignalRClient,
    mainViewModel: com.omni.sync.viewmodel.MainViewModel,
    onBack: () -> Unit,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val isKeyboardVisible = WindowInsets.isImeVisible
    val editingFile by filesViewModel.editingFile.collectAsState()
    val editingContent by filesViewModel.editingContent.collectAsState()
    val openFiles by filesViewModel.openFiles.collectAsState()
    val openFileContents by filesViewModel.openFileContents.collectAsState()
    val isSaving by filesViewModel.isSaving.collectAsState()
    val hasUnsavedChanges by filesViewModel.hasUnsavedChanges.collectAsState()
    val autoSaveEnabled by filesViewModel.autoSaveEnabled.collectAsState()
    val recentFiles by filesViewModel.recentFiles.collectAsState()
    val scrollPositions by filesViewModel.fileScrollPositions.collectAsState()
    val cursorPositions by filesViewModel.fileCursorPositions.collectAsState()
    
    var textFieldValue by remember { 
        val initialSelection = editingFile?.path?.let { cursorPositions[it] } ?: TextRange.Zero
        mutableStateOf(TextFieldValue(editingContent, initialSelection)) 
    }
    
    // Sync external changes to internal state
    LaunchedEffect(editingContent) {
        if (editingContent != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = editingContent)
        }
    }

    // Sync cursor position to ViewModel
    LaunchedEffect(textFieldValue.selection) {
        editingFile?.path?.let { path ->
            if (cursorPositions[path] != textFieldValue.selection) {
                filesViewModel.updateCursorPosition(path, textFieldValue.selection)
            }
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
    var showDebugPanel by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    
    // Editor features state
    var wordWrap by remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var forceMarkdown by remember { mutableStateOf(false) }
    
    val verticalScrollState = remember(editingFile?.path) { 
        val savedPos = editingFile?.path?.let { scrollPositions[it] } ?: 0
        ScrollState(initial = savedPos)
    }
    var scrollRestored by remember(editingFile?.path) { mutableStateOf(true) } // Already restored by initial state
    
    // Restore scroll position - logic removed as it's handled by ScrollState(initial = ...)

    // Save scroll position
    LaunchedEffect(verticalScrollState.value) {
        if (scrollRestored) {
            editingFile?.path?.let { path ->
                if (scrollPositions[path] != verticalScrollState.value) {
                    filesViewModel.updateScrollPosition(path, verticalScrollState.value)
                }
            }
        }
    }

    val horizontalScrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val isMarkdown = (editingFile?.name?.endsWith(".md") == true) || forceMarkdown
    val visualTransformation = remember(colorScheme, isMarkdown, fontSize, searchQuery, textFieldValue.selection) { 
        EditorVisualTransformation(colorScheme, isMarkdown, fontSize, searchQuery, textFieldValue.selection) 
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    var currentTextLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var lastText by remember { mutableStateOf(editingContent) }

    val customTextToolbar = remember {
        CustomTextToolbar(
            onBold = {
                val text = textFieldValue.text
                val sel = textFieldValue.selection
                if (sel.start != sel.end) {
                    val selectedText = text.substring(sel.start, sel.end)
                    val newText = text.replaceRange(sel.start, sel.end, "**$selectedText**")
                    filesViewModel.updateEditingContent(newText)
                    textFieldValue = textFieldValue.copy(text = newText, selection = TextRange(sel.start, sel.end + 4))
                }
            },
            onItalic = {
                val text = textFieldValue.text
                val sel = textFieldValue.selection
                if (sel.start != sel.end) {
                    val selectedText = text.substring(sel.start, sel.end)
                    val newText = text.replaceRange(sel.start, sel.end, "_${selectedText}_")
                    filesViewModel.updateEditingContent(newText)
                    textFieldValue = textFieldValue.copy(text = newText, selection = TextRange(sel.start, sel.end + 2))
                }
            },
            onEmoji = { emoji ->
                val text = textFieldValue.text
                val sel = textFieldValue.selection
                val newText = text.replaceRange(sel.start, sel.end, emoji)
                filesViewModel.updateEditingContent(newText)
                val newCursorPos = sel.start + emoji.length
                textFieldValue = textFieldValue.copy(text = newText, selection = TextRange(newCursorPos, newCursorPos))
            },
            onHide = {
                // Do nothing here to avoid killing the selection while dragging handles.
                // The system calls hide() and showMenu() repeatedly during interaction.
            }
        )
    }

    // --- Swiping between files ---
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { openFiles.size })
    
    // Debounced Autosave
    LaunchedEffect(editingContent) {
        if (autoSaveEnabled && hasUnsavedChanges) {
            delay(1000)
            filesViewModel.saveEditingContent()
        }
    }
    
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

    LaunchedEffect(openFiles.size) {
        if (openFiles.isEmpty()) {
            onBack()
        }
    }

    fun scrollToSelection(index: Int) {
        val layout = currentTextLayoutResult ?: return
        val visualLine = layout.getLineForOffset(index)
        val lineTop = layout.getLineTop(visualLine)
        coroutineScope.launch {
            verticalScrollState.animateScrollTo(lineTop.toInt())
        }
    }

    // Manual scroll-to-cursor logic for typing
    LaunchedEffect(editingContent, textFieldValue.selection.end) {
        val layout = currentTextLayoutResult ?: return@LaunchedEffect
        
        // Only trigger scroll if text actually changed (typing)
        if (editingContent != lastText) {
            lastText = editingContent
            
            val cursorOffset = textFieldValue.selection.end
            val cursorLine = layout.getLineForOffset(cursorOffset)
            val lineTop = layout.getLineTop(cursorLine)
            val lineBottom = layout.getLineBottom(cursorLine)
            
            val scrollPos = verticalScrollState.value
            val vHeight = if (viewportHeight > 0) viewportHeight else with(density) { 200.dp.toPx() } 
            
            // Task 7 Fix: Only scroll if cursor is truly out of view
            val isOutOfView = lineTop < scrollPos || lineBottom > (scrollPos + vHeight)
            
            if (isOutOfView) {
                val lineHeight = with(density) { (fontSize * 1.4f).sp.toPx() }
                val buffer = lineHeight * 2 // 2 lines buffer
                
                if (lineTop < (scrollPos + buffer)) {
                    verticalScrollState.animateScrollTo((lineTop - buffer).toInt().coerceAtLeast(0))
                } else if (lineBottom > (scrollPos + vHeight - buffer)) {
                    verticalScrollState.animateScrollTo((lineBottom - vHeight + buffer).toInt())
                }
            }
        } else {
            lastText = editingContent
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

    CompositionLocalProvider(
        LocalTextToolbar provides customTextToolbar
    ) {
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
                                            IconButton(onClick = { 
                                                filesViewModel.closeFile(file)
                                                if (filesViewModel.openFiles.value.isEmpty()) {
                                                    onBack()
                                                }
                                            }) {
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
                                IconButton(onClick = { 
                                    editingFile?.let { filesViewModel.closeFile(it) } 
                                    if (openFiles.isEmpty()) {
                                        onBack()
                                    }
                                }) {
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
                                    text = { Text("Zoom In") },
                                    onClick = { fontSize = (fontSize + 2f).coerceAtMost(100f); showMenu = false },
                                    leadingIcon = { Icon(Icons.Default.ZoomIn, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Zoom Out") },
                                    onClick = { fontSize = (fontSize - 2f).coerceAtLeast(2f); showMenu = false },
                                    leadingIcon = { Icon(Icons.Default.ZoomOut, null) }
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
                                DropdownMenuItem(
                                    text = { Text(if (showDebugPanel) "Hide Debug Panel" else "Show Debug Panel") },
                                    onClick = { 
                                        showDebugPanel = !showDebugPanel
                                        showMenu = false 
                                    },
                                    leadingIcon = { Icon(Icons.Default.BugReport, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Word/Char Count") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                                        val chars = text.length
                                        android.widget.Toast.makeText(context, "Words: $words, Chars: $chars", android.widget.Toast.LENGTH_LONG).show()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Numbers, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Insert Current Date/Time") },
                                    onClick = {
                                        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        filesViewModel.updateEditingContent(text.replaceRange(sel.start, sel.end, now))
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Event, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Base64 Encode Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val selected = text.substring(sel.start, sel.end)
                                        if (selected.isNotEmpty()) {
                                            val encoded = android.util.Base64.encodeToString(selected.toByteArray(), android.util.Base64.NO_WRAP)
                                            filesViewModel.updateEditingContent(text.replaceRange(sel.start, sel.end, encoded))
                                        }
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.EnhancedEncryption, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Base64 Decode Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val selected = text.substring(sel.start, sel.end)
                                        if (selected.isNotEmpty()) {
                                            try {
                                                val decoded = String(android.util.Base64.decode(selected, android.util.Base64.DEFAULT))
                                                filesViewModel.updateEditingContent(text.replaceRange(sel.start, sel.end, decoded))
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "Invalid Base64", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.NoEncryption, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("URL Encode Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val selected = text.substring(sel.start, sel.end)
                                        if (selected.isNotEmpty()) {
                                            val encoded = java.net.URLEncoder.encode(selected, "UTF-8")
                                            filesViewModel.updateEditingContent(text.replaceRange(sel.start, sel.end, encoded))
                                        }
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Link, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("URL Decode Selection") },
                                    onClick = {
                                        val text = textFieldValue.text
                                        val sel = textFieldValue.selection
                                        val selected = text.substring(sel.start, sel.end)
                                        if (selected.isNotEmpty()) {
                                            try {
                                                val decoded = java.net.URLDecoder.decode(selected, "UTF-8")
                                                filesViewModel.updateEditingContent(text.replaceRange(sel.start, sel.end, decoded))
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "Invalid URL encoding", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.LinkOff, null) }
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
                            val text = textFieldValue.text
                            val range = getSelectedLinesRange()
                            var deleteStart = range.first
                            var deleteEnd = range.second
                            
                            // Expand to include newline
                            if (deleteEnd < text.length && text[deleteEnd] == '\n') {
                                deleteEnd++
                            } else if (deleteStart > 0 && text[deleteStart - 1] == '\n') {
                                deleteStart--
                            }
                            
                            val before = text.substring(0, deleteStart)
                            val after = text.substring(deleteEnd)
                            val newText = before + after
                            
                            filesViewModel.updateEditingContent(newText)
                            textFieldValue = textFieldValue.copy(
                                text = newText,
                                selection = TextRange(deleteStart, deleteStart)
                            )
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
        Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding(), bottom = if (isKeyboardVisible) 0.dp else parentPadding.calculateBottomPadding()).fillMaxSize()) {
            if (showDebugPanel) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val layout = currentTextLayoutResult
                    if (layout != null) {
                        val cursorOffset = textFieldValue.selection.end
                        val cursorLine = layout.getLineForOffset(cursorOffset) + 1
                        
                        val scrollOffset = verticalScrollState.value
                        val firstVisibleLine = layout.getLineForVerticalPosition(scrollOffset.toFloat()) + 1
                        
                        // Without accounting for keyboard (full viewport)
                        val config = LocalConfiguration.current
                        val totalHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
                        val lastVisibleLineNoKbd = layout.getLineForVerticalPosition(scrollOffset.toFloat() + totalHeightPx) + 1

                        // With accounting for keyboard (current viewportHeight which has imePadding)
                        val lastVisibleLineWithKbd = if (viewportHeight > 0) {
                            layout.getLineForVerticalPosition(scrollOffset.toFloat() + viewportHeight) + 1
                        } else 0

                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Cursor Line: $cursorLine", style = MaterialTheme.typography.labelSmall)
                            Text("Visible (No Kbd): $firstVisibleLine - $lastVisibleLineNoKbd", style = MaterialTheme.typography.labelSmall)
                            Text("Visible (With Kbd): $firstVisibleLine - $lastVisibleLineWithKbd", style = MaterialTheme.typography.labelSmall)
                            Text("Scroll Offset: $scrollOffset", style = MaterialTheme.typography.labelSmall)
                            Text("Viewport Height: ${viewportHeight.toInt()}", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text("No layout data", modifier = Modifier.padding(8.dp))
                    }
                }
            }

            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = true
            ) { page ->
            val fileAtPage = openFiles[page]
            val contentAtPage = openFileContents[fileAtPage.path] ?: ""
            var textLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            
            // Note: Each page has its own scroll state and text field value for now
            // To make it fully robust, we'd need to store scroll state and TextFieldValue per-file in ViewModel
            
            Box(modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .onGloballyPositioned { coordinates ->
                    viewportHeight = coordinates.size.height.toFloat()
                }
                .verticalScroll(verticalScrollState)
            ) {
                // Render the custom menu if visible. 
                // Being inside the Box makes coordinates local to the editor area.
                CustomTextSelectionMenu(customTextToolbar)

                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Line numbers column
                    val lineNumbers = remember(textLayoutResult, textFieldValue.text) {
                        if (textLayoutResult == null) {
                            (1..(textFieldValue.text.count { it == '\n' } + 1)).joinToString("\n")
                        } else {
                            val layout = textLayoutResult!!
                            val text = textFieldValue.text
                            val sb = StringBuilder()
                            var logicalLine = 1
                            for (i in 0 until layout.lineCount) {
                                val lineStart = layout.getLineStart(i)
                                val isNewLogicalLine = i == 0 || (lineStart > 0 && lineStart <= text.length && text[lineStart - 1] == '\n')
                                if (isNewLogicalLine) {
                                    sb.append(logicalLine++)
                                }
                                sb.append("\n")
                            }
                            sb.toString().trimEnd('\n')
                        }
                    }
                    
                    Text(
                        text = lineNumbers,
                        modifier = Modifier
                            .width(44.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(vertical = 16.dp, horizontal = 4.dp),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.End
                        ),
                        lineHeight = (fontSize * 1.4).sp,
                        softWrap = false
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 16.dp)
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
                            onTextLayout = { 
                                textLayoutResult = it
                                if (editingFile?.path == fileAtPage.path) {
                                    currentTextLayoutResult = it
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
            text = { Text("The file '${showRemoteChangeDialog}' was modified on the Hub. Would you like to force your local changes to the Hub or reload the remote version?") },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { 
                            // Reload logic: close and reopen or just fetch
                            editingFile?.let { filesViewModel.openForEditing(it) }
                            showRemoteChangeDialog = null 
                        }
                    ) {
                        Text("RELOAD FROM HUB")
                    }
                    Button(
                        onClick = { 
                            filesViewModel.saveEditingContent() // Override remote with local
                            showRemoteChangeDialog = null 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("FORCE SAVE LOCAL")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteChangeDialog = null }) {
                    Text("IGNORE")
                }
            }
        )
    }
}
}
}
}
