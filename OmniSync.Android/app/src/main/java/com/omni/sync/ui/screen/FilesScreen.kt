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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Bookmark
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
import com.omni.sync.utils.isAudioFile
import com.omni.sync.utils.isImageFile
import com.omni.sync.utils.isPdfFile
import com.omni.sync.utils.isVideoFile
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Add
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.sp
import com.omni.sync.ui.components.VerticalScrollbar
import com.omni.sync.ui.components.DirectoryPickerDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    filesViewModel: FilesViewModel = viewModel(),
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val currentPath by filesViewModel.currentPath.collectAsState()
    val fileSystemEntries by filesViewModel.fileSystemEntries.collectAsState()
    val bookmarks by filesViewModel.bookmarks.collectAsState()
    val isLoading by filesViewModel.isLoading.collectAsState()
    val searchQuery by filesViewModel.searchQuery.collectAsState()
    val errorMessage by filesViewModel.errorMessage.collectAsState()
    val pendingEditPaths by filesViewModel.pendingEditPaths.collectAsState()
    val recentlyChangedPaths by filesViewModel.recentlyChangedPaths.collectAsState()
    val cachedPaths by filesViewModel.cachedPaths.collectAsState()

    val isGitRepo by filesViewModel.isGitRepo.collectAsState()
    val gitLog by filesViewModel.gitLog.collectAsState()
    val commitDiff by filesViewModel.commitDiff.collectAsState()
    val isGitLoading by filesViewModel.isGitLoading.collectAsState()

    var showGitDialog by remember { mutableStateOf(false) }
    var showDiffDialog by remember { mutableStateOf(false) }

    val sessions by filesViewModel.signalRClient.aiSessions.collectAsState()
    val selectedPid by filesViewModel.signalRClient.selectedPid.collectAsState()
    val activeSessionName = sessions[selectedPid]

    var showBookmarksList by remember { mutableStateOf(false) }
    var showCachesList by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordTargetEntry by remember { mutableStateOf<FileSystemEntry?>(null) }
    var passwordAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var sourceEntry by remember { mutableStateOf<FileSystemEntry?>(null) }

    // Handle back press to navigate up or close panels
    val isKeyboardVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    BackHandler(enabled = !isKeyboardVisible && ((currentPath.isNotEmpty() && currentPath != "/") || showBookmarksList || showCachesList || showCopyDialog || showMoveDialog)) {
        if (showBookmarksList) {
            showBookmarksList = false
        } else if (showCachesList) {
            showCachesList = false
        } else if (showCopyDialog) {
            showCopyDialog = false
        } else if (showMoveDialog) {
            showMoveDialog = false
        } else {
            filesViewModel.loadDirectory(getParentPath(currentPath))
        }
    }

    // Download-specific states
    val isDownloading by filesViewModel.isDownloading.collectAsState()
    val downloadingFile by filesViewModel.downloadingFile.collectAsState()
    val downloadProgress by filesViewModel.downloadProgress.collectAsState()
    val downloadingSpeed by filesViewModel.downloadingSpeed.collectAsState()
    val downloadErrorMessage by filesViewModel.downloadErrorMessage.collectAsState()

    val context = LocalContext.current // Get context for Toast

    val listState = rememberLazyListState()
    
    // Restore scroll position when currentPath changes
    LaunchedEffect(currentPath) {
        val (index, offset) = filesViewModel.getScrollPosition(currentPath)
        listState.scrollToItem(index, offset)
    }

    // Save scroll position when navigating away or scrolling
    DisposableEffect(currentPath) {
        onDispose {
            filesViewModel.saveScrollPosition(currentPath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }

    LaunchedEffect(Unit) {
        if (currentPath.isEmpty() && filesViewModel.mainViewModel.pendingNavigationPath.value == null) {
            filesViewModel.loadDirectory("")
        }
    }

    Scaffold(
        topBar = {
            var showHeaderMenu by remember { mutableStateOf(false) }
            TopAppBar(
                title = { 
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AutoResizingText(
                                text = "Files: ${currentPath.ifEmpty { "/" }}",
                                modifier = Modifier.combinedClickable(
                                    onClick = { },
                                    onLongClick = { showHeaderMenu = true }
                                )
                            )
                            if (isGitRepo) {
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { showGitDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Menu, // Using Menu as placeholder for branch/git icon
                                        contentDescription = "Git Log",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showHeaderMenu,
                            onDismissRequest = { showHeaderMenu = false }
                        ) {
                            if (currentPath.isNotEmpty() && currentPath != "/") {
                                if (isGitRepo) {
                                    DropdownMenuItem(
                                        text = { Text("View Git Log") },
                                        onClick = {
                                            showHeaderMenu = false
                                            showGitDialog = true
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Copy Path to Clipboard") },
                                    onClick = {
                                        showHeaderMenu = false
                                        filesViewModel.copyPathToClipboard(currentPath)
                                        Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                if (selectedPid != -1 && activeSessionName != null) {
                                     DropdownMenuItem(
                                        text = { Text("AI: Add dir to $activeSessionName") },
                                        onClick = {
                                            showHeaderMenu = false
                                            filesViewModel.signalRClient.sendAiMessage("/dir add \"$currentPath\"")
                                            Toast.makeText(context, "Added to $activeSessionName", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Open folder in AI chat") },
                                    onClick = {
                                        showHeaderMenu = false
                                        filesViewModel.mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.AI_CHAT)
                                        filesViewModel.signalRClient.sendAiMessage("/dir add \"$currentPath\"")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("CLI Here") },
                                    onClick = {
                                        showHeaderMenu = false
                                        filesViewModel.openCliHere(FileSystemEntry("", currentPath, true, 0, java.util.Date()))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cache Whole Folder") },
                                    onClick = {
                                        showHeaderMenu = false
                                        filesViewModel.cacheFolderRecursive(FileSystemEntry("", currentPath, true, 0, java.util.Date()))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (filesViewModel.isBookmarked(currentPath)) "Remove Bookmark" else "Add Bookmark") },
                                    onClick = {
                                        showHeaderMenu = false
                                        filesViewModel.bookmarkCurrentDirectory()
                                    }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (showBookmarksList) {
                        IconButton(onClick = { showBookmarksList = false }) {
                            Icon(Icons.Default.Close, "Close Bookmarks")
                        }
                    } else if (currentPath.isNotEmpty() && currentPath != "/") {
                        IconButton(onClick = { filesViewModel.loadDirectory(getParentPath(currentPath)) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (currentPath.isNotEmpty() && currentPath != "/") {
                        IconButton(onClick = { filesViewModel.bookmarkCurrentDirectory() }) {
                            Icon(
                                if (filesViewModel.isBookmarked(currentPath)) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Bookmark Current Folder"
                            )
                        }
                    }
                    IconButton(onClick = { filesViewModel.loadDirectory("") }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { filesViewModel.loadDirectory(currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (currentPath.isNotEmpty() && !showBookmarksList && !showCachesList) {
                FloatingActionButton(
                    onClick = { showCreateFileDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Create New File")
                }
            }
        },
        bottomBar = {
            // --- Compact Bookmarks Area (Bottom, always visible) ---
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                    ) {
                        IconButton(onClick = { 
                            showBookmarksList = !showBookmarksList
                            if (showBookmarksList) showCachesList = false
                        }) {
                            Icon(if (showBookmarksList) Icons.Default.Close else Icons.Default.Menu, contentDescription = "Manage Bookmarks")
                        }
                        IconButton(onClick = { 
                            showCachesList = !showCachesList
                            if (showCachesList) showBookmarksList = false
                        }) {
                            Icon(if (showCachesList) Icons.Default.Close else Icons.Default.Storage, contentDescription = "Manage Caches")
                        }
                        if (bookmarks.isEmpty()) {
                            Text(
                                "No bookmarks yet", 
                                style = MaterialTheme.typography.bodySmall, 
                                modifier = Modifier.weight(1f).padding(8.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(bookmarks) { bookmark ->
                                    InputChip(
                                        selected = currentPath == bookmark.path,
                                        onClick = { 
                                            if (bookmark.isDirectory) {
                                                filesViewModel.loadDirectory(bookmark.path)
                                            } else {
                                                filesViewModel.openForEditing(bookmark)
                                            }
                                        },
                                        label = { Text(bookmark.name, maxLines = 1) },
                                        leadingIcon = { 
                                            Icon(
                                                if (bookmark.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, 
                                                null, 
                                                modifier = Modifier.size(16.dp)
                                            ) 
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize().padding(bottom = parentPadding.calculateBottomPadding())
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
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
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(fileSystemEntries) { entry ->
                            val hasPendingEdit = !entry.isDirectory && pendingEditPaths.contains(entry.path)
                            val isRecentlyChanged = recentlyChangedPaths.contains(entry.path)
                            FileSystemEntryItem(
                                entry = entry,
                                activeSessionName = activeSessionName,
                                isSearching = searchQuery.isNotEmpty(),
                                isBookmarked = filesViewModel.isBookmarked(entry.path),
                                hasPendingEdit = hasPendingEdit,
                                isRecentlyChanged = isRecentlyChanged,
                                formatFileSize = { filesViewModel.formatFileSize(it) },
                                onBookmarkToggle = { filesViewModel.toggleBookmark(it) },
                                onAddToSession = { entry, name ->
                                    filesViewModel.signalRClient.sendAiMessage("/dir add \"${entry.path}\"")
                                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                                },
                                onClick = { clickedEntry ->
                                    if (clickedEntry.isDirectory) {
                                        if (clickedEntry.path == "VIRTUAL_ENCRYPTED") {
                                            if (filesViewModel.isGlobalPasswordSet()) {
                                                passwordAction = { filesViewModel.loadDirectory(clickedEntry.path) }
                                                showPasswordDialog = true
                                            } else {
                                                // First time setup handled in dialog
                                                passwordTargetEntry = clickedEntry
                                                showPasswordDialog = true
                                            }
                                        } else {
                                            filesViewModel.loadDirectory(clickedEntry.path)
                                        }
                                    } else {
                                        when {
                                            clickedEntry.name.lowercase().endsWith(".flv") -> {
                                                Toast.makeText(context, "FLV format not supported by player", Toast.LENGTH_SHORT).show()
                                            }
                                            isVideoFile(clickedEntry.name) -> {
                                                val playlist = fileSystemEntries.filter { isVideoFile(it.name) }.map { it.path }
                                                filesViewModel.mainViewModel.playVideo(clickedEntry.path, playlist)
                                            }
                                            isImageFile(clickedEntry.name) -> {
                                                val playlist = fileSystemEntries.filter { isImageFile(it.name) }.map { it.path }
                                                filesViewModel.mainViewModel.viewImages(clickedEntry.path, playlist)
                                            }
                                            isPdfFile(clickedEntry.name) || isAudioFile(clickedEntry.name) -> {
                                                filesViewModel.handleFileOpen(clickedEntry)
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
                                    filesViewModel.handleFileOpen(clickedEntry)
                                },
                                onOpenFolder = { path ->
                                    filesViewModel.loadDirectory(path)
                                },
                                onOpenInAiChat = { entry ->
                                    filesViewModel.mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.AI_CHAT)
                                    filesViewModel.signalRClient.sendAiMessage("/dir add \"${entry.path}\"")
                                },
                                onDuplicate = { entry ->
                                    filesViewModel.duplicateFile(entry)
                                },
                                onCliHere = { entry ->
                                    filesViewModel.openCliHere(entry)
                                },
                                onCacheFolderRecursive = { entry ->
                                    filesViewModel.cacheFolderRecursive(entry)
                                },
                                onDownloadVideo = { entry, isEncrypted ->
                                    if (isEncrypted) {
                                        val cachedPassword = filesViewModel.getVerifiedPassword()
                                        if (cachedPassword != null) {
                                            filesViewModel.downloadVideoWithGlobalPassword(entry, cachedPassword, true)
                                        } else if (filesViewModel.isGlobalPasswordSet()) {
                                            passwordAction = { 
                                                filesViewModel.downloadVideoWithGlobalPassword(entry, filesViewModel.getVerifiedPassword(), true)
                                            }
                                            passwordTargetEntry = entry
                                            showPasswordDialog = true
                                        } else {
                                            // First time setup handled in dialog
                                            passwordTargetEntry = entry
                                            showPasswordDialog = true
                                        }
                                    } else {
                                        filesViewModel.downloadVideoWithGlobalPassword(entry, null, false)
                                    }
                                },
                                onDeleteByPath = { path ->
                                    filesViewModel.deleteByPath(path)
                                },
                                onDeleteAllEncrypted = {
                                    filesViewModel.deleteAllEncrypted()
                                },
                                onCopyTo = { entry ->
                                    sourceEntry = entry
                                    showCopyDialog = true
                                },
                                onMoveTo = { entry ->
                                    sourceEntry = entry
                                    showMoveDialog = true
                                },
                                onViewGitLog = { entry ->
                                    filesViewModel.loadGitLog(entry.path)
                                    showGitDialog = true
                                }
                            )
                        }
                    }
                    
                    VerticalScrollbar(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }

            // --- Bookmarks Management List (Toggleable) - Moved to bottom ---
            if (showBookmarksList && bookmarks.isNotEmpty()) {
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
                        bookmarks.forEach { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (bookmark.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, 
                                    null, 
                                    modifier = Modifier.size(16.dp), 
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(bookmark.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                
                                IconButton(onClick = { filesViewModel.moveBookmarkUp(bookmark) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, null)
                                }
                                IconButton(onClick = { filesViewModel.moveBookmarkDown(bookmark) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, null)
                                }
                                IconButton(onClick = { filesViewModel.removeBookmark(bookmark) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            if (showCachesList) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Manage Caches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { filesViewModel.clearAllCaches() }) {
                                Text("Clear All", color = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { showCachesList = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (cachedPaths.isEmpty()) {
                            Text("No items cached", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(cachedPaths) { path ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Storage, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    
                                    IconButton(onClick = { filesViewModel.uncachePath(path) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    }
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

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false; passwordInput = ""; passwordTargetEntry = null; passwordAction = null },
            title = { 
                if (filesViewModel.isGlobalPasswordSet()) Text("Enter Password")
                else Text("Create drive")
            },
            text = {
                Column {
                    if (!filesViewModel.isGlobalPasswordSet()) {
                        Text("Name: Add...", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (filesViewModel.isGlobalPasswordSet()) {
                            if (filesViewModel.verifyGlobalPassword(passwordInput)) {
                                passwordAction?.invoke()
                                showPasswordDialog = false
                                passwordInput = ""
                            } else {
                                Toast.makeText(context, "Incorrect Password", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            if (passwordInput.isNotBlank()) {
                                filesViewModel.setGlobalPassword(null, passwordInput)
                                if (passwordTargetEntry != null) {
                                    filesViewModel.loadDirectory(passwordTargetEntry!!.path)
                                }
                                showPasswordDialog = false
                                passwordInput = ""
                            }
                        }
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false; passwordInput = ""; passwordTargetEntry = null; passwordAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCopyDialog && sourceEntry != null) {
        val isConnectedState by filesViewModel.mainViewModel.isConnected.collectAsState()
        DirectoryPickerDialog(
            signalRClient = filesViewModel.signalRClient,
            isConnected = isConnectedState,
            onDismiss = { showCopyDialog = false },
            onConfirm = { destDir: String ->
                showCopyDialog = false
                val separator = if (destDir.contains("/")) "/" else "\\"
                val entryName = sourceEntry?.name ?: ""
                val destPath = if (destDir.endsWith(separator)) "${destDir}${entryName}" else "${destDir}${separator}${entryName}"
                filesViewModel.copyFile(sourceEntry!!.path, destPath)
                sourceEntry = null
            }
        )
    }

    if (showMoveDialog && sourceEntry != null) {
        val isConnectedState by filesViewModel.mainViewModel.isConnected.collectAsState()
        DirectoryPickerDialog(
            signalRClient = filesViewModel.signalRClient,
            isConnected = isConnectedState,
            onDismiss = { showMoveDialog = false },
            onConfirm = { destDir: String ->
                showMoveDialog = false
                val separator = if (destDir.contains("/")) "/" else "\\"
                val entryName = sourceEntry?.name ?: ""
                val destPath = if (destDir.endsWith(separator)) "${destDir}${entryName}" else "${destDir}${separator}${entryName}"
                filesViewModel.moveFile(sourceEntry!!.path, destPath)
                sourceEntry = null
            }
        )
    }

    if (showGitDialog) {
        AlertDialog(
            onDismissRequest = { showGitDialog = false },
            title = { Text("Git Log: ${currentPath.substringAfterLast("\\").substringAfterLast("/")}") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    if (isGitLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (gitLog.isNullOrBlank()) {
                        Text("No log available or error loading.")
                    } else {
                        val commits = gitLog!!.split("\n").filter { it.isNotBlank() }
                        LazyColumn {
                            items(commits) { commitLine ->
                                // Format: "hash|author|relative_date|subject"
                                val parts = commitLine.removeSurrounding("\"").split("|")
                                if (parts.size >= 4) {
                                    val hash = parts[0]
                                    val author = parts[1]
                                    val date = parts[2]
                                    val subject = parts[3]
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { 
                                                filesViewModel.loadCommitDiff(currentPath, hash)
                                                showDiffDialog = true
                                            }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Text(subject, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row {
                                            Text(author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(8.dp))
                                            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                        Text(hash.take(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGitDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showDiffDialog) {
        AlertDialog(
            onDismissRequest = { showDiffDialog = false },
            title = { Text("Commit Diff") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    if (isGitLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (commitDiff.isNullOrBlank()) {
                        Text("No diff available.")
                    } else {
                        LazyColumn {
                            val lines = commitDiff!!.split("\n")
                            items(lines) { line ->
                                val color = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFFE6FFEC) // Light green
                                    line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFFEBEE) // Light red
                                    line.startsWith("@@") -> Color(0xFFF1F8FF) // Light blue
                                    else -> Color.Transparent
                                }
                                val textColor = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF22863A)
                                    line.startsWith("-") && !line.startsWith("---") -> Color(0xFFCB2431)
                                    line.startsWith("@@") -> Color(0xFF0366D6)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                
                                Text(
                                    text = line,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(color)
                                        .padding(horizontal = 4.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiffDialog = false }) {
                    Text("Back")
                }
            }
        )
    }
}

// Add this at the bottom of the file or in a separate file if preferred
@Composable
fun AutoResizingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    targetTextSize: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.titleMedium.fontSize
) {
    var textSize by remember { mutableStateOf(targetTextSize) }
    var readyToDraw by remember { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        maxLines = 2,
        style = MaterialTheme.typography.titleMedium,
        fontSize = textSize,
        lineHeight = textSize * 1.1f,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent {
            if (readyToDraw) drawContent()
        },
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowHeight || textLayoutResult.didOverflowWidth) {
                if (textSize.value > 8f) { // Scale down until 8sp
                    textSize = (textSize.value * 0.9f).sp
                } else {
                    readyToDraw = true
                }
            } else {
                readyToDraw = true
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileSystemEntryItem(
    entry: FileSystemEntry, 
    activeSessionName: String? = null,
    onAddToSession: (FileSystemEntry, String) -> Unit = { _, _ -> },
    isSearching: Boolean = false,
    isBookmarked: Boolean = false,
    hasPendingEdit: Boolean = false,
    isRecentlyChanged: Boolean = false,
    formatFileSize: (Long) -> String = { "" },
    onBookmarkToggle: (FileSystemEntry) -> Unit = {},
    onClick: (FileSystemEntry) -> Unit, 
    onLongClick: (FileSystemEntry) -> Unit,
    onDownloadAndOpen: (FileSystemEntry) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onOpenInAiChat: (FileSystemEntry) -> Unit = {},
    onDuplicate: (FileSystemEntry) -> Unit = {},
    onCliHere: (FileSystemEntry) -> Unit = {},
    onCacheFolderRecursive: (FileSystemEntry) -> Unit = {},
    onDownloadVideo: (FileSystemEntry, Boolean) -> Unit = { _, _ -> },
    onDeleteByPath: (String) -> Unit = {},
    onDeleteAllEncrypted: () -> Unit = {},
    onCopyTo: (FileSystemEntry) -> Unit = {},
    onMoveTo: (FileSystemEntry) -> Unit = {},
    onViewGitLog: (FileSystemEntry) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val flashColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isRecentlyChanged) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 500)
    )

    Box(modifier = Modifier.background(flashColor, RoundedCornerShape(8.dp))) {
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
                val displayName = if (!entry.isDirectory && hasPendingEdit) entry.name + " *" else entry.name
                Text(text = displayName, style = MaterialTheme.typography.bodyLarge)
                if (isSearching) {
                    Text(text = entry.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!entry.isDirectory) {
                    Text(text = formatFileSize(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            IconButton(onClick = { onBookmarkToggle(entry) }) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Bookmark",
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
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
            if (entry.path == "VIRTUAL_ENCRYPTED") {
                DropdownMenuItem(
                    text = { Text("Delete All Encrypted") },
                    onClick = {
                        showMenu = false
                        onDeleteAllEncrypted()
                    }
                )
            } else if (entry.path != "VIRTUAL_DOWNLOADS" && entry.name != "..") {
                val deleteLabel = if (entry.path.contains("downloaded_videos")) "Delete Local File" else "Delete"
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        showMenu = false
                        showDeleteConfirmation = true
                    }
                )
                
                if (!entry.path.contains("downloaded_videos")) {
                    DropdownMenuItem(
                        text = { Text("Copy to...") },
                        onClick = {
                            showMenu = false
                            onCopyTo(entry)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move to...") },
                        onClick = {
                            showMenu = false
                            onMoveTo(entry)
                        }
                    )
                }
            }

            val isLocal = entry.path.contains("downloaded_videos")

            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text("Edit as Text") },
                    onClick = {
                        showMenu = false
                        onClick(entry)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    onClick = {
                        showMenu = false
                        onDuplicate(entry)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (isLocal) "Open (Ext App)" else "Download & Open (Ext App)") },
                    onClick = {
                        showMenu = false
                        onDownloadAndOpen(entry)
                    }
                )
                if (isVideoFile(entry.name) && !isLocal) {
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
            if (!isLocal) {
                DropdownMenuItem(
                    text = { Text("Open on PC") },
                    onClick = {
                        showMenu = false
                        onLongClick(entry)
                    }
                )
            }
            if (entry.isDirectory) {
                if (activeSessionName != null) {
                    DropdownMenuItem(
                        text = { Text("AI: Add dir to $activeSessionName") },
                        onClick = {
                            showMenu = false
                            onAddToSession(entry, activeSessionName)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Open folder in AI chat") },
                    onClick = {
                        showMenu = false
                        onOpenInAiChat(entry)
                    }
                )
                DropdownMenuItem(
                    text = { Text("CLI Here") },
                    onClick = {
                        showMenu = false
                        onCliHere(entry)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Cache Whole Folder") },
                    onClick = {
                        showMenu = false
                        onCacheFolderRecursive(entry)
                    }
                )
                DropdownMenuItem(
                    text = { Text("View Git Log") },
                    onClick = {
                        showMenu = false
                        onViewGitLog(entry)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(if (isBookmarked) "Remove Bookmark" else "Add Bookmark") },
                onClick = {
                    showMenu = false
                    onBookmarkToggle(entry)
                }
            )
        }
        
        if (showDownloadDialog) {
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
                            Text("Encrypt with global password")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDownloadDialog = false
                            onDownloadVideo(entry, encrypt)
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

        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text("Confirm Delete") },
                text = { Text("Are you sure you want to delete '${entry.name}'? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmation = false
                            onDeleteByPath(entry.path)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = false }) {
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
        val parent = path.substring(0, lastIndex)
        if (parent.endsWith(":")) {
            return parent + separator
        }
        return parent
    }
    return ""
}