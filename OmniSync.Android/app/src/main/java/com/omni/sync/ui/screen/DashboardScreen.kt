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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.ClipboardManager
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.viewmodel.AppScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.material.icons.filled.ContentCopy

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    ERROR,
    UNKNOWN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient, mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val appConfig = mainViewModel.appConfig
    // Collect connection states
    val connectionStateString by signalRClient.connectionState.collectAsState()
    val isConnected by mainViewModel.isConnected.collectAsState()
    
    val sleepDuration by mainViewModel.sleepDuration.collectAsState()
    val isSleeping by mainViewModel.isSleeping.collectAsState()

    val logs by mainViewModel.dashboardLogs.collectAsState()
    val hubLogs by mainViewModel.hubLogs.collectAsState()
    val isShowingHubLogs by mainViewModel.isShowingHubLogs.collectAsState()
    
    val displayLogs = if (isShowingHubLogs) hubLogs else logs

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Determine connection status based on actual isConnected state
    val connectionStatus = when {
        isConnected -> ConnectionStatus.CONNECTED
        connectionStateString.contains("Error", ignoreCase = true) -> ConnectionStatus.ERROR
        connectionStateString.contains("Disconnected", ignoreCase = true) -> ConnectionStatus.DISCONNECTED
        else -> ConnectionStatus.UNKNOWN
    }
    
    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(displayLogs.size) {
        if (displayLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayLogs.size - 1)
        }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("OmniSync Dashboard") },
            actions = {
                IconButton(onClick = { mainViewModel.navigateTo(AppScreen.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        )
        
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Connection Status Card
            HubConnectionCard(
                connectionStatus = connectionStatus,
                connectionMessage = connectionStateString,
                onReconnect = { signalRClient?.manualReconnect() },
                onWake = { mainViewModel.sendWakeOnLan(appConfig.wakeOnLanMac) }
            )

            SleepTrackingCard(
                isSleeping = isSleeping,
                duration = sleepDuration,
                onStartSleep = { mainViewModel.startSleep() },
                onWokeUp = { mainViewModel.resetSleep() }
            )

            if (!isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Remote Wake-on-LAN Help", fontWeight = FontWeight.Bold)
                        Text(
                            "To wake your PC from outside your home network, you must configure your router to forward UDP port 9 to your PC's IP address. Use MAC-IP binding (ARP reservation) for best results.",
                            style = MaterialTheme.typography.bodySmall
                        )
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
                                mainViewModel.addLog("Triggering Shutdown...", LogType.WARNING)
                                signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\shutdown.bat")
                            },
                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Shutdown")
                        }
                        
                                                Button(
                                                    onClick = {
                                                        mainViewModel.addLog("Triggering Sleep...", LogType.INFO)
                                                        signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\sleep.bat")
                                                    },
                                                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                                                ) {
                                                    Text("Sleep")
                                                }
                                            }
                        
                                            Spacer(modifier = Modifier.height(8.dp))
                        
                                            Button(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clipData = clipboard.primaryClip
                                                    if (clipData != null && clipData.itemCount > 0) {
                                                        val text = clipData.getItemAt(0).text?.toString()
                                                        if (!text.isNullOrBlank()) {
                                                            val prompt = "Summarize and analyze the following content from my clipboard:\n\n$text"
                                                            signalRClient.sendAiMessage(prompt)
                                                            mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.AI_CHAT)
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                            ) {
                                                Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Smart AI (Clipboard)")
                                            }
                        
                                            Spacer(modifier = Modifier.height(8.dp))                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(
                            onClick = {
                                mainViewModel.addLog("Toggling TV...", LogType.INFO)
                                signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat")
                            },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) {
                            Text("TV")
                        }
                        
                        Button(
                            onClick = {
                                mainViewModel.sendWakeOnLan(appConfig.wakeOnLanMac)
                            },
                            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("WOL")
                        }

                        Button(
                            onClick = {
                                mainViewModel.addLog("Capturing ADB logs...", LogType.INFO)
                                // We use the full path to extractcrash.py which is in the project root/OmniSync.Android
                                val scriptPath = "D:\\SSDProjects\\Omni\\OmniSync.Android\\extractcrash.py"
                                signalRClient.executeCommand("python \"$scriptPath\"")
                            },
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("ADB Log")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = {
                            mainViewModel.clearLogs() 
                            mainViewModel.addLog("Logs cleared", LogType.INFO)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Clear Logs")
                    }
                }
            }
            
            // Logs Section
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isShowingHubLogs) "Hub Log" else "Activity Log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val allLogs = displayLogs.joinToString("\n") { 
                                    "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp))}] ${it.message}" 
                                }
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(allLogs))
                                mainViewModel.addLog("Logs copied to clipboard", LogType.SUCCESS)
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy All Logs")
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            TextButton(onClick = { 
                                if (!isShowingHubLogs) {
                                    mainViewModel.fetchHubLogs(signalRClient)
                                    mainViewModel.toggleLogSource()
                                } else {
                                    // Switch back to App logs
                                    mainViewModel.toggleLogSource()
                                }
                            }) {
                                Text(if (isShowingHubLogs) "Show App Log" else "Fetch Hub Log")
                            }
                        }
                    }
                    
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayLogs) { log ->
                            LogItem(log)
                        }
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
    
    androidx.compose.foundation.text.selection.SelectionContainer {
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
}

@Composable
fun HubConnectionCard(
    connectionStatus: ConnectionStatus,
    connectionMessage: String,
    onReconnect: () -> Unit,
    onWake: () -> Unit
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
    
                    
    
                    // Show reconnect and wake buttons when not connected
    
                    if (connectionStatus != ConnectionStatus.CONNECTED) {
    
                        Row {
    
                            IconButton(onClick = onWake) {
    
                                Icon(
    
                                    imageVector = Icons.Filled.PowerSettingsNew,
    
                                    contentDescription = "Wake PC",
    
                                    tint = MaterialTheme.colorScheme.tertiary
    
                                )
    
                            }
    
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
    
    }
    
    
    
    @Composable
    
    fun SleepTrackingCard(
    
        isSleeping: Boolean,
    
        duration: String,
    
        onStartSleep: () -> Unit,
    
        onWokeUp: () -> Unit
    
    ) {
    
        Card(
    
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    
            colors = CardDefaults.cardColors(
    
                containerColor = if (isSleeping) Color(0xFF3F51B5).copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    
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
    
                            imageVector = if (isSleeping) Icons.Default.CheckCircle else Icons.Default.Refresh,
    
                            contentDescription = "Sleep Status",
    
                            tint = if (isSleeping) Color(0xFF3F51B5) else MaterialTheme.colorScheme.outline,
    
                            modifier = Modifier.size(32.dp)
    
                        )
    
                        Spacer(modifier = Modifier.width(8.dp))
    
                        Column {
    
                            Text(
    
                                text = "Sleep Tracking",
    
                                style = MaterialTheme.typography.titleMedium,
    
                                fontWeight = FontWeight.Bold
    
                            )
    
                            Text(
    
                                text = if (isSleeping) "Asleep for $duration" else "User is active",
    
                                style = MaterialTheme.typography.bodyMedium,
    
                                color = if (isSleeping) Color(0xFF3F51B5) else MaterialTheme.colorScheme.onSurfaceVariant
    
                            )
    
                        }
    
                    }
    
                    
    
                    if (isSleeping) {
    
                        Button(onClick = onWokeUp) {
    
                            Text("Woke up")
    
                        }
    
                    } else {
    
                        OutlinedButton(onClick = onStartSleep) {
    
                            Text("Start Sleep")
    
                        }
    
                    }
    
                }
    
            }
    
        }
    
    }
    
    