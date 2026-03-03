package com.omni.sync.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.utils.*
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.viewmodel.BooksViewModel
import com.omni.sync.viewmodel.BooksViewModelFactory
import com.omni.sync.viewmodel.LibraryState
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.delay
import com.google.gson.annotations.SerializedName

// ─── Data & Enums ────────────────────────────────────────────────────────────

data class BookProgress(
    @SerializedName("BookPath") val bookPath: String,
    @SerializedName("Position") val position: String,
    @SerializedName("LastUpdated") val lastUpdated: java.util.Date
)

enum class BooksTab { ALL, EBOOKS, AUDIOBOOKS, DOWNLOADED }

fun FileSystemEntry.toBookItemOrNull(): com.omni.sync.viewmodel.BookItem? {
    if (isDirectory) return null
    val type = getBookType(name)
    if (type == BookType.UNKNOWN) return null
    return com.omni.sync.viewmodel.BookItem(name = name, path = path, type = type, size = size)
}

fun bookTypeIcon(type: BookType): ImageVector = when (type) {
    BookType.PDF -> Icons.Default.PictureAsPdf
    BookType.EPUB -> Icons.Default.MenuBook
    BookType.AUDIOBOOK -> Icons.Default.Headphones
    BookType.UNKNOWN -> Icons.Default.InsertDriveFile
}

fun bookTypeLabel(type: BookType): String = when (type) {
    BookType.PDF -> "PDF"
    BookType.EPUB -> "EPUB"
    BookType.AUDIOBOOK -> "Audiobook"
    BookType.UNKNOWN -> "File"
}


// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    val booksViewModel: BooksViewModel = viewModel(
        factory = BooksViewModelFactory(mainViewModel)
    )
    
    val isConnected by mainViewModel.isConnected.collectAsState()
    val baseUrl = mainViewModel.getBaseUrl()
    val libraryState by booksViewModel.libraryState.collectAsState()
    val allBooks by booksViewModel.allBooks.collectAsState()
    val downloadStatuses by booksViewModel.downloadStatuses.collectAsState()

    val context = LocalContext.current

    // Register download receiver
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != -1L) {
                    booksViewModel.onDownloadCompleted(id)
                }
            }
        }
        val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    var selectedTab by remember { mutableStateOf(BooksTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var nowPlayingBook by remember { mutableStateOf<com.omni.sync.viewmodel.BookItem?>(null) }

    BackHandler {
        onBack()
    }

    // Grouping for sub-categories
    val categories = remember(allBooks, selectedTab, downloadStatuses) {
        val filteredByType = allBooks.filter { book ->
            when (selectedTab) {
                BooksTab.ALL -> true
                BooksTab.EBOOKS -> book.type == BookType.PDF || book.type == BookType.EPUB
                BooksTab.AUDIOBOOKS -> book.type == BookType.AUDIOBOOK
                BooksTab.DOWNLOADED -> booksViewModel.isDownloaded(book)
            }
        }
        listOf("All") + filteredByType.map { it.category }.distinct().sorted()
    }

    val filteredBooks = remember(allBooks, selectedTab, selectedCategory, searchQuery, downloadStatuses) {
        allBooks.filter { book ->
            val matchesTab = when (selectedTab) {
                BooksTab.ALL -> true
                BooksTab.EBOOKS -> book.type == BookType.PDF || book.type == BookType.EPUB
                BooksTab.AUDIOBOOKS -> book.type == BookType.AUDIOBOOK
                BooksTab.DOWNLOADED -> booksViewModel.isDownloaded(book)
            }
            val matchesCategory = selectedCategory == "All" || book.category == selectedCategory
            val matchesSearch = searchQuery.isBlank() || book.name.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesCategory && matchesSearch
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected && libraryState is LibraryState.Idle) {
            booksViewModel.scanLibrary()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Books") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        booksViewModel.scanLibrary()
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            // Tabs
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                BooksTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { 
                            selectedTab = tab
                            selectedCategory = "All"
                        },
                        text = {
                            Text(when (tab) {
                                BooksTab.ALL -> "All"
                                BooksTab.EBOOKS -> "eBooks"
                                BooksTab.AUDIOBOOKS -> "Audio"
                                BooksTab.DOWNLOADED -> "Offline"
                            })
                        },
                        icon = {
                            Icon(when (tab) {
                                BooksTab.ALL -> Icons.Default.LibraryBooks
                                BooksTab.EBOOKS -> Icons.Default.MenuBook
                                BooksTab.AUDIOBOOKS -> Icons.Default.Headphones
                                BooksTab.DOWNLOADED -> Icons.Default.DownloadDone
                            }, contentDescription = null)
                        }
                    )
                }
            }

            // Sub-category Filter (Scrollable Row)
            if (categories.size > 1) {
                ScrollableTabRow(
                    selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {}
                ) {
                    categories.forEach { cat ->
                        Tab(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            text = { Text(cat) }
                        )
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text("Search title…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty())
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (libraryState is LibraryState.Loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (!isConnected) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("Not connected to Hub", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (filteredBooks.isEmpty() && libraryState !is LibraryState.Loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryBooks, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (allBooks.isEmpty()) "No books found in B:\\GDrive\\Books"
                            else "No books match your filters.",
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(filteredBooks) { book ->
                        BookListItem(
                            book = book,
                            isDownloaded = booksViewModel.isDownloaded(book),
                            isDownloading = booksViewModel.isDownloading(book),
                            onDownloadClick = { booksViewModel.downloadBook(book) },
                            baseUrl = baseUrl,
                            onClick = {
                                when (book.type) {
                                    BookType.AUDIOBOOK -> nowPlayingBook = book
                                    BookType.PDF -> {
                                        val localPath = booksViewModel.downloadManager.getLocalPath(book.path)
                                        if (localPath == null) {
                                            booksViewModel.downloadBook(book)
                                        }
                                        mainViewModel.updateConfig { it.copy(lastOpenedFilePath = book.path) }
                                        mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.PDF_VIEWER)
                                    }
                                    BookType.EPUB -> {
                                        val localPath = booksViewModel.downloadManager.getLocalPath(book.path)
                                        if (localPath == null) {
                                            booksViewModel.downloadBook(book)
                                            booksViewModel.log("EPUB not local, opening streamed reader")
                                        } else {
                                            booksViewModel.log("Opening local EPUB in app reader")
                                        }
                                        mainViewModel.updateConfig { it.copy(lastOpenedFilePath = book.path) }
                                        mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.EPUB_VIEWER)
                                    }
                                    else -> {
                                        val path = booksViewModel.downloadManager.getLocalPath(book.path) ?: book.path
                                        val isLocal = path != book.path
                                        
                                        if (isLocal) {
                                            // Handle local file opening
                                            mainViewModel.openUrlOnPhone("file://$path")
                                        } else {
                                            val encoded = java.net.URLEncoder.encode(book.path, "UTF-8")
                                            mainViewModel.openUrlOnPhone("$baseUrl/api/stream?path=$encoded")
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Audiobook player docked at bottom
            nowPlayingBook?.let { book ->
                var lastKnownPlayback by remember { mutableStateOf("0:0") }
                AudiobookMiniPlayer(
                    book = book,
                    baseUrl = baseUrl,
                    booksViewModel = booksViewModel,
                    onPositionUpdate = { lastKnownPlayback = it },
                    onClose = { 
                        booksViewModel.saveProgress(book.path, lastKnownPlayback)
                        nowPlayingBook = null 
                    }
                )
            }
        }
    }
}

// ─── Book List Item ───────────────────────────────────────────────────────────

@Composable
fun BookListItem(
    book: com.omni.sync.viewmodel.BookItem, 
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    baseUrl: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp)) {
                if (book.coverPath != null) {
                    val encodedCover = java.net.URLEncoder.encode(book.coverPath, "UTF-8")
                    AsyncImage(
                        model = "$baseUrl/api/stream?path=$encodedCover",
                        contentDescription = "Cover",
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    )
                } else {
                    Icon(
                        imageVector = bookTypeIcon(book.type),
                        contentDescription = bookTypeLabel(book.type),
                        modifier = Modifier.size(36.dp).align(Alignment.Center),
                        tint = when (book.type) {
                            BookType.PDF -> Color(0xFFE53935)
                            BookType.EPUB -> Color(0xFF43A047)
                            BookType.AUDIOBOOK -> Color(0xFF1E88E5)
                            BookType.UNKNOWN -> MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            text = bookTypeLabel(book.type),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (book.category != "Unsorted") {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = book.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
            
            if (isDownloaded) {
                Icon(Icons.Default.DownloadDone, null, tint = Color(0xFF43A047))
            } else if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownloadClick) {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            
            Spacer(Modifier.width(8.dp))
            
            Icon(
                imageVector = if (book.type == BookType.AUDIOBOOK) Icons.Default.PlayCircle else Icons.Default.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ─── Folder path browser dialog ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksPathBrowser(
    signalRClient: com.omni.sync.data.repository.SignalRClient,
    isConnected: Boolean,
    onSelectPath: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun loadPath(path: String) {
        if (!isConnected) return
        loading = true
        if (path.isEmpty()) {
            signalRClient.getAvailableDrives()
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ e -> entries = e; loading = false }, { loading = false })
        } else {
            signalRClient.listDirectory(path)
                ?.subscribeOn(Schedulers.io())
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe({ e -> entries = e.filter { it.isDirectory }; loading = false }, { loading = false })
        }
    }

    LaunchedEffect(Unit) { loadPath("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse to folder") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    text = if (currentPath.isEmpty()) "/" else currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LazyColumn {
                    if (currentPath.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val parent = currentPath.substringBeforeLast('\\').substringBeforeLast('/')
                                    currentPath = parent
                                    loadPath(parent)
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(8.dp))
                                Text("..", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    items(entries) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                currentPath = entry.path
                                loadPath(entry.path)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (currentPath.isNotEmpty()) onSelectPath(currentPath) }) { Text("Select This Folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─── Audiobook Mini-Player ────────────────────────────────────────────────────

@OptIn(androidx.annotation.OptIn::class, UnstableApi::class)
@Composable
fun AudiobookMiniPlayer(
    book: com.omni.sync.viewmodel.BookItem,
    baseUrl: String,
    booksViewModel: com.omni.sync.viewmodel.BooksViewModel,
    onPositionUpdate: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    LaunchedEffect(book) {
        // Do not block playback on remote progress RPC.
        val cached = booksViewModel.getCachedProgress(book.path)
        val (startIndex, startMs) = if (cached != null && cached.contains(":")) {
            val parts = cached.split(':', limit = 2)
            (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        } else {
            0 to (cached?.toLongOrNull() ?: 0L)
        }
        booksViewModel.log("Audiobook start position (cached): idx=$startIndex ms=$startMs")

        booksViewModel.resolveAudiobookMediaItems(book) { items ->
            if (items.isEmpty()) {
                booksViewModel.log("No playable audiobook tracks found for ${book.name}", LogType.ERROR)
                return@resolveAudiobookMediaItems
            }

            booksViewModel.log("Preparing ${items.size} media items for ${book.name}")
            exoPlayer.setMediaItems(items)
            if (startMs > 0 || startIndex > 0) {
                if (startIndex in items.indices) {
                    exoPlayer.seekTo(startIndex, startMs)
                } else {
                    exoPlayer.seekTo(startMs)
                }
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val label = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                booksViewModel.log("Audiobook player state: $label, idx=${exoPlayer.currentMediaItemIndex}, pos=${exoPlayer.currentPosition}, dur=${exoPlayer.duration}")
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                booksViewModel.log("Audiobook isPlaying=$isPlayingNow, idx=${exoPlayer.currentMediaItemIndex}, pos=${exoPlayer.currentPosition}")
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                booksViewModel.log("Audiobook media transition: reason=$reason, item=${mediaItem?.mediaId ?: mediaItem?.localConfiguration?.uri}")
            }

            override fun onPlayerError(error: PlaybackException) {
                booksViewModel.log("Audiobook playback error: ${error.errorCodeName} ${error.message}", LogType.ERROR)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    LaunchedEffect(exoPlayer) {
        var lastSavedPos = -1L
        var lastSavedIndex = -1
        var tick = 0
        while (true) {
            delay(1000)
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            isPlaying = exoPlayer.isPlaying
            val index = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
            onPositionUpdate("$index:$currentPosition")
            tick += 1
            if (tick % 5 == 0) {
                booksViewModel.log("Audiobook heartbeat: playing=${exoPlayer.isPlaying}, state=${exoPlayer.playbackState}, idx=${exoPlayer.currentMediaItemIndex}, pos=${exoPlayer.currentPosition}, dur=${exoPlayer.duration}")
            }
            
            // Save position every 10 seconds or when significantly changed
            if (isPlaying && (index != lastSavedIndex || Math.abs(currentPosition - lastSavedPos) > 10_000)) {
                booksViewModel.saveProgress(book.path, "$index:$currentPosition")
                lastSavedPos = currentPosition
                lastSavedIndex = index
            }
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Surface(
        tonalElevation = 8.dp, shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    book.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
            if (duration > 0) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { exoPlayer.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatAudioMs(currentPosition), style = MaterialTheme.typography.labelSmall)
                    Text(formatAudioMs(duration), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 30_000).coerceAtLeast(0L)) }) {
                    Icon(Icons.Default.Replay30, "Back 30s")
                }
                IconButton(
                    onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 30_000) }) {
                    Icon(Icons.Default.Forward30, "Forward 30s")
                }
            }
        }
    }
}

fun formatAudioMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
