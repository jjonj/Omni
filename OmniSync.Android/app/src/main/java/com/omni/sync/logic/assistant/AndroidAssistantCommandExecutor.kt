package com.omni.sync.logic.assistant

import android.content.Context
import com.omni.sync.ui.screen.LogType
import com.omni.sync.viewmodel.MainViewModel

object AndroidAssistantCommandExecutor {
    fun tryHandle(
        context: Context,
        mainViewModel: MainViewModel,
        transcript: String
    ): Boolean {
        val command = AssistantCommandRouter.resolve(transcript) ?: return false

        return when (command.id) {
            "wake.pc" -> {
                val runAt = command.runAtMillis
                if (runAt != null && runAt > System.currentTimeMillis() + 500L) {
                    val run = AssistantScheduleStore.add(
                        context,
                        command.id,
                        runAt,
                        "android-voice"
                    )
                    mainViewModel.addLog(
                        "[Assistant] Scheduled wake.pc for ${java.util.Date(runAt)} (${run.runId})",
                        LogType.SUCCESS
                    )
                    AssistantFeedback.toast(
                        context,
                        "PC wake scheduled for ${java.util.Date(runAt)}"
                    )
                } else {
                    mainViewModel.addLog(
                        "[Assistant] Executing wake.pc locally on Android",
                        LogType.INFO
                    )
                    mainViewModel.sendWakeOnLan(
                        mainViewModel.appConfig.value.wakeOnLanMac
                    )
                    AssistantFeedback.toast(context, "Sending Wake-on-LAN")
                }
                true
            }
            else -> false
        }
    }
}
