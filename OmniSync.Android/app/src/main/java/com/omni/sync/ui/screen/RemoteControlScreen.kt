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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Layered layout: Trackpad fills the screen, ButtonPanel sits on top with shadow
    Box(modifier = modifier.fillMaxSize()) {
        TrackpadArea(
            signalRClient = signalRClient,
            modifier = Modifier.fillMaxSize()
        )
        
        // ButtonPanel with elevation and shadow at the bottom
        // We use a dynamic padding calculation to ensure the panel sits flush with the 
        // bottom navigation bar when at rest, and follows the keyboard top smoothly
        // once the keyboard height exceeds the rest position.
        val imeHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottomBarHeight = paddingValues.calculateBottomPadding()
        
        // Landing position relative to screen bottom (Total height of bars)
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
                focusRequester = focusRequester
            )
        }
    }
}

@Composable
fun TrackpadArea(signalRClient: SignalRClient, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var isDraggingLeftClick by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                            
                            if (pressedCount >= 2) {
                                // Scroll logic
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
                                        // Send Left Down
                                        signalRClient.sendPayload("MOUSE_CLICK_DOWN", mapOf("Button" to "Left"))
                                    }
                                }

                                if (isDrag) {
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        // Reduced sensitivity
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
                            // Send Left Up
                            signalRClient.sendPayload("MOUSE_CLICK_UP", mapOf("Button" to "Left"))
                            isDraggingLeftClick = false
                            lastTapTime = 0 // Reset to avoid triple tap drag
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
        Text(
            text = "Trackpad Active\n(Tap = Left Click | Hold 2s = Right Click)",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * Isolated keyboard input component.
 * Uses an "Append-Only" strategy to avoid fighting the keyboard state.
 */
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
            
            // 1. Force Cursor to End (prevents editing middle of buffer)
            if (cursor < newText.length) {
                textFieldValue = newValue.copy(selection = TextRange(newText.length))
                return@BasicTextField
            }

            // 2. Diffing Logic
            if (newText.length > oldText.length) {
                // Characters Added
                val addedCount = newText.length - oldText.length
                val addedText = newText.takeLast(addedCount)
                
                if (addedText.isNotEmpty()) {
                    // --- SMART FILTER: Detect Auto-Space ---
                    // If the keyboard sent exactly 2 chars, the second is a space,
                    // and the first is NOT a letter/digit (likely punctuation),
                    // we assume it's an auto-space insertion and strip it.
                    if (addedText.length == 2 && addedText[1] == ' ' && !addedText[0].isLetterOrDigit()) {
                        signalRClient.sendText(addedText[0].toString())
                    } else {
                        signalRClient.sendText(addedText)
                    }
                }
                textFieldValue = newValue
            } 
            else if (newText.length < oldText.length) {
                // Backspace(s) detected
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

            // 3. Buffer Maintenance
            if (newText.length < refillThreshold || newText.length > maxBufferSize) {
                val resetText = String(CharArray(bufferSize) { dummyChar })
                textFieldValue = TextFieldValue(resetText, TextRange(resetText.length))
            }
        },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false, 
            // URI Type is safer against auto-spacing/capitalization than ASCII
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
                // Capture Hardware Keys
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
    focusRequester: FocusRequester
) {
    val coroutineScope = rememberCoroutineScope()
    val isShiftPressed by mainViewModel.isShiftPressed.collectAsState()
    val isCtrlPressed by mainViewModel.isCtrlPressed.collectAsState()
    val isAltPressed by mainViewModel.isAltPressed.collectAsState()
    val scheduledShutdownTime by mainViewModel.scheduledShutdownTime.collectAsState()
    val shutdownMode by mainViewModel.shutdownMode.collectAsState()

    var volumeLevel by remember { mutableFloatStateOf(50f) }
    var isMutedState by remember { mutableStateOf(false) }
    var shutdownLabel by remember { mutableStateOf("None") }
    var shutdownIndex by remember { mutableIntStateOf(0) }

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
        
        HiddenKeyboardInput(
            signalRClient = signalRClient,
            focusRequester = focusRequester
        )

        // Volume Slider
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

        // Key Grids
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Grid 1
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModifierKeyButton("Shift", isShiftPressed, Modifier.weight(1f), onToggle = { 
                    if (it) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_SHIFT)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
                    mainViewModel.setShiftPressed(it)
                }, onDoubleClick = {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_SHIFT)
                    signalRClient.sendMouseClick("Left")
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
                    mainViewModel.setShiftPressed(false)
                })
                ModifierKeyButton("Ctrl", isCtrlPressed, Modifier.weight(1f), onToggle = { 
                    if (it) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    mainViewModel.setCtrlPressed(it)
                }, onDoubleClick = {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    signalRClient.sendMouseClick("Left")
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    mainViewModel.setCtrlPressed(false)
                })
                ModifierKeyButton("Alt", isAltPressed, Modifier.weight(1f), onToggle = { 
                    if (it) signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    else signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                    mainViewModel.setAltPressed(it)
                }, onDoubleClick = {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient.sendMouseClick("Left")
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                    mainViewModel.setAltPressed(false)
                })
                ActionKeyButton(text = "Tab", modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB)
                }
            }

            // Grid 2
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionKeyButton(text = "Esc", modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE)
                }
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

            // Grid 3
            val shutdownTimes = listOf(0, 15, 30, 60, 120, 300, 720)
            val context = LocalContext.current
            var showMoreButtons by remember { mutableStateOf(false) }

            if (!showMoreButtons) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActionKeyButton(text = "Kbd", modifier = Modifier.weight(1f)) {
                        if (isKeyboardVisible) {
                            keyboardController?.hide()
                        } else {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    }
                    ActionKeyButton(text = "Space", modifier = Modifier.weight(1f)) {
                        signalRClient.sendText(" ")
                    }
                    ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardReturn, modifier = Modifier.weight(1f)) {
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
                    }
                    ActionKeyButton(text = "More", modifier = Modifier.weight(1f)) {
                        showMoreButtons = true
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionKeyButton(text = "Paste", modifier = Modifier.weight(1f)) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.text?.let { signalRClient.sendText(it.toString()) }
                        }

                        val isSleep = shutdownMode.contains("Sleep", ignoreCase = true)
                        ActionKeyButton(
                            icon = if (isSleep) Icons.Default.ModeNight else Icons.Default.PowerSettingsNew, 
                            text = shutdownLabel, 
                            modifier = Modifier.weight(1f),
                            onClick = {
                                shutdownIndex = (shutdownIndex + 1) % shutdownTimes.size
                                signalRClient.sendScheduleShutdown(shutdownTimes[shutdownIndex])
                            },
                            onLongClick = {
                                signalRClient.toggleShutdownMode()
                            }
                        )
                        
                        ActionKeyButton(text = "Alt+Tab", modifier = Modifier.weight(1f)) {
                            signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                            signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB)
                            signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionKeyButton(icon = Icons.Default.Delete, modifier = Modifier.weight(1f)) {
                            coroutineScope.launch {
                                signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                                signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_A)
                                delay(100)
                                signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                                signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                            }
                        }

                        ActionKeyButton(text = "Back", modifier = Modifier.weight(1f)) {
                            showMoreButtons = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModifierKeyButton(
    text: String,
    isToggled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
    onDoubleClick: () -> Unit
) {
    val containerColor = if (isToggled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (isToggled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    FilledTonalButton(
        onClick = { onToggle(!isToggled) },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = contentColor),
        modifier = modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleClick()
                    },
                    onTap = {
                         onToggle(!isToggled)
                    }
                )
            },
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Visible, fontSize = 11.sp)
    }
}

@Composable
fun ActionKeyButton(
    modifier: Modifier = Modifier, 
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, 
    text: String? = null, 
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { onLongClick() },
                onTap = { onClick() }
            )
        }
    } else {
        Modifier.clickable { onClick() }
    }

    Surface(
        modifier = modifier
            .height(40.dp)
            .then(clickModifier),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
            if (icon != null && text != null) Spacer(Modifier.width(4.dp))
            if (text != null) Text(text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Visible, fontSize = 11.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RemoteControlScreenPreview() {
    // Preview scaffolding
}