package com.omni.sync.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import android.util.Log
import com.omni.sync.OmniSyncApplication

class AndroidUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val deploymentRequestId = intent.getStringExtra(EXTRA_DEPLOYMENT_REQUEST_ID)
        val application = context.applicationContext as? OmniSyncApplication
        application?.androidUpdateManager?.handleInstallStatus(status, statusMessage)
        if (deploymentRequestId != null) {
            val deploymentStatus = when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> "confirmation_required"
                PackageInstaller.STATUS_SUCCESS -> "installed"
                else -> "error"
            }
            val deploymentMessage = when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    "Android opened the package-install confirmation prompt."
                PackageInstaller.STATUS_SUCCESS -> "Android update installed successfully."
                else -> statusMessage ?: "Android rejected the update (status $status)."
            }
            Log.i(
                "AndroidDeploy",
                "Installer status for $deploymentRequestId: $deploymentStatus; $deploymentMessage"
            )
            application?.signalRClient?.reportAndroidUpdateStatus(
                deploymentRequestId,
                deploymentStatus,
                deploymentMessage
            )
        }

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmationIntent != null) context.startActivity(confirmationIntent)
            return
        }

        val toastMessage = if (status == PackageInstaller.STATUS_SUCCESS) {
            "OmniSync update installed"
        } else {
            statusMessage ?: "OmniSync update failed"
        }
        Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.omni.sync.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_DEPLOYMENT_REQUEST_ID = "deployment_request_id"
    }
}
