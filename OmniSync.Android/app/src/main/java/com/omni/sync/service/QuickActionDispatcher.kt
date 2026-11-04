package com.omni.sync.service

import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.omni.sync.MainActivity
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.model.NotificationAction
import kotlinx.coroutines.launch

object QuickActionDispatcher {
    fun execute(context: Context, action: NotificationAction) {
        val app = context.applicationContext as OmniSyncApplication
        val signalRClient = app.signalRClient
        val mainViewModel = app.mainViewModel

        if (action.isTellPc) {
            mainViewModel.addLog("Notification: Triggering Tell PC...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.triggerTellPc()
            context.startActivity(mainActivityIntent(context))
        } else if (action.command.startsWith("VOICE:")) {
            val mode = if (action.command.substring(6) == "JARVIS")
                com.omni.sync.data.repository.SignalRClient.VoiceMode.JARVIS
            else com.omni.sync.data.repository.SignalRClient.VoiceMode.TYPE
            mainViewModel.addLog("Quick action: voice ${mode.name}...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.requestVoiceCapture(mode)
            context.startActivity(mainActivityIntent(context))
        } else if (action.isWol && action.macAddress != null) {
            mainViewModel.sendWakeOnLan(action.macAddress)
        } else if (action.command.startsWith("NAV:")) {
            context.startActivity(mainActivityIntent(context).putExtra("OPEN_SCREEN", action.command.substring(4)))
        } else if (action.command.startsWith("NAV_FILE:")) {
            context.startActivity(mainActivityIntent(context)
                .putExtra("OPEN_SCREEN", "FILES")
                .putExtra("FILE_PATH", action.command.substring(9)))
        } else if (action.command == "STILL_AWAKE") {
            com.omni.sync.utils.WakeAlarmActions.stillAwake(context)
        } else if (action.command == "CLIPBOARD_PHONE_TO_PC_PASTE") {
            // A visible shortcut Activity is needed to read Android 10+ clipboard reliably.
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) {
                signalRClient.sendText(text)
            } else {
                Toast.makeText(context, "Phone clipboard is empty or unavailable", Toast.LENGTH_SHORT).show()
            }
        } else if (action.command == "CLIPBOARD_PC_TO_PHONE_PASTE") {
            signalRClient.requestClipboard(pasteIntoFocusedApp = true)
        } else if (action.command == "PC_KEYBOARD") {
            context.startActivity(Intent(context, com.omni.sync.ui.PcKeyboardShortcutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (action.command.startsWith("ALARM:")) {
            val minutes = action.command.substring(6).toIntOrNull() ?: 0
            if (minutes > 0) {
                val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                val gson = com.google.gson.Gson()
                val configJson = prefs.getString("config", null)
                val config = if (configJson != null) gson.fromJson(configJson, com.omni.sync.ui.screen.GradualConfig::class.java) else com.omni.sync.ui.screen.GradualConfig()
                val calendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, minutes) }
                val alarmData = com.omni.sync.ui.screen.AlarmData(
                    enabled = true,
                    hour = calendar.get(java.util.Calendar.HOUR_OF_DAY).let { if (it == 0 || it == 12) 12 else it % 12 },
                    minute = calendar.get(java.util.Calendar.MINUTE),
                    isAM = calendar.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM,
                    repeatDaily = false
                )
                val alarms = com.omni.sync.utils.AlarmStore.load(prefs, gson)
                val targetId = alarms.firstOrNull()?.id ?: 1
                val updatedAlarm = alarmData.copy(id = targetId)
                val updatedAlarms = if (alarms.isEmpty()) {
                    listOf(updatedAlarm)
                } else {
                    alarms.mapIndexed { index, existing -> if (index == 0) updatedAlarm else existing }
                }
                com.omni.sync.utils.AlarmScheduler.scheduleAlarm(context, targetId, updatedAlarm, config)
                com.omni.sync.utils.AlarmStore.save(prefs, gson, updatedAlarms)
                mainViewModel.addLog("Quick Alarm set for $minutes minutes from now", com.omni.sync.ui.screen.LogType.SUCCESS)
            }
        } else if (action.command.startsWith("BOOKMARK:")) {
            val url = action.command.substring(9)
            mainViewModel.addLog("Notification: Opening bookmark on PC...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.sendBrowserCommand("Navigate", url, true)
        } else if (action.command.startsWith("BROWSER:")) {
            val command = action.command.substring(8)
            mainViewModel.addLog("Notification: Browser $command...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.sendBrowserCommand(command, "", false)
        } else if (action.command.startsWith("MACRO:")) {
            val script = action.command.substring(6)
            mainViewModel.addLog("Notification: Running macro...", com.omni.sync.ui.screen.LogType.INFO)
            val parser = com.omni.sync.logic.macro.MacroParser()
            val executor = com.omni.sync.logic.macro.MacroExecutor(signalRClient, mainViewModel.appConfig.value.macros)
            mainViewModel.viewModelScope.launch {
                executor.execute(parser.parse(script, context.applicationContext), context.applicationContext)
            }
        } else {
            mainViewModel.addLog("Notification: Triggering ${action.label}...", com.omni.sync.ui.screen.LogType.INFO)
            signalRClient.executeCommand(action.command)
        }
    }

    private fun mainActivityIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}
