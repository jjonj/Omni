package com.omni.sync.logic.macro

import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.delay

class MacroExecutor(private val signalRClient: SignalRClient) {
    suspend fun execute(commands: List<MacroCommand>) {
                for (command in commands) {
                    when (command) {
                        is MacroCommand.Send -> {
                            signalRClient.sendPayload("SEND_KEYS", mapOf("Keys" to command.keys))
                        }
                        is MacroCommand.Sleep -> {                    if (command.durationMs > 0) {
                        delay(command.durationMs)
                    }
                }
                is MacroCommand.Run -> {
                    signalRClient.executeCommand(command.path)
                }
                is MacroCommand.WinActivate -> {
                    // We might need a specific command for this on the Hub
                    // For now, let's use executeCommand with a powershell snippet or similar
                    signalRClient.executeCommand("powershell -Command \"(New-Object -ComObject WScript.Shell).AppActivate('${command.title}')\"")
                }
                is MacroCommand.KeyDown -> {
                    mapToKeyCode(command.key)?.let { signalRClient.sendKeyEvent("INPUT_KEY_DOWN", it) }
                }
                is MacroCommand.KeyUp -> {
                    mapToKeyCode(command.key)?.let { signalRClient.sendKeyEvent("INPUT_KEY_UP", it) }
                }
                is MacroCommand.Clipboard -> {
                    signalRClient.sendClipboardUpdate(command.text)
                }
                is MacroCommand.AiHere -> {
                    signalRClient.startCliAtWorkspace(command.workspace)
                }
                is MacroCommand.Unknown -> {
                    // Log or handle unknown command
                }
            }
        }
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
