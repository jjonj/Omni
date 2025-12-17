package com.omni.sync.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.viewmodel.FilesViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import android.widget.Toast // New import
import androidx.compose.ui.platform.LocalContext // New import

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    filesViewModel: FilesViewModel = viewModel()
) {
    val currentPath by filesViewModel.currentPath.collectAsState()
    val fileSystemEntries by filesViewModel.fileSystemEntries.collectAsState()
    val isLoading by filesViewModel.isLoading.collectAsState()
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
                        FileSystemEntryItem(entry = entry) { clickedEntry ->
                            if (clickedEntry.isDirectory) {
                                // For ".." entry, we just use the path provided by the server which is already parent
                                filesViewModel.loadDirectory(clickedEntry.path)
                            } else {
                                if (isVideoFile(clickedEntry.name)) {
                                    // STREAM VIDEO
                                    filesViewModel.mainViewModel.playVideo(clickedEntry.path)
                                } else {
                                    // Handle file click (initiate download/streaming)
                                    filesViewModel.startFileDownload(clickedEntry)
                                    Toast.makeText(context, "Starting download for ${clickedEntry.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileSystemEntryItem(entry: FileSystemEntry, onClick: (FileSystemEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(entry) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
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
