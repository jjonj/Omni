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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.utils.*
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.viewmodel.BookItem
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.delay

// ─── Data & Enums ────────────────────────────────────────────────────────────

enum class BooksTab { ALL, EBOOKS, AUDIOBOOKS }

fun FileSystemEntry.toBookItemOrNull(): BookItem? {
    if (isDirectory) return null
    val type = getBookType(name)
    if (type == BookType.UNKNOWN) return null
    return BookItem(name = name, path = path, type = type, size = size)
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
    val signalRClient = mainViewModel.signalRClient
    val isConnected by mainViewModel.isConnected.collectAsState()
    val baseUrl = mainViewModel.getBaseUrl()

    var selectedTab by remember { mutableStateOf(BooksTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var allBooks by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var browseStack by remember { mutableStateOf<List<String>>(listOf("")) }
    var nowPlayingBook by remember { mutableStateOf<BookItem?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }

    val currentBrowsePath = browseStack.last()

    BackHandler {
        if (browseStack.size > 1) browseStack = browseStack.dropLast(1)
        else onBack()
    }

    fun loadBooksFromPath(path: String) {
        if (!isConnected) return
        isLoading = true
        errorMsg = null
        signalRClient.listDirectory(path)
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ entries ->
                val newBooks = entries.mapNotNull { it.toBookItemOrNull() }
                allBooks = (allBooks + newBooks).distinctBy { it.path }
                isLoading = false
            }, { e ->
                errorMsg = "Error: ${e.message}"
                isLoading = false
            })
    }

    LaunchedEffect(isConnected) {
        if (isConnected && allBooks.isEmpty()) {
            loadBooksFromPath("")
        }
    }

    val filteredBooks = allBooks.filter { book ->
        val matchesTab = when (selectedTab) {
            BooksTab.ALL -> true
            BooksTab.EBOOKS -> book.type == BookType.PDF || book.type == BookType.EPUB
            BooksTab.AUDIOBOOKS -> book.type == BookType.AUDIOBOOK
        }
        val matchesSearch = searchQuery.isBlank() || book.name.contains(searchQuery, ignoreCase = true)
        matchesTab && matchesSearch
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Books") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (browseStack.size > 1) browseStack = browseStack.dropLast(1)
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        allBooks = emptyList()
                        loadBooksFromPath(currentBrowsePath)
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { showFolderPicker = !showFolderPicker }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse folder")
                    }
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
                        onClick = { selectedTab = tab },
                        text = {
                            Text(when (tab) {
                                BooksTab.ALL -> "All"
                                BooksTab.EBOOKS -> "eBooks"
                                BooksTab.AUDIOBOOKS -> "Audiobooks"
                            })
                        },
                        icon = {
                            Icon(when (tab) {
                                BooksTab.ALL -> Icons.Default.LibraryBooks
                                BooksTab.EBOOKS -> Icons.Default.MenuBook
                                BooksTab.AUDIOBOOKS -> Icons.Default.Headphones
                            }, contentDescription = null)
                        }
                    )
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text("Search books…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty())
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            errorMsg?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            }

            // Folder-browser chip (path breadcrumb)
            if (showFolderPicker) {
                BooksPathBrowser(
                    signalRClient = signalRClient,
                    isConnected = isConnected,
                    onSelectPath = { path ->
                        browseStack = browseStack + path
                        loadBooksFromPath(path)
                        showFolderPicker = false
                    },
                    onDismiss = { showFolderPicker = false }
                )
            }

            if (!isConnected) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("Not connected to Hub", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (filteredBooks.isEmpty() && !isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryBooks, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (allBooks.isEmpty()) "No books found. Use 📁 to browse a folder."
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
                            onClick = {
                                when (book.type) {
                                    BookType.AUDIOBOOK -> nowPlayingBook = book
                                    else -> {
                                        val encoded = java.net.URLEncoder.encode(book.path, "UTF-8")
                                        mainViewModel.openUrlOnPhone("$baseUrl/api/stream?path=$encoded")
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Audiobook player docked at bottom
            nowPlayingBook?.let { book ->
                AudiobookMiniPlayer(
                    book = book,
                    baseUrl = baseUrl,
                    onClose = { nowPlayingBook = null }
                )
            }
        }
    }
}

// ─── Book List Item ───────────────────────────────────────────────────────────

@Composable
fun BookListItem(book: BookItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = bookTypeIcon(book.type),
                contentDescription = bookTypeLabel(book.type),
                modifier = Modifier.size(36.dp),
                tint = when (book.type) {
                    BookType.PDF -> Color(0xFFE53935)
                    BookType.EPUB -> Color(0xFF43A047)
                    BookType.AUDIOBOOK -> Color(0xFF1E88E5)
                    BookType.UNKNOWN -> MaterialTheme.colorScheme.outline
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = bookTypeLabel(book.type),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
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
    book: BookItem,
    baseUrl: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    LaunchedEffect(book) {
        val encoded = java.net.URLEncoder.encode(book.path, "UTF-8")
        val mediaItem = MediaItem.fromUri(Uri.parse("$baseUrl/api/stream?path=$encoded"))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000)
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            isPlaying = exoPlayer.isPlaying
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
