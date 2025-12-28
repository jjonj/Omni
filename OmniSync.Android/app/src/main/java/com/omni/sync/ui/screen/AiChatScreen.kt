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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import androidx.compose.animation.core.*

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel
) {
    val messages by signalRClient.aiMessages.collectAsState()
    val aiStatus by signalRClient.aiStatus.collectAsState()
    val sessions by signalRClient.aiSessions.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showSessionMenu by remember { mutableStateOf(false) }
    var selectedPid by remember { mutableIntStateOf(-1) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var pidToRename by remember { mutableIntStateOf(-1) }
    
    val listState = rememberLazyListState()
    val isAiTyping = aiStatus != null
    val coroutineScope = rememberCoroutineScope()
    val isStartingSession by signalRClient.isStartingSessionFlow.collectAsState()
    val isConnected by mainViewModel.isConnected.collectAsState()

    LaunchedEffect(Unit) {
        signalRClient.getAiSessions()
        signalRClient.lastCreatedSessionPid.collect { pid ->
            selectedPid = pid
        }
    }

    // Sync selectedPid with available sessions
    LaunchedEffect(sessions) {
        if (selectedPid == -1 && sessions.isNotEmpty()) {
            selectedPid = sessions.keys.first()
        } else if (selectedPid != -1 && !sessions.containsKey(selectedPid)) {
            selectedPid = if (sessions.isNotEmpty()) sessions.keys.first() else -1
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(messages, isAiTyping) {
        if (messages.isNotEmpty() || isAiTyping) {
            kotlinx.coroutines.delay(100)
            val lastIndex = if (isAiTyping) messages.size else messages.size - 1
            if (lastIndex >= 0) {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { 
                            if (isConnected) {
                                signalRClient.getAiSessions()
                                showSessionMenu = true 
                            }
                        }, enabled = isConnected) {
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
                                        selectedPid = pid
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
                        val pidToStop = selectedPid
                        signalRClient.stopAiSession(pidToStop)
                        signalRClient.clearAiMessages(if (pidToStop != -1) pidToStop else null)
                    }, enabled = isConnected) {
                        Icon(Icons.Default.Close, contentDescription = "Close Session")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { (sender, content) ->
                    ChatBubble(sender, content)
                }

                if (isAiTyping) {
                    item {
                        AiTypingIndicator()
                    }
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
                        } else {
                            // If empty, send Enter key to Hub
                            signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            QuickActionPanel(signalRClient, coroutineScope, selectedPid, isConnected)
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Text("Esc", fontSize = 12.sp)
            }

            FilledTonalButton(
                onClick = { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_UP) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(18.dp))
            }

            FilledTonalButton(
                onClick = { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_DOWN) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
            }

            FilledTonalButton(
                onClick = { 
                    coroutineScope.launch {
                        signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_Y)
                        kotlinx.coroutines.delay(100)
                        signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    }
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Text("Yolo", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { 
                    coroutineScope.launch {
                        signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                        if (!isZoomed) {
                            repeat(20) { 
                                signalRClient.sendPayload("MOUSE_SCROLL", mapOf("Delta" to 120))
                                delay(10)
                            }
                        } else {
                            repeat(20) { 
                                signalRClient.sendPayload("MOUSE_SCROLL", mapOf("Delta" to -120))
                                delay(10)
                            }
                        }
                        signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                        isZoomed = !isZoomed
                    }
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Text(if (isZoomed) "Unzoom" else "Zoom 5x", fontSize = 12.sp)
            }

            FilledTonalButton(
                onClick = { signalRClient.clearAiMessages(if (selectedPid != -1) selectedPid else null) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear", fontSize = 12.sp)
            }
            
            FilledTonalButton(
                onClick = { signalRClient.requestAiHistory() },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = isConnected
            ) {
                Icon(imageVector = Icons.Default.Cached, contentDescription = "Reload History", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("History", fontSize = 12.sp)
            }
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

@Composable
fun ChatBubble(sender: String, content: String) {
    val isMe = sender == "Me"
    val isAi = sender == "AI"
    val isSystem = sender == "System" || content.startsWith("Error:")

    val alignment = when {
        isSystem -> Alignment.CenterHorizontally
        isMe -> Alignment.End
        else -> Alignment.Start
    }
    
    val bgColor = when {
        isSystem -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        isMe -> MaterialTheme.colorScheme.primaryContainer
        isAi -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isSystem -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isSystem) {
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
            modifier = Modifier.widthIn(max = if (isSystem) 350.dp else 300.dp)
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = if (isSystem) TextAlign.Center else TextAlign.Start
            )
        }
    }
}

val CircleShape = RoundedCornerShape(50)