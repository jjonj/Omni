package com.omni.sync

import android.app.Application
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.service.ClipboardWatcher
import com.omni.sync.viewmodel.MainViewModel

import timber.log.Timber
import com.omni.sync.utils.CrashReportingTree
import android.content.pm.ApplicationInfo

class OmniSyncApplication : Application() {

    // For simplicity, we're using a basic approach here.
    // In a real app, consider a DI framework like Hilt or Koin.

    lateinit var mainViewModel: MainViewModel
    lateinit var signalRClient: SignalRClient
    lateinit var clipboardWatcher: ClipboardWatcher

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging and crash reporting
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(CrashReportingTree(applicationContext))
        mainViewModel = MainViewModel(this) // Pass the Application instance
        // TODO: Replace with actual Hub URL and API Key from a secure source
        signalRClient = SignalRClient(
            context = applicationContext,
            hubUrl = "http://YOUR_PC_IP_ADDRESS:5000/signalrhub",
            apiKey = "YOUR_SECRET_API_KEY_HERE",
            mainViewModel = mainViewModel
        )

        clipboardWatcher = ClipboardWatcher(applicationContext, signalRClient)
        clipboardWatcher.startWatching()
    }
}
