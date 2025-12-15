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

import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?) {
    var commandInput by remember { mutableStateOf("") }
    var commandOutput by remember { mutableStateOf("") }
    var processes by remember { mutableStateOf(listOf<ProcessInfo>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        if (errorMessage != null) {
            Text(
                text = "Error: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Command Execution Section
        OutlinedTextField(
            value = commandInput,
            onValueChange = { commandInput = it },
            label = { Text("Enter Command (e.g. 'calc')") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                commandOutput = "Sending..."
                signalRClient?.executeCommand(commandInput)
                // Note: Output usually comes back via "ReceiveCommandOutput" listener in Client
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Execute Command")
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Process Management Section
        Text("Running Processes:", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { 
                errorMessage = null
                signalRClient?.listProcesses()?.subscribe(
                    { result -> 
                        processes = result 
                        if (result.isEmpty()) errorMessage = "No processes returned (or parsing failed)"
                    }, 
                    { error -> 
                        errorMessage = error.message 
                        Log.e("ProcessScreen", "List failed", error)
                    }
                ) 
            },
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ID: ${process.id}", style = MaterialTheme.typography.labelSmall)
                            Text(process.name, style = MaterialTheme.typography.bodyMedium)
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
