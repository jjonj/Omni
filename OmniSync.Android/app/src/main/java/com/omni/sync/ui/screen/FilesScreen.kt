package com.omni.sync.ui.screen

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.viewmodel.FilesViewModel
import java.text.SimpleDateFormat
import java.util.Locale

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    filesViewModel: FilesViewModel = viewModel()
) {
    val currentPath by filesViewModel.currentPath.collectAsState()
    val fileSystemEntries by filesViewModel.fileSystemEntries.collectAsState()
    val isLoading by filesViewModel.isLoading.collectAsState()
    val searchQuery by filesViewModel.searchQuery.collectAsState()
    val errorMessage by filesViewModel.errorMessage.collectAsState()

    // Download-specific states
    val isDownloading by filesViewModel.isDownloading.collectAsState()
    val downloadingFile by filesViewModel.downloadingFile.collectAsState()
    val downloadProgress by filesViewModel.downloadProgress.collectAsState()
    val downloadingSpeed by filesViewModel.downloadingSpeed.collectAsState()
    val downloadErrorMessage by filesViewModel.downloadErrorMessage.collectAsState()

    val context = LocalContext.current // Get context for Toast

    LaunchedEffect(Unit) {
        if (fileSystemEntries.isEmpty()) {
            filesViewModel.loadDirectory("")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Files: ${currentPath.ifEmpty { "/" }}") },
                navigationIcon = {
                    // Only show back button if currentPath is not empty and not just "/"
                    if (currentPath.isNotEmpty() && currentPath != "/") {
                        IconButton(onClick = { filesViewModel.loadDirectory(getParentPath(currentPath)) }) {
                            Icon(Icons.Filled.ArrowBack, "Back")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // --- Search Bar ---
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { filesViewModel.performSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                placeholder = { Text("Search in current folder...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { filesViewModel.performSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (isLoading || isDownloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (isDownloading && downloadingFile != null) {
                    Text(
                        text = "Downloading ${downloadingFile?.name}: $downloadProgress% (${downloadingSpeed})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
            downloadErrorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }


            if (fileSystemEntries.isEmpty() && !isLoading && errorMessage == null && !isDownloading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No files or directories found. Connect to Hub or check path.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(fileSystemEntries) { entry ->
                        FileSystemEntryItem(
                            entry = entry,
                            onClick = { clickedEntry ->
                                if (clickedEntry.isDirectory) {
                                    filesViewModel.loadDirectory(clickedEntry.path)
                                } else {
                                    when {
                                        isVideoFile(clickedEntry.name) -> {
                                            val playlist = fileSystemEntries.filter { isVideoFile(it.name) }.map { it.path }
                                            filesViewModel.mainViewModel.playVideo(clickedEntry.path, playlist)
                                        }
                                        isImageFile(clickedEntry.name) || isPdfFile(clickedEntry.name) || isAudioFile(clickedEntry.name) -> {
                                            filesViewModel.startFileDownload(clickedEntry)
                                            Toast.makeText(context, "Downloading ${clickedEntry.name}...", Toast.LENGTH_SHORT).show()
                                        }
                                        else -> {
                                            filesViewModel.openForEditing(clickedEntry)
                                        }
                                    }
                                }
                            },
                            onLongClick = { clickedEntry ->
                                filesViewModel.openFileOnPC(clickedEntry)
                                Toast.makeText(context, "Opening ${clickedEntry.name} on PC", Toast.LENGTH_SHORT).show()
                            },
                            onDownloadAndOpen = { clickedEntry ->
                                filesViewModel.startFileDownload(clickedEntry)
                                Toast.makeText(context, "Downloading ${clickedEntry.name}...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileSystemEntryItem(
    entry: FileSystemEntry, 
    onClick: (FileSystemEntry) -> Unit, 
    onLongClick: (FileSystemEntry) -> Unit,
    onDownloadAndOpen: (FileSystemEntry) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onClick(entry) },
                    onLongClick = { 
                        if (!entry.isDirectory) {
                            showMenu = true 
                        }
                    }
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                entry.isDirectory -> Icons.Default.Folder
                isVideoFile(entry.name) -> Icons.Default.InsertDriveFile
                isAudioFile(entry.name) -> Icons.Default.InsertDriveFile
                isImageFile(entry.name) -> Icons.Default.InsertDriveFile
                isPdfFile(entry.name) -> Icons.Default.InsertDriveFile
                else -> Icons.Default.InsertDriveFile
            }

            Icon(
                imageVector = icon,
                contentDescription = if (entry.isDirectory) "Folder" else "File",
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                if (!entry.isDirectory) {
                    Text(text = "${(entry.size / 1024.0).format(2)} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val lmText = run {
                val ts = entry.lastModified.time
                if (ts <= 0L) "" else SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(entry.lastModified)
            }
            if (lmText.isNotEmpty()) {
                Text(
                    text = lmText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Edit as Text") },
                onClick = {
                    showMenu = false
                    onClick(entry)
                }
            )
            DropdownMenuItem(
                text = { Text("Download & Open (Ext App)") },
                onClick = {
                    showMenu = false
                    onDownloadAndOpen(entry)
                }
            )
            DropdownMenuItem(
                text = { Text("Open on PC") },
                onClick = {
                    showMenu = false
                    onLongClick(entry)
                }
            )
        }
    }
}

private fun getParentPath(path: String): String {
    // This function is for client-side navigation up, if the server-provided ".." path is not used
    // However, the server now provides the correct ".." path, so this might not be needed for direct use with loadDirectory(entry.path)
    val separator = System.getProperty("file.separator") ?: "/"
    return if (path.contains(separator) && path.lastIndexOf(separator) > 0) { // Check if it's not just "/"
        path.substringBeforeLast(separator)
    } else {
        "" // Already at root or empty path
    }
}


fun Double.format(digits: Int) = "%.${digits}f".format(Locale.getDefault(), this)

// Helper function to check extensions
fun isVideoFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov")
}

fun isAudioFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".m4a")
}

fun isImageFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
}

fun isPdfFile(filename: String): Boolean {
    return filename.lowercase().endsWith(".pdf")
}