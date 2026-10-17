package com.omni.sync.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.logic.terminal.TerminalScreen
import com.omni.sync.ui.components.XtermTerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a raw terminal session is shown: full xterm rendering, or plain selectable text lines. */
enum class TerminalViewMode(val label: String) { LIVE("Live"), SIMPLE("Simple") }

private const val PREFS = "omni_agent_terminal"
private const val KEY_MODE = "mode"

private fun loadMode(context: Context) = runCatching {
    TerminalViewMode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null) ?: "LIVE")
}.getOrDefault(TerminalViewMode.LIVE)

private fun saveMode(context: Context, mode: TerminalViewMode) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()

private val TERMINAL_KEYS = listOf("Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "^C" to "\u0003")

/**
 * Raw terminal opened from the Agents screen (new terminal, or an agent's "Raw terminal" escape hatch).
 * Backed by the single RemoteTerminalClient session; the mode toggle only changes the rendering.
 */
@Composable
fun AgentTerminalScreen(
    signalRClient: SignalRClient,
    onBack: () -> Unit,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val p = agentPalette()
    val client = signalRClient.remoteTerminalClient
    val session by client.session.collectAsState()
    val status by client.sessionStatus.collectAsState()
    val connection by client.connectionState.collectAsState()
    val error by client.error.collectAsState()
    var mode by remember { mutableStateOf(loadMode(context)) }
    var input by rememberSaveable { mutableStateOf("") }
    val canSend = connection == "Connected" && session != null

    BackHandler(onBack = onBack)

    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = parentPadding.calculateTopPadding(), bottom = maxOf(imeBottom, parentPadding.calculateBottomPadding()))
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(p.surfaceAlt).padding(end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    session?.command ?: "Terminal",
                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = p.text,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (canSend) p.green else if (session != null) p.yellow else p.grey, 6)
                    Text(
                        " " + listOfNotNull(session?.workspace?.let(::projectOf), status.takeIf { session != null }).joinToString(" · ").ifBlank { "Not running" },
                        fontSize = 11.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            ModeToggle(mode) { mode = it; saveMode(context, it) }
            if (session != null) {
                IconButton(onClick = { client.stopSession() }) { Icon(Icons.Default.Stop, "Stop terminal", tint = p.red) }
            }
        }

        if (session != null && (connection != "Connected" || error != null)) {
            Text(
                error ?: "Terminal transport disconnected — reconnecting…",
                fontSize = 12.sp, color = p.red,
                modifier = Modifier.fillMaxWidth().background(p.redBg).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(if (mode == TerminalViewMode.LIVE) Color.Black else p.surface)) {
            val active = session
            if (active == null) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No terminal running", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text)
                    Text(
                        "Start one from AI → + → Terminal, or Raw terminal on a session.",
                        fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    AgentButton("Back to agents", small = true, onClick = onBack)
                }
            } else key(active.sessionId, mode) {
                when (mode) {
                    TerminalViewMode.LIVE -> {
                        val output by client.output.collectAsState()
                        XtermTerminalView(
                            output = output,
                            onInput = signalRClient::sendRemoteTerminalInput,
                            onResize = signalRClient::resizeRemoteTerminal,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    TerminalViewMode.SIMPLE -> SimpleTerminalView(signalRClient)
                }
            }
        }

        // Phone-side input
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type, then send to terminal") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                maxLines = 3,
                enabled = session != null
            )
            IconButton(
                enabled = canSend,
                onClick = {
                    if (input.isEmpty()) client.sendKey("\r") else { client.sendText(input, appendEnter = true); input = "" }
                }
            ) {
                Icon(
                    if (input.isEmpty()) Icons.AutoMirrored.Filled.KeyboardReturn else Icons.AutoMirrored.Filled.Send,
                    if (input.isEmpty()) "Send Enter" else "Send to terminal",
                    tint = if (canSend) p.text else p.faint
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TERMINAL_KEYS.forEach { (label, sequence) ->
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .background(p.raised, RoundedCornerShape(8.dp))
                        .border(1.dp, p.line, RoundedCornerShape(8.dp))
                        .clickable(enabled = canSend) { client.sendKey(sequence) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (canSend) p.text else p.faint)
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: TerminalViewMode, onChange: (TerminalViewMode) -> Unit) {
    val p = agentPalette()
    Row(
        Modifier
            .padding(horizontal = 4.dp)
            .background(p.surface, RoundedCornerShape(50))
            .border(1.dp, p.line, RoundedCornerShape(50))
            .padding(2.dp)
    ) {
        TerminalViewMode.values().forEach { option ->
            val on = option == mode
            Text(
                option.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (on) p.surface else p.muted,
                modifier = Modifier
                    .background(if (on) p.text else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onChange(option) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

/**
 * Plain-text rendering: raw PTY output runs through [TerminalScreen] at a size that fits the phone,
 * and the PTY is resized to match so TUIs lay themselves out for it.
 */
@Composable
private fun SimpleTerminalView(signalRClient: SignalRClient) {
    val p = agentPalette()
    val client = signalRClient.remoteTerminalClient
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = p.text)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
        val charWidth = remember(style) { measurer.measure("M".repeat(20), style).size.width / 20f }
        val lineHeight = with(density) { 14.sp.toPx() }
        val columns = (constraints.maxWidth / charWidth).toInt().coerceIn(20, 300)
        val rows = (constraints.maxHeight / lineHeight).toInt().coerceIn(5, 200)

        val screen = remember { TerminalScreen(columns, rows) }
        var lines by remember { mutableStateOf(emptyList<String>()) }
        val listState = rememberLazyListState()

        LaunchedEffect(columns, rows) {
            signalRClient.resizeRemoteTerminal(columns, rows)
            var fed = 0L
            withContext(Dispatchers.Default) { screen.resize(columns, rows) }
            client.snapshot.collect { snap ->
                lines = withContext(Dispatchers.Default) {
                    val delta = snap.total - fed
                    if (delta < 0 || delta > snap.text.length || fed == 0L) {
                        screen.reset()
                        screen.feed(snap.text)
                    } else if (delta > 0) {
                        screen.feed(snap.text.takeLast(delta.toInt()))
                    }
                    fed = snap.total
                    screen.lines()
                }
            }
        }

        // Follow the output unless the user scrolled up to read.
        LaunchedEffect(lines.size, lines.lastOrNull()) {
            val info = listState.layoutInfo
            val atBottom = info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 3 } ?: true
            if (atBottom && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }

        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, line ->
                    Text(line.ifEmpty { " " }, style = style, softWrap = false, maxLines = 1)
                }
                item { Spacer(Modifier.size(4.dp)) }
            }
        }
    }
}
