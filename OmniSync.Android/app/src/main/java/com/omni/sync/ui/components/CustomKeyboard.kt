package com.omni.sync.ui.components

import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.WindowsKeyCodes

data class KeyDef(
    val label: String,
    val code: UShort,
    val sub: String? = null,
    val weight: Float = 1f,
    val isSystem: Boolean = false,
    val onClick: (() -> Unit)? = null
)

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }
}

@Composable
fun CustomKeyboard(
    signalRClient: SignalRClient,
    appConfig: com.omni.sync.data.config.AppConfig,
    modifier: Modifier = Modifier
) {
    KeepScreenOn()
    var showNumbers by remember(appConfig.showKeyboardNumberRow) { mutableStateOf(appConfig.showKeyboardNumberRow) }
    
    // Track modifier states locally to handle character mapping
    var isShiftActive by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var isWinActive by remember { mutableStateOf(false) }

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
                try {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                } catch (e: Exception) {}
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    val rows = remember(showNumbers) {
        val r = mutableListOf<List<KeyDef>>()
        
        // Define problematic characters carefully using char codes
        val quoteStr = 34.toChar().toString()
        val backslashStr = 92.toChar().toString()

        val row1 = listOf(
            KeyDef("Esc", WindowsKeyCodes.VK_ESCAPE, weight = 1.2f, isSystem = true),
            KeyDef("# Row", WindowsKeyCodes.VK_ESCAPE, weight = 1.2f, isSystem = true, onClick = { showNumbers = !showNumbers; playClick() }),
            KeyDef("[", WindowsKeyCodes.VK_OEM_4, sub = "{"),
            KeyDef("]", WindowsKeyCodes.VK_OEM_6, sub = "}"),
            KeyDef("'", WindowsKeyCodes.VK_OEM_7, sub = quoteStr),
            KeyDef(backslashStr, WindowsKeyCodes.VK_OEM_5, sub = "|"),
            KeyDef("/", WindowsKeyCodes.VK_OEM_2, sub = "?"),
            KeyDef("Bksp", WindowsKeyCodes.VK_BACK, weight = 1.5f, isSystem = true)
        )
        r.add(row1)

        if (showNumbers) {
            val numRow = listOf(
                KeyDef("1", WindowsKeyCodes.VK_1, sub = "!"),
                KeyDef("2", WindowsKeyCodes.VK_2, sub = "@"),
                KeyDef("3", WindowsKeyCodes.VK_3, sub = "#"),
                KeyDef("4", WindowsKeyCodes.VK_4, sub = "$"),
                KeyDef("5", WindowsKeyCodes.VK_5, sub = "%"),
                KeyDef("6", WindowsKeyCodes.VK_6, sub = "^"),
                KeyDef("7", WindowsKeyCodes.VK_7, sub = "&"),
                KeyDef("8", WindowsKeyCodes.VK_8, sub = "*"),
                KeyDef("9", WindowsKeyCodes.VK_9, sub = "("),
                KeyDef("0", WindowsKeyCodes.VK_0, sub = ")"),
                KeyDef("-", WindowsKeyCodes.VK_OEM_MINUS, sub = "_"),
                KeyDef("=", WindowsKeyCodes.VK_OEM_PLUS, sub = "+")
            )
            r.add(numRow)
        }

        val row2 = listOf(
            KeyDef("Tab", WindowsKeyCodes.VK_TAB, weight = 1.5f, isSystem = true),
            KeyDef("Q", 0x51u.toUShort()), KeyDef("W", 0x57u.toUShort()), KeyDef("E", 0x45u.toUShort()),
            KeyDef("R", 0x52u.toUShort()), KeyDef("T", 0x54u.toUShort()), KeyDef("Y", 0x59u.toUShort()),
            KeyDef("U", 0x55u.toUShort()), KeyDef("I", 0x49u.toUShort()), KeyDef("O", 0x4Fu.toUShort()),
            KeyDef("P", 0x50u.toUShort())
        )
        r.add(row2)

        val row3 = listOf(
            KeyDef("Caps", WindowsKeyCodes.VK_CAPITAL, weight = 1.75f, isSystem = true),
            KeyDef("A", 0x41u.toUShort()), KeyDef("S", 0x53u.toUShort()), KeyDef("D", 0x44u.toUShort()),
            KeyDef("F", 0x46u.toUShort()), KeyDef("G", 0x47u.toUShort()), KeyDef("H", 0x48u.toUShort()),
            KeyDef("J", 0x4Au.toUShort()), KeyDef("K", 0x4Bu.toUShort()), KeyDef("L", 0x4Cu.toUShort()),
            KeyDef(";", WindowsKeyCodes.VK_OEM_1, sub = ":")
        )
        r.add(row3)

        val row4 = listOf(
            KeyDef("Shift", WindowsKeyCodes.VK_SHIFT, weight = 2.25f, isSystem = true),
            KeyDef("Z", 0x5Au.toUShort()), KeyDef("X", 0x58u.toUShort()), KeyDef("C", 0x43u.toUShort()),
            KeyDef("V", 0x56u.toUShort()), KeyDef("B", 0x42u.toUShort()), KeyDef("N", 0x4Eu.toUShort()),
            KeyDef("M", 0x4Du.toUShort()),
            KeyDef(",", WindowsKeyCodes.VK_OEM_COMMA, sub = "<"),
            KeyDef(".", WindowsKeyCodes.VK_OEM_PERIOD, sub = ">")
        )
        r.add(row4)

        val row5 = listOf(
            KeyDef("Ctrl", WindowsKeyCodes.VK_CONTROL, weight = 1.2f, isSystem = true),
            KeyDef("Win", WindowsKeyCodes.VK_LWIN, weight = 1.2f, isSystem = true),
            KeyDef("Alt", WindowsKeyCodes.VK_MENU, weight = 1.2f, isSystem = true),
            KeyDef("Space", WindowsKeyCodes.VK_SPACE, weight = 5f),
            KeyDef("Enter", WindowsKeyCodes.VK_RETURN, weight = 3f, isSystem = true)
        )
        r.add(row5)
        
        r
    }

    Column(modifier = modifier.background(Color.Black).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { def ->
                    val isModifier = def.code == WindowsKeyCodes.VK_SHIFT || 
                                    def.code == WindowsKeyCodes.VK_CONTROL || 
                                    def.code == WindowsKeyCodes.VK_MENU || 
                                    def.code == WindowsKeyCodes.VK_LWIN
                    
                    val isToggled = when(def.code) {
                        WindowsKeyCodes.VK_SHIFT -> isShiftActive
                        WindowsKeyCodes.VK_CONTROL -> isCtrlActive
                        WindowsKeyCodes.VK_MENU -> isAltActive
                        WindowsKeyCodes.VK_LWIN -> isWinActive
                        else -> false
                    }

                    KeyboardKey(
                        def = def, 
                        modifier = Modifier.weight(def.weight),
                        isToggled = isToggled,
                        onDown = { 
                            if (def.onClick != null) {
                                // Handled in onUp
                            } else if (isModifier) {
                                when(def.code) {
                                    WindowsKeyCodes.VK_SHIFT -> isShiftActive = true
                                    WindowsKeyCodes.VK_CONTROL -> isCtrlActive = true
                                    WindowsKeyCodes.VK_MENU -> isAltActive = true
                                    WindowsKeyCodes.VK_LWIN -> isWinActive = true
                                }
                                signalRClient.sendKeyEvent("INPUT_KEY_DOWN", def.code)
                                playClick()
                            } else if (def.isSystem) {
                                signalRClient.sendKeyEvent("INPUT_KEY_DOWN", def.code)
                                playClick()
                            } else {
                                // Character key - send as Unicode DOWN to support repeat and layout independence
                                val charToSend = if (isShiftActive && def.sub != null && def.sub.length == 1) {
                                    def.sub[0]
                                } else if (isShiftActive && def.label.length == 1) {
                                    def.label.uppercase()[0]
                                } else if (!isShiftActive && def.label.length == 1) {
                                    def.label.lowercase()[0]
                                } else if (def.label == "Space") {
                                    ' '
                                } else if (def.label.length == 1) {
                                    def.label[0]
                                } else null
                                
                                if (charToSend != null) {
                                    signalRClient.sendUnicodeEvent("INPUT_UNICODE_DOWN", charToSend)
                                } else {
                                    // Fallback for multi-char labels if any
                                    signalRClient.sendText(def.label)
                                }
                                playClick()
                            }
                        },
                        onUp = {
                            if (def.onClick != null) {
                                def.onClick.invoke()
                            } else if (isModifier) {
                                when(def.code) {
                                    WindowsKeyCodes.VK_SHIFT -> isShiftActive = false
                                    WindowsKeyCodes.VK_CONTROL -> isCtrlActive = false
                                    WindowsKeyCodes.VK_MENU -> isAltActive = false
                                    WindowsKeyCodes.VK_LWIN -> isWinActive = false
                                }
                                signalRClient.sendKeyEvent("INPUT_KEY_UP", def.code)
                            } else if (def.isSystem) {
                                signalRClient.sendKeyEvent("INPUT_KEY_UP", def.code)
                            } else {
                                // Character key - send as Unicode UP
                                val charToSend = if (isShiftActive && def.sub != null && def.sub.length == 1) {
                                    def.sub[0]
                                } else if (isShiftActive && def.label.length == 1) {
                                    def.label.uppercase()[0]
                                } else if (!isShiftActive && def.label.length == 1) {
                                    def.label.lowercase()[0]
                                } else if (def.label == "Space") {
                                    ' '
                                } else if (def.label.length == 1) {
                                    def.label[0]
                                } else null
                                
                                if (charToSend != null) {
                                    signalRClient.sendUnicodeEvent("INPUT_UNICODE_UP", charToSend)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun KeyboardKey(
    def: KeyDef, 
    modifier: Modifier = Modifier, 
    isToggled: Boolean = false,
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
        color = if (isPressed || isToggled) MaterialTheme.colorScheme.primaryContainer else if (def.isSystem) Color(0xFF444444) else Color(0xFF2C2C2C), 
        contentColor = if (isPressed || isToggled) MaterialTheme.colorScheme.onPrimaryContainer else if (def.isSystem) Color(0xFFBB86FC) else Color.White
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(def.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (def.sub != null) Text(def.sub, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp))
        }
    }
}