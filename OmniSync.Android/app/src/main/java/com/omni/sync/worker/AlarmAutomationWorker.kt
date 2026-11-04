package com.omni.sync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omni.sync.OmniSyncApplication
import com.omni.sync.data.config.ConfigManager
import com.omni.sync.logic.macro.MacroExecutor
import com.omni.sync.logic.macro.MacroParser
import com.omni.sync.service.QuickActionDispatcher
import java.util.concurrent.TimeUnit

class AlarmAutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val macroScript = inputData.getString(KEY_MACRO_SCRIPT)?.takeIf { it.isNotBlank() }
        val quickActionId = inputData.getString(KEY_QUICK_ACTION_ID)?.takeIf { it.isNotBlank() }

        return try {
            when {
                macroScript != null -> executeMacro(macroScript)
                quickActionId != null -> executeQuickAction(quickActionId)
                else -> Log.w(TAG, "Delayed alarm automation has no selected action.")
            }
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Delayed alarm automation failed.", error)
            Result.retry()
        }
    }

    private suspend fun executeMacro(script: String) {
        val app = applicationContext as OmniSyncApplication
        if (!com.omni.sync.utils.AlarmMacroPolicy.isCurrent(applicationContext, app.signalRClient, script)) return
        val executor = MacroExecutor(app.signalRClient, app.mainViewModel.appConfig.value.macros)
        executor.execute(MacroParser().parse(script, applicationContext), applicationContext)
    }

    private fun executeQuickAction(actionId: String) {
        val configManager = ConfigManager(applicationContext)
        val action = configManager.loadConfig().notificationActions
            .ifEmpty { configManager.getDefaultNotificationActions() }
            .find { it.id == actionId }

        if (action == null) {
            Log.w(TAG, "Delayed alarm quick action no longer exists: $actionId")
            return
        }
        QuickActionDispatcher.execute(applicationContext, action)
    }

    companion object {
        private const val TAG = "AlarmAutomationWorker"
        private const val KEY_MACRO_SCRIPT = "macro_script"
        private const val KEY_QUICK_ACTION_ID = "quick_action_id"

        fun enqueue(
            context: Context,
            macroScript: String?,
            quickActionId: String?,
            delaySeconds: Int
        ) {
            val input = Data.Builder().apply {
                macroScript?.let { putString(KEY_MACRO_SCRIPT, it) }
                quickActionId?.let { putString(KEY_QUICK_ACTION_ID, it) }
            }.build()
            val request = OneTimeWorkRequestBuilder<AlarmAutomationWorker>()
                .setInputData(input)
                .setInitialDelay(delaySeconds.coerceIn(0, 86_400).toLong(), TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
