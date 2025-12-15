package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?) {
    // Collect connection state
    val connectionState by signalRClient?.connectionState?.collectAsState() ?: remember { mutableStateOf("No Client") }
    
    // Local log state for this screen
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    
    // Helper function to add logs
    fun addLog(message: String, type: LogType = LogType.INFO) {
        logs = logs + LogEntry(message, type, System.currentTimeMillis())
        if (logs.size > 100) {
            logs = logs.takeLast(100)
        }
    }
    
    // Monitor connection state changes
    LaunchedEffect(connectionState) {
        addLog("Connection state: $connectionState", 
            if (connectionState.contains("Connected") && !connectionState.contains("Disconnected")) 
                LogType.SUCCESS 
            else if (connectionState.contains("Error"))
                LogType.ERROR
            else 
                LogType.INFO
        )
    }
    
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    connectionState.contains("Connected") && !connectionState.contains("Disconnected") -> 
                        Color(0xFF4CAF50).copy(alpha = 0.1f)
                    connectionState.contains("Error") -> Color(0xFFF44336).copy(alpha = 0.1f)
                    else -> Color(0xFFFF9800).copy(alpha = 0.1f)
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (connectionState.contains("Connected") && !connectionState.contains("Disconnected"))
                                Icons.Filled.CheckCircle
                            else
                                Icons.Filled.Error,
                            contentDescription = "Connection Status",
                            tint = when {
                                connectionState.contains("Connected") && !connectionState.contains("Disconnected") -> 
                                    Color(0xFF4CAF50)
                                connectionState.contains("Error") -> Color(0xFFF44336)
                                else -> Color(0xFFFF9800)
                            },
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Hub Connection",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = connectionState,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Reconnect Button
                    IconButton(
                        onClick = {
                            addLog("Manual reconnection initiated", LogType.INFO)
                            coroutineScope.launch {
                                signalRClient?.stopConnection()
                                delay(500)
                                signalRClient?.startConnection()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reconnect",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        
        // Test Buttons Section
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Quick Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = {
                            addLog("Testing connection...", LogType.INFO)
                            signalRClient?.executeCommand("echo Connection test successful")
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Text("Test Echo")
                    }
                    
                    Button(
                        onClick = {
                            addLog("Listing notes...", LogType.INFO)
                            signalRClient?.listNotes()?.subscribe(
                                { notes ->
                                    addLog("Found ${notes.size} notes", LogType.SUCCESS)
                                },
                                { error ->
                                    addLog("Error listing notes: ${error.message}", LogType.ERROR)
                                }
                            )
                        },
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    ) {
                        Text("List Notes")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = {
                            addLog("Sending clipboard update...", LogType.INFO)
                            signalRClient?.sendClipboardUpdate("Test from Android: ${System.currentTimeMillis()}")
                            addLog("Clipboard update sent", LogType.SUCCESS)
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Text("Test Clipboard")
                    }
                    
                    Button(
                        onClick = {
                            logs = emptyList()
                            addLog("Logs cleared", LogType.INFO)
                        },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Clear Logs")
                    }
                }
            }
        }
        
        // Logs Section
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                Text(
                    text = "Activity Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { log ->
                        LogItem(log)
                    }
                }
            }
        }
    }
}

data class LogEntry(
    val message: String,
    val type: LogType,
    val timestamp: Long
)

enum class LogType {
    INFO, SUCCESS, ERROR, WARNING
}

@Composable
fun LogItem(log: LogEntry) {
    val backgroundColor = when (log.type) {
        LogType.SUCCESS -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        LogType.ERROR -> Color(0xFFF44336).copy(alpha = 0.1f)
        LogType.WARNING -> Color(0xFFFF9800).copy(alpha = 0.1f)
        LogType.INFO -> Color.Transparent
    }
    
    val textColor = when (log.type) {
        LogType.SUCCESS -> Color(0xFF4CAF50)
        LogType.ERROR -> Color(0xFFF44336)
        LogType.WARNING -> Color(0xFFFF9800)
        LogType.INFO -> MaterialTheme.colorScheme.onSurface
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        Text(
            text = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))}]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}
