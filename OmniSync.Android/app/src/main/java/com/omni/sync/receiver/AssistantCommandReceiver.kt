package com.omni.sync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.sync.OmniSyncApplication
import com.omni.sync.logic.assistant.WakeOnLanSender
import com.omni.sync.ui.screen.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AssistantCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE) return

        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: return
        val pendingResult = goAsync()
        val app = context.applicationContext as OmniSyncApplication

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (commandId) {
                    "wake.pc" -> {
                        val config = app.mainViewModel.appConfig.value
                        app.mainViewModel.addLog(
                            "[Assistant] Executing scheduled wake.pc",
                            LogType.INFO
                        )
                        val result = WakeOnLanSender.send(
                            macAddress = config.wakeOnLanMac,
                            broadcastIp = config.subnetBroadcastIp,
                            wanIp = config.wanIp
                        )
                        result.messages.forEach { message ->
                            app.mainViewModel.addLog(
                                "[Assistant] $message",
                                if (message.contains("failed", ignoreCase = true))
                                    LogType.WARNING
                                else
                                    LogType.SUCCESS
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                app.mainViewModel.addLog(
                    "[Assistant] Scheduled $commandId failed: ${error.message}",
                    LogType.ERROR
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE = "com.omni.sync.ASSISTANT_COMMAND_EXECUTE"
        const val EXTRA_COMMAND_ID = "assistant_command_id"
    }
}
