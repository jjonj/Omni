package com.omni.sync.logic.macro

sealed class MacroCommand {
    data class Send(val keys: String) : MacroCommand()
    data class Sleep(val durationMs: Long) : MacroCommand()
    data class Run(val path: String) : MacroCommand()
    data class WinActivate(val title: String) : MacroCommand()
    data class WinClose(val title: String) : MacroCommand()
    data class WinMinimize(val title: String) : MacroCommand()
    data class WinMaximize(val title: String) : MacroCommand()
    data class WinHide(val title: String) : MacroCommand()
    data class WaitWinActive(val title: String, val timeoutMs: Int) : MacroCommand()
    data class MouseMoveAbs(val x: Int, val y: Int) : MacroCommand()
    data class MouseClickAt(val button: String, val x: Int, val y: Int) : MacroCommand()
    data class PowerShell(val code: String) : MacroCommand()
    data class MacroChain(val name: String) : MacroCommand()
    object VolUp : MacroCommand()
    object VolDown : MacroCommand()
    object VolMute : MacroCommand()
    object Screenshot : MacroCommand()
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

        val lines = processedScript.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(";") }
        val result = mutableListOf<MacroCommand>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("ps {", ignoreCase = true) || line.startsWith("powershell {", ignoreCase = true)) {
                var code = line.substringAfter("{")
                if (code.endsWith("}")) {
                    result.add(MacroCommand.PowerShell(code.removeSuffix("}").trim()))
                } else {
                    i++
                    while (i < lines.size && !lines[i].contains("}")) {
                        code += "\n" + lines[i]
                        i++
                    }
                    if (i < lines.size) {
                        code += "\n" + lines[i].substringBefore("}")
                    }
                    result.add(MacroCommand.PowerShell(code.trim()))
                }
            } else {
                result.add(parseLine(line))
            }
            i++
        }
        return result
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
            "winclose" -> MacroCommand.WinClose(args)
            "winminimize" -> MacroCommand.WinMinimize(args)
            "winmaximize" -> MacroCommand.WinMaximize(args)
            "winhide" -> MacroCommand.WinHide(args)
            "waitwinactive" -> {
                val subParts = args.split(" ", limit = 2)
                val title = subParts[0].removeSurrounding("\"")
                val timeout = if (subParts.size > 1) subParts[1].toIntOrNull() ?: 5000 else 5000
                MacroCommand.WaitWinActive(title, timeout)
            }
            "mousemoveabs" -> {
                val subParts = args.split(" ", limit = 2)
                val x = subParts[0].toIntOrNull() ?: 0
                val y = if (subParts.size > 1) subParts[1].toIntOrNull() ?: 0 else 0
                MacroCommand.MouseMoveAbs(x, y)
            }
            "mouseclickat" -> {
                val subParts = args.split(" ", limit = 3)
                val btn = subParts[0]
                val x = if (subParts.size > 1) subParts[1].toIntOrNull() ?: 0 else 0
                val y = if (subParts.size > 2) subParts[2].toIntOrNull() ?: 0 else 0
                MacroCommand.MouseClickAt(btn, x, y)
            }
            "powershell", "ps" -> MacroCommand.PowerShell(args.removeSurrounding("{", "}").trim())
            "macro" -> MacroCommand.MacroChain(args.removeSurrounding("\""))
            "volup" -> MacroCommand.VolUp
            "voldown" -> MacroCommand.VolDown
            "volmute" -> MacroCommand.VolMute
            "screenshot" -> MacroCommand.Screenshot
            "keydown" -> MacroCommand.KeyDown(args)
            "keyup" -> MacroCommand.KeyUp(args)
            "clipboard" -> MacroCommand.Clipboard(args)
            "aihere" -> MacroCommand.AiHere(args)
            else -> MacroCommand.Unknown(line)
        }
    }
}
