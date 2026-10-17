package com.omni.sync.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.omni.sync.OmniSyncApplication
import com.omni.sync.logic.assistant.AssistantFeedback
import com.omni.sync.logic.assistant.AssistantScheduleStore
import com.omni.sync.logic.assistant.WakeOnLanSender
import com.omni.sync.ui.screen.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AssistantCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_VERIFY_WAKE) {
            val app = context.applicationContext as OmniSyncApplication
            val online = app.mainViewModel.isConnected.value
            val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: "wake-verification"
            val reportToHub = intent.getBooleanExtra(EXTRA_REPORT_TO_HUB, false)
            val message = if (online)
                "Wake-on-LAN was followed by a Hub connection."
            else
                "No Hub connection appeared after Wake-on-LAN."
            AssistantFeedback.notify(
                context,
                if (online) "PC is online" else "PC did not respond",
                message,
                runId
            )
            if (reportToHub) {
                app.signalRClient.reportAssistantRunResult(
                    runId,
                    if (online) "succeeded" else "failed",
                    message
                )
            }
            return
        }

        if (intent.action != ACTION_EXECUTE) return
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        val run = AssistantScheduleStore.take(context, runId) ?: return
        val pendingResult = goAsync()
        val app = context.applicationContext as OmniSyncApplication

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lateness = System.currentTimeMillis() - run.runAtMillis
                if (lateness > AssistantScheduleStore.maxDelayMillis(run.commandId)) {
                    val message =
                        "Skipped ${run.commandId}; it was ${lateness / 60_000} minutes late."
                    AssistantFeedback.notify(
                        context,
                        "Jarvis command missed",
                        message,
                        run.runId
                    )
                    if (run.origin == "hub") {
                        app.signalRClient.reportAssistantRunResult(
                            run.runId,
                            "skipped",
                            message
                        )
                    }
                    return@launch
                }

                when (run.commandId) {
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

                        AssistantFeedback.notify(
                            context,
                            "Wake-on-LAN sent",
                            if (result.anySuccess)
                                "Wake packets were sent; checking whether the PC comes online."
                            else
                                "No Wake-on-LAN packet could be sent.",
                            run.runId
                        )
                        if (result.anySuccess) {
                            scheduleWakeVerification(
                                context,
                                run.runId,
                                reportToHub = run.origin == "hub"
                            )
                            app.signalRClient.startConnection()
                        } else if (run.origin == "hub") {
                            app.signalRClient.reportAssistantRunResult(
                                run.runId,
                                "failed",
                                "No Wake-on-LAN packet could be sent."
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                app.mainViewModel.addLog(
                    "[Assistant] Scheduled ${run.commandId} failed: ${error.message}",
                    LogType.ERROR
                )
                val message = "${run.commandId}: ${error.message}"
                AssistantFeedback.notify(
                    context,
                    "Jarvis command failed",
                    message,
                    run.runId
                )
                if (run.origin == "hub") {
                    app.signalRClient.reportAssistantRunResult(
                        run.runId,
                        "failed",
                        message
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE = "com.omni.sync.ASSISTANT_COMMAND_EXECUTE"
        const val ACTION_VERIFY_WAKE = "com.omni.sync.ASSISTANT_VERIFY_WAKE"
        const val EXTRA_COMMAND_ID = "assistant_command_id"
        const val EXTRA_RUN_ID = "assistant_run_id"
        const val EXTRA_REPORT_TO_HUB = "assistant_report_to_hub"

        fun scheduleWakeVerification(
            context: Context,
            runId: String,
            reportToHub: Boolean = false
        ) {
            val intent = Intent(context, AssistantCommandReceiver::class.java).apply {
                action = ACTION_VERIFY_WAKE
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_REPORT_TO_HUB, reportToHub)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                runId.hashCode() xor 0x5A17,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + 3 * 60 * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }
}
