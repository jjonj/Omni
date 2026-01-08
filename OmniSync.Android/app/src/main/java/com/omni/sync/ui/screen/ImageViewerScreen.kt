package com.omni.sync.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    initialImageUrl: String,
    playlist: List<String> = emptyList(),
    initialIndex: Int = 0,
    onBack: () -> Unit,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { if (playlist.isNotEmpty()) playlist.size else 1 })
    val coroutineScope = rememberCoroutineScope()
    
    var isSlideshowActive by remember { mutableStateOf(false) }
    var slideshowInterval by remember { mutableLongStateOf(5000L) }
    var showControls by remember { mutableStateOf(true) }
    var showSlideshowMenu by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val prefs = remember { context.getSharedPreferences("omni_settings", android.content.Context.MODE_PRIVATE) }
    var isShuffleEnabled by remember { 
        mutableStateOf(prefs.getBoolean("image_slideshow_random", false)) 
    }

    // Slideshow logic
    LaunchedEffect(isSlideshowActive, slideshowInterval, isShuffleEnabled) {
        if (isSlideshowActive) {
            while (true) {
                delay(slideshowInterval)
                if (isShuffleEnabled && playlist.size > 1) {
                    var nextPage = pagerState.currentPage
                    while (nextPage == pagerState.currentPage) {
                        nextPage = (0 until playlist.size).random()
                    }
                    pagerState.animateScrollToPage(nextPage)
                } else {
                    if (pagerState.currentPage < pagerState.pageCount - 1) {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    } else {
                        pagerState.animateScrollToPage(0) // Loop back
                    }
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
        .padding(bottom = parentPadding.calculateBottomPadding())
        .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls }
                    )
                },
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
                    
                    Box {
                        IconButton(onClick = { showSlideshowMenu = true }) {
                            Icon(Icons.Default.Settings, "Slideshow Settings", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showSlideshowMenu,
                            onDismissRequest = { showSlideshowMenu = false }
                        ) {
                            Text("Interval: ${slideshowInterval / 1000}s", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = slideshowInterval.toFloat(),
                                onValueChange = { slideshowInterval = it.toLong() },
                                valueRange = 1000f..15000f,
                                steps = 14,
                                modifier = Modifier.width(150.dp).padding(horizontal = 16.dp)
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (isShuffleEnabled) "Shuffle: ON" else "Shuffle: OFF") },
                                onClick = {
                                    isShuffleEnabled = !isShuffleEnabled
                                    prefs.edit().putBoolean("image_slideshow_random", isShuffleEnabled).apply()
                                },
                                leadingIcon = { Icon(Icons.Default.Shuffle, null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        }
    }
}
