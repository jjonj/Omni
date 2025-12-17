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
const val VK_W: UShort = 0x57u // W key
const val VK_A: UShort = 0x41u // A key

@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    signalRClient: SignalRClient?,
    mainViewModel: MainViewModel?
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope() // Scope for side-effects like the timer

    var isShiftPressed by remember { mutableStateOf(false) }
    var isCtrlPressed by remember { mutableStateOf(false) }
    var isAltPressed by remember { mutableStateOf(false) }

    var volumeLevel by remember { mutableStateOf(50f) }
    var isMutedState by remember { mutableStateOf(false) }

    // Hidden TextField to manage soft keyboard input
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(signalRClient?.connectionState?.collectAsState()?.value) {
        if (signalRClient?.connectionState?.value?.contains("Connected") == true) {
            signalRClient.getVolume()?.subscribe({ volume ->
                volumeLevel = volume
            }, { error ->
                Log.e("RemoteControlScreen", "Error getting initial volume: ${error.message}")
            })
            signalRClient.isMuted()?.subscribe({ muted ->
                isMutedState = muted
            }, { error ->
                Log.e("RemoteControlScreen", "Error getting initial mute state: ${error.message}")
            })
        }
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            keyboardController?.hide()
            if (isShiftPressed) signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
            if (isCtrlPressed) signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
            if (isAltPressed) signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .imePadding()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val unicodeChar = keyEvent.nativeKeyEvent.unicodeChar
                    if (unicodeChar != 0) {
                        signalRClient?.sendText(unicodeChar.toChar().toString())
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
    ) {
        // Hidden TextField
        TextField(
            value = textInput,
            onValueChange = { newText: String ->
                if (newText.length > textInput.length) {
                    val charToSend = newText.last()
                    signalRClient?.sendText(charToSend.toString())
                } else if (newText.length < textInput.length) {
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                }
                textInput = newText
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
                    textInput = ""
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(0.dp)
        )

        // --- TRACKPAD AREA ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    // This creates a dedicated gesture handler
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPoint = down.position
                        var isDrag = false
                        var isRightClickTriggered = false
                        val movementThreshold = 10.dp.toPx()
                        
                        // Launch a job on the UI scope to handle the 2-second right-click timer
                        val longPressJob = coroutineScope.launch {
                            delay(1750) // The 1.75-second threshold
                            if (!isDrag) {
                                isRightClickTriggered = true
                                Log.d("RemoteControlScreen", "Sending Right Click (Timer)")
                                signalRClient?.sendRightClick()
                            }
                        }

                        try {
                            // Process events until the finger is lifted
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                
                                if (change == null || !change.pressed) {
                                    // Finger Up
                                    change?.consume()
                                    break
                                }

                                val positionChange = change.position - downPoint
                                val distance = positionChange.getDistance()

                                // If moved beyond threshold, it's a drag
                                if (!isDrag && distance > movementThreshold) {
                                    isDrag = true
                                    longPressJob.cancel() // Cancel the right-click timer immediately
                                }

                                if (isDrag) {
                                    // Handle Mouse Movement
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        val sensitivity = 1.2f
                                        signalRClient?.sendMouseMove(delta.x * sensitivity, delta.y * sensitivity)
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            // Ensure the timer is cancelled when the gesture ends (finger up)
                            longPressJob.cancel()
                        }

                        // Gesture Finished (Finger Up)
                        // If it wasn't a drag and we haven't triggered right click yet, it's a left click
                        if (!isDrag && !isRightClickTriggered) {
                            Log.d("RemoteControlScreen", "Sending Left Click (Tap)")
                            signalRClient?.sendLeftClick()
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

        // --- CONTROLS ROW ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                signalRClient?.sendToggleMute()
                isMutedState = !isMutedState
            }) {
                Icon(
                    imageVector = if (isMutedState) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (isMutedState) "Unmute" else "Mute"
                )
            }
            Slider(
                value = volumeLevel,
                onValueChange = { newValue -> volumeLevel = newValue },
                onValueChangeFinished = { signalRClient?.sendSetVolume(volumeLevel) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f)
            )
            Text(text = "${volumeLevel.toInt()}%", modifier = Modifier.padding(start = 8.dp))
        }

        // --- KEYBOARD CONTROLS ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        if (dragAmount.y < -40) {
                            keyboardController?.show()
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: Modifiers + Tab
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModifierKeyButton("Shift", isShiftPressed, Modifier.weight(1f)) {
                    isShiftPressed = !isShiftPressed
                    signalRClient?.sendKeyEvent(if (isShiftPressed) "INPUT_KEY_DOWN" else "INPUT_KEY_UP", VK_SHIFT)
                }
                ModifierKeyButton("Ctrl", isCtrlPressed, Modifier.weight(1f)) {
                    isCtrlPressed = !isCtrlPressed
                    signalRClient?.sendKeyEvent(if (isCtrlPressed) "INPUT_KEY_DOWN" else "INPUT_KEY_UP", VK_CONTROL)
                }
                ModifierKeyButton("Alt", isAltPressed, modifier = Modifier.weight(1f)) {
                    isAltPressed = !isAltPressed
                    signalRClient?.sendKeyEvent(if (isAltPressed) "INPUT_KEY_DOWN" else "INPUT_KEY_UP", VK_MENU)
                }
                ActionKeyButton(text = "Tab", onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB) }, modifier = Modifier.weight(1f))
            }

            // Row 2: Enter, Backspace, Delete
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardReturn, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN) }, modifier = Modifier.weight(1f))
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardBackspace, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK) }, modifier = Modifier.weight(1f))
                ActionKeyButton(icon = Icons.Default.Delete, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_DELETE) }, modifier = Modifier.weight(1f))
            }

            // Row 3: Shortcuts
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionKeyButton(text = "Esc", onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE) }, modifier = Modifier.weight(1f))
                ActionKeyButton(text = "Alt+Tab", onClick = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB)
                    signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                }, modifier = Modifier.weight(1f))
                ActionKeyButton(text = "Alt+F4", onClick = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_F4)
                    signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ModifierKeyButton(text: String, isToggled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (isToggled) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
    Button(onClick = onClick, colors = colors, modifier = modifier.height(40.dp)) {
        Text(text, softWrap = false, fontSize = 10.sp)
    }
}

@Composable
fun ActionKeyButton(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, text: String? = null, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(40.dp)) {
        if (icon != null) Icon(icon, contentDescription = text)
        if (text != null) Text(text, softWrap = false, fontSize = 10.sp)
    }
}

@Preview(showBackground = true)
@Composable
fun RemoteControlScreenPreview() {
    RemoteControlScreen(signalRClient = null, mainViewModel = null)
}
