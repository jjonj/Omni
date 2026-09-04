package com.omni.sync

import android.app.Application
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.service.ClipboardWatcher
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.update.AndroidUpdateManager

import timber.log.Timber
import com.omni.sync.utils.CrashReportingTree
import android.content.pm.ApplicationInfo

class OmniSyncApplication : Application() {

    // For simplicity, we're using a basic approach here.
    // In a real app, consider a DI framework like Hilt or Koin.

    lateinit var mainViewModel: MainViewModel
    lateinit var signalRClient: SignalRClient
    lateinit var clipboardWatcher: ClipboardWatcher
    lateinit var androidUpdateManager: AndroidUpdateManager

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging and crash reporting
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(CrashReportingTree(applicationContext))
        mainViewModel = MainViewModel(this) // Pass the Application instance
        androidUpdateManager = AndroidUpdateManager(applicationContext)
        signalRClient = SignalRClient(
            context = applicationContext,
            mainViewModel = mainViewModel
        )
        mainViewModel.signalRClient = signalRClient

        clipboardWatcher = ClipboardWatcher(applicationContext, signalRClient)
        clipboardWatcher.startWatching()

        // Restore alarm states (e.g. isSnoozing) from SharedPreferences
        com.omni.sync.service.AlarmService.init(this)
    }
}
