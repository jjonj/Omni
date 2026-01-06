package com.omni.sync.logic.macro

sealed class MacroCommand {
    data class Send(val keys: String) : MacroCommand()
    data class Sleep(val durationMs: Long) : MacroCommand()
    data class Run(val path: String) : MacroCommand()
    data class WinActivate(val title: String) : MacroCommand()
    data class KeyDown(val key: String) : MacroCommand()
    data class KeyUp(val key: String) : MacroCommand()
    data class Clipboard(val text: String) : MacroCommand()
    data class AiHere(val workspace: String) : MacroCommand()
    data class Unknown(val raw: String) : MacroCommand()
}

class MacroParser {
    fun parse(script: String, context: android.content.Context? = null): List<MacroCommand> {
        var processedScript = script
        
        // Feature 1: {CLIPBOARD} Placeholder
        if (context != null && script.contains("{CLIPBOARD}")) {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            processedScript = script.replace("{CLIPBOARD}", clipText)
        }

        return processedScript.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(";") } // Ignore comments
            .map { parseLine(it) }
    }

    fun isValid(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(";")) return true
        return parseLine(trimmed) !is MacroCommand.Unknown
    }

    private fun parseLine(line: String): MacroCommand {
        val parts = line.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = if (parts.size > 1) parts[1] else ""

        return when (command) {
            "send" -> MacroCommand.Send(args)
            "sleep" -> MacroCommand.Sleep(args.toLongOrNull() ?: 0L)
            "run" -> MacroCommand.Run(args)
            "winactivate" -> MacroCommand.WinActivate(args)
            "keydown" -> MacroCommand.KeyDown(args)
            "keyup" -> MacroCommand.KeyUp(args)
            "clipboard" -> MacroCommand.Clipboard(args)
            "aihere" -> MacroCommand.AiHere(args)
            else -> MacroCommand.Unknown(line)
        }
    }
}
