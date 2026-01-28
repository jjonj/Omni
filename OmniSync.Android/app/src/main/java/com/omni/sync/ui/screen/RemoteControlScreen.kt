package com.omni.sync.ui.screen

import android.content.Context
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.data.model.Macro
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.Image
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import com.omni.sync.utils.WindowsKeyCodes.VK_A
import com.omni.sync.utils.WindowsKeyCodes.VK_BACK
import com.omni.sync.utils.WindowsKeyCodes.VK_CONTROL
import com.omni.sync.utils.WindowsKeyCodes.VK_DELETE
import com.omni.sync.utils.WindowsKeyCodes.VK_DOWN
import com.omni.sync.utils.WindowsKeyCodes.VK_ESCAPE
import com.omni.sync.utils.WindowsKeyCodes.VK_LEFT
import com.omni.sync.utils.WindowsKeyCodes.VK_MENU
import com.omni.sync.utils.WindowsKeyCodes.VK_RETURN
import com.omni.sync.utils.WindowsKeyCodes.VK_RIGHT
import com.omni.sync.utils.WindowsKeyCodes.VK_SHIFT
import com.omni.sync.utils.WindowsKeyCodes.VK_TAB
import com.omni.sync.utils.WindowsKeyCodes.VK_UP
import com.omni.sync.utils.WindowsKeyCodes.VK_LWIN
import com.omni.sync.ui.components.ActionKeyButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.view.HapticFeedbackConstants
import android.media.ToneGenerator
import android.media.AudioManager
import android.media.SoundPool
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.platform.LocalView
import com.omni.sync.utils.WindowsKeyCodes.VK_F1
import com.omni.sync.utils.WindowsKeyCodes.VK_F10
import com.omni.sync.utils.WindowsKeyCodes.VK_F11
import com.omni.sync.utils.WindowsKeyCodes.VK_F12
import com.omni.sync.utils.WindowsKeyCodes.VK_F2
import com.omni.sync.utils.WindowsKeyCodes.VK_F3
import com.omni.sync.utils.WindowsKeyCodes.VK_F4
import com.omni.sync.utils.WindowsKeyCodes.VK_F5
import com.omni.sync.utils.WindowsKeyCodes.VK_F6
import com.omni.sync.utils.WindowsKeyCodes.VK_F7
import com.omni.sync.utils.WindowsKeyCodes.VK_F8
import com.omni.sync.utils.WindowsKeyCodes.VK_F9
import com.omni.sync.utils.WindowsKeyCodes.VK_CAPITAL
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_1
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_2
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_3
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_4
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_5
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_6
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_7
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_COMMA
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_MINUS
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_PERIOD
import com.omni.sync.utils.WindowsKeyCodes.VK_OEM_PLUS
import com.omni.sync.utils.WindowsKeyCodes.VK_SPACE
import com.omni.sync.utils.WindowsKeyCodes.VK_0
import com.omni.sync.utils.WindowsKeyCodes.VK_1
import com.omni.sync.utils.WindowsKeyCodes.VK_2
import com.omni.sync.utils.WindowsKeyCodes.VK_3
import com.omni.sync.utils.WindowsKeyCodes.VK_4
import com.omni.sync.utils.WindowsKeyCodes.VK_5
import com.omni.sync.utils.WindowsKeyCodes.VK_6
import com.omni.sync.utils.WindowsKeyCodes.VK_7
import com.omni.sync.utils.WindowsKeyCodes.VK_8
import com.omni.sync.utils.WindowsKeyCodes.VK_9
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    paddingValues: PaddingValues = PaddingValues(0.dp)
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val isKeyboardVisible = WindowInsets.isImeVisible
    var isMonitorVisible by remember { mutableStateOf(false) }
    var monitorScale by remember { mutableFloatStateOf(1f) }
    var monitorOffset by remember { mutableStateOf(Offset.Zero) }
    var showMacroGrid by remember { mutableStateOf(false) }
    var showMoreButtons by remember { mutableStateOf(false) }

    val selectedPid by signalRClient.selectedPid.collectAsState()
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val appConfig by mainViewModel.appConfig.collectAsState()

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                signalRClient.sendText(spokenText)
            }
        }
    }

    fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        voiceLauncher.launch(intent)
    }

    // Handle back press
    androidx.activity.compose.BackHandler(enabled = (showMacroGrid || showMoreButtons) && !isLandscape) {
        if (showMoreButtons) {
            showMoreButtons = false
        } else if (showMacroGrid) {
            showMacroGrid = false
        }
    }

    if (isLandscape) {
        CustomKeyboard(
            signalRClient = signalRClient,
            appConfig = appConfig,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            if (!showMacroGrid) {
                TrackpadArea(
                    signalRClient = signalRClient,
                    modifier = Modifier.fillMaxSize(),
                    mainViewModel = mainViewModel,
                    appConfig = appConfig,
                    isMonitorVisible = isMonitorVisible,
                    scale = monitorScale,
                    offset = monitorOffset,
                    onTransform = { s, o ->
                        monitorScale = (monitorScale * s).coerceIn(1f, 5f)
                        monitorOffset += o
                    }
                )
            } else {
                MacroGridPanel(
                    mainViewModel = mainViewModel,
                    signalRClient = signalRClient,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            val imeHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val bottomBarHeight = paddingValues.calculateBottomPadding()
            
            val restHeight = bottomBarHeight
            val keyboardOverlapOffset = 15.dp
            val floatHeight = imeHeight - keyboardOverlapOffset
            
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = maxOf(floatHeight, restHeight)),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                ButtonPanel(
                    signalRClient = signalRClient,
                    mainViewModel = mainViewModel,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    keyboardController = keyboardController,
                    isKeyboardVisible = isKeyboardVisible,
                    focusRequester = focusRequester,
                    isMonitorVisible = isMonitorVisible,
                    showMoreButtons = showMoreButtons,
                    onToggleMore = { showMoreButtons = it },
                    onToggleMonitor = { 
                        isMonitorVisible = it
                        if (!it) {
                            monitorScale = 1f
                            monitorOffset = Offset.Zero
                        }
                    },
                    onToggleMacros = { showMacroGrid = !showMacroGrid },
                    onStartVoice = { startVoiceRecognition() }
                )
            }
        }
    }
}

@Composable
fun MacroGridPanel(
    mainViewModel: MainViewModel,
    signalRClient: SignalRClient,
    modifier: Modifier = Modifier
) {
    val appConfig by mainViewModel.appConfig.collectAsState()
    var macros by remember(appConfig.macros) { mutableStateOf(appConfig.macros) }
    val coroutineScope = rememberCoroutineScope()
    val parser = remember { com.omni.sync.logic.macro.MacroParser() }
    val executor = remember { com.omni.sync.logic.macro.MacroExecutor(signalRClient, macros) }
    val context = LocalContext.current
    var macroProgress by remember { mutableStateOf<String?>(null) }

    var draggingMacroId by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        if (macros.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AutoFixOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))
                Text("No macros defined", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.MACRO_MANAGER) }) {
                    Text("Manage Macros")
                }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(bottom = 180.dp)
            ) {
                itemsIndexed(macros, key = { _, m -> m.id }) { index, macro ->
                    val isDragging = draggingMacroId == macro.id
                    
                    MacroButton(
                        macro = macro,
                        isDragging = isDragging,
                        onDragStart = { draggingMacroId = macro.id },
                        onDragEnd = { 
                            draggingMacroId = null
                            // Update the config with the new macro order
                            val newConfig = appConfig.copy(macros = macros)
                            // We need a way to push this back to ViewModel. 
                            // Since appConfig is read-only StateFlow, we must update the underlying object and call save.
                            // Ideally, ViewModel should have a updateMacros method.
                            // For now, we update the object inside the flow's current value (if mutable) or create copy.
                            // But mainViewModel.appConfig is a StateFlow of a Data Class.
                            // We must update the value in ViewModel.
                            // Let's modify MainViewModel to support updates or just hack it for now by modifying the object 
                            // IF AppConfig was a class, but it's a data class.
                            // We need to update the ViewModel's state.
                            // For now, let's assume we can't easily write back to the flow without a method.
                            // BUT, we can't assign to appConfig.macros if appConfig is from collectAsState (it's val).
                            // The previous code did appConfig.macros = macros.
                            // We need to fix MainViewModel to allow updating config.
                            // Or just access the raw config object for writing?
                            // No, we should add updateConfig to ViewModel.
                            mainViewModel.updateMacros(macros)
                        },
                        onDrag = { dragAmount ->
                            val threshold = 50f
                            if (java.lang.Math.abs(dragAmount.x) > java.lang.Math.abs(dragAmount.y)) {
                                if (dragAmount.x > threshold && index % 3 < 2 && index + 1 < macros.size) {
                                    val newList = macros.toMutableList()
                                    val item = newList.removeAt(index)
                                    newList.add(index + 1, item)
                                    macros = newList
                                } else if (dragAmount.x < -threshold && index % 3 > 0) {
                                    val newList = macros.toMutableList()
                                    val item = newList.removeAt(index)
                                    newList.add(index - 1, item)
                                    macros = newList
                                }
                            } else {
                                if (dragAmount.y > threshold && index + 3 < macros.size) {
                                    val newList = macros.toMutableList()
                                    val item = newList.removeAt(index)
                                    newList.add(index + 3, item)
                                    macros = newList
                                } else if (dragAmount.y < -threshold && index - 3 >= 0) {
                                    val newList = macros.toMutableList()
                                    val item = newList.removeAt(index)
                                    newList.add(index - 3, item)
                                    macros = newList
                                }
                            }
                        },
                        onClick = {
                            if (draggingMacroId == null) {
                                coroutineScope.launch {
                                    executor.execute(parser.parse(macro.script, context), context) { macroProgress = it }
                                    macroProgress = null
                                }
                            }
                        }
                    )
                }
                
                item {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .clickable { mainViewModel.navigateTo(com.omni.sync.viewmodel.AppScreen.MACRO_MANAGER) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Default.Settings, contentDescription = "Manage", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (macroProgress != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.width(250.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(macroProgress!!, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { macroProgress = null },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MacroButton(
    macro: Macro,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .graphicsLayer {
                if (isDragging) {
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.7f
                }
            }
            .pointerInput(macro.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    }
                )
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isDragging) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val icon = when (macro.iconName.lowercase()) {
                "browser" -> Icons.Default.Language
                "folder" -> Icons.Default.Folder
                "ai" -> Icons.Default.SmartToy
                "terminal" -> Icons.Default.Terminal
                "code" -> Icons.Default.Code
                "settings" -> Icons.Default.Settings
                "music" -> Icons.Default.MusicNote
                "video" -> Icons.Default.Movie
                "chat" -> Icons.Default.Chat
                "work" -> Icons.Default.Work
                else -> Icons.Default.PlayArrow
            }
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                macro.name, 
                style = MaterialTheme.typography.labelMedium, 
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TrackpadArea(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    appConfig: com.omni.sync.data.config.AppConfig,
    modifier: Modifier = Modifier,
    isMonitorVisible: Boolean = false,
    scale: Float = 1f,
    offset: Offset = Offset.Zero,
    onTransform: (Float, Offset) -> Unit = { _, _ -> }
) {
    val coroutineScope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var isDraggingLeftClick by remember { mutableStateOf(false) }
    var screenshotTick by remember { mutableIntStateOf(0) }
    var currentFrame by remember { mutableStateOf<Painter?>(null) }

    LaunchedEffect(isMonitorVisible, appConfig.streamFps) {
        if (isMonitorVisible) {
            val fps = if (appConfig.streamFps > 0) appConfig.streamFps else 10
            val interval = (1000 / fps).toLong()
            while (true) {
                delay(interval)
                screenshotTick++
            }
        }
    }

    val baseUrl = mainViewModel.getBaseUrl()
    val scaleParam = appConfig.streamResolution / 100f
    val imageUrl = "$baseUrl/api/screenshot?t=$screenshotTick&scale=$scaleParam&quality=${appConfig.streamResolution}"

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(imageUrl)
            .crossfade(false)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
            .build(),
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                currentFrame = state.painter
            } else if (state is AsyncImagePainter.State.Error) {
                Log.e("TrackpadArea", "Failed to load screenshot: ${state.result.throwable.message}")
            }
        }
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .pointerInput(isMonitorVisible) {
                if (isMonitorVisible) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onTransform(zoom, pan)
                    }
                }
            }
            .pointerInput(signalRClient) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPoint = down.position
                    var isDrag = false
                    var isRightClickTriggered = false
                    val movementThreshold = 5.dp.toPx()
                    
                    val currentTime = System.currentTimeMillis()
                    val isDoubleTapCandidate = currentTime - lastTapTime < 300
                    
                    val longPressJob = coroutineScope.launch {
                        delay(1750)
                        if (!isDrag && !isDoubleTapCandidate) {
                            isRightClickTriggered = true
                            signalRClient.sendRightClick()
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val changes = event.changes
                            val pressedCount = changes.count { it.pressed }
                            
                            if (pressedCount >= 2 && !isMonitorVisible) {
                                val scrollDelta = changes.first().positionChange()
                                if (scrollDelta != Offset.Zero) {
                                    signalRClient.sendPayload("MOUSE_SCROLL", mapOf("Delta" to scrollDelta.y.toInt()))
                                }
                                isDrag = true
                                longPressJob.cancel()
                            } else {
                                val change = changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    change?.consume()
                                    break
                                }

                                val positionChange = change.position - downPoint
                                val distance = positionChange.getDistance()

                                if (!isDrag && distance > movementThreshold) {
                                    isDrag = true
                                    longPressJob.cancel()
                                    
                                    if (isDoubleTapCandidate) {
                                        isDraggingLeftClick = true
                                        signalRClient.sendPayload("MOUSE_CLICK_DOWN", mapOf("Button" to "Left"))
                                    }
                                }

                                if (isDrag) {
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        val sensitivity = 0.72f
                                        signalRClient.sendMouseMove(delta.x * sensitivity, delta.y * sensitivity)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    } finally {
                        longPressJob.cancel()
                        if (isDraggingLeftClick) {
                            signalRClient.sendPayload("MOUSE_CLICK_UP", mapOf("Button" to "Left"))
                            isDraggingLeftClick = false
                            lastTapTime = 0
                        } else if (!isDrag && !isRightClickTriggered) {
                            signalRClient.sendLeftClick()
                            lastTapTime = System.currentTimeMillis()
                        } else {
                            lastTapTime = 0
                        }
                    }
                }
            }
    ) {
        if (isMonitorVisible && currentFrame != null) {
            Image(
                painter = currentFrame!!,
                contentDescription = "PC Monitor",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        }

        Text(
            text = if (isMonitorVisible) "" else "Trackpad Active\n(Tap = Left Click | Hold 2s = Right Click)",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun HiddenKeyboardInput(
    signalRClient: SignalRClient,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val bufferSize = 100
    val refillThreshold = 20
    val maxBufferSize = 500
    val dummyChar = 'x'
    
    var textFieldValue by remember { 
        mutableStateOf(
            TextFieldValue(
                text = String(CharArray(bufferSize) { dummyChar }), 
                selection = TextRange(bufferSize)
            )
        ) 
    }

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val oldText = textFieldValue.text
            val newText = newValue.text
            val cursor = newValue.selection.start
            
            if (cursor < newText.length) {
                textFieldValue = newValue.copy(selection = TextRange(newText.length))
                return@BasicTextField
            }

            if (newText.length > oldText.length) {
                val addedCount = newText.length - oldText.length
                val addedText = newText.takeLast(addedCount)
                
                if (addedText.isNotEmpty()) {
                    if (addedText.length == 2 && addedText[1] == ' ' && !addedText[0].isLetterOrDigit()) {
                        signalRClient.sendText(addedText[0].toString())
                    } else {
                        signalRClient.sendText(addedText)
                    }
                }
                textFieldValue = newValue
            } 
            else if (newText.length < oldText.length) {
                val deletedCount = oldText.length - newText.length
                if (deletedCount > 0) {
                    repeat(deletedCount) {
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                    }
                }
                textFieldValue = newValue
            } else {
                textFieldValue = newValue
            }

            if (newText.length < refillThreshold || newText.length > maxBufferSize) {
                val resetText = String(CharArray(bufferSize) { dummyChar })
                textFieldValue = TextFieldValue(resetText, TextRange(resetText.length))
            }
        },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false, 
            keyboardType = KeyboardType.Uri, 
            imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(
            onSend = {
                signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
            }
        ),
        modifier = modifier
            .focusRequester(focusRequester)
            .size(1.dp) 
            .alpha(0f)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                     when (keyEvent.key) {
                         Key.Tab -> { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB); true }
                         Key.DirectionUp -> { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_UP); true }
                         Key.DirectionDown -> { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_DOWN); true }
                         Key.DirectionLeft -> { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_LEFT); true }
                         Key.DirectionRight -> { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RIGHT); true }
                         Key.Escape -> { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE); true }
                         else -> false
                     }
                } else false
            }
    )
}

@Composable
fun ButtonPanel(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    isKeyboardVisible: Boolean,
    focusRequester: FocusRequester,
    isMonitorVisible: Boolean,
    showMoreButtons: Boolean,
    onToggleMore: (Boolean) -> Unit,
    onToggleMonitor: (Boolean) -> Unit,
    onToggleMacros: () -> Unit,
    onStartVoice: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val isShiftPressed by mainViewModel.isShiftPressed.collectAsState()
    val isCtrlPressed by mainViewModel.isCtrlPressed.collectAsState()
    val isAltPressed by mainViewModel.isAltPressed.collectAsState()
    val isWinPressed by mainViewModel.isWinPressed.collectAsState()
    val scheduledShutdownTime by mainViewModel.scheduledShutdownTime.collectAsState()
    val shutdownMode by mainViewModel.shutdownMode.collectAsState()

    var volumeLevel by remember { mutableFloatStateOf(50f) }
    var isMutedState by remember { mutableStateOf(false) }
    var shutdownLabel by remember { mutableStateOf("None") }
    var shutdownIndex by remember { mutableIntStateOf(0) }

    val context = LocalContext.current

    LaunchedEffect(scheduledShutdownTime) {
        if (scheduledShutdownTime == null) {
            shutdownLabel = "None"
            shutdownIndex = 0
        } else {
            try {
                val targetTime = java.time.OffsetDateTime.parse(scheduledShutdownTime).toInstant().toEpochMilli()
                while (true) {
                    val now = System.currentTimeMillis()
                    val diff = targetTime - now
                    if (diff <= 0) {
                        shutdownLabel = "Now"
                        break
                    }
                    val totalMinutes = (diff / 1000) / 60
                    val hours = totalMinutes / 60
                    val minutes = totalMinutes % 60
                    shutdownLabel = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                    delay(1000)
                }
            } catch (e: Exception) {
                shutdownLabel = "Active"
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (signalRClient.connectionState.value.contains("Connected")) {
            signalRClient.getVolume()?.subscribe({ volumeLevel = it }, {})
            signalRClient.isMuted()?.subscribe({ isMutedState = it }, {})
        }
    }

    Column(modifier = modifier.padding(8.dp)) {
        HiddenKeyboardInput(signalRClient = signalRClient, focusRequester = focusRequester)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                signalRClient.sendToggleMute()
                isMutedState = !isMutedState
            }, modifier = Modifier.size(32.dp)) {
                Icon(if (isMutedState) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(20.dp))
            }
            Slider(
                value = volumeLevel,
                onValueChange = { volumeLevel = it },
                onValueChangeFinished = { signalRClient.sendSetVolume(volumeLevel) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModifierKeyButton("Ctrl", isCtrlPressed, Modifier.weight(1f), label = "Ctrl", mainViewModel = mainViewModel, onToggle = { 
                    if (it) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    mainViewModel.setCtrlPressed(it)
                }, onDoubleClick = {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    signalRClient.sendMouseClick("Left")
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    mainViewModel.setCtrlPressed(false)
                })
                ModifierKeyButton("Alt", isAltPressed, Modifier.weight(1f), label = "Alt", mainViewModel = mainViewModel, onToggle = { 
                    if (it) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                    mainViewModel.setAltPressed(it)
                }, onDoubleClick = {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient.sendMouseClick("Left")
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                    mainViewModel.setAltPressed(false)
                })
                ModifierKeyButton("Win", isWinPressed, Modifier.weight(1f), toggleOnTap = false, label = "Win", mainViewModel = mainViewModel, onToggle = { 
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_LWIN)
                }, onDoubleClick = {}, onLongPress = {
                    val newState = !isWinPressed
                    if (newState) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_LWIN)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_LWIN)
                    mainViewModel.setWinPressed(newState)
                })
                ActionKeyButton(text = "Tab", modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionKeyButton(text = "Esc", modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE)
                }
                ModifierKeyButton("Shift", isShiftPressed, Modifier.weight(1f), label = "Shift", mainViewModel = mainViewModel, onToggle = { 
                    if (it) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_SHIFT)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
                    mainViewModel.setShiftPressed(it)
                }, onDoubleClick = {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_SHIFT)
                    signalRClient.sendMouseClick("Left")
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
                    mainViewModel.setShiftPressed(false)
                })
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.ArrowBack, modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_LEFT)
                }
                Column(modifier = Modifier.weight(1f)) {
                    ActionKeyButton(icon = Icons.Default.KeyboardArrowUp, modifier = Modifier.fillMaxWidth().height(20.dp)) {
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_UP)
                    }
                    ActionKeyButton(icon = Icons.Default.KeyboardArrowDown, modifier = Modifier.fillMaxWidth().height(20.dp)) {
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_DOWN)
                    }
                }
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.ArrowForward, modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RIGHT)
                }
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardBackspace, modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                }
            }

            val shutdownTimes = listOf(0, 15, 30, 60, 120, 300, 720)
            if (!showMoreButtons) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActionKeyButton(icon = Icons.Default.Keyboard, modifier = Modifier.weight(1f)) { 
                        if (isKeyboardVisible) keyboardController?.hide() else { focusRequester.requestFocus(); keyboardController?.show() }
                    }
                    ActionKeyButton(icon = Icons.Default.AutoFixHigh, modifier = Modifier.weight(1f)) { onToggleMacros() }
                    ActionKeyButton(icon = Icons.Default.Mic, modifier = Modifier.weight(1f)) { onStartVoice() }
                    ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardReturn, modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN) }
                    ActionKeyButton(icon = Icons.Default.Tv, text = if (isMonitorVisible) "Off" else "On", modifier = Modifier.weight(1f), onClick = { onToggleMonitor(!isMonitorVisible) })
                    ActionKeyButton(text = "More", modifier = Modifier.weight(1f)) { onToggleMore(true) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionKeyButton(text = "Paste", modifier = Modifier.weight(1f)) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.text?.let { signalRClient.sendText(it.toString()) }
                        }
                        var showFKeys by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            ActionKeyButton(text = "F-Keys", icon = Icons.Default.ArrowDropDown) { showFKeys = true }
                            DropdownMenu(expanded = showFKeys, onDismissRequest = { showFKeys = false }) {
                                val fkeys = listOf("F1" to VK_F1, "F2" to VK_F2, "F3" to VK_F3, "F4" to VK_F4, "F5" to VK_F5, "F6" to VK_F6, "F7" to VK_F7, "F8" to VK_F8, "F9" to VK_F9, "F10" to VK_F10, "F11" to VK_F11, "F12" to VK_F12)
                                fkeys.chunked(4).forEach { row ->
                                    Row {
                                        row.forEach { (label, code) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { signalRClient.sendKeyEvent("INPUT_KEY_PRESS", code); showFKeys = false }, modifier = Modifier.width(80.dp))
                                        }
                                    }
                                }
                            }
                        }
                        val isSleep = shutdownMode.contains("Sleep", ignoreCase = true)
                        ActionKeyButton(icon = if (isSleep) Icons.Default.ModeNight else Icons.Default.PowerSettingsNew, text = shutdownLabel, modifier = Modifier.weight(1f), onClick = { shutdownIndex = (shutdownIndex + 1) % shutdownTimes.size; signalRClient.sendScheduleShutdown(shutdownTimes[shutdownIndex]) }, onLongClick = { signalRClient.toggleShutdownMode() })
                        ActionKeyButton(text = "Alt+Tab", modifier = Modifier.weight(1f)) { signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU); signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB); signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionKeyButton(icon = Icons.Default.Delete, modifier = Modifier.weight(1f)) {
                            coroutineScope.launch {
                                signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL); signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_A)
                                delay(100); signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL); signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                            }
                        }
                        ActionKeyButton(text = "Back", modifier = Modifier.weight(1f)) { onToggleMore(false) }
                    }
                }
            }
        }
    }
}

@Composable

fun CustomKeyboard(

    signalRClient: SignalRClient,

    appConfig: com.omni.sync.data.config.AppConfig,

    modifier: Modifier = Modifier

) {

    var showNumbers by remember(appConfig.showKeyboardNumberRow) { mutableStateOf(appConfig.showKeyboardNumberRow) }

    

    val context = LocalContext.current

    val soundPool = remember {

        SoundPool.Builder()

            .setMaxStreams(5)

            .setAudioAttributes(

                android.media.AudioAttributes.Builder()

                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)

                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)

                    .build()

            )

            .build()

    }

    

        var soundId by remember { mutableIntStateOf(0) }

        var soundLoaded by remember { mutableStateOf(false) }

        val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }

        

        LaunchedEffect(Unit) {

            soundId = soundPool.load(context, com.omni.sync.R.raw.button_click_01, 1)

            soundPool.setOnLoadCompleteListener { _, _, status ->

                if (status == 0) {

                    soundLoaded = true

                    Log.d("CustomKeyboard", "ButtonClick sound loaded successfully")

                } else {

                    Log.e("CustomKeyboard", "ButtonClick sound failed to load: $status")

                }

            }

        }

        

        val view = LocalView.current

    

        DisposableEffect(Unit) {

            onDispose {

                soundPool.release()

                toneGenerator.release()

            }

        }

    

        fun playClick() {

            if (appConfig.keyboardSoundEnabled) {

                if (soundLoaded) {

                    soundPool.play(soundId, 1f, 1f, 1, 0, 1f)

                } else {

                    // Fallback to tone from functional commit style

                    try {

                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

                    } catch (e: Exception) {}

                }
            }

            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    

        }



    val rows = mutableListOf<List<KeyDef>>()

    rows.add(listOf(

        KeyDef("Esc", VK_ESCAPE, weight = 1.2f, isSystem = true),

        KeyDef("# Row", VK_ESCAPE, weight = 1.2f, isSystem = true, onClick = { showNumbers = !showNumbers; playClick() }),

        KeyDef("[", VK_OEM_4, sub = "{"), KeyDef("]", VK_OEM_6, sub = "}"), KeyDef("'", VK_OEM_7, sub = "\""), KeyDef("\\", VK_OEM_5, sub = "|"), KeyDef("/", VK_OEM_2, sub = "?"), KeyDef("Bksp", VK_BACK, weight = 1.5f, isSystem = true)

    ))

    if (showNumbers) {

        rows.add(listOf(

            KeyDef("1", VK_1, sub = "!"), KeyDef("2", VK_2, sub = "@"), KeyDef("3", VK_3, sub = "#"), KeyDef("4", VK_4, sub = "$"), KeyDef("5", VK_5, sub = "%"), KeyDef("6", VK_6, sub = "^"), KeyDef("7", VK_7, sub = "&"), KeyDef("8", VK_8, sub = "*"), KeyDef("9", VK_9, sub = "("), KeyDef("0", VK_0, sub = ")"), KeyDef("-", VK_OEM_MINUS, sub = "_"), KeyDef("=", VK_OEM_PLUS, sub = "+")

        ))

    }

    rows.add(listOf(

        KeyDef("Tab", VK_TAB, weight = 1.5f, isSystem = true),

        KeyDef("Q", 0x51u.toUShort()), KeyDef("W", 0x57u.toUShort()), KeyDef("E", 0x45u.toUShort()),

        KeyDef("R", 0x52u.toUShort()), KeyDef("T", 0x54u.toUShort()), KeyDef("Y", 0x59u.toUShort()),

        KeyDef("U", 0x55u.toUShort()), KeyDef("I", 0x49u.toUShort()), KeyDef("O", 0x4Fu.toUShort()),

        KeyDef("P", 0x50u.toUShort())

    ))

    rows.add(listOf(

        KeyDef("Caps", VK_CAPITAL, weight = 1.75f, isSystem = true),

        KeyDef("A", 0x41u.toUShort()), KeyDef("S", 0x53u.toUShort()), KeyDef("D", 0x44u.toUShort()),

        KeyDef("F", 0x46u.toUShort()), KeyDef("G", 0x47u.toUShort()), KeyDef("H", 0x48u.toUShort()),

        KeyDef("J", 0x4Au.toUShort()), KeyDef("K", 0x4Bu.toUShort()), KeyDef("L", 0x4Cu.toUShort()),

        KeyDef(";", VK_OEM_1, sub = ":")

    ))

    rows.add(listOf(

        KeyDef("Shift", VK_SHIFT, weight = 2.25f, isSystem = true),

        KeyDef("Z", 0x5Au.toUShort()), KeyDef("X", 0x58u.toUShort()), KeyDef("C", 0x43u.toUShort()),

        KeyDef("V", 0x56u.toUShort()), KeyDef("B", 0x42u.toUShort()), KeyDef("N", 0x4Eu.toUShort()),

        KeyDef("M", 0x4Du.toUShort()),

        KeyDef(",", VK_OEM_COMMA, sub = "<"),

        KeyDef(".", VK_OEM_PERIOD, sub = ">")

    ))

    rows.add(listOf(

        KeyDef("Ctrl", VK_CONTROL, weight = 1.2f, isSystem = true), KeyDef("Win", VK_LWIN, weight = 1.2f, isSystem = true), KeyDef("Alt", VK_MENU, weight = 1.2f, isSystem = true), KeyDef("Space", VK_SPACE, weight = 5f), KeyDef("Enter", VK_RETURN, weight = 3f, isSystem = true)

    ))



    Column(modifier = modifier.background(Color.Black).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

        rows.forEach { row ->

            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {

                row.forEach { def ->

                    KeyboardKey(

                        def = def, 

                        modifier = Modifier.weight(def.weight), 

                        onDown = { 

                            if (def.onClick != null) {

                                // Special keys like # Row don't have down/up split

                            } else {

                                signalRClient.sendKeyEvent("INPUT_KEY_DOWN", def.code)

                                playClick()

                            }

                        },

                        onUp = {

                            if (def.onClick != null) {

                                def.onClick.invoke()

                            } else {

                                signalRClient.sendKeyEvent("INPUT_KEY_UP", def.code)

                            }

                        }

                    )

                }

            }

        }

    }

}



data class KeyDef(val label: String, val code: UShort, val sub: String? = null, val weight: Float = 1f, val isSystem: Boolean = false, val onClick: (() -> Unit)? = null)



@Composable

fun KeyboardKey(

    def: KeyDef, 

    modifier: Modifier = Modifier, 

    onDown: () -> Unit,

    onUp: () -> Unit

) {

    var isPressed by remember { mutableStateOf(false) }

    

        Surface(

    

            modifier = modifier

    

                .fillMaxHeight()

    

                .pointerInput(def.label) {

    

                    awaitEachGesture {

    

                        val down = awaitFirstDown()

    

                        isPressed = true

    

                        onDown()

    

                        

    

                        var pointerId = down.id

    

                        while (true) {

    

                            val event = awaitPointerEvent()

    

                            val isUp = event.changes.any { it.id == pointerId && !it.pressed }

    

                            if (isUp) break

    

                        }

    

                        

    

                        isPressed = false

    

                        onUp()

    

                    }

    

                }, 

    

            shape = RoundedCornerShape(6.dp), 

    

            color = if (isPressed) MaterialTheme.colorScheme.primaryContainer else if (def.isSystem) Color(0xFF444444) else Color(0xFF2C2C2C), 

    

            contentColor = if (isPressed) MaterialTheme.colorScheme.onPrimaryContainer else if (def.isSystem) Color(0xFFBB86FC) else Color.White

    

        ) {

        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {

            Text(def.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            if (def.sub != null) Text(def.sub, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp))

        }

    }

}

@Composable
fun ModifierKeyButton(
    text: String,
    isToggled: Boolean,
    modifier: Modifier = Modifier,
    toggleOnTap: Boolean = true,
    label: String = text,
    mainViewModel: MainViewModel? = null,
    onToggle: (Boolean) -> Unit,
    onDoubleClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    var localToggled by remember { mutableStateOf(isToggled) }
    LaunchedEffect(isToggled) { if (localToggled != isToggled) localToggled = isToggled }
    val containerColor = if (localToggled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (localToggled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { mainViewModel?.addLog("[Remote] $label Double-Tapped", LogType.INFO); onDoubleClick() },
                    onTap = { if (toggleOnTap) { localToggled = !localToggled; mainViewModel?.addLog("[Remote] $label Toggled: $localToggled", LogType.INFO); onToggle(localToggled) } else { mainViewModel?.addLog("[Remote] $label Tapped (No toggle)", LogType.INFO); onToggle(localToggled) } },
                    onLongPress = { localToggled = !localToggled; mainViewModel?.addLog("[Remote] $label Long-Pressed. New state: $localToggled", LogType.INFO); onLongPress?.invoke() }
                )
            }
    ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text(text, maxLines = 1, overflow = TextOverflow.Visible, fontSize = 11.sp) } }
}