package com.omni.sync.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.WindowManager
import android.media.AudioManager
import android.content.Context

@OptIn(UnstableApi::class) 
@Composable
fun VideoPlayerScreen(
    videoUrl: String, 
    playlist: List<String> = emptyList(),
    initialIndex: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    
    val prefs = remember { context.getSharedPreferences("omni_settings", Context.MODE_PRIVATE) }
    val skipIntervalMs = remember { prefs.getInt("video_skip_interval", 10) * 1000L }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isControllerVisible by remember { mutableStateOf(true) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var skipFeedbackText by remember { mutableStateOf<String?>(null) }
    
    // Use a ref for lastTapTime to avoid state update issues
    val lastTapTimeRef = remember { mutableStateOf(0L) }
    
    var currentBrightness by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness ?: -1f) }
    val initialBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }

    // Handle system back press
    BackHandler {
        onBack()
    }

    // Effect to clear skip feedback after delay
    LaunchedEffect(skipFeedbackText) {
        if (skipFeedbackText != null) {
            delay(800)
            skipFeedbackText = null
        }
    }

    // Dynamic Orientation and Fullscreen
    DisposableEffect(isLandscape) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            // Restore visibility and brightness when component is destroyed
            val window = activity?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                
                val lp = window.attributes
                lp.screenBrightness = initialBrightness
                window.attributes = lp
            }
        }
    }

    // Force sensor orientation regardless of landscape state
    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // Prepare the MediaSource when videoUrl or playlist changes
    LaunchedEffect(playlist, initialIndex) {
        if (playlist.isNotEmpty()) {
            val mediaItems = playlist.map { MediaItem.fromUri(Uri.parse(it)) }
            exoPlayer.setMediaItems(mediaItems)
            exoPlayer.seekTo(initialIndex, 0L)
            exoPlayer.prepare()
        } else {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }

    // Dispose the player when leaving the screen to free resources
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    var playerViewInstance by remember { mutableStateOf<PlayerView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            // Unified gesture handler for vertical drag (brightness/volume) and double-tap (skip)
            .pointerInput(containerSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val downY = down.position.y
                    val downTime = System.currentTimeMillis()
                    var totalDrag = 0f
                    var isDragging = false
                    var hasMoved = false
                    
                    // Determine which zone was tapped
                    val leftZone = downX < containerSize.width * 0.25f
                    val rightZone = downX > containerSize.width * 0.75f
                    val centerZone = !leftZone && !rightZone
                    
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        
                        if (change != null) {
                            val currentY = change.position.y
                            val dragAmount = currentY - change.previousPosition.y
                            
                            // Check if finger has moved significantly
                            val totalMovement = kotlin.math.abs(currentY - downY)
                            if (totalMovement > 5f) {
                                hasMoved = true
                            }
                            
                            if (kotlin.math.abs(dragAmount) > 0.1f) {
                                isDragging = true
                                totalDrag += dragAmount
                                
                                if (scale <= 1.05f) {
                                    // Left zone: brightness control
                                    if (leftZone) {
                                        val delta = -totalDrag / containerSize.height.toFloat()
                                        val newBrightness = (currentBrightness + delta).coerceIn(0.01f, 1f)
                                        if (kotlin.math.abs(newBrightness - currentBrightness) > 0.01f) {
                                            currentBrightness = newBrightness
                                            val lp = activity?.window?.attributes
                                            lp?.screenBrightness = newBrightness
                                            activity?.window?.attributes = lp
                                            totalDrag = 0f
                                            scope.launch {
                                                skipFeedbackText = "Brightness: ${(newBrightness * 100).toInt()}%"
                                            }
                                        }
                                        change.consume()
                                    }
                                    // Right zone: volume control
                                    else if (rightZone) {
                                        val deltaVolume = (-totalDrag / containerSize.height.toFloat()) * maxVolume.toFloat()
                                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val newVol = (currentVol + deltaVolume.toInt()).coerceIn(0, maxVolume)
                                        if (newVol != currentVol) {
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                            totalDrag = 0f
                                            scope.launch {
                                                skipFeedbackText = "Volume: ${(newVol.toFloat() / maxVolume.toFloat() * 100).toInt()}%"
                                            }
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    
                    // Handle tap gestures after pointer release (only if didn't move much)
                    if (!hasMoved) {
                        val tapTime = System.currentTimeMillis()
                        val lastTap = lastTapTimeRef.value
                        val timeDiff = tapTime - lastTap
                        val isDoubleTap = timeDiff < 300 && timeDiff > 0
                        
                        if (isDoubleTap) {
                            // Left zone: skip back
                            if (leftZone && scale <= 1.05f) {
                                val newPos = exoPlayer.currentPosition - skipIntervalMs
                                exoPlayer.seekTo(newPos.coerceAtLeast(0))
                                skipFeedbackText = "Back ${skipIntervalMs / 1000}s"
                            }
                            // Right zone: skip forward
                            else if (rightZone && scale <= 1.05f) {
                                val newPos = exoPlayer.currentPosition + skipIntervalMs
                                exoPlayer.seekTo(newPos.coerceAtMost(exoPlayer.duration))
                                skipFeedbackText = "Forward ${skipIntervalMs / 1000}s"
                            }
                            // Center zone: reset zoom
                            else if (centerZone && scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            lastTapTimeRef.value = 0L
                        } else {
                            lastTapTimeRef.value = tapTime
                            // Center zone single tap: toggle controls (with delay to detect double-tap)
                            if (centerZone) {
                                scope.launch {
                                    delay(310) // Wait slightly longer than double-tap threshold
                                    if (lastTapTimeRef.value == tapTime) { // Check if wasn't reset by double-tap
                                        if (isControllerVisible) playerViewInstance?.hideController()
                                        else playerViewInstance?.showController()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f || scale > 1.05f) {
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            val extraWidth = (scale - 1) * containerSize.width
                            val extraHeight = (scale - 1) * containerSize.height
                            val maxX = extraWidth / 2f
                            val maxY = extraHeight / 2f
                            offset = Offset(
                                x = (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y * scale).coerceIn(-maxY, maxY)
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    controllerShowTimeoutMs = 3000
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        isControllerVisible = visibility == android.view.View.VISIBLE
                    })
                    playerViewInstance = this
                }
            }
        )

        // Skip Feedback Overlay
        skipFeedbackText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), shape = MaterialTheme.shapes.medium)
                    .padding(16.dp)
            ) {
                androidx.compose.material3.Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }

        // Back Button Overlay
        if (isControllerVisible || !isLandscape) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }
}