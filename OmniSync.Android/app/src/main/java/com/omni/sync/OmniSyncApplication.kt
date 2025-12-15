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
        
        // Connection Configuration
        // IMPORTANT: Update this IP address to match your PC's actual IP
        // Find your PC's IP: Open Command Prompt and type 'ipconfig'
        // Look for "IPv4 Address" under your WiFi adapter
        val pcIpAddress = "10.0.0.37" // Hub is always located at this IP
        val hubPort = "5000" // Ensure PC firewall allows traffic on port 5000
        val hubEndpoint = "signalrhub" // Must match Program.cs on server: app.MapHub<...>(...);
        
        val hubUrl = "http://$pcIpAddress:$hubPort/$hubEndpoint"
        
        Timber.d("=== OMNISYNC CONNECTION CONFIG ===")
        Timber.d("Hub URL: $hubUrl")
        Timber.d("Endpoint breakdown:")
        Timber.d("  - PC IP: $pcIpAddress")
        Timber.d("  - Port: $hubPort")
        Timber.d("  - Endpoint: /$hubEndpoint")
        Timber.d("==================================")
        
        signalRClient = SignalRClient(
            context = applicationContext,
            hubUrl = hubUrl,
            apiKey = "test_api_key",  // Must match the AuthApiKey in Hub's appsettings.json
            mainViewModel = mainViewModel
        )

        clipboardWatcher = ClipboardWatcher(applicationContext, signalRClient)
        clipboardWatcher.startWatching()
    }
}
