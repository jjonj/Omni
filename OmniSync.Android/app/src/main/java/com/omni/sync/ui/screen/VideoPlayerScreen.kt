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
import androidx.compose.foundation.gestures.detectTapGestures
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
    
    var currentBrightness by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }

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
            // Restore visibility when component is destroyed
            val window = activity?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            if (tapOffset.x < size.width / 2) {
                                val newPos = exoPlayer.currentPosition - skipIntervalMs
                                exoPlayer.seekTo(newPos.coerceAtLeast(0))
                                skipFeedbackText = "Back ${skipIntervalMs / 1000}s"
                            } else {
                                val newPos = exoPlayer.currentPosition + skipIntervalMs
                                exoPlayer.seekTo(newPos.coerceAtMost(exoPlayer.duration))
                                skipFeedbackText = "Forward ${skipIntervalMs / 1000}s"
                            }
                        }
                    },
                    onTap = {
                        if (isControllerVisible) playerViewInstance?.hideController()
                        else playerViewInstance?.showController()
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f || scale > 1.05f) {
                        // Handle Zoom and Pan when zoomed in
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            val extraWidth = (scale - 1) * containerSize.width
                            val extraHeight = (scale - 1) * containerSize.height
                            val maxX = extraWidth / 2
                            val maxY = extraHeight / 2
                            offset = Offset(
                                x = (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                y = (offset.y + pan.y * scale).coerceIn(-maxY, maxY)
                            )
                        } else {
                            offset = Offset.Zero
                        }
                    } else if (pan.y != 0f && Math.abs(pan.y) > Math.abs(pan.x)) {
                        // Handle Brightness/Volume when not zoomed in
                        if (centroid.x < containerSize.width / 2) {
                            // Left side: Brightness
                            val delta = -pan.y / containerSize.height
                            val newBrightness = (currentBrightness + delta).coerceIn(0.01f, 1f)
                            currentBrightness = newBrightness
                            val lp = activity?.window?.attributes
                            lp?.screenBrightness = newBrightness
                            activity?.window?.attributes = lp
                            skipFeedbackText = "Brightness: ${(newBrightness * 100).toInt()}%"
                        } else {
                            // Right side: Volume
                            val deltaY = -pan.y
                            val deltaVolume = (deltaY / containerSize.height) * maxVolume * 2f
                            
                            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val newVol = (currentVol + deltaVolume).toInt().coerceIn(0, maxVolume)
                            
                            if (newVol != currentVol) {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                skipFeedbackText = "Volume: ${(newVol.toFloat() / maxVolume * 100).toInt()}%"
                            }
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