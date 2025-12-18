package com.omni.sync.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.Bookmark
import com.omni.sync.viewmodel.BrowserViewModel
import com.omni.sync.viewmodel.BrowserViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserControlScreen(
    modifier: Modifier = Modifier,
    signalRClient: SignalRClient?,
    viewModel: BrowserViewModel
) {
    val urlInput by viewModel.urlInput.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val openInNewTab by viewModel.openInNewTab.collectAsState()
    val customCleanupPatterns by viewModel.customCleanupPatterns.collectAsState()
    val tabList by viewModel.tabList.collectAsState()
    var showCleanupPatterns by remember { mutableStateOf(false) }
    var showTabList by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- 1. Address Bar & Main Actions ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { viewModel.onUrlChanged(it) },
                label = { Text("URL") },
                placeholder = { Text("google.com") },
                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { viewModel.navigate(urlInput) }
                )
            )
            
            // Open on Phone
            IconButton(onClick = { viewModel.openCurrentTabOnPhone() }) {
                Icon(Icons.Default.PhoneAndroid, "Open on Phone", tint = MaterialTheme.colorScheme.secondary)
            }

            // Paste & Go
            IconButton(onClick = { viewModel.loadUrlFromClipboard(context) }) {
                Icon(Icons.Default.ContentPasteGo, "Paste & Go", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- 2. Navigation Controls ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row {
                IconButton(onClick = { viewModel.sendCommand("Back") }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                IconButton(onClick = { viewModel.sendCommand("Refresh") }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                IconButton(onClick = { viewModel.sendCommand("Forward") }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                }
                IconButton(onClick = { viewModel.sendCommand("CloseTab") }) {
                    Icon(Icons.Default.Close, "Close Tab", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = { viewModel.sendSpacebar() }) {
                    Icon(Icons.Default.SpaceBar, "Spacebar")
                }
                IconButton(onClick = { viewModel.toggleMedia() }) {
                    Icon(Icons.Default.PlayArrow, "Play/Pause Media", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // New Tab Toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New Tab", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = openInNewTab,
                    onCheckedChange = { viewModel.toggleNewTab(it) },
                    modifier = Modifier.graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
                )
            }
        }
        
        // --- 3. Add Bookmark Button ---
        Button(
            onClick = { viewModel.bookmarkCurrentTab() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Bookmark Current Tab")
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // --- 4. Bookmarks List ---
        Text(
            text = "Bookmarks",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp)
        ) {
            items(bookmarks) { bookmark ->
                BookmarkItem(
                    bookmark = bookmark,
                    onClick = { 
                        viewModel.onUrlChanged(bookmark.url)
                        viewModel.navigate(bookmark.url) 
                    },
                    onDelete = { viewModel.removeBookmark(bookmark) },
                    onMoveUp = { viewModel.moveBookmarkUp(bookmark) },
                    onMoveDown = { viewModel.moveBookmarkDown(bookmark) }
                )
            }
        }
        
        // --- 5. Advanced Actions ---
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Advanced Actions",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.sendCommand("CleanTabs") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clean Tabs")
            }
            
            OutlinedButton(
                onClick = { 
                    showTabList = !showTabList
                    if (showTabList) viewModel.requestTabList()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.List, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tab List")
            }

            OutlinedButton(
                onClick = { viewModel.addCurrentTabToCleanup() },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, "Add current tab to cleanup")
            }
            
            OutlinedButton(
                onClick = { showCleanupPatterns = !showCleanupPatterns },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    if (showCleanupPatterns) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Show patterns"
                )
            }
        }
        
        // Tab List display
        if (showTabList && tabList.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    item {
                        Text(
                            "Open Chrome Tabs",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(tabList) { tab ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { 
                                    val url = tab["url"] as? String ?: ""
                                    if (url.isNotEmpty()) {
                                        viewModel.onUrlChanged(url)
                                        viewModel.navigate(url)
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab["title"] as? String ?: "Untitled",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { 
                                    val id = tab["id"]
                                    if (id != null) viewModel.closeSpecificTab(id)
                                    viewModel.requestTabList() // Refresh
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "Close",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Custom cleanup patterns list
        if (showCleanupPatterns && customCleanupPatterns.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "Custom Cleanup Patterns",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    customCleanupPatterns.forEach { pattern ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pattern,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { viewModel.removeCleanupPattern(pattern) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun BookmarkItem(
    bookmark: Bookmark, 
    onClick: () -> Unit, 
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookmark.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Move Up")
            }
            IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Move Down")
            }
            
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = "Remove", 
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f) 
                )
            }
        }
    }
}