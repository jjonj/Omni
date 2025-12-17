package com.omni.sync.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NoteViewerScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient) {
    var notes by remember { mutableStateOf(listOf<String>()) }
    var selectedNoteContent by remember { mutableStateOf<String?>(null) }
    var selectedNoteName by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(signalRClient) {
        if (signalRClient != null) {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    signalRClient.listNotes()?.subscribe({ notesList: List<String> ->
                        notes = notesList
                    }, { error: Throwable ->
                        println("Error listing notes: ${error.message}")
                    })
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(text = "Note Viewer Screen", modifier = Modifier.padding(8.dp))

        if (selectedNoteContent != null && selectedNoteName != null) {
            // Display selected note content
            Text(text = "--- $selectedNoteName ---", modifier = Modifier.padding(8.dp))
            Text(text = selectedNoteContent!!, modifier = Modifier.padding(8.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Display list of notes
        if (notes.isEmpty()) {
            Text(text = "No notes found or not connected.", modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn {
                items(notes) { noteName ->
                    Text(
                        text = noteName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        signalRClient?.getNoteContent(noteName)?.subscribe({ content: String ->
                                            selectedNoteContent = content
                                            selectedNoteName = noteName
                                        }, { error: Throwable ->
                                            println("Error getting note content: ${error.message}")
                                            selectedNoteContent = "Error: ${error.message}"
                                            selectedNoteName = noteName
                                        })
                                    }
                                }
                            }
                            .padding(8.dp)
                    )
                    Divider()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NoteViewerScreenPreview() {
    NoteViewerScreen(signalRClient = com.omni.sync.OmniSyncApplication().signalRClient)
}
