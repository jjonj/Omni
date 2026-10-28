package com.omni.sync.data.taskboard

import java.time.Instant

/** TaskBoard desktop's portable Description timeline wire format.
 * Malformed history is intentionally treated as original text, never discarded.
 */
data class TaskBoardDescriptionUpdate(val kind: String, val timestamp: String, val text: String)
data class TaskBoardDescription(val initial: String, val updates: List<TaskBoardDescriptionUpdate>)

object TaskBoardTimeline {
    private val start = Regex("^<!-- taskboard:update:(update|user-note|legacy-ai)\\|([^\\n]+) -->$")
    private const val end = "<!-- taskboard:end-update -->"

    fun parse(description: String): TaskBoardDescription {
        val normalized = description.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        val first = lines.indexOfFirst { start.matches(it) }
        if (first < 0) return TaskBoardDescription(normalized, emptyList())

        val prefix = lines.take(first).joinToString("\n")
        val initial = if (prefix.endsWith("\n")) prefix.dropLast(1) else prefix
        val updates = mutableListOf<TaskBoardDescriptionUpdate>()
        var i = first
        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) i++
            if (i == lines.size) break
            val match = start.matchEntire(lines[i])
                ?: return TaskBoardDescription(normalized, emptyList())
            try { Instant.parse(match.groupValues[2]) }
            catch (_: Exception) { return TaskBoardDescription(normalized, emptyList()) }
            i++
            val quoted = mutableListOf<String>()
            while (i < lines.size && lines[i] != end) {
                if (lines[i] != ">" && !lines[i].startsWith("> ")) {
                    return TaskBoardDescription(normalized, emptyList())
                }
                quoted += if (lines[i] == ">") "" else lines[i].removePrefix("> ")
                i++
            }
            if (i >= lines.size) return TaskBoardDescription(normalized, emptyList())
            i++
            updates += TaskBoardDescriptionUpdate(
                match.groupValues[1],
                match.groupValues[2],
                quoted.joinToString("\n")
            )
        }
        return TaskBoardDescription(initial, updates)
    }

    fun format(initial: String, updates: List<TaskBoardDescriptionUpdate>): String {
        val blocks = updates.map { update ->
            val quoted = update.text.replace("\r\n", "\n").trim()
                .split("\n").joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
            "<!-- taskboard:update:${update.kind}|${update.timestamp} -->\n$quoted\n$end"
        }
        return (listOf(initial).filter { it.isNotEmpty() } + blocks).joinToString("\n\n")
    }

    fun append(description: String, additions: List<Pair<String, String>>): String {
        val parsed = parse(description)
        val now = Instant.now().toString()
        val updates = parsed.updates + additions
            .filter { it.second.isNotBlank() }
            .map { (kind, text) -> TaskBoardDescriptionUpdate(kind, now, text.trim()) }
        return format(parsed.initial, updates)
    }
}
