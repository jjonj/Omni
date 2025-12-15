package com.omni.sync.ui.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.ProcessInfo
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel // Ensure you have this import
import java.text.DecimalFormat

// Import local or shared ViewModel instance mechanism
// For this snippet, I assume commandOutput is passed or accessible. 

enum class SortOption { NAME, MEMORY, CPU }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(
    modifier: Modifier = Modifier, 
    signalRClient: SignalRClient?,
    mainViewModel: MainViewModel // Ideally pass this in
) {
    // --- STATE ---
    var commandInput by remember { mutableStateOf("") }
    // Local state for processes
    var processes by remember { mutableStateOf(listOf<ProcessInfo>()) }
    var isLoading by remember { mutableStateOf(false) }
    
    // Sort and Filter State
    var filterText by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.MEMORY) }

    // --- HELPER: Fetch Function ---
    fun fetchProcesses() {
        if (signalRClient == null) return
        isLoading = true
        signalRClient.listProcesses()?.subscribe({ result ->
            processes = result
            isLoading = false
        }, { error ->
            Log.e("ProcessScreen", "Error fetching processes", error)
            isLoading = false
        })
    }

    // --- EFFECT: Initial Load ---
    LaunchedEffect(Unit) {
        fetchProcesses()
    }

    // --- DERIVED STATE: Filter & Sort ---
    // This updates immediately when processes, filterText, or sortOption changes
    val filteredProcesses = remember(processes, filterText, sortOption) {
        processes
            .filter { (it.name ?: "").contains(filterText, ignoreCase = true) }
            .sortedWith(compareByDescending<ProcessInfo> { 
                when(sortOption) {
                    SortOption.MEMORY -> it.memoryUsage.toDouble()
                    SortOption.CPU -> it.cpuUsage
                    else -> 0.0
                }
            }.let { comparator ->
                if (sortOption == SortOption.NAME) compareBy { it.name } else comparator
            })
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        
        // ================= COMMAND SECTION =================
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
                    executeCommands(commandInput, signalRClient)
                    commandInput = "" 
                }
            )
        )
        
        // Output Window with Auto-Scroll
        val scrollState = rememberScrollState()
        // NOTE: Replace 'output_text_here' with mainViewModel.commandOutput
        // I am creating a dummy state here so it compiles for you, but connect it to VM!
        val outputText by mainViewModel.commandOutput.collectAsState()

        // Auto-Scroll Logic
        LaunchedEffect(outputText) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Text(
                text = outputText, // Bind this to MainViewModel commandOutput
                color = Color(0xFF00FF00),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(scrollState)
            )
        }

        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // ================= PROCESS SECTION =================
        
        // Header & Manual Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Processes (${filteredProcesses.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            IconButton(onClick = { fetchProcesses() }) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
        }

        // Filter & Sorting
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Filter Input
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it }, // Triggers local filter immediately
                placeholder = { Text("Filter...") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            
            // Sort Chips
            Row {
                SortChip("Mem", SortOption.MEMORY, sortOption) { sortOption = it }
                SortChip("Cpu", SortOption.CPU, sortOption) { sortOption = it }
                SortChip("Name", SortOption.NAME, sortOption) { sortOption = it }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        // Process List
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (filteredProcesses.isEmpty() && !isLoading) {
                item { Text("No processes found.", modifier = Modifier.padding(8.dp)) }
            }
            itemsIndexed(filteredProcesses) { index, process ->
                ProcessItem(process, signalRClient)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortChip(label: String, option: SortOption, current: SortOption, onSelect: (SortOption) -> Unit) {
    FilterChip(
        selected = (option == current),
        onClick = { onSelect(option) },
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.padding(end = 4.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
fun ProcessItem(process: ProcessInfo, signalRClient: SignalRClient?) {
    Card(
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 4.dp, horizontal = 8.dp) // Compact padding
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name Info
            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = process.name ?: "",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                if (!process.mainWindowTitle.isNullOrEmpty()) {
                                    Text(
                                        text = process.mainWindowTitle ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        fontSize = 10.sp
                                    )
                                }            }
            
            // Stats
            Column(
                horizontalAlignment = Alignment.End, 
                modifier = Modifier.padding(horizontal = 8.dp).width(70.dp)
            ) {
                Text(formatBytes(process.memoryUsage), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("${process.cpuUsage}%", fontSize = 11.sp, color = if(process.cpuUsage > 10) Color.Red else Color.Gray)
            }

            // Compact Kill Button
            IconButton(
                onClick = { 
                    signalRClient?.killProcess(process.id.toInt())?.subscribe() 
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close, 
                    contentDescription = "Kill", 
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

fun executeCommands(input: String, client: SignalRClient?) {
    val delimiters = charArrayOf(';', 'ø')
    val commands = input.split(*delimiters)
    commands.forEach { cmd ->
        if (cmd.isNotBlank()) {
            client?.executeCommand(cmd.trim())
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups < 0) digitGroups = 0
    if (digitGroups >= units.size) digitGroups = units.size - 1
    return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}