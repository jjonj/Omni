package com.omni.sync.ui.screen

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.viewmodel.FilesViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.material.icons.filled.Add
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    filesViewModel: FilesViewModel = viewModel()
) {
    val currentPath by filesViewModel.currentPath.collectAsState()
    val fileSystemEntries by filesViewModel.fileSystemEntries.collectAsState()
    val folderBookmarks by filesViewModel.folderBookmarks.collectAsState()
    val isLoading by filesViewModel.isLoading.collectAsState()
    val searchQuery by filesViewModel.searchQuery.collectAsState()
    val errorMessage by filesViewModel.errorMessage.collectAsState()

    var showBookmarksList by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }

    // Handle back press to navigate up
    BackHandler(enabled = currentPath.isNotEmpty() && currentPath != "/") {
        filesViewModel.loadDirectory(getParentPath(currentPath))
    }

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
                },
                actions = {
                    IconButton(onClick = { 
                        filesViewModel.mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.DOWNLOADED_VIDEOS)
                    }) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Downloaded Videos")
                    }
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = { showCreateFileDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Create File")
                        }
                    }
                    IconButton(onClick = { filesViewModel.loadDirectory(currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    val isCurrentBookmarked = filesViewModel.isFolderBookmarked(currentPath)
                    IconButton(onClick = { 
                        filesViewModel.toggleFolderBookmark(
                            FileSystemEntry(
                                name = currentPath.substringAfterLast("\\").substringAfterLast("/").ifEmpty { "Root" },
                                path = currentPath,
                                isDirectory = true,
                                size = 0,
                                lastModified = java.util.Date()
                            )
                        )
                    }) {
                        Icon(if (isCurrentBookmarked) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Bookmark Current")
                    }
                }
            )
        },
        bottomBar = {
            // --- Compact Bookmarks Area (Bottom, always visible) ---
            if (folderBookmarks.isNotEmpty()) {
                Surface(tonalElevation = 2.dp) {
                    Column {
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                        ) {
                            IconButton(onClick = { showBookmarksList = !showBookmarksList }) {
                                Icon(if (showBookmarksList) Icons.Default.Close else Icons.Default.Menu, contentDescription = "Manage Bookmarks")
                            }
                            LazyRow(
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(folderBookmarks) { bookmark ->
                                    InputChip(
                                        selected = currentPath == bookmark.path,
                                        onClick = { filesViewModel.loadDirectory(bookmark.path) },
                                        label = { Text(bookmark.name, maxLines = 1) },
                                        leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // --- Search Bar ---
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { filesViewModel.performSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No files found.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(fileSystemEntries) {
                        entry ->
                        FileSystemEntryItem(
                            entry = entry,
                            isSearching = searchQuery.isNotEmpty(),
                            isBookmarked = filesViewModel.isFolderBookmarked(entry.path),
                            onBookmarkToggle = { filesViewModel.toggleFolderBookmark(it) },
                            onClick = { clickedEntry ->
                                if (clickedEntry.isDirectory) {
                                    filesViewModel.loadDirectory(clickedEntry.path)
                                } else {
                                    when {
                                        clickedEntry.name.lowercase().endsWith(".flv") -> {
                                            Toast.makeText(context, "FLV format not supported by player", Toast.LENGTH_SHORT).show()
                                        }
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
                            },
                            onOpenFolder = { path ->
                                filesViewModel.loadDirectory(path)
                            },
                            onOpenInAiChat = { entry ->
                                filesViewModel.mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.AI_CHAT)
                                filesViewModel.signalRClient.sendAiMessage("/dir add \"${entry.path}\"")
                            },
                            onDownloadVideo = { entry, password ->
                                filesViewModel.downloadVideoToAppData(entry, password)
                                Toast.makeText(context, "Downloading video...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // --- Bookmarks Management List (Toggleable) - Moved to bottom ---
            if (showBookmarksList && folderBookmarks.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Manage Bookmarks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showBookmarksList = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        folderBookmarks.forEach { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(bookmark.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                
                                IconButton(onClick = { filesViewModel.moveFolderBookmarkUp(bookmark) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, null)
                                }
                                IconButton(onClick = { filesViewModel.moveFolderBookmarkDown(bookmark) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, null)
                                }
                                IconButton(onClick = { filesViewModel.removeFolderBookmark(bookmark) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create New File") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Filename (e.g. note.txt)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            filesViewModel.createNewFile(newFileName)
                            showCreateFileDialog = false
                            newFileName = ""
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileSystemEntryItem(
    entry: FileSystemEntry, 
    isSearching: Boolean = false,
    isBookmarked: Boolean = false,
    onBookmarkToggle: (FileSystemEntry) -> Unit = {},
    onClick: (FileSystemEntry) -> Unit, 
    onLongClick: (FileSystemEntry) -> Unit,
    onDownloadAndOpen: (FileSystemEntry) -> Unit,
    onOpenFolder: (String) -> Unit = {},
    onOpenInAiChat: (FileSystemEntry) -> Unit = {},
    onDownloadVideo: (FileSystemEntry, String?) -> Unit = { _, _ -> }
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onClick(entry) },
                    onLongClick = { 
                        showMenu = true
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
                if (isSearching) {
                    Text(text = entry.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!entry.isDirectory) {
                    Text(text = "${(entry.size / 1024.0).format(2)} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            if (entry.isDirectory) {
                IconButton(onClick = { onBookmarkToggle(entry) }) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
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
            if (!entry.isDirectory) {
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
                if (isVideoFile(entry.name)) {
                    DropdownMenuItem(
                        text = { Text("Download Video to App") },
                        onClick = {
                            showMenu = false
                            showDownloadDialog = true
                        }
                    )
                }
            }
            if (isSearching) {
                DropdownMenuItem(
                    text = { Text("Open Containing Folder") },
                    onClick = {
                        showMenu = false
                        onOpenFolder(getParentPath(entry.path))
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Open on PC") },
                onClick = {
                    showMenu = false
                    onLongClick(entry)
                }
            )
            if (entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text("Open folder in AI chat") },
                    onClick = {
                        showMenu = false
                        onOpenInAiChat(entry)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (isBookmarked) "Remove Bookmark" else "Add Bookmark") },
                    onClick = {
                        showMenu = false
                        onBookmarkToggle(entry)
                    }
                )
            }
        }
        
        if (showDownloadDialog) {
            var password by remember { mutableStateOf("") }
            var encrypt by remember { mutableStateOf(false) }
            
            AlertDialog(
                onDismissRequest = { showDownloadDialog = false },
                title = { Text("Download Video") },
                text = {
                    Column {
                        Text("Download '${entry.name}' to app storage?")
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = encrypt,
                                onCheckedChange = { encrypt = it }
                            )
                            Text("Encrypt with password")
                        }
                        if (encrypt) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDownloadDialog = false
                            onDownloadVideo(entry, if (encrypt && password.isNotBlank()) password else null)
                        }
                    ) {
                        Text("Download")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDownloadDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun getParentPath(path: String): String {
    if (path.isEmpty()) return ""
    val separator = if (path.contains("/")) "/" else "\\"
    
    // Check if it's a root drive (e.g. "C:\" or "C:/")
    if (path.length <= 3 && path.contains(":")) {
        return "" // Go to drive list (root)
    }

    val lastIndex = path.lastIndexOf(separator)
    if (lastIndex > 0) {
        // If we are at "C:\Users", lastIndex is 2. Substring(0, 2) is "C:".
        // We want to return "C:\" so we include the separator if it's the root.
        // Or simply: if the result ends in ":", append separator.
        val parent = path.substring(0, lastIndex)
        if (parent.endsWith(":")) {
            return parent + separator
        }
        return parent
    }
    return ""
}


fun Double.format(digits: Int) = "%.${digits}f".format(Locale.getDefault(), this)

// Helper function to check extensions
fun isVideoFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || 
           lower.endsWith(".mov") || lower.endsWith(".mpg") || lower.endsWith(".wmv") || 
           lower.endsWith(".3gp")
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