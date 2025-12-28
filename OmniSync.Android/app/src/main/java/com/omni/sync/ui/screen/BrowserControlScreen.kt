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
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardBackspace
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.AdsClick
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.Bookmark
import com.omni.sync.viewmodel.BrowserViewModel
import com.omni.sync.viewmodel.BrowserViewModelFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import com.omni.sync.utils.WindowsKeyCodes.VK_BACK
import com.omni.sync.utils.WindowsKeyCodes.VK_ESCAPE
import com.omni.sync.utils.WindowsKeyCodes.VK_RETURN
import com.omni.sync.utils.WindowsKeyCodes.VK_SPACE
import com.omni.sync.utils.WindowsKeyCodes.VK_UP
import com.omni.sync.utils.WindowsKeyCodes.VK_DOWN
import com.omni.sync.utils.WindowsKeyCodes.VK_LEFT
import com.omni.sync.utils.WindowsKeyCodes.VK_RIGHT
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType

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

    val defaultPatterns = listOf(
        "twitch.tv/directory/following",
        "youtube.com (not watch/channel)",
        "google.com/*",
        "file:///*",
        "chrome://newtab/",
        "about:blank"
    )

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
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        IconButton(onClick = { viewModel.onUrlChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear URL")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { viewModel.navigate(urlInput) }
                )
            )

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New Tab", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = openInNewTab,
                    onCheckedChange = { viewModel.toggleNewTab(it) },
                    modifier = Modifier.graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
                )
            }
        }
        
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
            // 1. Open Current Tab on Phone
            OutlinedButton(
                onClick = { viewModel.openCurrentTabOnPhone() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("🌐→📱", fontSize = 16.sp)
            }
            
            // 2. Latest YT to Phone
            OutlinedButton(
                onClick = { viewModel.sendLatestYouTubeToPhone() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("▶↺📱", fontSize = 16.sp)
            }

            // 3. Latest YT on PC
            OutlinedButton(
                onClick = { viewModel.openLatestYouTubeOnPC() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("▶↺🌐", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Clean Tabs
            OutlinedButton(
                onClick = { viewModel.sendCommand("CleanTabs") },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(20.dp))
            }
            
            // 2. Tab List
            OutlinedButton(
                onClick = { 
                    showTabList = !showTabList
                    if (showTabList) viewModel.requestTabList()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tab List")
            }

            // 3. Add current tab to cleanup
            OutlinedButton(
                onClick = { viewModel.addCurrentTabToCleanup() },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, "Add current to cleanup")
            }
            
            // 4. Show patterns
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
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Chrome Tabs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showTabList = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(tabList) { tab ->
                            val tabId = tab["id"]
                            val title = tab["title"] as? String ?: "Untitled"
                            val url = tab["url"] as? String ?: ""
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        if (url.isNotEmpty()) {
                                            viewModel.onUrlChanged(url)
                                            viewModel.navigate(url)
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (url.isNotEmpty()) {
                                        Text(text = url, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                IconButton(
                                    onClick = { 
                                        if (tabId != null) {
                                            viewModel.closeSpecificTab(tabId)
                                            viewModel.requestTabList()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }

        // Custom cleanup patterns list
        if (showCleanupPatterns) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Cleanup Patterns", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showCleanupPatterns = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var newPattern by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = newPattern,
                            onValueChange = { newPattern = it },
                            label = { Text("New Pattern (e.g. *.google.com/*)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp)
                        )
                        IconButton(onClick = { 
                            viewModel.addCleanupPattern(newPattern)
                            newPattern = ""
                        }) {
                            Icon(Icons.Default.Add, "Add pattern")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text("Built-in (Non-removable):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    defaultPatterns.forEach { pattern ->
                        Text("• $pattern", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(PaddingValues(start = 8.dp, top = 1.dp, bottom = 1.dp)))
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Custom:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)

                    if (customCleanupPatterns.isEmpty()) {
                        Text("No custom patterns", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(PaddingValues(start = 8.dp, top = 4.dp, bottom = 4.dp)))
                    }

                    customCleanupPatterns.forEach { pattern ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• $pattern",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { viewModel.removeCleanupPattern(pattern) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        BrowserInputControls(signalRClient)
    }
}

@Composable
fun BrowserInputControls(signalRClient: SignalRClient?) {
    if (signalRClient == null) return
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider()
        Text(
            text = "Input Controls",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Hidden input for keyboard
        BrowserHiddenKeyboard(signalRClient, focusRequester)

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Buttons Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Row 1: Kbd, Esc, Backspace
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                     ActionKeyButton(text = "Kbd", modifier = Modifier.weight(1f)) {
                         focusRequester.requestFocus()
                         keyboardController?.show()
                     }
                     ActionKeyButton(text = "Esc", modifier = Modifier.weight(1f)) {
                         signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE)
                     }
                     ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardBackspace, modifier = Modifier.weight(1f)) {
                         signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                     }
                }
                
                // Row 2: Space, Enter
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                     ActionKeyButton(text = "Space", modifier = Modifier.weight(1.5f)) {
                         signalRClient.sendText(" ")
                     }
                     ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardReturn, modifier = Modifier.weight(1f)) {
                         signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
                     }
                }

                // Row 3: Arrows
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                     ActionKeyButton(icon = Icons.AutoMirrored.Filled.ArrowBack, modifier = Modifier.weight(1f)) {
                         signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_LEFT)
                     }
                     Column(modifier = Modifier.weight(1f)) {
                        ActionKeyButton(icon = Icons.Default.KeyboardArrowUp, modifier = Modifier.fillMaxWidth().height(20.dp)) {
                            signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_UP)
                        }
                        ActionKeyButton(icon = Icons.Default.KeyboardArrowDown, modifier = Modifier.fillMaxWidth().height(20.dp)) {
                            signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_DOWN)
                        }
                     }
                     ActionKeyButton(icon = Icons.AutoMirrored.Filled.ArrowForward, modifier = Modifier.weight(1f)) {
                         signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RIGHT)
                     }
                }
            }

            // Mini Trackpad
            MiniTrackpad(
                signalRClient = signalRClient,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .aspectRatio(1f)
            )
        }
    }
}

@Composable
fun MiniTrackpad(signalRClient: SignalRClient, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var isDraggingLeftClick by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(signalRClient) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPoint = down.position
                        var isDrag = false
                        var isRightClickTriggered = false
                        val movementThreshold = 5.dp.toPx()
                        
                        val currentTime = System.currentTimeMillis()
                        val isDoubleTapCandidate = currentTime - lastTapTime < 300
                        
                        val longPressJob = coroutineScope.launch {
                            delay(1000) // Shorter hold for mini trackpad
                            if (!isDrag && !isDoubleTapCandidate) {
                                isRightClickTriggered = true
                                signalRClient.sendRightClick()
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                val change = changes.firstOrNull { it.id == down.id }
                                
                                if (change == null || !change.pressed) {
                                    change?.consume()
                                    break
                                }

                                val positionChange = change.position - downPoint
                                val distance = positionChange.getDistance()

                                if (!isDrag && distance > movementThreshold) {
                                    isDrag = true
                                    longPressJob.cancel()
                                }

                                if (isDrag) {
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        // Reduced sensitivity for small area
                                        val sensitivity = 1.2f 
                                        signalRClient.sendMouseMove(delta.x * sensitivity, delta.y * sensitivity)
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            longPressJob.cancel()
                            if (!isDrag && !isRightClickTriggered) {
                                signalRClient.sendLeftClick()
                                lastTapTime = System.currentTimeMillis()
                            } else {
                                lastTapTime = 0
                            }
                        }
                    }
                }
        ) {
            Icon(
                Icons.Default.AdsClick, 
                contentDescription = null, 
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun BrowserHiddenKeyboard(
    signalRClient: SignalRClient,
    focusRequester: FocusRequester
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val text = newValue.text
            if (text.isNotEmpty()) {
                val lastChar = text.last()
                signalRClient.sendText(lastChar.toString())
                textFieldValue = TextFieldValue("")
            }
        },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false, 
            keyboardType = KeyboardType.Ascii, 
            imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(
            onSend = {
                signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
            }
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .size(1.dp) 
            .alpha(0f)
    )
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
