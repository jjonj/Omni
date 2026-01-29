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