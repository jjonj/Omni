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
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient, mainViewModel: MainViewModel) {
    // Collect connection states
    val connectionStateString by signalRClient?.connectionState?.collectAsState() ?: remember { mutableStateOf("No Client") }
    val isConnected by mainViewModel.isConnected.collectAsState()
    
    val logs by mainViewModel.dashboardLogs.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Determine connection status based on actual isConnected state
    val connectionStatus = when {
        isConnected -> ConnectionStatus.CONNECTED
        connectionStateString.contains("Error", ignoreCase = true) -> ConnectionStatus.ERROR
        connectionStateString.contains("Disconnected", ignoreCase = true) -> ConnectionStatus.DISCONNECTED
        else -> ConnectionStatus.UNKNOWN
    }
    
    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Connection Status Card
        HubConnectionCard(
            connectionStatus = connectionStatus,
            connectionMessage = connectionStateString,
            onReconnect = { signalRClient?.manualReconnect() }
        )
        
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
                            mainViewModel.addLog("Testing connection...", LogType.INFO)
                            signalRClient?.executeCommand("echo Connection test successful")
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Text("Test Echo")
                    }
                    
                    Button(
                        onClick = {
                            mainViewModel.addLog("Listing notes...", LogType.INFO)
                            signalRClient?.listNotes()?.subscribe(
                                { notes: List<String> ->
                                    mainViewModel.addLog("Found ${notes.size} notes", LogType.SUCCESS)
                                },
                                { error: Throwable ->
                                    mainViewModel.addLog("Error listing notes: ${error.message}", LogType.ERROR)
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
                            mainViewModel.addLog("Sending clipboard update...", LogType.INFO)
                            signalRClient?.sendClipboardUpdate("Test from Android: ${System.currentTimeMillis()}")
                            mainViewModel.addLog("Clipboard update sent", LogType.SUCCESS)
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Text("Test Clipboard")
                    }
                    
                    Button(
                        onClick = {
                            // Call a function in MainViewModel to clear logs
                            // This will be added in MainViewModel in the next step
                            mainViewModel.clearLogs() 
                            mainViewModel.addLog("Logs cleared", LogType.INFO)
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

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    ERROR,
    UNKNOWN
}

@Composable
fun HubConnectionCard(
    connectionStatus: ConnectionStatus,
    connectionMessage: String,
    onReconnect: () -> Unit
) {
    val statusColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
        ConnectionStatus.ERROR -> Color(0xFFF44336)
        ConnectionStatus.DISCONNECTED -> Color(0xFFFF9800)
        ConnectionStatus.UNKNOWN -> Color(0xFF9E9E9E)
    }
    
    val statusIcon = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Error
    }
    
    val statusText = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.ERROR -> "Connection Error"
        ConnectionStatus.UNKNOWN -> "Unknown Status"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = "Connection Status",
                        tint = statusColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hub Connection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                        if (connectionMessage.isNotBlank() && connectionMessage != statusText) {
                            Text(
                                text = connectionMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
                
                // Show reconnect button only when not connected
                if (connectionStatus != ConnectionStatus.CONNECTED) {
                    IconButton(onClick = onReconnect) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reconnect",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}