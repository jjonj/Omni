package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardBackspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import android.util.Log
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel // Add this import
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


// Common Windows Virtual Key Codes (VK_ values from InputService.cs)
const val VK_SHIFT: UShort = 0x10u
const val VK_CONTROL: UShort = 0x11u
const val VK_MENU: UShort = 0x12u // Alt key
const val VK_RETURN: UShort = 0x0Du // Enter key
const val VK_BACK: UShort = 0x08u // Backspace key
const val VK_TAB: UShort = 0x09u // Tab key
const val VK_ESCAPE: UShort = 0x1Bu // Esc key
const val VK_LEFT: UShort = 0x25u // Left arrow key
const val VK_UP: UShort = 0x26u // Up arrow key
const val VK_RIGHT: UShort = 0x27u // Right arrow key
const val VK_DOWN: UShort = 0x28u // Down arrow key
const val VK_DELETE: UShort = 0x2Bu // Delete key
const val VK_HOME: UShort = 0x24u // Home key
const val VK_END: UShort = 0x23u // End key
const val VK_PRIOR: UShort = 0x21u // Page Up
const val VK_NEXT: UShort = 0x22u // Page Down
const val VK_SPACE: UShort = 0x20u // Spacebar
const val VK_F4: UShort = 0x73u // F4 key
const val VK_W: UShort = 0x57u // W key
const val VK_A: UShort = 0x41u // A key

data class MouseMovePayload(val x: Float, val y: Float)


@Composable
fun RemoteControlScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?, mainViewModel: MainViewModel?) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var isShiftPressed by remember { mutableStateOf(false) }
    var isCtrlPressed by remember { mutableStateOf(false) }
    var isAltPressed by remember { mutableStateOf(false) }

    var volumeLevel by remember { mutableStateOf(50f) } // Initial value
    var isMutedState by remember { mutableStateOf(false) }
    
    // Hidden TextField to manage soft keyboard input
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(signalRClient?.connectionState?.value) {
        // Fetch initial volume and mute state when connected
        if (signalRClient?.connectionState?.value == "Connected") {
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
        keyboardController?.show()
    }

    DisposableEffect(Unit) {
        onDispose {
            keyboardController?.hide()
            // Ensure modifier keys are released when leaving the screen
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
            .onPreviewKeyEvent {
                // Intercept hardware key events (e.g., from a physical keyboard)
                // For soft keyboard, we mostly rely on TextField's value for text input
                if (it.type == KeyEventType.KeyDown) {
                    val unicodeChar = it.nativeKeyEvent.unicodeChar.toUShort()
                    if (unicodeChar.toInt() != 0) { // If it's a printable character
                        signalRClient?.sendText(unicodeChar.toInt().toChar().toString())
                        return@onPreviewKeyEvent true
                    }
                } else if (it.type == KeyEventType.KeyUp) {
                    // Handle special keys if needed, e.g., releasing a hardware modifier
                }
                false
            }
    ) {
        // Hidden TextField to capture soft keyboard input and automatically show keyboard
        TextField(
            value = textInput,
            onValueChange = { newText: String ->
                if (newText.length > textInput.length) { // New character typed
                    val charToSend = newText.last()
                    signalRClient?.sendText(charToSend.toString())
                } else if (newText.length < textInput.length) { // Backspace
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                }
                textInput = newText
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)
                    textInput = "" // Clear input after sending Enter
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(0.dp) // Hide the TextField visually
        )

        // Trackpad Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // FIX: Use the specific method that logs to dashboard
                        // Note: Depending on server, you might need to scale sensitivity
                        val sensitivity = 1.2f 
                        signalRClient?.sendMouseMove(dragAmount.x * sensitivity, dragAmount.y * sensitivity)
                    }
                    detectTapGestures(
                        onTap = { signalRClient?.sendLeftClick() },
                        onLongPress = { signalRClient?.sendRightClick() }
                    )
                }
        ) {
            Text(
                text = "Trackpad Active\n(Drag to move mouse)",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // Volume Control Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                signalRClient?.sendToggleMute()
                isMutedState = !isMutedState // Optimistic update
            }) {
                Icon(
                    imageVector = if (isMutedState) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (isMutedState) "Unmute" else "Mute"
                )
            }
            Slider(
                value = volumeLevel,
                onValueChange = { newValue ->
                    volumeLevel = newValue
                },
                onValueChangeFinished = {
                    signalRClient?.sendSetVolume(volumeLevel)
                },
                valueRange = 0f..100f,
                steps = 0,
                modifier = Modifier.weight(1f)
            )
            Text(text = "${volumeLevel.toInt()}%", modifier = Modifier.padding(start = 8.dp))
        }

        // Keyboard Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        // Check for a clear upward swipe to reopen the keyboard
                        if (dragAmount.y < -40) { // Threshold for a deliberate swipe
                            keyboardController?.show()
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Modifier Keys
                ModifierKeyButton(
                    text = "Shift",
                    isToggled = isShiftPressed,
                    onClick = {
                        isShiftPressed = !isShiftPressed
                        signalRClient?.sendKeyEvent(if (isShiftPressed) "INPUT_KEY_DOWN" else "INPUT_KEY_UP", VK_SHIFT)
                    },
                    modifier = Modifier.weight(1f)
                )
                ModifierKeyButton(
                    text = "Ctrl",
                    isToggled = isCtrlPressed,
                    onClick = {
                        isCtrlPressed = !isCtrlPressed
                        signalRClient?.sendKeyEvent(if (isCtrlPressed) "INPUT_KEY_DOWN" else "INPUT_KEY_UP", VK_CONTROL)
                    },
                    modifier = Modifier.weight(1f)
                )
                ModifierKeyButton(
                    text = "Alt",
                    isToggled = isAltPressed,
                    onClick = {
                        isAltPressed = !isAltPressed
                        signalRClient?.sendKeyEvent(if (isAltPressed) "INPUT_KEY_DOWN" else "INPUT_KEY_UP", VK_MENU)
                    },
                    modifier = Modifier.weight(1f)
                )
                // Moved Tab button to the first row
                ActionKeyButton(text = "Tab", onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB) }, modifier = Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Special Action Keys - Only Return and Delete remain here
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardReturn, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN) }, modifier = Modifier.weight(1f))
                ActionKeyButton(icon = Icons.Default.Delete, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_DELETE) }, modifier = Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // New Multi-Action Keys and moved Backspace
                ActionKeyButton(text = "Esc", onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_ESCAPE) }, modifier = Modifier.weight(1f), fontSize = 8.sp)
                ActionKeyButton(text = "A+Tab", onClick = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB)
                    signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                }, modifier = Modifier.weight(1f), fontSize = 8.sp)
                ActionKeyButton(text = "A+F4", onClick = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_MENU)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_F4)
                    signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_MENU)
                }, modifier = Modifier.weight(1f), fontSize = 8.sp)
                ActionKeyButton(text = "C+W", onClick = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_W)
                    signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                }, modifier = Modifier.weight(1f), fontSize = 8.sp)
                ActionKeyButton(text = "C+A", onClick = {
                    signalRClient?.sendKeyEvent("INPUT_KEY_DOWN", VK_CONTROL)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_A)
                    signalRClient?.sendKeyEvent("INPUT_KEY_UP", VK_CONTROL)
                    signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK)
                }, modifier = Modifier.weight(1f), fontSize = 8.sp)
                ActionKeyButton(icon = Icons.AutoMirrored.Filled.KeyboardBackspace, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK) }, modifier = Modifier.weight(1f))
            }
        }

        // Arrow Keys (Optional, can be added later or integrated into a sub-row)
        // For simplicity, omitting for now, but the structure allows it.
    }
}

@Composable
fun ModifierKeyButton(text: String, isToggled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (isToggled) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                 else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
    Button(
        onClick = onClick,
        colors = colors,
        modifier = modifier.height(40.dp)
    ) {
        Text(text, softWrap = false, fontSize = 10.sp)
    }
}

@Composable
fun ActionKeyButton(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, text: String? = null, onClick: () -> Unit, fontSize: androidx.compose.ui.unit.TextUnit = 10.sp) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp)
    ) {
        icon?.let { Icon(it, contentDescription = text ?: "") }
        text?.let { Text(it, softWrap = false, fontSize = fontSize) }
    }
}

@Preview(showBackground = true)
@Composable
fun RemoteControlScreenPreview() {
    RemoteControlScreen(signalRClient = null, mainViewModel = null)
}