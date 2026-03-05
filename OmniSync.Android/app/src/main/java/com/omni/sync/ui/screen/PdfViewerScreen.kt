package com.omni.sync.ui.screen

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omni.sync.ui.components.ReaderSettingsOverlay
import com.omni.sync.viewmodel.BooksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    booksViewModel: BooksViewModel,
    bookPath: String,
    bookName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val pdfRenderer = remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var startPage by remember { mutableIntStateOf(booksViewModel.getCachedProgress(bookPath)?.toIntOrNull() ?: 0) }
    var isLoading by remember { mutableStateOf(true) }
    var hasRestoredProgress by remember { mutableStateOf(false) }
    val isSettingsVisible = remember { mutableStateOf(false) }
    val readerTheme by booksViewModel.readerTheme.collectAsState()

    val pagerState = rememberPagerState(initialPage = startPage) { pageCount }

    // State used by child to tell parent if pager should scroll
    var globalScale by remember { mutableFloatStateOf(1f) }
    var isAtHorizontalEdge by remember { mutableStateOf(true) }

    // 1. Initialize PDF
    LaunchedEffect(bookPath) {
        isLoading = true
        booksViewModel.log("Initializing PDF Viewer: $bookName")
        withContext(Dispatchers.IO) {
            try {
                var path = booksViewModel.downloadManager.getLocalPath(bookPath)
                booksViewModel.log("Resolved local path: $path")
                
                // Wait for download if not available yet (max 30s)
                var retryCount = 0
                while (path == null && retryCount < 60) {
                    if (retryCount % 10 == 0) booksViewModel.log("Waiting for download... ($retryCount)")
                    delay(500)
                    path = booksViewModel.downloadManager.getLocalPath(bookPath)
                    retryCount++
                }

                if (path != null) {
                    booksViewModel.log("Opening local file: $path")
                    val file = File(path)
                    if (file.exists()) {
                        val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(input)
                        pdfRenderer.value = renderer
                        pageCount = renderer.pageCount
                        booksViewModel.log("PDF loaded successfully. Pages: $pageCount")
                        isLoading = false
                        hasRestoredProgress = true
                        if (startPage in 0 until pageCount) {
                            scope.launch { pagerState.scrollToPage(startPage) }
                        }
                        
                        // 2. Load position
                        booksViewModel.getProgress(bookPath) { pos ->
                            val remotePage = pos?.toIntOrNull() ?: 0
                            startPage = remotePage
                            booksViewModel.log("Restoring position to page: $startPage")
                            scope.launch {
                                if (remotePage in 0 until pageCount && remotePage != pagerState.currentPage) {
                                    pagerState.scrollToPage(remotePage)
                                }
                            }
                        }
                    } else {
                        booksViewModel.log("ERROR: Local file does not exist at $path", com.omni.sync.ui.screen.LogType.ERROR)
                        isLoading = false
                    }
                } else {
                    booksViewModel.log("ERROR: Timeout waiting for download", com.omni.sync.ui.screen.LogType.ERROR)
                    isLoading = false
                }
            } catch (e: Exception) {
                booksViewModel.log("ERROR loading PDF: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                isLoading = false
            }
        }
    }

    // 3. Save position on page change
    LaunchedEffect(pagerState.currentPage) {
        if (!isLoading && hasRestoredProgress && pageCount > 0) {
            booksViewModel.saveProgress(bookPath, pagerState.currentPage.toString())
        }
    }

    DisposableEffect(Unit) {
        onDispose { pdfRenderer.value?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isSettingsVisible.value = !isSettingsVisible.value }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            if (pageCount > 0) {
                Surface(tonalElevation = 4.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Page ${pagerState.currentPage + 1} of $pageCount")
                        Row {
                            IconButton(
                                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                                enabled = pagerState.currentPage > 0
                            ) { Icon(Icons.Default.ChevronLeft, null) }
                            IconButton(
                                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                                enabled = pagerState.currentPage < pageCount - 1
                            ) { Icon(Icons.Default.ChevronRight, null) }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val bgColor = try { Color(android.graphics.Color.parseColor(readerTheme.backgroundColor)) } catch(e: Exception) { Color.DarkGray }
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(bgColor)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (pdfRenderer.value != null) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = globalScale <= 1.05f || isAtHorizontalEdge
                ) { pageIndex ->
                    PdfPageImage(
                        renderer = pdfRenderer.value!!, 
                        index = pageIndex, 
                        invert = readerTheme.invertPdf,
                        onScaleChanged = { if (pagerState.currentPage == pageIndex) globalScale = it },
                        onEdgeReached = { if (pagerState.currentPage == pageIndex) isAtHorizontalEdge = it }
                    )
                }
            } else {
                Text("Failed to load PDF. Is it downloaded?", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }

            if (isSettingsVisible.value) {
                Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                    ReaderSettingsOverlay(
                        theme = readerTheme,
                        onThemeChange = { booksViewModel.updateTheme(it) },
                        onClose = { isSettingsVisible.value = false }
                    )
                }
            }
        }
    }
}

@Composable
fun PdfPageImage(
    renderer: PdfRenderer, 
    index: Int, 
    invert: Boolean,
    onScaleChanged: (Float) -> Unit,
    onEdgeReached: (Boolean) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    
    LaunchedEffect(index) {
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(index)
                val b = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap = b
                page.close()
            } catch (e: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = Math.abs(1 - zoom) * centroidSize
                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }

                            if (pastTouchSlop) {
                                scale = (scale * zoomChange).coerceIn(1f, 5f)
                                onScaleChanged(scale)

                                if (scale > 1f) {
                                    val maxX = (containerSize.width * (scale - 1)) / 2f
                                    val maxY = (containerSize.height * (scale - 1)) / 2f
                                    
                                    val newOffset = offset + panChange
                                    val constrainedOffset = Offset(
                                        newOffset.x.coerceIn(-maxX, maxX),
                                        newOffset.y.coerceIn(-maxY, maxY)
                                    )
                                    
                                    // Only consume X if we are NOT at the edge or moving away from it
                                    val atLeftEdge = offset.x >= maxX - 5f
                                    val atRightEdge = offset.x <= -maxX + 5f
                                    val movingLeft = panChange.x < 0
                                    val movingRight = panChange.x > 0
                                    
                                    val shouldConsumeX = !(atLeftEdge && movingRight) && !(atRightEdge && movingLeft)
                                    
                                    offset = constrainedOffset
                                    
                                    event.changes.forEach {
                                        if (it.positionChanged()) {
                                            if (shouldConsumeX) it.consume()
                                        }
                                    }

                                    onEdgeReached(atLeftEdge || atRightEdge)
                                } else {
                                    offset = Offset.Zero
                                    onEdgeReached(true)
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { bmap ->
            val colorFilter = if (invert) {
                val matrix = ColorMatrix(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                ))
                ColorFilter.colorMatrix(matrix)
            } else null

            Image(
                bitmap = bmap.asImageBitmap(),
                contentDescription = "Page $index",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                colorFilter = colorFilter
            )
        } ?: CircularProgressIndicator()
    }
}
