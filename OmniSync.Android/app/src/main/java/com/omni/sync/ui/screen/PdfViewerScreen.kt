package com.omni.sync.ui.screen

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var startPage by remember { mutableIntStateOf(booksViewModel.getCachedProgress(bookPath)?.toIntOrNull() ?: 0) }
    var isLoading by remember { mutableStateOf(true) }
    var hasRestoredProgress by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = startPage) { pageCount }

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
                        pdfRenderer = renderer
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
        onDispose { pdfRenderer?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color.DarkGray)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (pdfRenderer != null) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    PdfPageImage(pdfRenderer!!, pageIndex)
                }
            } else {
                Text("Failed to load PDF. Is it downloaded?", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun PdfPageImage(renderer: PdfRenderer, index: Int) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(index) {
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            // Scale bitmap for better quality (basic implementation)
            val b = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap = b
            page.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Page $index",
                modifier = Modifier.fillMaxSize()
            )
        } ?: CircularProgressIndicator()
    }
}
