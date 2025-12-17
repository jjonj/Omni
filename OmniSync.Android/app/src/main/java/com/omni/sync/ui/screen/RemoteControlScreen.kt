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
const val VK_W: UShort = 0x57u // W key
const val VK_A: UShort = 0x41u // A key

@Composable
fun RemoteControlScreen(
    modifier: Modifier = Modifier,
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope() // Scope for side-effects like the timer

    val isShiftPressed by mainViewModel.isShiftPressed.collectAsState()
    val isCtrlPressed by mainViewModel.isCtrlPressed.collectAsState()
    val isAltPressed by mainViewModel.isAltPressed.collectAsState()

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
            // Only send key up if the ViewModel says they are pressed
            if (mainViewModel?.isShiftPressed?.value == true) signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
            if (mainViewModel?.isCtrlPressed?.value == true) signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
            if (mainViewModel?.isAltPressed?.value == true) signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
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
                .pointerInput(signalRClient) { // Pass signalRClient as a key
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
                                signalRClient.sendRightClick() // Use non-nullable signalRClient
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
                                        signalRClient.sendMouseMove(delta.x * sensitivity, delta.y * sensitivity) // Use non-nullable signalRClient
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
                            signalRClient.sendLeftClick() // Use non-nullable signalRClient
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
                ModifierKeyButton(
                    "Shift",
                    isShiftPressed,
                    Modifier.weight(1f),
                    onDown = {
                        mainViewModel?.setShiftPressed(true)
                        signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_SHIFT)
                    },
                    onUp = {
                        mainViewModel?.setShiftPressed(false)
                        signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
                    },
                    onDoubleClick = {
                        signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_SHIFT)
                        signalRClient?.sendMouseClick("Left")
                        signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_SHIFT)
                        mainViewModel?.setShiftPressed(false) // Reset state after double-click action
                    }
                )
                ModifierKeyButton(
                    "Ctrl",
                    isCtrlPressed,
                    Modifier.weight(1f),
                    onDown = {
                        mainViewModel?.setCtrlPressed(true)
                        signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    },
                    onUp = {
                        mainViewModel?.setCtrlPressed(false)
                        signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    },
                    onDoubleClick = {
                        signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                        signalRClient?.sendMouseClick("Left")
                        signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                        mainViewModel?.setCtrlPressed(false) // Reset state after double-click action
                    }
                )
                ModifierKeyButton(
                    "Alt",
                    isAltPressed,
                    modifier = Modifier.weight(1f),
                    onDown = {
                        mainViewModel?.setAltPressed(true)
                        signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    },
                    onUp = {
                        mainViewModel?.setAltPressed(false)
                        signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                    },
                    onDoubleClick = {
                        signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                        signalRClient?.sendMouseClick("Left")
                        signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                        mainViewModel?.setAltPressed(false) // Reset state after double-click action
                    }
                )
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
            // Row 4: Special Actions
            val context = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionKeyButton(text = "Paste Clipboard", onClick = {
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = clipboardManager.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val clipboardText = clipData.getItemAt(0).text?.toString()
                        if (clipboardText != null) {
                            signalRClient?.sendClipboardUpdate(clipboardText)
                        }
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ModifierKeyButton(
    text: String,
    isToggled: Boolean,
    modifier: Modifier = Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()

    val colors = if (isToggled) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)

    Button(
        onClick = { /* Handled by pointerInput */ },
        colors = colors,
        modifier = modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleClick()
                    }
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                // This onClick is for single tap behavior, to trigger onDown/onUp
                // We'll manage the state with interactionSource
            }
    ) {
        Text(text, softWrap = false, fontSize = 10.sp)
    }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    onDown()
                }
                is PressInteraction.Release -> {
                    onUp()
                }
                is PressInteraction.Cancel -> {
                    onUp()
                }
            }
        }
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
    val application = com.omni.sync.OmniSyncApplication()
    val signalRClient = application.signalRClient
    val mainViewModel = com.omni.sync.viewmodel.MainViewModel(application)
    RemoteControlScreen(signalRClient = signalRClient, mainViewModel = mainViewModel)
}
