package com.omni.sync.logic.macro

import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.delay
import android.util.Log

class MacroExecutor(
    private val signalRClient: SignalRClient,
    private val allMacros: List<com.omni.sync.data.model.Macro> = emptyList()
) {
    suspend fun execute(
        commands: List<MacroCommand>, 
        context: android.content.Context,
        onProgress: (String) -> Unit = {}
    ) {
        // Decide if we can batch everything to Hub
        // We can't batch if there is a 'macro' chain (recursion/lookup needed) 
        // or 'aihere' (android navigation needed)
        val hasAndroidCmds = commands.any { it is MacroCommand.AiHere || it is MacroCommand.MacroChain }
        
        if (!hasAndroidCmds && commands.isNotEmpty()) {
            onProgress("Executing batch on Hub...")
            signalRClient.executeMacroBatch(commands)
            onProgress("Execution finished")
            return
        }

        Log.d("MacroExecutor", "Executing ${commands.size} commands step-by-step")
        for (command in commands) {
            val label = when(command) {
                is MacroCommand.Send -> "Sending Keys"
                is MacroCommand.Run -> "Running App"
                is MacroCommand.Sleep -> "Waiting..."
                is MacroCommand.WinActivate -> "Focusing Window"
                is MacroCommand.MacroChain -> "Chaining Macro: ${command.name}"
                is MacroCommand.AiHere -> "Launching AI CLI"
                else -> command.javaClass.simpleName
            }
            onProgress(label)
            Log.d("MacroExecutor", "Command: $command")
            when (command) {
                is MacroCommand.Send -> {
                    val processedKeys = command.keys.replace(Regex("\\(([a-zA-Z0-9]+)\\)")) { matchResult ->
                        "{" + matchResult.groupValues[1].uppercase() + "}"
                    }
                    Log.d("MacroExecutor", "Sending keys: $processedKeys")
                    signalRClient.sendPayload("SEND_KEYS", mapOf("Keys" to processedKeys))
                }
                is MacroCommand.Sleep -> {
                    Log.d("MacroExecutor", "Sleeping ${command.durationMs}ms")
                    if (command.durationMs > 0) {
                        delay(command.durationMs)
                    }
                }
                is MacroCommand.Run -> {
                    Log.d("MacroExecutor", "Running: ${command.path}")
                    signalRClient.executeCommand(command.path)
                }
                is MacroCommand.WinActivate -> {
                    Log.d("MacroExecutor", "Activating window: ${command.title}")
                    signalRClient.winActivate(command.title)
                }
                is MacroCommand.WinClose -> {
                    Log.d("MacroExecutor", "Closing window: ${command.title}")
                    signalRClient.winClose(command.title)
                }
                is MacroCommand.WinMinimize -> signalRClient.sendPayload("WIN_MINIMIZE", mapOf("Title" to command.title))
                is MacroCommand.WinMaximize -> signalRClient.sendPayload("WIN_MAXIMIZE", mapOf("Title" to command.title))
                is MacroCommand.WinHide -> signalRClient.sendPayload("WIN_HIDE", mapOf("Title" to command.title))
                is MacroCommand.WaitWinActive -> signalRClient.sendPayload("WAIT_WIN_ACTIVE", mapOf("Title" to command.title, "TimeoutMs" to command.timeoutMs))
                is MacroCommand.MouseMoveAbs -> signalRClient.sendPayload("MOUSE_MOVE_ABS", mapOf("X" to command.x, "Y" to command.y))
                is MacroCommand.MouseClickAt -> signalRClient.sendPayload("MOUSE_CLICK_AT", mapOf("Button" to command.button, "X" to command.x, "Y" to command.y))
                is MacroCommand.PowerShell -> signalRClient.sendPayload("POWERSHELL", mapOf("Code" to command.code))
                is MacroCommand.MacroChain -> {
                    val target = allMacros.find { it.name.equals(command.name, ignoreCase = true) }
                    if (target != null) {
                        val subCommands = com.omni.sync.logic.macro.MacroParser().parse(target.script, context)
                        execute(subCommands, context, onProgress)
                    } else {
                        Log.w("MacroExecutor", "Macro not found for chaining: ${command.name}")
                    }
                }
                is MacroCommand.VolUp -> {
                    Log.d("MacroExecutor", "Volume Up")
                    signalRClient.sendKeyEvent("VOLUME_CONTROL", 0xAFu.toUShort()) // VK_VOLUME_UP
                }
                is MacroCommand.VolDown -> {
                    Log.d("MacroExecutor", "Volume Down")
                    signalRClient.sendKeyEvent("VOLUME_CONTROL", 0xAEu.toUShort()) // VK_VOLUME_DOWN
                }
                is MacroCommand.VolMute -> {
                    Log.d("MacroExecutor", "Volume Mute")
                    signalRClient.sendKeyEvent("VOLUME_CONTROL", 0xADu.toUShort()) // VK_VOLUME_MUTE
                }
                is MacroCommand.Screenshot -> {
                    Log.d("MacroExecutor", "Taking screenshot")
                    signalRClient.sendPayload("TAKE_SCREENSHOT", null)
                }
                is MacroCommand.KeyDown -> {
                    Log.d("MacroExecutor", "Key down: ${command.key}")
                    mapToKeyCode(command.key)?.let { signalRClient.sendKeyEvent("INPUT_KEY_DOWN", it) }
                }
                is MacroCommand.KeyUp -> {
                    Log.d("MacroExecutor", "Key up: ${command.key}")
                    mapToKeyCode(command.key)?.let { signalRClient.sendKeyEvent("INPUT_KEY_UP", it) }
                }
                is MacroCommand.Clipboard -> {
                    Log.d("MacroExecutor", "Set clipboard: ${command.text}")
                    signalRClient.sendClipboardUpdate(command.text)
                }
                is MacroCommand.AiHere -> {
                    Log.d("MacroExecutor", "AI Here: ${command.workspace}")
                    signalRClient.startCliAtWorkspace(command.workspace)
                }
                is MacroCommand.Unknown -> {
                    Log.w("MacroExecutor", "Unknown command: ${command.raw}")
                }
            }
        }
        onProgress("Execution finished")
        Log.d("MacroExecutor", "Execution finished")
    }

    private fun mapToKeyCode(key: String): UShort? {
        return when (key.lowercase()) {
            "ctrl", "control" -> com.omni.sync.utils.WindowsKeyCodes.VK_CONTROL
            "alt", "menu" -> com.omni.sync.utils.WindowsKeyCodes.VK_MENU
            "shift" -> com.omni.sync.utils.WindowsKeyCodes.VK_SHIFT
            "win", "lwin" -> 0x5Bu.toUShort()
            "enter", "return" -> com.omni.sync.utils.WindowsKeyCodes.VK_RETURN
            "tab" -> com.omni.sync.utils.WindowsKeyCodes.VK_TAB
            "esc", "escape" -> com.omni.sync.utils.WindowsKeyCodes.VK_ESCAPE
            "space" -> com.omni.sync.utils.WindowsKeyCodes.VK_SPACE
            "backspace" -> com.omni.sync.utils.WindowsKeyCodes.VK_BACK
            "delete" -> com.omni.sync.utils.WindowsKeyCodes.VK_DELETE
            "up" -> com.omni.sync.utils.WindowsKeyCodes.VK_UP
            "down" -> com.omni.sync.utils.WindowsKeyCodes.VK_DOWN
            "left" -> com.omni.sync.utils.WindowsKeyCodes.VK_LEFT
            "right" -> com.omni.sync.utils.WindowsKeyCodes.VK_RIGHT
            else -> {
                if (key.length == 1) {
                    key.uppercase()[0].code.toUShort()
                } else null
            }
        }
    }
}
