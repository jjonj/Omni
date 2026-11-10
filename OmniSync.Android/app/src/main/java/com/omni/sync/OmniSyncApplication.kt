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
        // One-time requested remote PC wake: 10 Nov 2026, 12:37 Copenhagen time.
        // Schedule locally on first launch after installing this build, even with Hub offline.
        val requestedWakeAt = java.time.ZonedDateTime.of(
            2026, 11, 10, 12, 37, 0, 0, java.time.ZoneId.of("Europe/Copenhagen")
        ).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val wakePrefs = getSharedPreferences("one_time_wake_20261110", MODE_PRIVATE)
        if (now in (requestedWakeAt - 8 * 60 * 60 * 1000L) until requestedWakeAt &&
            !wakePrefs.getBoolean("scheduled", false)) {
            com.omni.sync.logic.assistant.AssistantScheduleStore.add(
                this, "wake.pc", requestedWakeAt, "one-time-request",
                "pc-wake-20261110-1237-copenhagen"
            )
            wakePrefs.edit().putBoolean("scheduled", true).commit()
            com.omni.sync.logic.assistant.AssistantFeedback.notify(
                this, "PC wake armed", "Wake-on-LAN scheduled for today at 12:37 Copenhagen time.",
                "pc-wake-20261110-1237-copenhagen"
            )
        }
        mainViewModel.addLog("[Android startup] Alarm, reminder, and assistant schedules restored", com.omni.sync.ui.screen.LogType.INFO)
    }
}
