package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.ui.components.ActionKeyButton
import com.omni.sync.ui.components.VerticalScrollbar
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.omni.sync.viewmodel.AppScreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CenterFocusStrong
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.omni.sync.utils.WindowsKeyCodes.VK_CONTROL
import com.omni.sync.utils.WindowsKeyCodes.VK_DOWN
import com.omni.sync.utils.WindowsKeyCodes.VK_ESCAPE
import com.omni.sync.utils.WindowsKeyCodes.VK_RETURN
import com.omni.sync.utils.WindowsKeyCodes.VK_UP
import com.omni.sync.utils.WindowsKeyCodes.VK_Y
import com.omni.sync.ui.components.DirectoryPickerDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AiChatScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    filesViewModel: com.omni.sync.viewmodel.FilesViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val messages by signalRClient.aiMessages.collectAsState()
    val aiStatus by signalRClient.aiStatus.collectAsState()
    val aiThought by signalRClient.aiThought.collectAsState()
    val sessions by signalRClient.aiSessions.collectAsState()
    val inputText by signalRClient.aiInputText.collectAsState()
    var showSessionMenu by remember { mutableStateOf(false) }
    val selectedPid by signalRClient.selectedPid.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var pidToRename by remember { mutableIntStateOf(-1) }

    val bookmarks by filesViewModel.bookmarks.collectAsState()
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }
    var showWorkspaceMenu by remember { mutableStateOf(false) }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    var browseMode by remember { mutableStateOf("new") }
    val context = LocalContext.current
    
    val listState = rememberLazyListState()
    val isAiThinking = aiStatus?.contains("Thinking", ignoreCase = true) == true
    val coroutineScope = rememberCoroutineScope()
    val isStartingSession by signalRClient.isStartingSessionFlow.collectAsState()
    val isConnected by mainViewModel.isConnected.collectAsState()

    var textFieldValue by remember { mutableStateOf(TextFieldValue(inputText)) }
    
    // Sync external changes to internal state (e.g. clearing after send)
    LaunchedEffect(inputText) {
        if (inputText != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = inputText)
        }
    }

    // Filter messages to avoid empty/dangling bubbles
    val filteredMessages = remember(messages) {
        messages.filter { it.text.isNotBlank() }
    }

    var lastMessageCount by remember { mutableIntStateOf(filteredMessages.size) }

    // Animation for session button flash
    val sessionButtonAnim = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        signalRClient.anyAiActivityEvent.collect {
            // Flash animation
            sessionButtonAnim.animateTo(1f, animationSpec = tween(300))
            sessionButtonAnim.animateTo(0f, animationSpec = tween(300))
        }
    }

    LaunchedEffect(Unit) {
        signalRClient.getAiSessions()
    }

    // Auto-scroll logic
    LaunchedEffect(filteredMessages.size, isAiThinking) {
        val countIncreased = filteredMessages.size > lastMessageCount
        
        if (filteredMessages.isNotEmpty()) {
            val isReloading = aiStatus?.contains("Reloading", ignoreCase = true) == true || 
                             aiStatus?.contains("Switching", ignoreCase = true) == true
            
            if (isReloading) {
                // Jump instantly for history reloads/switches
                mainViewModel.addLog("[UI] History reload/switch detected for PID $selectedPid. Scrolling to bottom.", com.omni.sync.ui.screen.LogType.INFO)
                delay(100) // Small delay to let items render
                listState.scrollToItem(0)
            } else {
                // Only auto-scroll if we are already at the bottom (index 0)
                // We check first visible item index. In reverseLayout, 0 is bottom.
                val isAtBottom = listState.firstVisibleItemIndex <= 1
                val newestIsMe = filteredMessages.lastOrNull()?.sender == "Me"

                if (isAtBottom || (countIncreased && newestIsMe)) {
                    if (countIncreased) {
                        mainViewModel.addLog("[UI] New message in PID $selectedPid. Auto-scrolling.", com.omni.sync.ui.screen.LogType.INFO)
                    }
                    delay(50) // Small delay to let Compose measure new items
                    listState.animateScrollToItem(0)
                }
            }
        }
        lastMessageCount = filteredMessages.size
    }

    // Jump between user messages
    val userMessageItemIndices = remember(filteredMessages) {
        val total = filteredMessages.size
        filteredMessages.indices.filter { filteredMessages[it].sender == "Me" }
            .map { total - 1 - it } // Map to reversed list indices
            .sorted()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { 
                                if (isConnected) {
                                    signalRClient.getAiSessions()
                                    showSessionMenu = true 
                                }
                            }, 
                            enabled = isConnected,
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (sessionButtonAnim.value > 0f) 
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = sessionButtonAnim.value * 0.5f)
                                    else Color.Transparent
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val currentName = if (!isConnected) "Disconnected" else if (isStartingSession) "Creating Session..." else (sessions[selectedPid] ?: "Select Session")
                                Text(currentName, style = MaterialTheme.typography.titleMedium)
                                if (isConnected) {
                                    if (aiStatus != null) {
                                        Text(aiStatus!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Text("${sessions.size} sessions active", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showSessionMenu,
                            onDismissRequest = { showSessionMenu = false },
                            modifier = Modifier.widthIn(min = 200.dp)
                        ) {
                            if (sessions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No active sessions") },
                                    onClick = { showSessionMenu = false },
                                    enabled = false
                                )
                            }
                            sessions.forEach { (pid, name) ->
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(name, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { 
                                                pidToRename = pid
                                                renameText = name
                                                showRenameDialog = true
                                                showSessionMenu = false
                                            }) {
                                                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    onClick = {
                                        signalRClient.switchAiSession(pid)
                                        showSessionMenu = false
                                    }
                                )
                            }
                            
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Reload All Sessions (Debug)") },
                                onClick = {
                                    signalRClient.clearSessions() // New method to clear local list
                                    signalRClient.reloadAiSessions()
                                    showSessionMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Cached, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Nuke All Sessions", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    signalRClient.resetAiSessions()
                                    showSessionMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                },
                actions = {
                    if (selectedPid != -1) {
                        IconButton(onClick = { 
                            val hubUrl = mainViewModel.appConfig.hubUrl
                            val baseUrl = hubUrl.substringBeforeLast("/")
                            mainViewModel.openUrlOnPhone("$baseUrl/Settings.html")
                        }, enabled = isConnected) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Session Settings")
                        }
                    }
                    Box {
                        IconButton(onClick = { showWorkspaceMenu = true }, enabled = isConnected) {
                            Icon(Icons.Default.Folder, contentDescription = "AI Actions")
                        }
                        DropdownMenu(
                            expanded = showWorkspaceMenu,
                            onDismissRequest = { showWorkspaceMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New Session (Default Workspace)") },
                                onClick = { 
                                    selectedWorkspace = null
                                    signalRClient.startNewAiSession(null)
                                    showWorkspaceMenu = false 
                                },
                                leadingIcon = { Icon(Icons.Default.Add, null) }
                            )
                            
                            HorizontalDivider()
                            
                            Text("Switch Workspace / New from Bookmark", 
                                style = MaterialTheme.typography.labelSmall, 
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )

                            DropdownMenuItem(
                                text = { Text("Default Workspace", fontWeight = if (selectedWorkspace == null) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { 
                                    selectedWorkspace = null
                                    signalRClient.startNewAiSession(null)
                                    showWorkspaceMenu = false 
                                },
                                leadingIcon = { Icon(Icons.Default.Home, null) }
                            )

                            bookmarks.filter { it.isDirectory }.forEach { bookmark ->
                                DropdownMenuItem(
                                    text = { Text(bookmark.name, fontWeight = if (selectedWorkspace == bookmark.path) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { 
                                        selectedWorkspace = bookmark.path
                                        signalRClient.startNewAiSession(bookmark.path)
                                        showWorkspaceMenu = false 
                                    },
                                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            signalRClient.sendAiMessage("/dir add \"${bookmark.path}\"")
                                            showWorkspaceMenu = false 
                                        }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Add to current session")
                                        }
                                    }
                                )
                            }
                            
                            DropdownMenuItem(
                                text = { Text("Browse for New Session...") },
                                onClick = {
                                    browseMode = "new"
                                    showDirectoryPicker = true
                                    showWorkspaceMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Add, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Browse to Add Context...") },
                                onClick = {
                                    browseMode = "add"
                                    showDirectoryPicker = true
                                    showWorkspaceMenu = false
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                            )
                        }
                    }
                    IconButton(onClick = { 
                        if (selectedPid != -1) {
                            signalRClient.stopAiSession(selectedPid)
                            signalRClient.clearAiMessages(selectedPid)
                        }
                    }, enabled = isConnected) {
                        Icon(Icons.Default.Close, contentDescription = "Close Session")
                    }
                }
            )
        }
    ) { padding ->
        val imeHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottomBarHeight = parentPadding.calculateBottomPadding()
        val keyboardOverlapOffset = 25.dp
        val floatHeight = if (imeHeight > 0.dp) imeHeight - keyboardOverlapOffset else 0.dp
        val currentBottomPadding = maxOf(floatHeight, bottomBarHeight)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = currentBottomPadding + 160.dp) // Leave room for floating panel (increased from 115)
            ) {
                if (!isConnected) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Disconnected from Hub",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                    ) {
                        if (isAiThinking) {
                            item(key = "typing_indicator") {
                                AiTypingIndicator()
                            }
                        }

                        if (aiThought != null) {
                            item(key = "thought_bubble") {
                                ThoughtBubble(aiThought!!)
                            }
                        }

                        items(
                            items = filteredMessages.reversed(),
                            key = { it.id }
                        ) { message ->
                            ChatBubble(
                                message = message, 
                                signalRClient = signalRClient, 
                                mainViewModel = mainViewModel,
                                selectedPid = selectedPid
                            )
                        }
                    }
                    
                    VerticalScrollbar(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp, top = 8.dp, bottom = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { 
                            textFieldValue = it
                            signalRClient.updateAiInputText(it.text) 
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (isConnected) "Ask AI something..." else "Connecting...") },
                        maxLines = 3,
                        enabled = isConnected
                    )
                    Spacer(Modifier.width(8.dp))
                    
                    val presets by signalRClient.aiPresets.collectAsState()
                    var showPresetsMenu by remember { mutableStateOf(false) }
                    
                    Box {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { 
                                        signalRClient.getAiPresets()
                                        showPresetsMenu = true 
                                    },
                                    onLongClick = {
                                        if (inputText.isNotBlank()) {
                                            signalRClient.addAiPreset(inputText)
                                            Toast.makeText(context, "Preset saved!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.List, 
                                contentDescription = "Presets",
                                tint = if (isConnected) MaterialTheme.colorScheme.onSurface else Color.Gray
                            )
                        }
                        
                        DropdownMenu(
                            expanded = showPresetsMenu,
                            onDismissRequest = { showPresetsMenu = false }
                        ) {
                            if (presets.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No presets") },
                                    onClick = { showPresetsMenu = false },
                                    enabled = false
                                )
                            }
                            presets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(preset, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { 
                                                signalRClient.removeAiPreset(preset)
                                            }) {
                                                Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    onClick = {
                                        signalRClient.updateAiInputText(preset)
                                        textFieldValue = TextFieldValue(preset, TextRange(preset.length))
                                        showPresetsMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                signalRClient.sendAiMessage(inputText, if (selectedPid != -1) selectedPid else null)
                                signalRClient.updateAiInputText("")
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            } else {
                                // If empty, send Enter key to targeted PID
                                signalRClient.sendAiSpecialKey("enter")
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            }
                        },
                        modifier = Modifier.background(
                            if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray, 
                            CircleShape
                        ),
                        enabled = isConnected,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = currentBottomPadding),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    QuickActionPanel(
                        signalRClient, 
                        coroutineScope, 
                        selectedPid, 
                        isConnected, 
                        listState, 
                        userMessageItemIndices,
                        filteredMessages.size
                    )
                }
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Session") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Session Name") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        signalRClient.renameAiSession(pidToRename, renameText)
                        showRenameDialog = false
                    }) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showDirectoryPicker) {
            DirectoryPickerDialog(
                signalRClient = signalRClient,
                isConnected = isConnected,
                onDismiss = { showDirectoryPicker = false },
                onConfirm = { path ->
                    showDirectoryPicker = false
                    if (browseMode == "new") {
                        signalRClient.startNewAiSession(path)
                    } else {
                        selectedWorkspace = path
                        signalRClient.sendAiMessage("/dir add \"$path\"")
                    }
                }
            )
        }
    }
}

@Composable
fun QuickActionPanel(
    signalRClient: SignalRClient, 
    coroutineScope: kotlinx.coroutines.CoroutineScope, 
    selectedPid: Int,
    isConnected: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    userMessageItemIndices: List<Int>,
    totalMessages: Int
) {
    var isZoomed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionKeyButton(
                text = "Esc",
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("escape", selectedPid) }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardArrowUp,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("up", selectedPid) }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardArrowDown,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("down", selectedPid) }
            )

            ActionKeyButton(
                text = "Enter",
                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("enter", selectedPid) }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionKeyButton(
                icon = Icons.Default.KeyboardDoubleArrowUp,
                text = "Prev Msg",
                modifier = Modifier.weight(1f),
                onClick = { 
                    coroutineScope.launch {
                        if (userMessageItemIndices.isEmpty()) return@launch
                        val currentFirstVisibleItem = listState.firstVisibleItemIndex
                        val targetIndex = userMessageItemIndices.filter { it > currentFirstVisibleItem }.minOrNull()
                        if (targetIndex != null) {
                            listState.animateScrollToItem(targetIndex)
                        } else {
                            // Wrap around to the "oldest" message (highest index in reverse layout)
                            listState.animateScrollToItem(userMessageItemIndices.maxOrNull() ?: 0)
                        }
                    }
                }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardDoubleArrowDown,
                text = "Next Msg",
                modifier = Modifier.weight(1f),
                onClick = { 
                    coroutineScope.launch {
                        if (userMessageItemIndices.isEmpty()) return@launch
                        val currentFirstVisibleItem = listState.firstVisibleItemIndex
                        // Find the largest index that is smaller than current view (newer message)
                        val targetIndex = userMessageItemIndices.filter { it < currentFirstVisibleItem }.maxOrNull()
                        if (targetIndex != null) {
                            listState.animateScrollToItem(targetIndex)
                        } else {
                            // If already at the newest user message, jump to the absolute bottom (index 0)
                            listState.animateScrollToItem(0)
                        }
                    }
                }
            )

            ActionKeyButton(
                text = "Yolo",
                modifier = Modifier.weight(1f),
                onClick = { 
                    coroutineScope.launch {
                        signalRClient.sendAiYolo(selectedPid)
                    }
                }
            )

            ActionKeyButton(
                text = "Focus",
                icon = Icons.Default.Adjust,
                modifier = Modifier.weight(1f),
                onClick = { 
                    if (selectedPid != -1) {
                        signalRClient.focusAiSession(selectedPid)
                    }
                }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionKeyButton(
                text = if (isZoomed) "Unzoom" else "Zoom 1.5x",
                modifier = Modifier.weight(1f),
                onClick = { 
                    if (selectedPid != -1) {
                        val newLevel = if (!isZoomed) 1.5 else 1.0
                        signalRClient.setAiZoom(selectedPid, newLevel)
                        isZoomed = !isZoomed
                    }
                }
            )

            ActionKeyButton(
                text = "Clear",
                icon = Icons.Default.Delete,
                modifier = Modifier.weight(1f),
                onClick = { if (selectedPid != -1) signalRClient.clearAiMessages(selectedPid) } 
            )            
            ActionKeyButton(
                text = "History",
                icon = Icons.Default.Cached,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.requestAiHistory() }
            )
        }
    }
}

@Composable
fun AiTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dotAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse), label = "dot1"
    )
    val dotAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 200), repeatMode = RepeatMode.Reverse), label = "dot2"
    )
    val dotAlpha3 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 400), repeatMode = RepeatMode.Reverse), label = "dot3"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "AI",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(dotAlpha1)
                Dot(dotAlpha2)
                Dot(dotAlpha3)
            }
        }
    }
}

@Composable
fun ThoughtBubble(thought: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 300.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ) {
            Text(
                text = thought,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha), CircleShape)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: com.omni.sync.data.repository.AiMessage,
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    selectedPid: Int
) {
    val sender = message.sender
    val content = message.text
    val isMe = sender == "Me"
    val isAi = sender == "AI"
    val isCodeDiff = sender == "CodeDiff"
    val isError = sender == "Error" || content.startsWith("Error:")
    val isSystem = sender == "System" || (!isMe && !isAi && !isError && !isCodeDiff)
    
    val context = LocalContext.current
    val timestamp = remember { 
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) 
    }

    val alignment = when {
        isError || isSystem || isCodeDiff -> Alignment.CenterHorizontally
        isMe -> Alignment.End
        else -> Alignment.Start
    }
    
    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        isSystem -> Color(0xFFFFF176).copy(alpha = 0.7f) // Yellow for system
        isCodeDiff -> Color.Black.copy(alpha = 0.9f)
        isMe -> MaterialTheme.colorScheme.primaryContainer
        isAi -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isSystem -> Color(0xFF333333) // Dark text for yellow
        isCodeDiff -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    val icon = when {
        isMe -> Icons.Default.Person
        isAi -> Icons.Default.SmartToy
        isCodeDiff -> Icons.Default.Code
        isError -> Icons.Default.Error
        else -> Icons.Default.SettingsSuggest
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isSystem && !isError && !isCodeDiff) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                if (!isMe) {
                    Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = sender,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                if (isMe) {
                    if (message.isQueued) {
                        Text(
                            text = "Queued",
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        } else if (isCodeDiff || isError || isSystem) {
             Icon(
                 icon, 
                 null, 
                 modifier = Modifier.size(16.dp).padding(bottom = 2.dp), 
                 tint = if (isError) MaterialTheme.colorScheme.error else if (isSystem) Color(0xFF666600) else Color.White
             )
        }

        Surface(
            color = bgColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = if (isSystem || isError || isCodeDiff) 380.dp else 300.dp)
                .combinedClickable(
                    onClick = { },
                    onLongClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("AI Message", content)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
        ) {
            SelectionContainer {
                Column {
                    if (isCodeDiff) {
                        DiffText(content)
                    } else {
                        MarkdownText(
                            text = content,
                            textColor = textColor,
                            textAlign = if (isSystem || isError) TextAlign.Center else TextAlign.Start,
                            signalRClient = signalRClient,
                            mainViewModel = mainViewModel,
                            selectedPid = selectedPid
                        )
                    }
                    
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = textColor.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String, 
    textColor: Color, 
    textAlign: TextAlign,
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    selectedPid: Int
) {
    val parts = remember(text) {
        val regex = Regex("```([a-zA-Z]*)\\n?([\\s\\S]*?)```")
        val matches = regex.findAll(text)
        val result = mutableListOf<Pair<String, Boolean>>() // text, isCode
        var lastIndex = 0
        for (match in matches) {
            if (match.range.first > lastIndex) {
                result.add(text.substring(lastIndex, match.range.first) to false)
            }
            result.add(match.groupValues[2] to true)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            result.add(text.substring(lastIndex) to false)
        }
        if (result.isEmpty()) result.add(text to false)
        result
    }

    Column(modifier = Modifier.padding(8.dp)) {
        parts.forEach { (content, isCode) ->
            if (isCode) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = content.trim(),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    )
                }
            } else {
                if (content.isNotBlank()) {
                    // Improved bullet point rendering
                    val lines = content.trim().split("\n")
                    lines.forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.startsWith("* ") || trimmedLine.startsWith("- ")) {
                            Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                RenderTextWithStyles(
                                    text = trimmedLine.substring(2),
                                    textColor = textColor,
                                    textAlign = textAlign,
                                    signalRClient = signalRClient,
                                    mainViewModel = mainViewModel,
                                    selectedPid = selectedPid
                                )
                            }
                        } else if (trimmedLine.isNotEmpty()) {
                            RenderTextWithStyles(
                                text = trimmedLine,
                                textColor = textColor,
                                textAlign = textAlign,
                                signalRClient = signalRClient,
                                mainViewModel = mainViewModel,
                                selectedPid = selectedPid,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenderTextWithStyles(
    text: String, 
    textColor: Color, 
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    selectedPid: Int
) {
    val aiWorkspaces by signalRClient.aiWorkspaces.collectAsState()
    val workspace = aiWorkspaces[selectedPid] ?: ""

    val annotatedString = buildAnnotatedString {
        val titleRegex = Regex("\\[(.*?)\\]\\s*:\\s*###\\s*(.*)")
        val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
        val italicRegex = Regex("\\*(.*?)\\*")
        val codeRegex = Regex("`(.*?)`")
        val urlRegex = Regex("(https?://[\\w\\d./?=#+%&-]+)")
        
        val allMatches = (titleRegex.findAll(text) + 
                          boldRegex.findAll(text) + 
                          italicRegex.findAll(text) +
                          codeRegex.findAll(text) +
                          urlRegex.findAll(text))
            .sortedBy { it.range.first }
            .toList()
            
        var currentPos = 0
        for (match in allMatches) {
            if (match.range.first < currentPos) continue 
            
            append(text.substring(currentPos, match.range.first))
            
            when {
                match.value.startsWith("[") && match.value.contains(": ###") -> {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                        append("[${match.groupValues[1]}]")
                    }
                    append(" : ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Black)) {
                        append("### ${match.groupValues[2]}")
                    }
                }
                match.value.startsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                }
                match.value.startsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(match.groupValues[1])
                    }
                }
                match.value.startsWith("`") -> {
                    val inner = match.groupValues[1]
                    val isLikelyFile = inner.contains(".") && inner.length > 3
                    
                    if (isLikelyFile) {
                        pushStringAnnotation(tag = "FILE", annotation = inner)
                        withStyle(SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            fontWeight = FontWeight.Bold
                        )) {
                            append(inner)
                        }
                        pop()
                    } else {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.Black.copy(alpha = 0.05f),
                            color = MaterialTheme.colorScheme.secondary
                        )) {
                            append(inner)
                        }
                    }
                }
                match.value.startsWith("http") -> {
                    withStyle(SpanStyle(
                        color = Color(0xFF2196F3),
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )) {
                        append(match.value)
                    }
                }
            }
            currentPos = match.range.last + 1
        }
        
        if (currentPos < text.length) {
            append(text.substring(currentPos))
        }
    }

    androidx.compose.foundation.text.ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor, textAlign = textAlign),
        modifier = modifier,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "FILE", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val filename = annotation.item
                    // We just pass the raw name now, FilesViewModel handles finding it in workspace/current dir
                    mainViewModel.setPendingNavigationPath("AI_FILE:$filename")
                    mainViewModel.navigateTo(AppScreen.EDITOR)
                }
        }
    )
}

@Composable
fun DiffText(diffJson: String) {
    val displayData = remember(diffJson) {
        try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            // Try parsing as JSON first
            if (diffJson.trim().startsWith("{") || diffJson.trim().startsWith("[")) {
                val element = gson.fromJson(diffJson, com.google.gson.JsonElement::class.java)
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("fileDiff")) {
                        obj.get("fileDiff").asString to true
                    } else {
                        gson.toJson(element) to false
                    }
                } else {
                    gson.toJson(element) to false
                }
            } else {
                // Not JSON, check if it's a raw diff
                diffJson to (diffJson.contains("@@") || diffJson.contains("--- ") || diffJson.contains("+++ "))
            }
        } catch (e: Exception) {
            diffJson to (diffJson.contains("@@") || diffJson.contains("--- ") || diffJson.contains("+++ "))
        }
    }

    val (text, isDiff) = displayData
    val lines = remember(text) { text.split("\n") }

    Column(modifier = Modifier.padding(8.dp)) {
        lines.forEach { line ->
            val bgColor = if (isDiff) {
                when {
                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                    line.startsWith("-") && !line.startsWith("---") -> Color(0xFFB71C1C).copy(alpha = 0.3f)
                    line.startsWith("@@") -> Color(0xFF0D47A1).copy(alpha = 0.3f)
                    else -> Color.Transparent
                }
            } else {
                Color.Transparent
            }

            val textColor = if (isDiff) {
                when {
                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFFA5D6A7)
                    line.startsWith("-") && !line.startsWith("---") -> Color(0xFFEF9A9A)
                    line.startsWith("@@") -> Color(0xFF90CAF9)
                    else -> Color.White
                }
            } else {
                Color(0xFF81D4FA) // Light blue for generic tool data
            }

            Surface(
                color = bgColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = textColor
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

val CircleShape = RoundedCornerShape(50)