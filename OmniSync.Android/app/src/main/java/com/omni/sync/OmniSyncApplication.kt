package com.omni.sync

import android.app.Application
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.service.ClipboardWatcher
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.update.AndroidUpdateManager

import timber.log.Timber
import com.omni.sync.utils.CrashReportingTree
import android.content.pm.ApplicationInfo
import android.net.Uri
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.omni.sync.data.repository.HubMediaAuth
import okhttp3.OkHttpClient

class OmniSyncApplication : Application(), ImageLoaderFactory {

    // Coil's singleton loader serves both image/cover browsing and remote screen
    // frames. Authenticate only the configured Hub's media REST endpoints.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val request = chain.request()
                val headers = HubMediaAuth.headersFor(Uri.parse(request.url.toString()), mainViewModel)
                // A redirect to another host must not inherit the Hub secret.
                val builder = request.newBuilder().removeHeader(HubMediaAuth.HEADER)
                headers.forEach { (name, value) -> builder.header(name, value) }
                chain.proceed(builder.build())
            }.build()
        }.build()


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
        mainViewModel.addLog("[Android startup] Application ViewModel initialized", com.omni.sync.ui.screen.LogType.INFO)
        androidUpdateManager = AndroidUpdateManager(applicationContext)
        signalRClient = SignalRClient(
            context = applicationContext,
            mainViewModel = mainViewModel
        )
        mainViewModel.signalRClient = signalRClient
        mainViewModel.addLog("[Android startup] SignalR client initialized and linked", com.omni.sync.ui.screen.LogType.INFO)

        clipboardWatcher = ClipboardWatcher(applicationContext, signalRClient)
        clipboardWatcher.startWatching()

        // Restore alarm states (e.g. isSnoozing) from SharedPreferences
        com.omni.sync.service.AlarmService.init(this)
        com.omni.sync.utils.AlarmScheduler.restoreEnabledAlarms(this)
        com.omni.sync.utils.ReminderDelivery.restore(this)
        com.omni.sync.logic.assistant.AssistantScheduleStore.restore(this)
        mainViewModel.addLog("[Android startup] Alarm, reminder, and assistant schedules restored", com.omni.sync.ui.screen.LogType.INFO)
    }
}
