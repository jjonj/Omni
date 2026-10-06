package com.omni.sync.logic.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.omni.sync.receiver.AssistantCommandReceiver
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
                    schedule(context, command.id, runAt)
                    mainViewModel.addLog(
                        "[Assistant] Scheduled wake.pc for ${java.util.Date(runAt)}",
                        LogType.SUCCESS
                    )
                } else {
                    mainViewModel.addLog(
                        "[Assistant] Executing wake.pc locally on Android",
                        LogType.INFO
                    )
                    mainViewModel.sendWakeOnLan(
                        mainViewModel.appConfig.value.wakeOnLanMac
                    )
                }
                true
            }
            else -> false
        }
    }

    private fun schedule(
        context: Context,
        commandId: String,
        runAtMillis: Long
    ) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AssistantCommandReceiver::class.java).apply {
            action = AssistantCommandReceiver.ACTION_EXECUTE
            data = Uri.parse("omni://assistant/$commandId/$runAtMillis")
            putExtra(AssistantCommandReceiver.EXTRA_COMMAND_ID, commandId)
        }
        val requestCode = (runAtMillis xor (runAtMillis ushr 32)).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    runAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    runAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                runAtMillis,
                pendingIntent
            )
        }
    }
}
