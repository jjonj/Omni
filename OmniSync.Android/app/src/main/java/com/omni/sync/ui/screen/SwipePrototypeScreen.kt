package com.omni.sync.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** In-app playground: not registered as a system IME and never touches other apps' text. */
@Composable
fun SwipePrototypeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember(context) { SwipeRecognizer(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading FUTO English models…") }
    var typed by rememberSaveable { mutableStateOf("") }
    var shift by remember { mutableStateOf(false) }
    var alternatives by remember { mutableStateOf(emptyList<String>()) }
    var lastStart by remember { mutableIntStateOf(-1) }
    var requestId by remember { mutableIntStateOf(0) }

    LaunchedEffect(engine) {
        try {
            engine.prepare()
            ready = true
            status = "Ready: swipe English, tap English or Danish"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ready = false
            status = "FUTO decoder failed: " + (e.message ?: e.javaClass.simpleName)
        }
    }
    DisposableEffect(engine) {
        onDispose {
            // Separate scope: a recomposition leaving the screen must not cancel cleanup.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                engine.close()
            }
        }
    }

    fun invalidateSuggestions() {
        requestId++
        alternatives = emptyList()
        lastStart = -1
    }
    fun type(value: String) {
        invalidateSuggestions()
        typed += value
        shift = false
    }
    fun backspace() {
        invalidateSuggestions()
        if (typed.isNotEmpty()) {
            val count = Character.charCount(Character.codePointBefore(typed, typed.length))
            typed = typed.dropLast(count)
        }
    }
    fun acceptAlternative(word: String) {
        if (lastStart >= 0 && lastStart < typed.length && alternatives.isNotEmpty()) {
            typed = typed.substring(0, lastStart) + word + " "
        }
        invalidateSuggestions()
    }
    fun handleSwipe(path: List<SwipePoint>) {
        if (!ready) {
            status = "Swipe model is not ready yet"
            return
        }
        invalidateSuggestions()
        val myRequest = requestId
        val words = typed.trim().split(Regex("\\s+"))
            .filter { it.matches(Regex("[A-Za-z]+")) }.takeLast(16)
        status = "Recognizing English swipe…"
        scope.launch {
            try {
                val guesses = engine.recognize(path, words)
                if (requestId != myRequest) return@launch
                if (guesses.isEmpty()) {
                    status = "No match found — try again"
                } else {
                    val best = guesses.first()
                    lastStart = typed.length
                    typed += (if (shift) best.replaceFirstChar { it.uppercaseChar() } else best) + " "
                    alternatives = guesses
                    shift = false
                    status = "FUTO: " + best + " (tap another suggestion to correct it)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (requestId == myRequest)
                    status = "Swipe recognition failed: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("← Prototypes") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { invalidateSuggestions(); typed = ""; status = if (ready) "Ready" else status }) {
                Text("Clear")
            }
        }
        Text("Swipe typing experiment", style = MaterialTheme.typography.titleLarge)
        Text("Swipe the letter keys for English. Tap letters or Æ Ø Å to type Danish. No language switch.",
            style = MaterialTheme.typography.bodySmall)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.padding(14.dp).height(110.dp)) {
                Text(
                    typed.ifEmpty { "Your text appears here…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (typed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Text(status, style = MaterialTheme.typography.labelSmall,
            color = if (status.contains("failed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().height(43.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val visible = if (alternatives.isEmpty()) listOf(" ", " ", " ", " ") else alternatives.take(4)
            visible.forEach { word ->
                Surface(
                    color = if (word.isBlank()) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                        .clickable(enabled = word.isNotBlank()) { acceptAlternative(word) }
                ) {
                    Box(Modifier.padding(6.dp)) {
                        Text(word, maxLines = 1, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        SwipeLetters(
            shift = shift,
            onTap = { char -> type(if (shift) char.uppercase() else char.toString()) },
            onSwipe = ::handleSwipe
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            PrototypeKey("⇧", 0.8f, shift) { shift = !shift }
            PrototypeKey("Æ", 0.7f) { type(if (shift) "Æ" else "æ") }
            PrototypeKey("Ø", 0.7f) { type(if (shift) "Ø" else "ø") }
            PrototypeKey("Å", 0.7f) { type(if (shift) "Å" else "å") }
            PrototypeKey(",", 0.55f) { type(",") }
            PrototypeKey("space", 2.0f) { type(" ") }
            PrototypeKey(".", 0.55f) { type(".") }
            PrototypeKey("⌫", 0.8f) { backspace() }
            PrototypeKey("↵", 0.8f) { type("\n") }
        }
        Text("Prototype only • On-device • Powered by FUTO Swipe", 
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PrototypeKey(
    label: String, fraction: Float, active: Boolean = false, onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.weight(fraction).height(50.dp)
    ) {
        Box(Modifier.padding(vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SwipeLetters(
    shift: Boolean,
    onTap: (Char) -> Unit,
    onSwipe: (List<SwipePoint>) -> Unit
) {
    var trace by remember { mutableStateOf(emptyList<SwipePoint>()) }
    val onTapLatest by rememberUpdatedState(onTap)
    val onSwipeLatest by rememberUpdatedState(onSwipe)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val keyColor = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    } }
    Canvas(
        Modifier.fillMaxWidth().height(213.dp)
            .background(background, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.uptimeMillis
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val points = mutableListOf<SwipePoint>()
                    fun sample(pos: Offset, stamp: Long) {
                        points.add(SwipePoint(
                            (pos.x / w).coerceIn(0f, 1f),
                            (pos.y / h).coerceIn(0f, 1f),
                            max(0L, stamp - start).toFloat()
                        ))
                    }
                    sample(down.position, down.uptimeMillis)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        sample(change.position, change.uptimeMillis)
                        pressed = change.pressed
                        change.consume()
                        if (pressed) trace = points.toList()
                    }
                    trace = emptyList()
                    if (SwipeGeometry.isSwipe(points)) {
                        onSwipeLatest(points)
                    } else {
                        SwipeGeometry.keyAt(points.first().x, points.first().y)?.let(onTapLatest)
                    }
                }
            }
    ) {
        val keyHeight = size.height / 3f
        val keyWidth = size.width / 10f
        paint.color = ink.toArgb()
        paint.textSize = keyHeight * 0.32f
        for (ch in "qwertyuiopasdfghjklzxcvbnm") {
            val (x, y) = SwipeGeometry.center(ch) ?: continue
            val cx = x * size.width
            val cy = y * size.height
            drawRoundRect(
                color = keyColor,
                topLeft = Offset(cx - keyWidth * 0.46f, cy - keyHeight * 0.42f),
                size = Size(keyWidth * 0.92f, keyHeight * 0.84f),
                cornerRadius = CornerRadius(9.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.drawText(
                if (shift) ch.uppercaseChar().toString() else ch.toString(),
                cx, cy - ((paint.ascent() + paint.descent()) / 2), paint
            )
        }
        if (trace.size > 1) {
            val path = Path().apply {
                moveTo(trace[0].x * size.width, trace[0].y * size.height)
                for (p in trace.drop(1)) lineTo(p.x * size.width, p.y * size.height)
            }
            drawPath(path, trackColor, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            val last = trace.last()
            drawCircle(trackColor, radius = 6.dp.toPx(),
                center = Offset(last.x * size.width, last.y * size.height))
        }
    }
}
