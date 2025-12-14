package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import com.omni.sync.data.repository.SignalRClient
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

data class MouseMovePayload(val x: Float, val y: Float)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RemoteControlScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var isShiftPressed by remember { mutableStateOf(false) }
    var isCtrlPressed by remember { mutableStateOf(false) }
    var isAltPressed by remember { mutableStateOf(false) }
    
    // Hidden TextField to manage soft keyboard input
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
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
                .weight(1f) // Takes remaining vertical space
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        signalRClient?.sendPayload(
                            "MOUSE_MOVE",
                            MouseMovePayload(dragAmount.x, dragAmount.y)
                        )
                    }
                }
        ) {
            Text(
                text = "Trackpad Area",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        // Keyboard Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
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

            // Special Action Keys
            ActionKeyButton(icon = Icons.Default.KeyboardBackspace, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_BACK) }, modifier = Modifier.weight(1f))
            ActionKeyButton(icon = Icons.Default.KeyboardReturn, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN) }, modifier = Modifier.weight(1f))
            ActionKeyButton(text = "Tab", onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_TAB) }, modifier = Modifier.weight(1f))
            ActionKeyButton(icon = Icons.Default.Delete, onClick = { signalRClient?.sendKeyEvent("INPUT_KEY_PRESS", VK_DELETE) }, modifier = Modifier.weight(1f))
        }

        // Arrow Keys (Optional, can be added later or integrated into a sub-row)
        // For simplicity, omitting for now, but the structure allows it.
    }
}

@Composable
fun ModifierKeyButton(text: String, isToggled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (isToggled) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                 else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
    Button(onClick = onClick, colors = colors, modifier = modifier.padding(horizontal = 2.dp)) {
        Text(text)
    }
}

@Composable
fun ActionKeyButton(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, text: String? = null, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.padding(horizontal = 2.dp)) {
        icon?.let { Icon(it, contentDescription = text ?: "") }
        text?.let { Text(it) }
    }
}

@Preview(showBackground = true)
@Composable
fun RemoteControlScreenPreview() {
    RemoteControlScreen(signalRClient = null)
}