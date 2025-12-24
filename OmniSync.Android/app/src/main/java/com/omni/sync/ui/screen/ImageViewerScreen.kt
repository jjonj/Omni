package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    initialImageUrl: String,
    playlist: List<String> = emptyList(),
    initialIndex: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { if (playlist.isNotEmpty()) playlist.size else 1 })
    val coroutineScope = rememberCoroutineScope()
    
    var isSlideshowActive by remember { mutableStateOf(false) }
    var slideshowInterval by remember { mutableLongStateOf(5000L) }
    var showControls by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // Slideshow logic
    LaunchedEffect(isSlideshowActive, slideshowInterval) {
        if (isSlideshowActive) {
            while (true) {
                delay(slideshowInterval)
                if (pagerState.currentPage < pagerState.pageCount - 1) {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                } else {
                    pagerState.animateScrollToPage(0) // Loop back
                }
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls && isSlideshowActive) {
            delay(3000)
            showControls = false
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                if (zoom > 1f) {
                    offset += pan
                } else {
                    offset = androidx.compose.ui.geometry.Offset.Zero
                }
            }
        }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = zoom == 1f // Disable paging when zoomed in
        ) { page ->
            val url = if (playlist.isNotEmpty()) playlist[page] else initialImageUrl
            
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        }

        // Overlay Controls
        if (showControls) {
            TopAppBar(
                title = { 
                    if (playlist.isNotEmpty()) {
                        Text("${pagerState.currentPage + 1} / ${playlist.size}", color = Color.White)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { isSlideshowActive = !isSlideshowActive }) {
                        Icon(
                            if (isSlideshowActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Slideshow",
                            tint = if (isSlideshowActive) Color.Green else Color.White
                        )
                    }
                    if (isSlideshowActive) {
                        TextButton(onClick = { 
                            slideshowInterval = if (slideshowInterval >= 10000L) 2000L else slideshowInterval + 2000L
                        }) {
                            Text("${slideshowInterval / 1000}s", color = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        }

        // Background click to toggle controls
        Box(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                androidx.compose.foundation.gestures.detectTapGestures(
                    onTap = { showControls = !showControls }
                )
            }
        )
    }
}
