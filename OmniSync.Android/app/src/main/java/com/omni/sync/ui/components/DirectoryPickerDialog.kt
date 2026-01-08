package com.omni.sync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.viewmodel.FilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPickerDialog(
    filesViewModel: FilesViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    val entries by filesViewModel.fileSystemEntries.collectAsState()
    val isLoading by filesViewModel.isLoading.collectAsState()

    LaunchedEffect(currentPath) {
        filesViewModel.loadDirectory(currentPath)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Folder") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Text(
                    text = if (currentPath.isEmpty()) "/" else currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (currentPath.isNotEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentPath = getParentPath(currentPath)
                                        }
                                        .padding(vertical = 12.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text("..", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        
                        items(entries.filter { it.isDirectory && it.name != ".." }) { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentPath = entry.path
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentPath) },
                enabled = currentPath.isNotEmpty() // Usually don't want to add root as a workspace
            ) {
                Text("Select Current")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun getParentPath(path: String): String {
    if (path.isEmpty()) return ""
    val separator = if (path.contains("/")) "/" else "\\"
    if (path.length <= 3 && path.contains(":")) return ""
    val lastIndex = path.lastIndexOf(separator)
    if (lastIndex > 0) {
        val parent = path.substring(0, lastIndex)
        if (parent.endsWith(":")) return parent + separator
        return parent
    }
    return ""
}
