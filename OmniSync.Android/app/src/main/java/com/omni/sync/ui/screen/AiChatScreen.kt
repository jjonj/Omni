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
import androidx.compose.material.icons.filled.Monitor
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

import androidx.compose.foundation.interaction.DragInteraction
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Intent
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.foundation.shape.CircleShape

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
    val aiDialog by signalRClient.aiDialog.collectAsState()
    val sessions by signalRClient.aiSessions.collectAsState()
    val workspaces by signalRClient.aiWorkspaces.collectAsState()
    val inputText by signalRClient.aiInputText.collectAsState()
    val aiModels by signalRClient.aiModels.collectAsState()
    val defaultAiModel by signalRClient.defaultAiModel.collectAsState()
    var showSessionMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
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
    val filteredMessages = remember(messages) {
        messages.filter { it.text.isNotBlank() }
    }

    // STRICT AUTO-SCROLL LOGIC
    var isAutoScrollEnabled by remember { mutableStateOf(true) }
    
    val coroutineScope = rememberCoroutineScope()
    fun scrollToBottom(animate: Boolean = true) {
        isAutoScrollEnabled = true
        coroutineScope.launch {
            // Slight delay to ensure layout is ready
            delay(100) 
            if (animate) {
                listState.animateScrollToItem(0)
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    // Scroll to bottom when navigating to this screen
    val currentScreen by mainViewModel.currentScreen.collectAsState()
    LaunchedEffect(currentScreen) {
        if (currentScreen == com.omni.sync.viewmodel.AppScreen.AI_CHAT) {
            scrollToBottom(animate = false)
        }
    }

    // Always scroll to bottom on session switch
    LaunchedEffect(selectedPid) {
        scrollToBottom(animate = false)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                // Send to current AI session
                signalRClient.sendAiMessage(spokenText, if (selectedPid != -1) selectedPid else null)
            }
        }
    }

    fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        isAutoScrollEnabled = true
        voiceLauncher.launch(intent)
    }

    // Auto-trigger voice recognition on Hub status
    LaunchedEffect(aiStatus) {
        if (aiStatus == "READY_TO_LISTEN") {
            startVoiceRecognition()
        }
    }

    val isWaitingForAiMap by signalRClient.isWaitingForAiResponseMap.collectAsState()
    val isWaitingForAi = isWaitingForAiMap[selectedPid] ?: false
    // Robust Thinking Check: Check local waiting status OR hub status OR dialog existence
    val isAiThinking = isWaitingForAi || 
                       aiStatus?.contains("Thinking", ignoreCase = true) == true || 
                       aiStatus?.contains("Starting", ignoreCase = true) == true ||
                       aiDialog != null // Dialog means it's waiting for input, but effectively "active/busy" in a sense, but maybe not "thinking". Let's stick to active processing.
                       
    val isStartingSession by signalRClient.isStartingSessionFlow.collectAsState()
    val isConnected by mainViewModel.isConnected.collectAsState()

    var textFieldValue by remember { mutableStateOf(TextFieldValue(inputText)) }
    
    // Timer state for Stopwatch (PER SESSION)
    val sessionTimers by signalRClient.sessionTimers.collectAsState()
    var timeElapsedSeconds by remember { mutableLongStateOf(0L) }

    // Ticker updates the displayed elapsed time based on the SELECTED session
    LaunchedEffect(selectedPid, sessionTimers) {
        while(true) {
            val lastTime = sessionTimers[selectedPid]
            if (lastTime != null) {
                timeElapsedSeconds = (System.currentTimeMillis() - lastTime) / 1000
            } else {
                timeElapsedSeconds = 0
            }
            delay(1000)
        }
    }

    // Reset timer logic removed from here as it's now handled by SignalRClient Receive handlers.

    // Sync external changes to internal state
    LaunchedEffect(inputText) {
        if (inputText != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = inputText)
        }
    }

    // 1. Monitor scroll changes to detect direction
    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        
        snapshotFlow { listState.isScrollInProgress to (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) }
            .collect { (inProgress, pos) ->
                val (currentIndex, currentOffset) = pos
                
                // ONLY disable autoscroll if the USER is manually scrolling
                if (inProgress) {
                    // In reverseLayout=true, index 0 is bottom. 
                    // Scrolling UP (away from bottom) INCREASES index or offset.
                    if (currentIndex > lastIndex || (currentIndex == lastIndex && currentOffset > lastOffset)) {
                        isAutoScrollEnabled = false
                    }
                }
                
                // Re-enable if we reach the bottom (or very close to it)
                if (currentIndex == 0 && currentOffset < 100) {
                    isAutoScrollEnabled = true
                }
                
                lastIndex = currentIndex
                lastOffset = currentOffset
            }
    }

    // 2. Force Auto-Scroll when enabled
    LaunchedEffect(filteredMessages, isAiThinking, isAutoScrollEnabled) {
        if (isAutoScrollEnabled) {
            if (isAiThinking) {
                 scrollToBottom(animate = true)
            } else if (filteredMessages.isNotEmpty()) {
                 scrollToBottom(animate = false)
            }
        }
    }
    
    // Handle history reload (force jump)
    LaunchedEffect(aiStatus) {
         val isReloading = aiStatus?.contains("Reloading", ignoreCase = true) == true || 
                          aiStatus?.contains("Switching", ignoreCase = true) == true
         if (isReloading) {
             scrollToBottom(animate = false)
         }
    }


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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Auto-scroll active indicator
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (isAutoScrollEnabled) Color.Green else Color.Gray.copy(alpha = 0.5f), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            
                            // Stopwatch
                            Text(
                                text = String.format("%02d:%02d", timeElapsedSeconds / 60, timeElapsedSeconds % 60),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(end = 8.dp)
                            )

                            TextButton(
                                onClick = { 
                                    if (isConnected) {
                                        signalRClient.getAiSessions()
                                        showSessionMenu = true 
                                    }
                                }, 
                                enabled = isConnected,
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = when {
                                        aiDialog != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
                                        sessionButtonAnim.value > 0f -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = sessionButtonAnim.value * 0.5f)
                                        else -> Color.Transparent
                                    }
                                )
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val currentName = if (!isConnected) "Disconnected" 
                                        else if (isStartingSession) "Creating Session..." 
                                        else {
                                            sessions[selectedPid] ?: "Select Session"
                                        }

                                    Text(currentName, style = MaterialTheme.typography.titleMedium)
                                    if (isConnected) {
                                        if (aiStatus != null) {
                                            val displayStatus = if (aiStatus == "READY_TO_LISTEN") "Ready to listen" else aiStatus!!
                                            Text(displayStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Text("${sessions.size} sessions active", style = MaterialTheme.typography.labelSmall)
                                        }
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
                                val displayName = name
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(displayName, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { 
                                                pidToRename = pid
                                                renameText = displayName
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
                                text = { Text("Select Model (CLI)") },
                                onClick = {
                                    signalRClient.sendAiMessage("/model", selectedPid)
                                    showSessionMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
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
                        Box {
                            IconButton(onClick = { showModelMenu = true }, enabled = isConnected) {
                                Icon(imageVector = Icons.Default.Tune, contentDescription = "Model Selection")
                            }
                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = defaultAiModel == "",
                                                onClick = { signalRClient.setDefaultAiModel("") }
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Default (None)")
                                        }
                                    },
                                    onClick = { 
                                        signalRClient.sendAiMessage("/model", selectedPid)
                                        showModelMenu = false 
                                    },
                                    leadingIcon = { Icon(Icons.Default.SettingsSuggest, null) }
                                )
                                HorizontalDivider()
                                aiModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { 
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = defaultAiModel == model,
                                                    onClick = { signalRClient.setDefaultAiModel(model) }
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(model)
                                            }
                                        },
                                        onClick = {
                                            signalRClient.sendAiMessage("/model $model", selectedPid)
                                            showModelMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Hub Settings (Web)") },
                                    onClick = {
                                        val hubUrl = mainViewModel.appConfig.value.hubUrl
                                        val baseUrl = hubUrl.substringBeforeLast("/")
                                        mainViewModel.openUrlOnPhone("$baseUrl/Settings.html")
                                        showModelMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) }
                                )
                            }
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
                                            signalRClient.sendAiMessage("/dir add ${bookmark.path}")
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
                    .padding(bottom = currentBottomPadding + 160.dp) // Leave room for floating panel (restored to 160)
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

                        if (aiDialog != null) {
                            item(key = "dialog_bubble") {
                                AiDialogBubble(aiDialog!!, signalRClient, selectedPid)
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
                    
                    IconButton(
                        onClick = {
                            if (inputText.isBlank()) {
                                signalRClient.sendAiSpecialKey("enter", if (selectedPid != -1) selectedPid else null)
                            } else {
                                signalRClient.sendAiMessage(inputText, if (selectedPid != -1) selectedPid else null)
                                signalRClient.updateAiInputText("")
                            }
                            scrollToBottom(animate = false) // Instant jump
                        },
                        modifier = Modifier.background(
                            if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray, 
                            CircleShape
                        ),
                        enabled = isConnected,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Icon(
                            if (inputText.isBlank()) Icons.AutoMirrored.Filled.KeyboardReturn else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (inputText.isBlank()) "Send Enter" else "Send"
                        )
                    }
                }
            }

            // --- Added: Scroll to Bottom Button ---
            val showScrollToBottom by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0
                }
            }
            if (showScrollToBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        scrollToBottom(animate = true)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = currentBottomPadding + 240.dp, end = 16.dp), // Moved up from 220.dp
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.KeyboardDoubleArrowDown, contentDescription = "Scroll to Bottom")
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
                        filteredMessages.size,
                        textFieldValue,
                        onTextFieldValueChange = { textFieldValue = it },
                        onVoiceTrigger = { startVoiceRecognition() },
                        onMessageSent = { isAutoScrollEnabled = true },
                        onScrollToBottom = { scrollToBottom(animate = true) }
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
                        signalRClient.sendAiMessage("/dir add $path")
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
    totalMessages: Int,
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onVoiceTrigger: () -> Unit,
    onMessageSent: () -> Unit,
    onScrollToBottom: () -> Unit
) {
    var isZoomed by remember { mutableStateOf(false) }
    val presets by signalRClient.aiPresets.collectAsState()
    var showPresetsMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val inputText by signalRClient.aiInputText.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: Core navigation/keys
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionKeyButton(
                text = "Esc",
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { signalRClient.sendAiSpecialKey("escape", selectedPid) }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardArrowUp,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { signalRClient.sendAiSpecialKey("up", selectedPid) }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardArrowDown,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { signalRClient.sendAiSpecialKey("down", selectedPid) }
            )

            Box(modifier = Modifier.weight(1f)) {
                ActionKeyButton(
                    text = "Presets",
                    icon = Icons.Default.List,
                    modifier = Modifier.fillMaxWidth().height(33.dp),
                    onLongClick = {
                        if (inputText.isNotBlank()) {
                            signalRClient.addAiPreset(inputText)
                            Toast.makeText(context, "Preset saved!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClick = {
                        signalRClient.getAiPresets()
                        showPresetsMenu = true
                    }
                )

                DropdownMenu(
                    expanded = showPresetsMenu,
                    onDismissRequest = { showPresetsMenu = false }
                ) {
                    val allPresets = remember(presets) {
                        listOf("/auth", "/conductor:status") + presets
                    }

                    if (allPresets.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No presets") },
                            onClick = { showPresetsMenu = false },
                            enabled = false
                        )
                    }

                    allPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(preset, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { 
                                        if (preset == "/auth" || preset == "/conductor:status") {
                                            Toast.makeText(context, "System presets cannot be deleted", Toast.LENGTH_SHORT).show()
                                        } else {
                                            signalRClient.removeAiPreset(preset)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Close, 
                                            contentDescription = "Delete", 
                                            modifier = Modifier.size(18.dp),
                                            tint = if (preset.startsWith("/")) Color.Gray.copy(alpha = 0.5f) else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            onClick = {
                                signalRClient.updateAiInputText(preset)
                                onTextFieldValueChange(TextFieldValue(preset, TextRange(preset.length)))
                                showPresetsMenu = false
                            },
                            leadingIcon = {
                                val icon = when(preset) {
                                    "/auth" -> Icons.Default.Security
                                    "/conductor:status" -> Icons.Default.Info
                                    else -> Icons.Outlined.ChatBubbleOutline
                                }
                                Icon(imageVector = icon, null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }

        // Row 2: Message navigation & Yolo
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionKeyButton(
                icon = Icons.Default.KeyboardDoubleArrowUp,
                text = "Prev",
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { 
                    coroutineScope.launch {
                        if (userMessageItemIndices.isEmpty()) return@launch
                        val currentFirstVisibleItem = listState.firstVisibleItemIndex
                        val targetIndex = userMessageItemIndices.filter { it > currentFirstVisibleItem }.minOrNull()
                        if (targetIndex != null) {
                            listState.animateScrollToItem(targetIndex)
                        } else {
                            listState.animateScrollToItem(userMessageItemIndices.maxOrNull() ?: 0)
                        }
                    }
                }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardDoubleArrowDown,
                text = "Next",
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { 
                    coroutineScope.launch {
                        if (userMessageItemIndices.isEmpty()) return@launch
                        val currentFirstVisibleItem = listState.firstVisibleItemIndex
                        val targetIndex = userMessageItemIndices.filter { it < currentFirstVisibleItem }.maxOrNull()
                        if (targetIndex != null) {
                            listState.animateScrollToItem(targetIndex)
                        } else {
                            listState.animateScrollToItem(0)
                        }
                    }
                }
            )

            ActionKeyButton(
                text = "Yolo",
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { 
                    coroutineScope.launch {
                        signalRClient.sendAiYolo(selectedPid)
                    }
                }
            )

            ActionKeyButton(
                text = "Toggle Mon",
                icon = Icons.Default.Monitor,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { if (selectedPid != -1) signalRClient.toggleAiSessionMonitor(selectedPid) } 
            )
        }

        // Row 3: UI Controls & Trigger
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionKeyButton(
                text = if (isZoomed) "Unzoom" else "Zoom",
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { 
                    if (selectedPid != -1) {
                        val newLevel = if (!isZoomed) 2.0 else 1.0
                        signalRClient.setAiZoom(selectedPid, newLevel)
                        isZoomed = !isZoomed
                    }
                }
            )

            ActionKeyButton(
                text = "Clear",
                icon = Icons.Default.Delete,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { if (selectedPid != -1) signalRClient.clearAiMessages(selectedPid) } 
            )            
            
            ActionKeyButton(
                text = "History",
                icon = Icons.Default.Cached,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { signalRClient.requestAiHistory() }
            )

            ActionKeyButton(
                text = "Focus",
                icon = Icons.Default.Adjust,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { 
                    if (selectedPid != -1) {
                        signalRClient.focusAiSession(selectedPid)
                    }
                }
            )
        }

        // Row 4: Presets & Voice
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionKeyButton(
                text = "Trigger",
                icon = Icons.Default.Cached, // Use Cached as refresh icon
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { 
                    onMessageSent()
                    signalRClient.sendAiMessage("-", if (selectedPid != -1) selectedPid else null) 
                }
            )

            ActionKeyButton(
                text = "Voice",
                icon = Icons.Default.Mic,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { onVoiceTrigger() }
            )

            ActionKeyButton(
                text = "Bottom",
                icon = Icons.Default.KeyboardDoubleArrowDown,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { onScrollToBottom() }
            )

            ActionKeyButton(
                text = "Model",
                icon = Icons.Default.Tune,
                modifier = Modifier.weight(1f).height(33.dp),
                onClick = { signalRClient.sendAiMessage("/model", selectedPid) }
            )
            
            // Fill remaining space
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

@Composable
fun ToolCallBubble(content: String) {
    val toolData = remember(content) {
        val raw = content.removePrefix("[Tool Call]").trim()
                        .removePrefix("Tool Call:").trim()
        try {
            val gson = com.google.gson.Gson()
            if (raw.startsWith("{")) {
                val map = gson.fromJson(raw, Map::class.java)
                val name = map["name"] as? String
                val args = map["args"]
                if (name != null) {
                    val argsStr = if (args != null) {
                        if (args is String) args else gson.toJson(args)
                    } else raw
                    name to argsStr
                } else {
                    // Legacy/Fallback parsing: "Name { ... }"
                    val namePart = raw.substringBefore("{").trim().ifBlank { "Tool" }
                    val argsPart = raw.substring(raw.indexOf("{"))
                    namePart to argsPart
                }
            } else if (raw.contains("(")) {
                // Legacy/Fallback parsing: "Name( ... )"
                val namePart = raw.substringBefore("(").trim().ifBlank { "Tool" }
                val argsPart = raw.substringAfter("(").substringBeforeLast(")")
                namePart to argsPart
            } else {
                "Tool" to raw
            }
        } catch (e: Exception) {
            "Tool" to raw
        }
    }

    val isEditOrReplace = toolData.first.equals("Edit", ignoreCase = true) || 
                         toolData.first.equals("Replace", ignoreCase = true) ||
                         toolData.first.equals("patch", ignoreCase = true) ||
                         toolData.first.equals("replace", ignoreCase = true)

    Column(
        modifier = Modifier.padding(8.dp).fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp), tint = Color(0xFF666600))
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Tool Call: ${toolData.first}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF666600)
            )
        }
        Spacer(Modifier.height(4.dp))
        
        if (isEditOrReplace) {
            // Parse JSON args for diff rendering
            val diffInfo = remember(toolData.second) {
                try {
                    val gson = com.google.gson.Gson()
                    val map = gson.fromJson(toolData.second, Map::class.java)
                    val old = map["old_string"] as? String ?: map["find"] as? String
                    val new = map["new_string"] as? String ?: map["replace"] as? String
                    val file = map["file_path"] as? String ?: map["path"] as? String
                    val instruction = map["instruction"] as? String
                    Triple(old, new, file) to instruction
                } catch (e: Exception) {
                    null
                }
            }

            if (diffInfo != null) {
                val (old, new, file) = diffInfo.first
                val instruction = diffInfo.second
                
                Surface(
                    color = Color.White.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        if (!file.isNullOrBlank()) {
                            Text("File: $file", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        }
                        if (!instruction.isNullOrBlank()) {
                            Text("Instruction: $instruction", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = Color.DarkGray)
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        if (old != null && new != null) {
                            // Specialized interleaved Diff-like rendering
                            val diffLines = computeInterleavedDiff(old, new)
                            
                            Column(modifier = Modifier.background(Color.Black.copy(alpha = 0.05f)).padding(4.dp)) {
                                diffLines.forEach { (type, line) ->
                                    val (prefix, color, bgColor) = when(type) {
                                        "DEL" -> Triple("-", Color(0xFFB71C1C), Color(0xFFFFEBEE))
                                        "ADD" -> Triple("+", Color(0xFF1B5E20), Color(0xFFE6FFEC))
                                        else -> Triple(" ", Color.DarkGray, Color.Transparent)
                                    }
                                    
                                    Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = "$prefix $line", 
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                lineHeight = 12.sp
                                            ), 
                                            color = color,
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            ToolCallRawArgs(toolData.second)
                        }
                    }
                }
            } else {
                // Fallback to raw text
                ToolCallRawArgs(toolData.second)
            }
        } else {
            ToolCallRawArgs(toolData.second)
        }
    }
}

fun computeInterleavedDiff(old: String, new: String): List<Pair<String, String>> {
    val oldLines = old.split("\n")
    val newLines = new.split("\n")
    val result = mutableListOf<Pair<String, String>>()
    
    // Simple greedy matcher for tool-call style diffs
    // Since these are usually small chunks, we just output all DEL then all ADD
    // but try to match common prefixes/suffixes if they were identical (context)
    
    var startMatch = 0
    while (startMatch < oldLines.size && startMatch < newLines.size && oldLines[startMatch] == newLines[startMatch]) {
        result.add("UNCHANGED" to oldLines[startMatch])
        startMatch++
    }
    
    var endMatchOld = oldLines.size - 1
    var endMatchNew = newLines.size - 1
    val endResult = mutableListOf<Pair<String, String>>()
    while (endMatchOld >= startMatch && endMatchNew >= startMatch && oldLines[endMatchOld] == newLines[endMatchNew]) {
        endResult.add(0, "UNCHANGED" to oldLines[endMatchOld])
        endMatchOld--
        endMatchNew--
    }
    
    // Middle part is different
    for (i in startMatch..endMatchOld) {
        result.add("DEL" to oldLines[i])
    }
    for (i in startMatch..endMatchNew) {
        result.add("ADD" to newLines[i])
    }
    
    result.addAll(endResult)
    return result
}

@Composable
fun ToolCallRawArgs(args: String) {
    val prettyArgs = remember(args) {
        try {
            if (args.trim().startsWith("{") || args.trim().startsWith("[")) {
                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                val parser = com.google.gson.JsonParser()
                val element = parser.parse(args)
                gson.toJson(element)
            } else {
                args
            }
        } catch (e: Exception) {
            args
        }
    }
    
    Surface(
        color = Color.White.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = prettyArgs,
            modifier = Modifier.padding(6.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp
            ),
            color = Color(0xFF333333)
        )
    }
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
    val isUser = sender == "User"
    val isCodeDiff = sender == "CodeDiff"
    val isToolCall = sender == "System" && (content.startsWith("Tool Call:") || content.startsWith("[Tool Call]"))
    val isError = sender == "Error" || content.startsWith("Error:")
    val isSystem = sender == "System" && !isToolCall || (!isMe && !isAi && !isUser && !isError && !isCodeDiff && !isToolCall)
    
    val context = LocalContext.current
    val timestamp = remember { 
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) 
    }

    val alignment = when {
        isError || isSystem || isCodeDiff || isToolCall -> Alignment.CenterHorizontally
        isMe -> Alignment.End
        else -> Alignment.Start
    }
    
    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        isSystem -> Color(0xFFFFF176).copy(alpha = 0.7f) // Yellow for system
        isToolCall -> Color(0xFFFFF176).copy(alpha = 0.9f) // Dull yellow for tool calls
        isCodeDiff -> Color.Black.copy(alpha = 0.9f)
        isMe -> MaterialTheme.colorScheme.primaryContainer
        isAi -> MaterialTheme.colorScheme.secondaryContainer
        isUser -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isSystem -> Color(0xFF333333) // Dark text for yellow
        isToolCall -> Color(0xFF666600) // Dark yellowish for tool call text
        isCodeDiff -> Color.White
        isUser -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val icon = when {
        isMe -> Icons.Default.Person
        isAi -> Icons.Default.SmartToy
        isCodeDiff -> Icons.Default.Code
        isToolCall -> Icons.Default.Tune
        isError -> Icons.Default.Error
        else -> Icons.Default.SettingsSuggest
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isSystem && !isError && !isCodeDiff && !isToolCall) {
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
        } else if (isCodeDiff || isError || isSystem || isToolCall) {
             Icon(
                 icon, 
                 null, 
                 modifier = Modifier.size(16.dp).padding(bottom = 2.dp), 
                 tint = when {
                     isError -> MaterialTheme.colorScheme.error
                     isToolCall -> Color(0xFF666600)
                     isSystem -> Color(0xFF666600)
                     else -> Color.White
                 }
             )
        }

        Surface(
            color = bgColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = if (isSystem || isError || isCodeDiff || isToolCall) 380.dp else 300.dp)
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
                    when {
                        isCodeDiff -> DiffText(content)
                        isToolCall -> ToolCallBubble(content)
                        else -> MarkdownText(
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

@Composable
fun AiDialogBubble(
    dialog: SignalRClient.AiDialog,
    signalRClient: SignalRClient,
    selectedPid: Int
) {
    // Gracefully ignore internal or ready dialogs
    if (dialog.type == "ready" || dialog.type == "status") return

    // State to hold answers for questions if present
    val answers = remember(dialog) { mutableMapOf<Int, String>() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 350.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val isStatus = dialog.options != null && dialog.options.isEmpty() && dialog.questions == null
                val hasQuestions = dialog.questions != null && dialog.questions!!.isNotEmpty()
                
                Text(
                    text = when {
                        isStatus -> "Status"
                        hasQuestions -> "Questions Required"
                        else -> "Choice Required"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(8.dp))
                
                if (!hasQuestions) {
                    Text(
                        text = dialog.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                
                if (!isStatus) {
                    Spacer(Modifier.height(16.dp))
                    
                    if (hasQuestions) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            dialog.questions!!.forEachIndexed { index, question ->
                                AiQuestionInput(
                                    question = question,
                                    onAnswerChanged = { answer ->
                                        answers[index] = answer
                                    }
                                )
                            }
                            
                            Button(
                                onClick = {
                                    val gson = com.google.gson.Gson()
                                    val jsonResponse = gson.toJson(answers.mapKeys { it.key.toString() })
                                    signalRClient.sendAiDialogResponse(jsonResponse, selectedPid)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Submit Answers")
                            }
                        }
                    } else if (dialog.options != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dialog.options.forEach { option ->
                                Button(
                                    onClick = { signalRClient.sendAiDialogResponse(option, selectedPid) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Text(option)
                                }
                            }
                        }
                    } else {
                        // Default to Yes/No if options and questions are null
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { signalRClient.sendAiDialogResponse("yes", selectedPid) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Text("Yes")
                            }
                            Button(
                                onClick = { signalRClient.sendAiDialogResponse("no", selectedPid) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("No")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiQuestionInput(
    question: SignalRClient.AiQuestion,
    onAnswerChanged: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (question.header.isNotEmpty()) {
            Text(
                text = question.header,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        
        when (question.type) {
            "choice" -> {
                val options = question.options ?: emptyList()
                var selectedLabel by remember { mutableStateOf("") }
                
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { option ->
                        val isSelected = selectedLabel == option.label
                        Surface(
                            onClick = {
                                selectedLabel = option.label
                                onAnswerChanged(option.label)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (option.description.isNotEmpty()) {
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            "yesno" -> {
                var selectedValue by remember { mutableStateOf<Boolean?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedValue == true,
                        onClick = {
                            selectedValue = true
                            onAnswerChanged("yes")
                        },
                        label = { Text("Yes") }
                    )
                    FilterChip(
                        selected = selectedValue == false,
                        onClick = {
                            selectedValue = false
                            onAnswerChanged("no")
                        },
                        label = { Text("No") }
                    )
                }
            }
            else -> { // "text"
                var textValue by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        onAnswerChanged(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { question.placeholder?.let { Text(it) } },
                    maxLines = 1,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

val CircleShape = RoundedCornerShape(50)