package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.omni.sync.data.repository.AgentEventData
import com.omni.sync.data.repository.AgentSessionState
import com.omni.sync.utils.AgentInboxStore
import com.omni.sync.utils.AgentLocalState
import kotlinx.coroutines.delay

// ---------- palette (mockup v4 semantics on top of the app theme) ----------

@Immutable
data class AgentPalette(
    val surface: Color, val surfaceAlt: Color, val raised: Color, val line: Color, val lineStrong: Color,
    val text: Color, val muted: Color, val faint: Color,
    val red: Color, val redBg: Color, val redLine: Color,
    val green: Color, val greenBg: Color, val greenLine: Color,
    val yellow: Color, val yellowBg: Color, val yellowLine: Color,
    val blue: Color, val blueBg: Color, val blueLine: Color,
    val grey: Color
)

private val DarkAgentPalette = AgentPalette(
    surface = Color(0xFF151516), surfaceAlt = Color(0xFF121214), raised = Color(0xFF242427), line = Color(0xFF2F2F33), lineStrong = Color(0xFF45454A),
    text = Color(0xFFF1F1F2), muted = Color(0xFF9B9BA1), faint = Color(0xFF6E6E75),
    red = Color(0xFFEF8585), redBg = Color(0xFF2B1C1F), redLine = Color(0xFF5E3A3F),
    green = Color(0xFF6FD39A), greenBg = Color(0xFF17251C), greenLine = Color(0xFF34523F),
    yellow = Color(0xFFE6C76F), yellowBg = Color(0xFF29230F), yellowLine = Color(0xFF5A4C22),
    blue = Color(0xFF8FB8F5), blueBg = Color(0xFF18202D), blueLine = Color(0xFF34465F),
    grey = Color(0xFF6C6C72)
)

private val LightAgentPalette = AgentPalette(
    surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFF6F6F7), raised = Color(0xFFEDEDF0), line = Color(0xFFE0E0E4), lineStrong = Color(0xFFC5C5CB),
    text = Color(0xFF151517), muted = Color(0xFF5F5F66), faint = Color(0xFF8A8A91),
    red = Color(0xFFB3261E), redBg = Color(0xFFFCEDEC), redLine = Color(0xFFF0C2BF),
    green = Color(0xFF1E7A47), greenBg = Color(0xFFE8F5EC), greenLine = Color(0xFFB9DDC6),
    yellow = Color(0xFF8A6A00), yellowBg = Color(0xFFFFF6DB), yellowLine = Color(0xFFEAD9A0),
    blue = Color(0xFF2F5FA8), blueBg = Color(0xFFEAF1FC), blueLine = Color(0xFFC0D3F0),
    grey = Color(0xFF9A9AA0)
)

@Composable
fun agentPalette(): AgentPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) DarkAgentPalette else LightAgentPalette
}

// ---------- time ----------

const val MINUTE_MS = 60_000L
const val HOUR_MS = 3_600_000L
const val DAY_MS = 86_400_000L

fun isoMillis(iso: String): Long = AgentInboxStore.isoMillis(iso)

/** "now", "6m", "3h", "2d" */
fun agoShort(millis: Long, now: Long): String {
    if (millis <= 0) return ""
    val d = (now - millis).coerceAtLeast(0)
    return when {
        d < 45_000 -> "now"
        d < HOUR_MS -> "${(d / MINUTE_MS).coerceAtLeast(1)}m"
        d < DAY_MS -> "${d / HOUR_MS}h"
        else -> "${d / DAY_MS}d"
    }
}

fun agoLong(millis: Long, now: Long): String = agoShort(millis, now).let { if (it == "now") "just now" else if (it.isEmpty()) "" else "$it ago" }

/** Wall clock that ticks every [periodMs] so "6m" labels stay current. */
@Composable
fun rememberNow(periodMs: Long = 20_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

// ---------- sessions ----------

fun projectOf(cwd: String): String = cwd.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')

fun providerLetter(provider: String) = when (provider.lowercase()) {
    "codex" -> "CX"
    "claude" -> "CL"
    "antigravity" -> "AG"
    "opencode" -> "OC"
    "terminal" -> ">_"
    else -> provider.take(2).uppercase()
}

val NEEDS_STATES = setOf("question", "permission", "error")

enum class AgentSection(val label: String) {
    NEEDS("Needs you"), WORKING("Working"), FINISHED("Finished"), LIMITED("Usage limited"), IDLE("Idle · last 24h"), ENDED("Archived today")
}

/** Where a session sits in the inbox, or null when it only belongs in History. */
fun sectionOf(s: AgentSessionState, local: AgentLocalState, now: Long): AgentSection? {
    val modified = isoMillis(s.modified)
    val archivedAt = local.archived[s.id]
    if (archivedAt != null && modified <= archivedAt) return if (now - archivedAt < DAY_MS) AgentSection.ENDED else null
    return when (s.state) {
        in NEEDS_STATES -> AgentSection.NEEDS
        "working", "starting" -> AgentSection.WORKING
        "limited" -> AgentSection.LIMITED
        else -> when {
            now - modified > DAY_MS -> null
            modified > (local.seen[s.id] ?: 0L) -> AgentSection.FINISHED
            else -> AgentSection.IDLE
        }
    }
}

fun sectionColor(section: AgentSection?, p: AgentPalette) = when (section) {
    AgentSection.NEEDS -> p.red
    AgentSection.WORKING -> p.green
    AgentSection.FINISHED -> p.blue
    AgentSection.LIMITED -> p.yellow
    else -> p.grey
}

fun needsLabel(state: String) = when (state) {
    "question" -> "QUESTION"
    "permission" -> "PERMISSION"
    "error" -> "ERROR"
    else -> ""
}

// ---------- timeline (tool grouping) ----------

enum class ToolKind(val glyph: String) { EDIT("✎"), CMD("›_"), READ("≡"), SEARCH("⌕"), OTHER("•") }

data class ToolItem(
    val kind: ToolKind,
    val name: String,
    val target: String,
    val plus: Int = 0,
    val minus: Int = 0,
    val diff: List<Pair<String, String>>? = null,
    val output: String? = null,
    val failed: Boolean = false,
    val running: Boolean = false
)

sealed interface TimelineItem {
    val key: String
    data class User(override val key: String, val event: AgentEventData) : TimelineItem
    data class Assistant(override val key: String, val event: AgentEventData) : TimelineItem
    data class Tools(override val key: String, val items: List<ToolItem>) : TimelineItem
    data class System(override val key: String, val text: String) : TimelineItem
}

private val TARGET_KEYS = listOf(
    "file_path", "path", "AbsolutePath", "TargetFile", "filePath", "command", "cmd", "CommandLine",
    "pattern", "Query", "Pattern", "query", "url", "description", "prompt"
)

private fun kindOf(name: String): ToolKind {
    val n = name.lowercase()
    return when {
        listOf("edit", "write", "apply_patch", "replace", "patch", "create_file", "str_replace").any { it in n } -> ToolKind.EDIT
        listOf("bash", "shell", "exec", "command", "terminal", "run").any { it in n } -> ToolKind.CMD
        listOf("grep", "glob", "search", "find", "list", "ls").any { it in n } -> ToolKind.SEARCH
        listOf("read", "view", "cat", "open", "fetch").any { it in n } -> ToolKind.READ
        else -> ToolKind.OTHER
    }
}

private fun parseObject(text: String): JsonObject? =
    runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement.flat(): String = when {
    isJsonPrimitive -> asString
    isJsonArray -> asJsonArray.map { it.flat() }.let { parts ->
        // ["bash", "-lc", "npm test"] -> "npm test"
        if (parts.size >= 3 && parts[1] == "-lc") parts.last() else parts.joinToString(" ")
    }
    else -> toString()
}

private fun shortTarget(raw: String): String {
    val t = raw.trim().replace(Regex("\\s+"), " ")
    val looksLikePath = !t.contains(' ') && (t.contains('\\') || t.contains('/'))
    val short = if (looksLikePath) t.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/') else t
    return if (short.length > 90) short.take(90) + "…" else short
}

private fun countDiff(lines: List<Pair<String, String>>) = lines.count { it.first == "ADD" } to lines.count { it.first == "DEL" }

/** Turns one tool/command/output event into a presentable item. */
fun toolItemOf(event: AgentEventData, running: Boolean): ToolItem {
    val name = event.title.ifBlank { "Tool" }
    val kind = if (event.kind == "output") ToolKind.OTHER else kindOf(name)
    val args = parseObject(event.text)
    var target = ""
    var diff: List<Pair<String, String>>? = null

    if (args != null) {
        target = TARGET_KEYS.firstNotNullOfOrNull { k -> args.get(k)?.takeIf { !it.isJsonNull }?.flat()?.takeIf { it.isNotBlank() } } ?: ""
        val pairs = listOf("old_string" to "new_string", "TargetContent" to "ReplacementContent", "old" to "new")
        pairs.firstOrNull { (o, n) -> args.has(o) && args.has(n) }?.let { (o, n) ->
            diff = computeInterleavedDiff(args.get(o).flat(), args.get(n).flat())
        }
        if (diff == null && args.has("edits") && args.get("edits").isJsonArray) {
            diff = args.getAsJsonArray("edits").mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                .flatMap { e -> computeInterleavedDiff(e.get("old_string")?.flat() ?: "", e.get("new_string")?.flat() ?: "") }
        }
        if (diff == null) listOf("content", "CodeContent").firstOrNull { args.has(it) }?.let { k ->
            if (kind == ToolKind.EDIT) diff = args.get(k).flat().split("\n").map { "ADD" to it }
        }
    } else if (event.text.contains("*** Begin Patch") || event.text.contains("*** Update File:")) {
        // Codex apply_patch: the input is the patch itself.
        val lines = event.text.split("\n")
        target = lines.filter { it.startsWith("*** Update File:") || it.startsWith("*** Add File:") || it.startsWith("*** Delete File:") }
            .joinToString(", ") { it.substringAfter(':').trim().substringAfterLast('\\').substringAfterLast('/') }
        diff = lines.filterNot { it.startsWith("*** Begin") || it.startsWith("*** End") }.map {
            when {
                it.startsWith("+") -> "ADD" to it.drop(1)
                it.startsWith("-") -> "DEL" to it.drop(1)
                it.startsWith("***") || it.startsWith("@@") -> "HEADER" to it
                else -> "UNCHANGED" to it.drop(if (it.startsWith(" ")) 1 else 0)
            }
        }
    } else {
        target = event.text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    }

    val (plus, minus) = diff?.let { countDiff(it) } ?: (0 to 0)
    val output = when {
        event.kind == "output" -> event.text
        !event.result.isNullOrBlank() -> event.result
        else -> null
    }
    return ToolItem(
        kind = if (diff != null && kind == ToolKind.OTHER) ToolKind.EDIT else kind,
        name = name,
        target = shortTarget(target),
        plus = plus, minus = minus,
        diff = diff?.take(400),
        output = output,
        failed = event.resultError,
        running = running
    )
}

/**
 * Collapses consecutive technical events into one group between messages, the way the mockup shows
 * "Edited 3 files +62 −5 · ran 1 command".
 */
fun buildTimeline(events: List<AgentEventData>, working: Boolean, keyPrefix: String): List<TimelineItem> {
    val out = mutableListOf<TimelineItem>()
    var group = mutableListOf<ToolItem>()
    var groupStart = 0
    fun flush() {
        if (group.isNotEmpty()) out += TimelineItem.Tools("$keyPrefix-t$groupStart", group)
        group = mutableListOf()
    }
    events.forEachIndexed { i, e ->
        when (e.kind) {
            "user" -> { flush(); if (e.text.isNotBlank()) out += TimelineItem.User("$keyPrefix-u$i", e) }
            "assistant" -> { flush(); if (e.text.isNotBlank()) out += TimelineItem.Assistant("$keyPrefix-a$i", e) }
            "status" -> if (e.title == "turn aborted") { flush(); out += TimelineItem.System("$keyPrefix-s$i", "Turn interrupted") }
            else -> {
                if (e.kind == "tool" && e.title == "AskUserQuestion" && e.result == null) return@forEachIndexed
                if (group.isEmpty()) groupStart = i
                val isLast = i == events.lastIndex
                group += toolItemOf(e, running = working && isLast && e.kind == "tool")
            }
        }
    }
    flush()
    return out
}

fun toolSummary(items: List<ToolItem>): String {
    val edits = items.filter { it.kind == ToolKind.EDIT }
    val parts = mutableListOf<String>()
    if (edits.isNotEmpty()) parts += "edited ${edits.size} file${if (edits.size > 1) "s" else ""}"
    items.count { it.kind == ToolKind.CMD }.takeIf { it > 0 }?.let { parts += "ran $it command${if (it > 1) "s" else ""}" }
    items.count { it.kind == ToolKind.READ }.takeIf { it > 0 }?.let { parts += "read $it" }
    items.count { it.kind == ToolKind.SEARCH }.takeIf { it > 0 }?.let { parts += "searched $it×" }
    items.count { it.kind == ToolKind.OTHER }.takeIf { it > 0 }?.let { parts += "$it other step${if (it > 1) "s" else ""}" }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

// ---------- small components ----------

@Composable
fun ProviderMark(provider: String, size: Int = 24) {
    val p = agentPalette()
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(p.raised, RoundedCornerShape(7.dp))
            .border(1.dp, p.lineStrong, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(providerLetter(provider), fontSize = (size * 0.38f).sp, fontWeight = FontWeight.ExtraBold, color = p.muted)
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun AgentTag(text: String, fg: Color, bg: Color, line: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, line, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

enum class AgentButtonStyle { NORMAL, PRIMARY, DANGER, OK, WARN, GHOST }

@Composable
fun AgentButton(
    text: String,
    modifier: Modifier = Modifier,
    style: AgentButtonStyle = AgentButtonStyle.NORMAL,
    small: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val p = agentPalette()
    val (bg, fg, line) = when (style) {
        AgentButtonStyle.PRIMARY -> Triple(p.text, p.surface, p.text)
        AgentButtonStyle.DANGER -> Triple(p.redBg, p.red, p.redLine)
        AgentButtonStyle.OK -> Triple(p.greenBg, p.green, p.greenLine)
        AgentButtonStyle.WARN -> Triple(p.yellowBg, p.yellow, p.yellowLine)
        AgentButtonStyle.GHOST -> Triple(Color.Transparent, p.text, p.lineStrong)
        AgentButtonStyle.NORMAL -> Triple(p.raised, p.text, p.lineStrong)
    }
    Box(
        modifier = modifier
            .heightIn(min = if (small) 38.dp else 44.dp)
            .background(bg.copy(alpha = bg.alpha * if (enabled) 1f else 0.4f), RoundedCornerShape(11.dp))
            .border(1.dp, line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg.copy(alpha = if (enabled) 1f else 0.5f), fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
fun AgentSectionLabel(label: String, count: Int, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val p = agentPalette()
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp, color = p.muted)
        Text("  $count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.faint, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun AgentBanner(text: String) {
    val p = agentPalette()
    Text(
        text,
        fontSize = 12.sp,
        color = p.yellow,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(p.yellowBg, RoundedCornerShape(12.dp))
            .border(1.dp, p.yellowLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    )
}

@Composable
fun MonoBlock(text: String, maxLines: Int = Int.MAX_VALUE) {
    val p = agentPalette()
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = p.text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp)
            .background(p.surfaceAlt, RoundedCornerShape(8.dp))
            .border(1.dp, p.line, RoundedCornerShape(8.dp))
            .padding(8.dp)
    )
}

/** Bottom-sheet menu row with an optional hint line. */
@Composable
fun AgentMenuItem(label: String, hint: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val p = agentPalette()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 15.sp, color = if (danger) p.red else p.text)
        if (!hint.isNullOrBlank()) Text(hint, fontSize = 12.sp, color = p.muted, maxLines = 2)
    }
}

@Composable
fun AgentRadioOption(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val p = agentPalette()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (selected) p.raised else p.surface, RoundedCornerShape(11.dp))
            .border(1.dp, if (selected) p.text.copy(alpha = 0.7f) else p.line, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(16.dp).border(2.dp, if (selected) p.text else p.faint, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) StatusDot(p.text, 7) }
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text)
            if (!hint.isNullOrBlank()) Text(hint, fontSize = 11.sp, color = p.muted, lineHeight = 15.sp)
        }
    }
}

@Composable
fun rememberProjects(sessions: List<AgentSessionState>): List<Pair<String, String>> = remember(sessions) {
    // (project name, folder) for every known workspace, most recently used first.
    sessions.filter { it.cwd.isNotBlank() }
        .sortedByDescending { isoMillis(it.modified) }
        .distinctBy { it.cwd.trimEnd('\\', '/').lowercase() }
        .map { projectOf(it.cwd) to it.cwd.trimEnd('\\', '/') }
}
