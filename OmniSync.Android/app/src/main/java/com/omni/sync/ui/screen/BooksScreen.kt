package com.omni.sync.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import kotlin.OptIn
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


@Composable
fun InProgressSection(
    books: List<com.omni.sync.viewmodel.BookItem>,
    baseUrl: String,
    onClick: (com.omni.sync.viewmodel.BookItem) -> Unit,
    onLongClick: (com.omni.sync.viewmodel.BookItem) -> Unit
) {
    if (books.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "In Progress",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.primary
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(books) { book ->
                InProgressBookItem(book, baseUrl, onClick, onLongClick)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp), thickness = 0.5.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookActionBottomSheet(
    book: com.omni.sync.viewmodel.BookItem,
    isOffline: Boolean,
    onDismiss: () -> Unit,
    onSetCategory: () -> Unit,
    onRemoveProgress: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = book.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            ListItem(
                headlineContent = { Text("Set Category") },
                leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                modifier = Modifier.clickable { onSetCategory() } // Don't call onDismiss here yet
            )
            ListItem(
                headlineContent = { Text("Remove Progress") },
                leadingContent = { Icon(Icons.Default.History, null) },
                modifier = Modifier.clickable { onRemoveProgress(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Hide") },
                leadingContent = { Icon(Icons.Default.VisibilityOff, null) },
                modifier = Modifier.clickable { onHide(); onDismiss() }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { onDelete(); onDismiss() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InProgressBookItem(
    book: com.omni.sync.viewmodel.BookItem,
    baseUrl: String,
    onClick: (com.omni.sync.viewmodel.BookItem) -> Unit,
    onLongClick: (com.omni.sync.viewmodel.BookItem) -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .combinedClickable(
                onClick = { onClick(book) },
                onLongClick = { onLongClick(book) }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.aspectRatio(0.7f).fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            val coverUrl = if (!book.coverPath.isNullOrEmpty()) {
                "${baseUrl}/api/stream?path=${Uri.encode(book.coverPath)}"
            } else null

            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(bookTypeIcon(book.type), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            book.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
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
    val inProgressBooks by booksViewModel.inProgressBooks.collectAsState()
    val downloadStatuses by booksViewModel.downloadStatuses.collectAsState()

    val context = LocalContext.current
    var nowPlayingBook by remember { mutableStateOf<com.omni.sync.viewmodel.BookItem?>(null) }
    var selectedActionBook by remember { mutableStateOf<com.omni.sync.viewmodel.BookItem?>(null) }
    var targetMoveBook by remember { mutableStateOf<com.omni.sync.viewmodel.BookItem?>(null) }
    var isDeleteConfirmVisible by remember { mutableStateOf(false) }
    var isPathBrowserVisible by remember { mutableStateOf(false) }

    val handleLongClick: (com.omni.sync.viewmodel.BookItem) -> Unit = { book ->
        selectedActionBook = book
    }

    // Navigation and opening logic
    val handleBookClick: (com.omni.sync.viewmodel.BookItem) -> Unit = { book ->
        when (book.type) {
            BookType.AUDIOBOOK -> nowPlayingBook = book
            BookType.PDF -> {
                val localPath = booksViewModel.downloadManager.getLocalPath(book.path)
                if (localPath == null) {
                    booksViewModel.downloadBook(book)
                    booksViewModel.log("Queued download for ${book.name} instead of opening PDF viewer")
                    mainViewModel.showToast("Downloading PDF first")
                } else {
                    mainViewModel.updateConfig { it.copy(lastOpenedFilePath = book.path) }
                    mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.PDF_VIEWER)
                }
            }
            BookType.EPUB -> {
                val localPath = booksViewModel.downloadManager.getLocalPath(book.path)
                if (localPath == null) {
                    booksViewModel.downloadBook(book)
                    booksViewModel.log("Queued download for ${book.name} instead of opening EPUB viewer")
                    mainViewModel.showToast("Downloading EPUB first")
                } else {
                    mainViewModel.updateConfig { it.copy(lastOpenedFilePath = book.path) }
                    mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.EPUB_VIEWER)
                }
            }
            else -> {
                // Not supported yet
                mainViewModel.showToast("Opening ${book.type} not supported yet")
            }
        }
    }

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
                    IconButton(
                        onClick = { booksViewModel.scanLibrary() },
                        enabled = isConnected
                    ) { 
                        Icon(
                            Icons.Default.Refresh, 
                            contentDescription = "Refresh",
                            tint = if (isConnected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                        ) 
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

            if (!isConnected && selectedTab != BooksTab.DOWNLOADED) {
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
                        Icon(
                            if (selectedTab == BooksTab.DOWNLOADED) Icons.Default.DownloadDone else Icons.Default.LibraryBooks,
                            null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                selectedTab == BooksTab.DOWNLOADED -> "No downloaded books found."
                                allBooks.isEmpty() -> "No books found. Connect to Hub and refresh."
                                searchQuery.isNotEmpty() || selectedCategory != "All" -> "No books match your filters."
                                else -> "No books found in library."
                            },
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    // Show In-Progress section at the top of ALL tab
                    if (selectedTab == BooksTab.ALL && searchQuery.isEmpty() && selectedCategory == "All") {
                        item {
                            InProgressSection(inProgressBooks, baseUrl, handleBookClick, handleLongClick)
                        }
                    }

                    items(filteredBooks) { book ->
                        BookListItem(
                            book = book,
                            isDownloaded = booksViewModel.isDownloaded(book),
                            isDownloading = booksViewModel.isDownloading(book),
                            onDownloadClick = { booksViewModel.downloadBook(book) },
                            baseUrl = baseUrl,
                            onClick = { handleBookClick(book) },
                            onLongClick = { handleLongClick(book) }
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
            
            // Overlays
            selectedActionBook?.let { book ->
                BookActionBottomSheet(
                    book = book,
                    isOffline = booksViewModel.isDownloaded(book),
                    onDismiss = { selectedActionBook = null },
                    onSetCategory = { 
                        targetMoveBook = book
                        isPathBrowserVisible = true 
                        selectedActionBook = null
                    },
                    onRemoveProgress = { 
                        booksViewModel.saveProgress(book.path, "0")
                        selectedActionBook = null
                    },
                    onHide = { 
                        booksViewModel.hideBook(book)
                        selectedActionBook = null
                    },
                    onDelete = { 
                        if (selectedTab == BooksTab.DOWNLOADED) {
                            booksViewModel.downloadManager.deleteLocal(book.path)
                            selectedActionBook = null
                        } else {
                            isDeleteConfirmVisible = true 
                        }
                    }
                )
            }

            if (isPathBrowserVisible) {
                targetMoveBook?.let { book ->
                    val typeRoot = if (book.type == com.omni.sync.utils.BookType.AUDIOBOOK) "B:\\GDrive\\Books\\Audiobooks" else "B:\\GDrive\\Books\\Books"
                    BooksPathBrowser(
                        signalRClient = mainViewModel.signalRClient,
                        isConnected = isConnected,
                        rootPath = typeRoot,
                        onSelectPath = { newPath ->
                            booksViewModel.moveBook(book, newPath)
                            isPathBrowserVisible = false
                            targetMoveBook = null
                        },
                        onDismiss = { 
                            isPathBrowserVisible = false
                            targetMoveBook = null
                        }
                    )
                }
            }

            if (isDeleteConfirmVisible) {
                selectedActionBook?.let { book ->
                    AlertDialog(
                        onDismissRequest = { isDeleteConfirmVisible = false },
                        title = { Text("Delete from library?") },
                        text = { Text("Are you sure you want to permanently delete '${book.name}' from the remote library? This cannot be undone.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    booksViewModel.deleteBook(book)
                                    isDeleteConfirmVisible = false
                                    selectedActionBook = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { isDeleteConfirmVisible = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}

// ─── Book List Item ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(
    book: com.omni.sync.viewmodel.BookItem, 
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
    baseUrl: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
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
    rootPath: String,
    onSelectPath: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var isNewFolderDialogVisible by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    fun loadPath(path: String) {
        if (!isConnected) return
        loading = true
        signalRClient.listDirectory(path)
            ?.subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
            ?.observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
            ?.subscribe({ e -> entries = e.filter { it.isDirectory }; loading = false }, { loading = false })
    }

    LaunchedEffect(currentPath) { loadPath(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Select category")
                IconButton(onClick = { isNewFolderDialogVisible = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    text = currentPath.removePrefix(rootPath).ifEmpty { "/" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LazyColumn {
                    if (currentPath.length > rootPath.length) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val parent = currentPath.substringBeforeLast('\\').substringBeforeLast('/')
                                    if (parent.length >= rootPath.length) {
                                        currentPath = parent
                                    }
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
            Button(onClick = { onSelectPath(currentPath) }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (isNewFolderDialogVisible) {
        AlertDialog(
            onDismissRequest = { isNewFolderDialogVisible = false },
            title = { Text("New Category") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            val separator = if (currentPath.contains('\\')) "\\" else "/"
                            val newPath = if (currentPath.endsWith(separator)) currentPath + newFolderName else currentPath + separator + newFolderName
                            signalRClient.createDirectory(newPath)
                            newFolderName = ""
                            isNewFolderDialogVisible = false
                            loadPath(currentPath) // Refresh
                        }
                    },
                    enabled = newFolderName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { isNewFolderDialogVisible = false }) { Text("Cancel") }
            }
        )
    }
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
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var sleepTimerMs by remember { mutableLongStateOf(0L) }
    var currentMediaIndex by remember { mutableIntStateOf(0) }
    var mediaItemCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    LaunchedEffect(sleepTimerMs) {
        if (sleepTimerMs > 0) {
            val startTime = System.currentTimeMillis()
            val targetTime = startTime + sleepTimerMs
            while (System.currentTimeMillis() < targetTime) {
                delay(1000)
                if (!exoPlayer.isPlaying) break // Pause timer if not playing? Or just let it run.
            }
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
                booksViewModel.log("Sleep timer reached: pausing playback")
            }
            sleepTimerMs = 0
        }
    }

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
            mediaItemCount = items.size
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
            currentMediaIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
            val index = currentMediaIndex
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
            var isSpeedMenuVisible by remember { mutableStateOf(false) }
            var isTimerMenuVisible by remember { mutableStateOf(false) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.name.substringBeforeLast('.'),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (mediaItemCount > 1) {
                        Text(
                            "Track ${currentMediaIndex + 1} of $mediaItemCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                
                // Speed Button
                Box {
                    TextButton(onClick = { isSpeedMenuVisible = true }) {
                        Text("${playbackSpeed}x", style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = isSpeedMenuVisible, onDismissRequest = { isSpeedMenuVisible = false }) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed}x") },
                                onClick = { playbackSpeed = speed; isSpeedMenuVisible = false }
                            )
                        }
                    }
                }

                // Timer Button
                Box {
                    IconButton(onClick = { isTimerMenuVisible = true }) {
                        Icon(
                            if (sleepTimerMs > 0) Icons.Default.Timer else Icons.Default.TimerOff,
                            null, modifier = Modifier.size(20.dp),
                            tint = if (sleepTimerMs > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    DropdownMenu(expanded = isTimerMenuVisible, onDismissRequest = { isTimerMenuVisible = false }) {
                        listOf(0, 15, 30, 60, 120).forEach { mins ->
                            DropdownMenuItem(
                                text = { Text(if (mins == 0) "Off" else "$mins mins") },
                                onClick = { sleepTimerMs = mins.toLong() * 60 * 1000; isTimerMenuVisible = false }
                            )
                        }
                    }
                }

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
                IconButton(
                    onClick = { exoPlayer.seekToPreviousMediaItem() },
                    enabled = currentMediaIndex > 0
                ) {
                    Icon(Icons.Default.SkipPrevious, "Prev Track")
                }
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
                IconButton(
                    onClick = { exoPlayer.seekToNextMediaItem() },
                    enabled = currentMediaIndex < mediaItemCount - 1
                ) {
                    Icon(Icons.Default.SkipNext, "Next Track")
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
