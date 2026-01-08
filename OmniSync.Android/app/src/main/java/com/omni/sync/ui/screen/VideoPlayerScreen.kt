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
    onBack: () -> Unit,
    parentPadding: PaddingValues = PaddingValues(0.dp)
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
    
    // Initialize brightness, handling -1f (system default)
    var currentBrightness by remember { 
        val initial = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableStateOf(if (initial < 0) 0.5f else initial) 
    }
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
    LaunchedEffect(playlist, initialIndex, videoUrl) {
        android.util.Log.d("VideoPlayer", "Preparing playback. URL: $videoUrl, Playlist size: ${playlist.size}, Index: $initialIndex")
        
        fun createMediaItem(url: String): MediaItem {
            return if (url.startsWith("file://")) {
                val path = url.substring(7)
                val file = java.io.File(path)
                android.util.Log.d("VideoPlayer", "Creating local MediaItem. Path: $path, Exists: ${file.exists()}")
                MediaItem.fromUri(Uri.fromFile(file))
            } else if (url.startsWith("content://")) {
                MediaItem.fromUri(Uri.parse(url))
            } else {
                MediaItem.fromUri(Uri.parse(url))
            }
        }

        if (playlist.isNotEmpty()) {
            val mediaItems = playlist.map { createMediaItem(it) }
            exoPlayer.setMediaItems(mediaItems)
            if (initialIndex >= 0 && initialIndex < mediaItems.size) {
                exoPlayer.seekTo(initialIndex, 0L)
            }
            exoPlayer.prepare()
        } else {
            val mediaItem = createMediaItem(videoUrl)
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
            .padding(bottom = parentPadding.calculateBottomPadding())
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
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
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
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

        // GESTURE OVERLAY ROW (.xxx..xxx.) with vertical gaps (...xxxx...)
        Row(modifier = Modifier.fillMaxSize()) {
            // 0-10% (Gap)
            Box(modifier = Modifier.weight(0.1f).fillMaxHeight())
            
            // 10-40% (Left Column)
            Column(modifier = Modifier.weight(0.3f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(0.3f).fillMaxWidth()) // Top Gap (30%)
                GestureZone(
                    modifier = Modifier.weight(0.4f).fillMaxWidth(), // Active Zone (40%)
                    isLeft = true,
                    currentValue = currentBrightness,
                    onValueChange = { currentBrightness = it },
                    onBrightnessChange = { brightness ->
                        val lp = activity?.window?.attributes
                        lp?.screenBrightness = brightness
                        activity?.window?.attributes = lp
                    },
                    onSkip = { delta ->
                        val newPos = exoPlayer.currentPosition + (delta * 1000)
                        exoPlayer.seekTo(newPos.coerceIn(0, exoPlayer.duration))
                        skipFeedbackText = if (delta > 0) "Forward ${delta}s" else "Back ${-delta}s"
                    },
                    onToggleController = {
                        if (isControllerVisible) playerViewInstance?.hideController()
                        else playerViewInstance?.showController()
                    },
                    skipIntervalSec = (skipIntervalMs / 1000).toInt(),
                    maxVolume = maxVolume,
                    audioManager = audioManager,
                    onFeedback = { skipFeedbackText = it },
                    lastTapTimeRef = lastTapTimeRef,
                    scope = scope,
                    scale = scale
                )
                Box(modifier = Modifier.weight(0.3f).fillMaxWidth()) // Bottom Gap (30%)
            }

            // 40-60% (Middle Gap: Passes through to native player)
            Box(modifier = Modifier.weight(0.2f).fillMaxHeight())

            // 60-90% (Right Column)
            Column(modifier = Modifier.weight(0.3f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(0.3f).fillMaxWidth()) // Top Gap (30%)
                GestureZone(
                    modifier = Modifier.weight(0.4f).fillMaxWidth(), // Active Zone (40%)
                    isLeft = false,
                    currentValue = 0f, 
                    onValueChange = {},
                    onBrightnessChange = {},
                    onSkip = { delta ->
                        val newPos = exoPlayer.currentPosition + (delta * 1000)
                        exoPlayer.seekTo(newPos.coerceIn(0, exoPlayer.duration))
                        skipFeedbackText = if (delta > 0) "Forward ${delta}s" else "Back ${-delta}s"
                    },
                    onToggleController = {
                        if (isControllerVisible) playerViewInstance?.hideController()
                        else playerViewInstance?.showController()
                    },
                    skipIntervalSec = (skipIntervalMs / 1000).toInt(),
                    maxVolume = maxVolume,
                    audioManager = audioManager,
                    onFeedback = { skipFeedbackText = it },
                    lastTapTimeRef = lastTapTimeRef,
                    scope = scope,
                    scale = scale
                )
                Box(modifier = Modifier.weight(0.3f).fillMaxWidth()) // Bottom Gap (30%)
            }

            // 90-100% (Gap)
            Box(modifier = Modifier.weight(0.1f).fillMaxHeight())
        }

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

@Composable
fun GestureZone(
    modifier: Modifier,
    isLeft: Boolean,
    currentValue: Float,
    onValueChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onSkip: (Int) -> Unit,
    onToggleController: () -> Unit,
    skipIntervalSec: Int,
    maxVolume: Int,
    audioManager: AudioManager,
    onFeedback: (String) -> Unit,
    lastTapTimeRef: MutableState<Long>,
    scope: kotlinx.coroutines.CoroutineScope,
    scale: Float
) {
    // CRITICAL: Use rememberUpdatedState so pointerInput always has fresh values
    val currentValState = rememberUpdatedState(currentValue)

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    
                    var hasMoved = false
                    var accumulatedDrag = 0f
                    
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                val dragAmount = change.position.y - change.previousPosition.y
                                val movement = kotlin.math.abs(change.position.y - down.position.y)
                                
                                if (movement > 15f) {
                                    hasMoved = true
                                }
                                
                                if (hasMoved && scale <= 1.05f) {
                                    accumulatedDrag += dragAmount
                                    if (isLeft) {
                                        // Brightness: Full range in 1/2 screen height
                                        val delta = -accumulatedDrag / (size.height / 2f)
                                        if (kotlin.math.abs(delta) >= 0.02f) {
                                            val newVal = (currentValState.value + delta).coerceIn(0.01f, 1f)
                                            onValueChange(newVal)
                                            onBrightnessChange(newVal)
                                            onFeedback("Brightness: ${(newVal * 100).toInt()}%")
                                            accumulatedDrag = 0f
                                        }
                                    } else {
                                        // Volume: Full range in 1/2 screen height
                                        val deltaVolume = (-accumulatedDrag / (size.height / 2f)) * maxVolume.toFloat()
                                        if (kotlin.math.abs(deltaVolume) >= 1f) {
                                            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            val newVol = (currentVol + deltaVolume.toInt()).coerceIn(0, maxVolume)
                                            if (newVol != currentVol) {
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                                onFeedback("Volume: ${(newVol.toFloat() / maxVolume.toFloat() * 100).toInt()}%")
                                            }
                                            accumulatedDrag = 0f
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!hasMoved) {
                        val tapTime = System.currentTimeMillis()
                        val lastTap = lastTapTimeRef.value
                        val timeDiff = tapTime - lastTap
                        
                        if (timeDiff < 300 && timeDiff > 0) {
                            lastTapTimeRef.value = 0L
                            if (scale <= 1.05f) {
                                onSkip(if (isLeft) -skipIntervalSec else skipIntervalSec)
                            }
                        } else {
                            lastTapTimeRef.value = tapTime
                            scope.launch {
                                delay(310)
                                if (lastTapTimeRef.value == tapTime) {
                                    lastTapTimeRef.value = 0L
                                    onToggleController()
                                }
                            }
                        }
                    }
                }
            }
    )
}
