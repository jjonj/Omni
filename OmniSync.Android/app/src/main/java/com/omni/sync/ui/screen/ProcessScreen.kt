package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.data.repository.ProcessInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?) {
    var commandInput by remember { mutableStateOf("") }
    var commandOutput by remember { mutableStateOf("") }
    var processes by remember { mutableStateOf(listOf<ProcessInfo>()) }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // Command Execution Section
        OutlinedTextField(
            value = commandInput,
            onValueChange = { commandInput = it },
            label = { Text("Enter Command") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                commandOutput = "" // Clear previous output
                signalRClient?.executeCommand(commandInput)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Execute Command")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Command Output:", style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                item {
                    Text(commandOutput)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Process Management Section
        Text("Running Processes:", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { signalRClient?.listProcesses()?.subscribe({ processes = it }, { /* Handle Error */ }) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh Processes")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                items(processes) { process ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("ID: ${process.id} Name: ${process.name}")
                            if (!process.mainWindowTitle.isNullOrEmpty()) {
                                Text("Title: ${process.mainWindowTitle}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = { signalRClient?.killProcess(process.id) }) {
                            Text("Kill")
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ProcessScreenPreview() {
    ProcessScreen(signalRClient = null)
}
