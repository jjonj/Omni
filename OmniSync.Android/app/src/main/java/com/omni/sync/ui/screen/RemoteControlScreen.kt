package com.omni.sync.ui.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardBackspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import android.content.Context
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

// Common Windows Virtual Key Codes
const val VK_SHIFT: UShort = 0x10u
const val VK_CONTROL: UShort = 0x11u
const val VK_MENU: UShort = 0x12u // Alt key
const val VK_RETURN: UShort = 0x0Du // Enter key
const val VK_BACK: UShort = 0x08u // Backspace key
const val VK_TAB: UShort = 0x09u // Tab key
const val VK_ESCAPE: UShort = 0x1Bu // Esc key
const val VK_DELETE: UShort = 0x2Bu // Delete key
const val VK_F4: UShort = 0x73u // F4 key
const val VK_A: UShort = 0x41u // A key
const val VK_W: UShort = 0x57u // W key

@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TrackpadArea(
                signalRClient = signalRClient,
                modifier = Modifier.weight(1f)
            )
            ButtonPanel(
                signalRClient = signalRClient,
                mainViewModel = mainViewModel,
                modifier = Modifier.fillMaxWidth().imePadding(),
                keyboardController = keyboardController,
                focusRequester = focusRequester
            )
        }
    }
}

@Composable
fun TrackpadArea(signalRClient: SignalRClient, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
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
                    val movementThreshold = 10.dp.toPx()
                    
                    val longPressJob = coroutineScope.launch {
                        delay(1750)
                        if (!isDrag) {
                            isRightClickTriggered = true
                            signalRClient.sendRightClick()
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            
                            if (change == null || !change.pressed) {
                                change?.consume()
                                break
                            }

                            val positionChange = change.position - downPoint
                            val distance = positionChange.getDistance()

                            if (!isDrag && distance > movementThreshold) {
                                isDrag = true
                                longPressJob.cancel()
                            }

                            if (isDrag) {
                                val delta = change.positionChange()
                                if (delta != Offset.Zero) {
                                    val sensitivity = 1.2f
                                    signalRClient.sendMouseMove(delta.x * sensitivity, delta.y * sensitivity)
                                    change.consume()
                                }
                            }
                        }
                    } finally {
                        longPressJob.cancel()
                    }

                    if (!isDrag && !isRightClickTriggered) {
                        signalRClient.sendLeftClick()
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

@Composable
fun ButtonPanel(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusRequester: FocusRequester
) {
    val coroutineScope = rememberCoroutineScope()
    val isShiftPressed by mainViewModel.isShiftPressed.collectAsState()
    val isCtrlPressed by mainViewModel.isCtrlPressed.collectAsState()
    val isAltPressed by mainViewModel.isAltPressed.collectAsState()
    val scheduledShutdownTime by mainViewModel.scheduledShutdownTime.collectAsState()

    var volumeLevel by remember { mutableStateOf(50f) }
    var isMutedState by remember { mutableStateOf(false) }
    var shutdownLabel by remember { mutableStateOf("None") }
    var shutdownIndex by remember { mutableStateOf(0) }
    var textInput by remember { mutableStateOf("") }

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
                shutdownLabel = "Error"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (signalRClient.connectionState.value.contains("Connected")) {
            signalRClient.getVolume()?.subscribe({ volumeLevel = it }, {})
            signalRClient.isMuted()?.subscribe({ isMutedState = it }, {})
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val unicodeChar = keyEvent.nativeKeyEvent.unicodeChar
                    if (unicodeChar != 0) {
                        signalRClient.sendText(unicodeChar.toChar().toString())
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
    ) {
        // Hidden TextField for keyboard
        TextField(
            value = textInput,
            onValueChange = { newText ->
                if (newText.length > textInput.length) {
                    signalRClient.sendText(newText.last().toString())
                } else if (newText.length < textInput.length) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                }
                textInput = newText
            },
            modifier = Modifier.height(0.dp).focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
                textInput = ""
            })
        )

        // Volume Slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                signalRClient.sendToggleMute()
                isMutedState = !isMutedState
            }) {
                Icon(if (isMutedState) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, null)
            }
            Slider(
                value = volumeLevel,
                onValueChange = { volumeLevel = it },
                onValueChangeFinished = { signalRClient.sendSetVolume(volumeLevel) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f)
            )
        }

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
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardReturn, modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
                }
                ActionKeyButton(icon = Icons.Default.Delete, modifier = Modifier.weight(1f)) {
                    coroutineScope.launch {
                        signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_A)
                        delay(100)
                        signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                        signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                    }
                }
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardBackspace, modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                }
            }

            // Grid 3
            val shutdownTimes = listOf(0, 15, 30, 60, 120, 300, 720)
            val context = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionKeyButton(text = "Alt+Tab", modifier = Modifier.weight(1f)) {
                    signalRClient.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB)
                    signalRClient.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                }
                ActionKeyButton(text = "Paste", modifier = Modifier.weight(1f)) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.primaryClip?.getItemAt(0)?.text?.let { signalRClient.sendText(it.toString()) }
                }
                ActionKeyButton(icon = Icons.Default.PowerSettingsNew, text = shutdownLabel, modifier = Modifier.weight(1f)) {
                    shutdownIndex = (shutdownIndex + 1) % shutdownTimes.size
                    signalRClient.sendScheduleShutdown(shutdownTimes[shutdownIndex])
                }
                ActionKeyButton(text = "Kbd", modifier = Modifier.weight(1f)) {
                    keyboardController?.show()
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
    val colors = if (isToggled) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)

    Button(
        onClick = { onToggle(!isToggled) },
        colors = colors,
        modifier = modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleClick()
                    }
                )
            },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text, softWrap = false, fontSize = 10.sp)
    }
}

@Composable
fun ActionKeyButton(
    modifier: Modifier = Modifier, 
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, 
    text: String? = null, 
    onClick: () -> Unit
) {
    Button(
        onClick = onClick, 
        modifier = modifier.height(40.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = text)
        if (text != null) Text(text, softWrap = false, fontSize = 10.sp)
    }
}

@Preview(showBackground = true)
@Composable
fun RemoteControlScreenPreview() {
    val application = com.omni.sync.OmniSyncApplication()
    val signalRClient = application.signalRClient
    val mainViewModel = com.omni.sync.viewmodel.MainViewModel(application)
    RemoteControlScreen(signalRClient = signalRClient, mainViewModel = mainViewModel)
}