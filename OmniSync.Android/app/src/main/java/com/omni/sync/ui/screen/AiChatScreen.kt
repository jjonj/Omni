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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.ui.components.ActionKeyButton
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.omni.sync.utils.WindowsKeyCodes.VK_CONTROL
import com.omni.sync.utils.WindowsKeyCodes.VK_DOWN
import com.omni.sync.utils.WindowsKeyCodes.VK_ESCAPE
import com.omni.sync.utils.WindowsKeyCodes.VK_RETURN
import com.omni.sync.utils.WindowsKeyCodes.VK_UP
import com.omni.sync.utils.WindowsKeyCodes.VK_Y

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AiChatScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val messages by signalRClient.aiMessages.collectAsState()
    val aiStatus by signalRClient.aiStatus.collectAsState()
    val sessions by signalRClient.aiSessions.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showSessionMenu by remember { mutableStateOf(false) }
    val selectedPid by signalRClient.selectedPid.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var pidToRename by remember { mutableIntStateOf(-1) }
    
    val listState = rememberLazyListState()
    val isAiThinking = aiStatus?.contains("Thinking", ignoreCase = true) == true
    val coroutineScope = rememberCoroutineScope()
    val isStartingSession by signalRClient.isStartingSessionFlow.collectAsState()
    val isConnected by mainViewModel.isConnected.collectAsState()

    // Animation for session button flash
    val sessionButtonAnim = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        signalRClient.anyAiActivityEvent.collect {
            // Flash animation
            sessionButtonAnim.animateTo(1f, animationSpec = tween(300))
            sessionButtonAnim.animateTo(0f, animationSpec = tween(300))
        }
    }

    // Filter messages to avoid empty/dangling bubbles
    val filteredMessages = remember(messages) {
        messages.filter { it.second.isNotBlank() }
    }

    LaunchedEffect(Unit) {
        signalRClient.getAiSessions()
    }

    // Auto-scroll to bottom (Index 0 in reverse layout)
    LaunchedEffect(filteredMessages, isAiThinking) {
        if (filteredMessages.isNotEmpty()) {
            val isReloading = aiStatus?.contains("Reloading", ignoreCase = true) == true || 
                             aiStatus?.contains("Switching", ignoreCase = true) == true
            
            if (isReloading) {
                // Jump instantly for history reloads/switches
                listState.scrollToItem(0)
            } else if (isAiThinking) {
                // Animate for thinking indicator
                listState.animateScrollToItem(0)
            }
        }
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
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { signalRClient.startNewAiSession() }, enabled = isConnected) {
                        Icon(Icons.Default.Add, contentDescription = "New Session")
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
                    .padding(bottom = currentBottomPadding + 115.dp) // Leave room for floating panel
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

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                ) {
                    if (isAiThinking) {
                        item {
                            AiTypingIndicator()
                        }
                    }

                    items(filteredMessages.reversed()) { (sender, content) ->
                        ChatBubble(sender, content)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (isConnected) "Ask AI something..." else "Connecting...") },
                        maxLines = 3,
                        enabled = isConnected
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                signalRClient.sendAiMessage(inputText, if (selectedPid != -1) selectedPid else null)
                                inputText = ""
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
                    QuickActionPanel(signalRClient, coroutineScope, selectedPid, isConnected)
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
    }
}

@Composable
fun QuickActionPanel(
    signalRClient: SignalRClient, 
    coroutineScope: kotlinx.coroutines.CoroutineScope, 
    selectedPid: Int,
    isConnected: Boolean
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
                onClick = { signalRClient.sendAiSpecialKey("escape") }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardArrowUp,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("up") }
            )

            ActionKeyButton(
                icon = Icons.Default.KeyboardArrowDown,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("down") }
            )

            ActionKeyButton(
                text = "Enter",
                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                modifier = Modifier.weight(1f),
                onClick = { signalRClient.sendAiSpecialKey("enter") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionKeyButton(
                text = "Yolo",
                modifier = Modifier.weight(1f),
                onClick = { 
                    coroutineScope.launch {
                        signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_Y)
                        kotlinx.coroutines.delay(100)
                        signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    }
                }
            )

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
fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha), CircleShape)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(sender: String, content: String) {
    val isMe = sender == "Me"
    val isAi = sender == "AI"
    val isError = sender == "Error" || content.startsWith("Error:")
    val isSystem = sender == "System" || (!isMe && !isAi && !isError)
    
    val context = LocalContext.current

    val alignment = when {
        isError || isSystem -> Alignment.CenterHorizontally
        isMe -> Alignment.End
        else -> Alignment.Start
    }
    
    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        isSystem -> Color(0xFFFFF176).copy(alpha = 0.7f) // Yellow for system
        isMe -> MaterialTheme.colorScheme.primaryContainer
        isAi -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isSystem -> Color(0xFF333333) // Dark text for yellow
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isSystem && !isError) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = if (isSystem || isError) 350.dp else 300.dp)
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
            MarkdownText(
                text = content,
                textColor = textColor,
                textAlign = if (isSystem || isError) TextAlign.Center else TextAlign.Start
            )
        }
    }
}

@Composable
fun MarkdownText(text: String, textColor: Color, textAlign: TextAlign) {
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
                    Text(
                        text = content.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        textAlign = textAlign
                    )
                }
            }
        }
    }
}

val CircleShape = RoundedCornerShape(50)