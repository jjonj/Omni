package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.ProcessInfo
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel // Add this import
import java.text.DecimalFormat

enum class SortOption { NAME, MEMORY, CPU }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?, mainViewModel: MainViewModel?) {
    // State
    var commandInput by remember { mutableStateOf("") }
    // We observe output from MainViewModel now
    val commandOutput by (mainViewModel?.commandOutput?.collectAsState() ?: remember { mutableStateOf("") })
    
    var processes by remember { mutableStateOf(listOf<ProcessInfo>()) }
    var filterText by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.MEMORY) } // Default sort by Memory
    
    // Sorting Logic
    val filteredProcesses = remember(processes, filterText, sortOption) {
        processes
            .filter { it.name.contains(filterText, ignoreCase = true) }
            .sortedWith(compareByDescending { 
                when(sortOption) {
                    SortOption.MEMORY -> it.memoryUsage.toDouble()
                    SortOption.CPU -> it.cpuUsage
                    else -> 0.0
                }
            })
            .let { if (sortOption == SortOption.NAME) it.sortedBy { p -> p.name } else it }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        
        // --- COMMAND SECTION ---
        Text("Command Execution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = commandInput,
            onValueChange = { commandInput = it },
            label = { Text("Commands (split by ';' or 'ø')") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    executeCommands(commandInput, signalRClient, mainViewModel) // Pass mainViewModel
                    commandInput = "" // Clear after send
                }
            )
        )
        
        Button(
            onClick = { 
                executeCommands(commandInput, signalRClient, mainViewModel) // Pass mainViewModel
                commandInput = ""
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Execute")
        }

        // Output Window
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp) // Fixed height output window
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            val scrollState = rememberScrollState()
            Text(
                text = commandOutput, // Use observed commandOutput
                color = Color.Green,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(scrollState)
            )
        }

        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- PROCESS LIST SECTION ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Processes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            // Refresh Button
            IconButton(onClick = { 
                signalRClient?.listProcesses()?.subscribe({ result -> 
                    processes = result 
                }, { /* Handle Error */ }) 
            }) {
                Icon(Icons.Default.Sort, "Refresh") // Placeholder icon
            }
        }

        // Filter & Sort
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                label = { Text("Filter by Name") },
                modifier = Modifier.weight(1f)
            )
            
            // Sort Buttons (Mini)
            Column {
                Row {
                    SortChip("Mem", SortOption.MEMORY, sortOption) { sortOption = it }
                    SortChip("Cpu", SortOption.CPU, sortOption) { sortOption = it }
                    SortChip("Name", SortOption.NAME, sortOption) { sortOption = it }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        // Process List
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredProcesses) { process ->
                ProcessItem(process, signalRClient, mainViewModel)
            }
        }
    }
}

@Composable
fun SortChip(label: String, option: SortOption, current: SortOption, onSelect: (SortOption) -> Unit) {
    FilterChip(
        selected = (option == current),
        onClick = { onSelect(option) },
        label = { Text(label, fontSize = 10.sp) },
        modifier = Modifier.padding(end = 4.dp)
    )
}

@Composable
fun ProcessItem(process: ProcessInfo, signalRClient: SignalRClient?, mainViewModel: MainViewModel?) {
    Card(
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name and Title
            Column(modifier = Modifier.weight(1f)) {
                Text(process.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (!process.mainWindowTitle.isNullOrEmpty()) {
                    Text(process.mainWindowTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            
            // Stats
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(formatBytes(process.memoryUsage), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                Text("${process.cpuUsage}% CPU", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            }

            // Kill Button (Compact)
            Button(
                onClick = { 
                    signalRClient?.killProcess(process.id)?.subscribe(
                        { mainViewModel?.addLog("Kill requested for process ${process.name} (${process.id})", LogType.INFO) }, 
                        { e -> mainViewModel?.addLog("Kill failed for process ${process.name} (${process.id}): ${e.message}", LogType.ERROR) }
                    ) 
                },
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.height(30.dp).width(50.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Kill", modifier = Modifier.size(16.dp))
            }
        }
    }
}

fun executeCommands(input: String, client: SignalRClient?, mainViewModel: MainViewModel?) {
    val delimiters = charArrayOf(';', 'ø')
    val commands = input.split(*delimiters)
    commands.forEach { cmd ->
        if (cmd.isNotBlank()) {
            client?.executeCommand(cmd.trim())
            mainViewModel?.addLog("Executing command: ${cmd.trim()}", LogType.INFO) // Add log
        }
    }
}

fun formatBytes(bytes: Long): String {
    val df = DecimalFormat("#.##")
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb > 1 -> "${df.format(gb)} GB"
        mb > 1 -> "${df.format(mb)} MB"
        else -> "${df.format(kb)} KB"
    }
}
