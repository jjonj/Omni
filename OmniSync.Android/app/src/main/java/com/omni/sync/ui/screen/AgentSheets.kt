package com.omni.sync.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.AgentSessionDetailData
import com.omni.sync.data.repository.AgentSessionState
import com.omni.sync.utils.AgentNotifyLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Providers Omni can start turns for (mirrors AgentSessionService.ControllableProviders on the Hub). */
val CONTROLLABLE_PROVIDERS = listOf("Codex", "Claude", "Antigravity")

/** Pseudo-provider in the New session sheet: a raw remote terminal instead of an agent turn. */
const val TERMINAL_PROVIDER = "Terminal"

private val TERMINAL_COMMANDS = listOf("pwsh", "claude", "codex", "agy", "cmd")

/** One-shot requests from the inbox to the detail screen (open the handover sheet, focus the composer). */
object AgentUiBus {
    val pendingHandover = MutableStateFlow<String?>(null)
    val focusComposer = MutableStateFlow<String?>(null)
    val drafts = mutableMapOf<String, String>()
}

private data class InstructionPreset(val name: String, val text: String)

private val PRESETS = listOf(
    InstructionPreset("Continue implementation", "Inspect the current project state and continue the most relevant unfinished work."),
    InstructionPreset("Review current changes", "Review the current working changes for bugs, regressions and unnecessary complexity."),
    InstructionPreset("What should we do next?", "Inspect the current project state and tell me the next implementation step."),
    InstructionPreset("Investigate and fix issue", "Investigate the issue, identify the root cause, implement a focused fix, and add a regression test when practical."),
    InstructionPreset("Custom instruction", "")
)

@Composable
fun AgentSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = agentPalette()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface,
        contentColor = p.text
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            content = content
        )
    }
}

@Composable
fun SheetTitle(title: String, lead: String? = null) {
    val p = agentPalette()
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.text)
        if (!lead.isNullOrBlank()) Text(lead, fontSize = 12.sp, color = p.muted, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    val p = agentPalette()
    Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = p.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun ProviderSegments(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val p = agentPalette()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { provider ->
            val on = provider == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .background(if (on) p.raised else p.surfaceAlt, RoundedCornerShape(11.dp))
                    .border(1.dp, if (on) p.text.copy(alpha = 0.8f) else p.line, RoundedCornerShape(11.dp))
                    .clickable { onSelect(provider) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ProviderMark(provider, 22)
                Text(provider, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
fun NotifySheet(session: AgentSessionState, current: AgentNotifyLevel, onPick: (AgentNotifyLevel) -> Unit, onDismiss: () -> Unit) {
    AgentSheet(onDismiss) {
        SheetTitle("Notifications", "${session.title.take(60)} stays in the inbox whatever you pick. This only controls push notifications.")
        Column(Modifier.padding(horizontal = 14.dp)) {
            AgentNotifyLevel.values().forEach { level ->
                AgentRadioOption(level.label + if (level == AgentNotifyLevel.DEFAULT) " (default)" else "", level.hint, level == current) { onPick(level) }
            }
        }
    }
}

@Composable
fun CapabilitiesSheet(session: AgentSessionState, onDismiss: () -> Unit) {
    val p = agentPalette()
    val c = session.capabilities
    val rows = listOf(
        Triple("Send messages", c?.canSend == true, if (c?.canSend == true) "Continue the same provider session" else "Not available right now"),
        Triple("Queue while busy", c?.canQueue == true, "Messages sent during a turn wait and send when it ends"),
        Triple("Parallel turn", c?.canSendParallel == true, "Start a turn now while another client is mid-turn"),
        Triple("Stop active turn", c?.canInterrupt == true, if (session.liveElsewhere) "The turn belongs to another client" else "Only turns started from Omni"),
        Triple("Answer questions", c?.canAnswerQuestions == true, "Answers are sent as your next message"),
        Triple("Approve tools", c?.canApproveTools == true, "Needs a structured provider backend"),
        Triple("Change model", c?.canChangeModel == true, "Needs a structured provider backend"),
        Triple("Live events", c?.canObserveLiveEvents == true, "Updates arrive by polling the transcript"),
        Triple("Raw terminal", c?.canOpenTerminal == true, session.terminalCommand.ifBlank { "No resume command known" })
    )
    AgentSheet(onDismiss) {
        SheetTitle("Capabilities", listOfNotNull(c?.backend?.takeIf { it.isNotBlank() }, c?.note?.takeIf { it.isNotBlank() }).joinToString("\n"))
        Column(Modifier.padding(horizontal = 18.dp)) {
            rows.forEach { (name, ok, hint) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(if (ok) "✓" else "—", color = if (ok) p.green else p.faint, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text)
                        Text(hint, fontSize = 12.sp, color = p.muted, fontFamily = if (name == "Raw terminal") FontFamily.Monospace else null)
                    }
                }
            }
        }
    }
}

/** Default context handed to the next provider; the user can edit it before sending. */
fun handoverSummary(detail: AgentSessionDetailData?, session: AgentSessionState): String {
    val events = detail?.events.orEmpty()
    val firstUser = events.firstOrNull { it.kind == "user" }?.text?.trim()
    val lastUser = events.lastOrNull { it.kind == "user" }?.text?.trim()
    val lastAgent = events.lastOrNull { it.kind == "assistant" }?.text?.trim()?.take(1500)
    val files = events.filter { it.kind == "tool" || it.kind == "command" }
        .map { toolItemOf(it, false) }
        .filter { it.kind == ToolKind.EDIT && it.target.isNotBlank() }
        .map { it.target }
        .distinct()
    return buildString {
        appendLine("You are taking over a coding task from a ${session.provider} session. Continue from where it stands.")
        appendLine()
        appendLine("Task: ${firstUser ?: session.title}")
        if (lastUser != null && lastUser != firstUser) appendLine("Latest request: $lastUser")
        if (!lastAgent.isNullOrBlank()) appendLine("Where it stands: $lastAgent")
        if (files.isNotEmpty()) appendLine("Files changed so far: ${files.joinToString(", ")}")
        session.question?.let { appendLine("Open question: ${it.text}") }
        session.error?.let { appendLine("Last error: $it") }
        append("Workspace: ${session.cwd}")
    }
}

@Composable
fun HandoverSheet(
    session: AgentSessionState,
    detail: AgentSessionDetailData?,
    onStart: (provider: String, summary: String, archiveOriginal: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val p = agentPalette()
    val targets = CONTROLLABLE_PROVIDERS.filter { !it.equals(session.provider, ignoreCase = true) }
    var target by remember { mutableStateOf(targets.first()) }
    var summary by remember(detail?.id) { mutableStateOf(handoverSummary(detail, session)) }
    var archive by remember { mutableStateOf(false) }
    AgentSheet(onDismiss) {
        SheetTitle("Hand over", "Starts a new session in ${projectOf(session.cwd).ifBlank { "the same folder" }} from a summary of this one. Nothing is lost; this session stays resumable.")
        Column(Modifier.padding(horizontal = 14.dp)) {
            FieldLabel("Continue with")
            ProviderSegments(targets, target) { target = it }
            FieldLabel("Context handed over · editable")
            OutlinedTextField(summary, { summary = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = p.text))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { archive = !archive }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(archive, { archive = it })
                Text("Archive this session afterwards", fontSize = 13.sp, color = p.text)
            }
            if (session.cwd.isBlank()) Text("This session has no known folder, so the new one cannot start.", fontSize = 12.sp, color = p.red)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                AgentButton("Hand over", Modifier.weight(1f), AgentButtonStyle.PRIMARY, enabled = session.cwd.isNotBlank() && summary.isNotBlank()) {
                    onStart(target, summary.trim(), archive)
                }
            }
        }
    }
}

@Composable
fun NewSessionSheet(
    projects: List<Pair<String, String>>,
    terminalCommand: String,
    onStart: (provider: String, cwd: String, message: String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = agentPalette()
    var provider by remember { mutableStateOf(CONTROLLABLE_PROVIDERS.first()) }
    var folder by remember { mutableStateOf(projects.firstOrNull()?.second ?: "") }
    var custom by remember { mutableStateOf(projects.isEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(PRESETS.first()) }
    var customText by remember { mutableStateOf("") }
    var command by remember { mutableStateOf(terminalCommand.ifBlank { "pwsh" }) }
    val isTerminal = provider == TERMINAL_PROVIDER
    val message = when {
        isTerminal -> command.trim()
        preset.text.isBlank() -> customText.trim()
        else -> preset.text
    }

    AgentSheet(onDismiss) {
        SheetTitle(
            "New session",
            if (isTerminal) "Opens a raw terminal on the PC in the chosen folder. Replaces any terminal already open."
            else "Runs on the PC in the chosen folder. It appears under Working while it starts."
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            FieldLabel("Provider")
            ProviderSegments(CONTROLLABLE_PROVIDERS + TERMINAL_PROVIDER, provider) { provider = it }

            FieldLabel("Project folder")
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(p.surfaceAlt, RoundedCornerShape(11.dp))
                        .border(1.dp, p.lineStrong, RoundedCornerShape(11.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (custom) "Custom folder…" else projectOf(folder), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text)
                        if (!custom) Text(folder, fontSize = 11.sp, color = p.muted, maxLines = 1)
                    }
                    Text("▾", color = p.muted)
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    projects.forEach { (name, path) ->
                        DropdownMenuItem(
                            text = { Column { Text(name, fontWeight = FontWeight.Bold); Text(path, fontSize = 11.sp, color = p.muted) } },
                            onClick = { folder = path; custom = false; menuOpen = false }
                        )
                    }
                    DropdownMenuItem(text = { Text("Custom folder…") }, onClick = { custom = true; folder = ""; menuOpen = false })
                }
            }
            if (custom) {
                OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("C:\\Dev\\MyProject") }, singleLine = true)
            }

            if (isTerminal) {
                FieldLabel("Command")
                OutlinedTextField(
                    command, { command = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = p.text)
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TERMINAL_COMMANDS.forEach { option ->
                        val on = option == command.trim()
                        Text(
                            option,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            color = if (on) p.surface else p.text,
                            modifier = Modifier
                                .background(if (on) p.text else p.raised, RoundedCornerShape(50))
                                .border(1.dp, p.line, RoundedCornerShape(50))
                                .clickable { command = option }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            } else {
                FieldLabel("First instruction")
                PRESETS.forEach { option ->
                    AgentRadioOption(option.name, option.text.ifBlank { "Write your own first message" }, option == preset) { preset = option }
                }
                if (preset.text.isBlank()) {
                    OutlinedTextField(customText, { customText = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(top = 4.dp), placeholder = { Text("What should the agent do?") })
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                AgentButton(if (isTerminal) "Open terminal" else "Start session", Modifier.weight(1f), AgentButtonStyle.PRIMARY, enabled = folder.isNotBlank() && message.isNotBlank()) {
                    onStart(provider, folder.trim(), message)
                }
            }
        }
    }
}

@Composable
fun RenameSheet(session: AgentSessionState, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = agentPalette()
    var title by remember(session.id) { mutableStateOf(session.title) }
    AgentSheet(onDismiss) {
        SheetTitle("Rename", "The name is kept by Omni on the PC; the provider's transcript is not changed.")
        Column(Modifier.padding(horizontal = 14.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Session name") })
            Text(
                "Use the original title",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.muted,
                modifier = Modifier.padding(top = 4.dp).clickable { onSave("") }.padding(vertical = 8.dp)
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                AgentButton("Save", Modifier.weight(1f), AgentButtonStyle.PRIMARY, enabled = title.isNotBlank()) { onSave(title.trim()) }
            }
        }
    }
}

/** Permanent delete, confirmed by holding the button (a tap does nothing). */
@Composable
fun DeleteSessionSheet(session: AgentSessionState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val openOnPc = session.liveElsewhere || !session.openIn.isNullOrBlank()
    AgentSheet(onDismiss) {
        SheetTitle(
            "Delete permanently",
            "Deletes the ${session.provider} transcript files for \"${session.title.take(60)}\" from the PC. It cannot be resumed or restored afterwards." +
                if (openOnPc) "\n\nIt is open on the PC right now; that client may write it again." else ""
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            HoldToConfirmButton("Hold to delete", Modifier.fillMaxWidth(), onConfirm = onDelete)
            AgentButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onDismiss)
        }
    }
}

@Composable
fun HoldToConfirmButton(text: String, modifier: Modifier = Modifier, holdMs: Int = 900, onConfirm: () -> Unit) {
    val p = agentPalette()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(p.redBg)
            .border(1.dp, p.redLine, shape)
            .drawBehind { drawRect(p.red.copy(alpha = 0.35f), size = Size(size.width * progress.value, size.height)) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = p.red, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}
