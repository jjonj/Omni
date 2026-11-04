package com.omni.sync

import android.content.Intent
import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.omni.sync.service.ForegroundService
import com.omni.sync.ui.screen.DashboardScreen
import com.omni.sync.ui.screen.BrowserControlScreen
import com.omni.sync.ui.screen.ProcessScreen
import com.omni.sync.ui.screen.RemoteControlScreen
import com.omni.sync.ui.screen.BooksScreen
import com.omni.sync.ui.screen.EpubViewerScreen
import com.omni.sync.ui.screen.PdfViewerScreen
import com.omni.sync.ui.theme.OmniSyncTheme
import com.omni.sync.viewmodel.AppScreen
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.ui.screen.FilesScreen
import com.omni.sync.viewmodel.FilesViewModel
import com.omni.sync.viewmodel.BrowserViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.sync.viewmodel.FilesViewModelFactory
import com.omni.sync.viewmodel.BrowserViewModelFactory
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.unit.dp
import com.omni.sync.ui.components.OmniBottomNavigation
import com.omni.sync.ui.components.CustomKeyboard

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.omni.sync.service.AlarmService
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class MainActivity : ComponentActivity() {
    companion object {
        // Accessibility may intercept PC volume keys only while Omni is foregrounded.
        @Volatile var isForeground: Boolean = false
            private set
    }

    private lateinit var mainViewModel: MainViewModel
    private lateinit var omniSyncApplication: OmniSyncApplication
    
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) {
                // Find the BookDownloadManager and notify it
                // This is a bit manual since we don't have direct access here easily
                // but we can trigger a refresh via the ViewModel if we expose it
            }
        }
    }

    private val swipeableScreens = listOf(
        AppScreen.DASHBOARD,
        AppScreen.REMOTECONTROL,
        AppScreen.BROWSER,
        AppScreen.FILES,
        AppScreen.AGENTS,
        AppScreen.ALARM,
        AppScreen.WEB_SERVER,
        AppScreen.PROCESS,
        AppScreen.BOOKS
    )

    fun startOverlayService() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        Intent(this, com.omni.sync.service.OverlayService::class.java).also { intent ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    fun stopOverlayService() {
        Intent(this, com.omni.sync.service.OverlayService::class.java).also { intent ->
            stopService(intent)
        }
    }

    fun requestOverlayPermission() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 123)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 123) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                startOverlayService()
            }
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        omniSyncApplication = application as OmniSyncApplication
        mainViewModel = omniSyncApplication.mainViewModel
        restoreRecentsVisibility()
        mainViewModel.addLog("[Android startup] Activity dependencies initialized; handling launch intent", com.omni.sync.ui.screen.LogType.INFO)

        handleIntent(intent)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permissions = mutableListOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_VIDEO)
            val toRequest = permissions.filter { 
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
            }
            if (toRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 102)
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        updateSystemBars(resources.configuration.orientation)

        mainViewModel.addLog("[Android startup] Permissions and window configured; starting connection", com.omni.sync.ui.screen.LogType.INFO)
        omniSyncApplication.signalRClient.startConnection()

        if (mainViewModel.appConfig.value.overlayEnabled && android.provider.Settings.canDrawOverlays(this)) {
            startOverlayService()
        }

        Intent(this, ForegroundService::class.java).also { intent ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContent {
                            val isConnected by mainViewModel.isConnected.collectAsState()
                            OmniSyncTheme(isConnected = isConnected) {
                                val currentScreen by mainViewModel.currentScreen.collectAsState()
                                 val appConfig by mainViewModel.appConfig.collectAsState()
                                val canGoBack by mainViewModel.canGoBack.collectAsState()
            
                                // Handle Orientation
                                LaunchedEffect(currentScreen) {
                                    requestedOrientation = when (currentScreen) {
                                        AppScreen.DASHBOARD -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        AppScreen.SETTINGS -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        // Add more exclusions here in the future
                                        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                                    }
                                }
            
                                val signalRClient = omniSyncApplication.signalRClient                
                val context = LocalContext.current
                val isKeyboardVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

                LaunchedEffect(Unit) {
                    mainViewModel.toastMessage.collect { message ->
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                // Observe Alarm State
                val isAlarmRinging by AlarmService.isRinging.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // BackHandler is only enabled when keyboard is NOT visible.
                    // This allows the system to handle keyboard dismissal naturally when it's open.
                    BackHandler(enabled = !isKeyboardVisible) {
                        mainViewModel.handleBackPress { finish() }
                    }
                    
                    val filesViewModel: FilesViewModel = viewModel(
                        factory = FilesViewModelFactory(application, signalRClient, mainViewModel)
                    )
                            val browserViewModel: BrowserViewModel = viewModel(
                                factory = BrowserViewModelFactory(application, signalRClient, mainViewModel)
                            )
                    val booksViewModel: com.omni.sync.viewmodel.BooksViewModel = viewModel(
                        factory = com.omni.sync.viewmodel.BooksViewModelFactory(mainViewModel)
                    )
                    val pagerState = rememberPagerState(pageCount = { swipeableScreens.size })

                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                    // Centralized Voice Recognition Launcher
                    var voiceTargetPid by remember { mutableStateOf<Int?>(null) }
                    val speechRecognizerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                            if (spokenText != null) {
                                mainViewModel.addLog("[Voice] Sending to Hub: $spokenText", com.omni.sync.ui.screen.LogType.INFO)
                                signalRClient.sendAiMessage(spokenText, voiceTargetPid)
                            }
                        }
                        voiceTargetPid = null
                    }

                    fun startGlobalVoiceRecognition(pid: Int? = null) {
                        voiceTargetPid = pid
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, if (pid == -1) "Tell PC..." else "Speak...")
                        }
                        try {
                            speechRecognizerLauncher.launch(intent)
                        } catch (e: Exception) {
                            mainViewModel.addLog("Voice recognition not supported", com.omni.sync.ui.screen.LogType.ERROR)
                        }
                    }

                    var voiceCaptureMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<com.omni.sync.data.repository.SignalRClient.VoiceMode?>(null) }
                    val voiceCaptureLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        val mode = voiceCaptureMode
                        voiceCaptureMode = null
                        signalRClient.completeVoiceCapture()
                        if (mode != null && result.resultCode == Activity.RESULT_OK) {
                            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                            if (!spokenText.isNullOrBlank()) {
                                mainViewModel.addLog("[Voice] ${mode.name}: $spokenText", com.omni.sync.ui.screen.LogType.INFO)
                                signalRClient.sendVoiceTranscript(mode, spokenText)
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        fun launchVoiceCapture(mode: com.omni.sync.data.repository.SignalRClient.VoiceMode) {
                            if (voiceCaptureMode != null) return
                            voiceCaptureMode = mode
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, if (mode == com.omni.sync.data.repository.SignalRClient.VoiceMode.JARVIS) "Jarvis..." else "Type...")
                            }
                            try {
                                voiceCaptureLauncher.launch(intent)
                            } catch (e: Exception) {
                                voiceCaptureMode = null
                                signalRClient.completeVoiceCapture()
                                mainViewModel.addLog("Voice recognition not supported: ${e.message}", com.omni.sync.ui.screen.LogType.ERROR)
                            }
                        }
                        signalRClient.consumePendingVoiceCapture()?.let { launchVoiceCapture(it) }
                        signalRClient.voiceCaptureRequests.collect { launchVoiceCapture(it) }
                    }

                    LaunchedEffect(Unit) {
                        signalRClient.isTriggeringTellPc.collect {
                            startGlobalVoiceRecognition(-1) // -1 signifies newest/TellPC session
                        }
                    }

                    LaunchedEffect(currentScreen) {
                        val index = swipeableScreens.indexOf(currentScreen)
                        if (index != -1 && pagerState.currentPage != index) {
                            pagerState.scrollToPage(index)
                        }
                    }

                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }.collect { page ->
                            val screen = swipeableScreens[page]
                            val currentScreen = mainViewModel.currentScreen.value
                            
                            // Only allow pager to trigger navigation if we are currently on a swipeable screen
                            if (currentScreen !in swipeableScreens) return@collect
                            
                            if (currentScreen != screen) {
                                if (screen == AppScreen.FILES) {
                                    // Restore the last active sub-screen (FILES or EDITOR)
                                    mainViewModel.navigateTo(mainViewModel.lastFilesScreen.value)
                                } else {
                                    mainViewModel.navigateTo(screen)
                                }
                            }
                        }
                    }

                    androidx.compose.material3.Scaffold(
                        modifier = if (!isLandscape) Modifier.systemBarsPadding() else Modifier,
                        bottomBar = {
                            if (!isLandscape) {
                                val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                OmniBottomNavigation(
                                    currentScreen = currentScreen,
                                    swipeNavigationEnabled = appConfig.swipeNavigationEnabled,
                                    onNavigate = { screen -> 
                                        if (screen == AppScreen.FILES) {
                                            if (currentScreen == AppScreen.FILES || currentScreen == AppScreen.EDITOR) {
                                                // Toggle if already in Files context
                                                if (filesViewModel.editingFile.value != null) {
                                                    if (currentScreen == AppScreen.EDITOR) {
                                                        mainViewModel.navigateTo(AppScreen.FILES)
                                                    } else {
                                                        mainViewModel.navigateTo(AppScreen.EDITOR)
                                                    }
                                                }
                                            } else {
                                                // Switching back from another screen: restore last active
                                                mainViewModel.navigateTo(mainViewModel.lastFilesScreen.value)
                                            }
                                        } else {
                                            mainViewModel.navigateTo(screen)
                                        }
                                    },
                                    onSwipe = { delta ->
                                        coroutineScope.launch {
                                            val next = (pagerState.currentPage + delta).coerceIn(0, swipeableScreens.size - 1)
                                            pagerState.animateScrollToPage(next)
                                        }
                                    }
                                )
                            }
                        }
                    ) { innerPadding ->
                        val isSwipeable = currentScreen in swipeableScreens
                        val wordWrap by filesViewModel.wordWrap.collectAsState()
                        val canSwipe = appConfig.swipeNavigationEnabled && when (currentScreen) {
                            AppScreen.EDITOR -> wordWrap
                            else -> isSwipeable
                        }
                        
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isLandscape && currentScreen != AppScreen.IMAGE_VIEWER && currentScreen != AppScreen.VIDEOPLAYER && currentScreen != AppScreen.PDF_VIEWER && currentScreen != AppScreen.EPUB_VIEWER) {
                                CustomKeyboard(
                                    signalRClient = signalRClient,
                                    appConfig = mainViewModel.appConfig.value,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                // Always keep pager in composition to avoid state resets and jumpy animations
                                // Custom touch slop to make paging less sensitive
                                val viewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
                                val customViewConfig = remember {
                                    object : androidx.compose.ui.platform.ViewConfiguration by viewConfig {
                                        override val touchSlop: Float get() = viewConfig.touchSlop * 6.0f
                                    }
                                }
                                
                            androidx.compose.runtime.CompositionLocalProvider(
                                    androidx.compose.ui.platform.LocalViewConfiguration provides customViewConfig
                                ) {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                        userScrollEnabled = canSwipe // Only allow swiping on swipeable screens and editor with word wrap
                                    ) { page ->
                                        val screenAtPage = swipeableScreens[page]
                                        val pageModifier = if (screenAtPage == AppScreen.REMOTECONTROL || screenAtPage == AppScreen.FILES || screenAtPage == AppScreen.AGENTS || screenAtPage == AppScreen.WEB_SERVER) Modifier else Modifier.padding(innerPadding)
                                        Box(modifier = pageModifier) {
                                            MainScreenContent(screenAtPage, signalRClient, browserViewModel, filesViewModel, mainViewModel, booksViewModel, innerPadding)
                                        }
                                    }
                                }

                                // Overlay non-swipeable screens on top when active
                                if (!isSwipeable) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        MainScreenContent(currentScreen, signalRClient, browserViewModel, filesViewModel, mainViewModel, booksViewModel, innerPadding)
                                    }
                                }
                            }
                        }
                    }
                }

                // Global Alarm Dismiss Overlay
                // ... (rest of code)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Auto-show the alarm at most once every two hours, including across
        // activity recreation. A user-selected destination always takes priority.
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val navPrefs = getSharedPreferences("alarm_navigation_prefs", Context.MODE_PRIVATE)
        val lastAutoOpen = navPrefs.getLong("last_auto_open", 0L)
        if (hour < 9 && intent?.getStringExtra("OPEN_SCREEN") == null &&
            (lastAutoOpen <= 0L || now < lastAutoOpen || now - lastAutoOpen >= 2 * 60 * 60 * 1000L)
        ) {
            navPrefs.edit().putLong("last_auto_open", now).apply()
            mainViewModel.navigateTo(AppScreen.ALARM)
            mainViewModel.addLog("[Android startup] Alarm auto-opened (two-hour cooldown)", com.omni.sync.ui.screen.LogType.INFO)
        }
        // Retry connection if disconnected when app comes to foreground
        val state = omniSyncApplication.signalRClient.connectionState.value
        val isConnecting = state.contains("Connecting", ignoreCase = true) || state.contains("Reconnecting", ignoreCase = true)
        if (!mainViewModel.isConnected.value && !isConnecting) {
            omniSyncApplication.signalRClient.startConnection()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        restoreRecentsVisibility()
        isForeground = true
        mainViewModel.addLog("[Android startup] Activity resumed; checking pending deployment", com.omni.sync.ui.screen.LogType.INFO)
        (application as? OmniSyncApplication)
            ?.androidUpdateManager
            ?.continuePendingDeploymentIfPermitted()
    }

    override fun onPause() {
        isForeground = false
        super.onPause()
    }

    // A task launched by an external intent can retain an excluded-from-recents bit.
    // Clear it on the existing task so returning Home or opening Recents preserves Omni.
    private fun restoreRecentsVisibility() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.firstOrNull { it.taskInfo.taskId == taskId }
                ?.setExcludeFromRecents(false)
        } catch (error: RuntimeException) {
            android.util.Log.w("OmniRecents", "Unable to restore task visibility", error)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBars(newConfig.orientation)
    }

    private fun updateSystemBars(orientation: Int) {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isLandscape) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun handleIntent(intent: Intent?) {
        val screenName = intent?.getStringExtra("OPEN_SCREEN")
        mainViewModel.addLog("[Android startup] Launch intent screen=${screenName ?: "default"}", com.omni.sync.ui.screen.LogType.INFO)
        if (screenName != null) {
            try {
                val screen = AppScreen.valueOf(screenName)
                mainViewModel.navigateTo(screen)
                
                if (screen == AppScreen.AGENT_DETAIL) {
                    intent.getStringExtra("AGENT_SESSION_ID")?.let { mainViewModel.openAgentSession(it) }
                }
                if (screen == AppScreen.FILES) {
                    val filePath = intent.getStringExtra("FILE_PATH")
                    if (filePath != null) {
                        // We need access to filesViewModel here or pass it through mainViewModel
                        // For now let's use a shared state in MainViewModel
                        mainViewModel.setPendingNavigationPath(filePath)
                    }
                }
            } catch (e: Exception) {
                // Ignore invalid screen names
            }
        }
    }

    @Composable
    private fun MainScreenContent(
        currentScreen: AppScreen,
        signalRClient: com.omni.sync.data.repository.SignalRClient,
        browserViewModel: BrowserViewModel,
        filesViewModel: FilesViewModel,
        mainViewModel: MainViewModel,
        booksViewModel: com.omni.sync.viewmodel.BooksViewModel,
        paddingValues: PaddingValues = PaddingValues(0.dp)
    ) {
        when (currentScreen) {
            AppScreen.DASHBOARD -> DashboardScreen(
                signalRClient = signalRClient, 
                mainViewModel = mainViewModel
            )
            AppScreen.REMOTECONTROL -> RemoteControlScreen(
                signalRClient = signalRClient, 
                mainViewModel = mainViewModel,
                paddingValues = paddingValues
            )
            AppScreen.BROWSER -> BrowserControlScreen(
                signalRClient = signalRClient,
                viewModel = browserViewModel
            )
            AppScreen.PROCESS -> ProcessScreen(
                signalRClient = signalRClient, 
                mainViewModel = mainViewModel
            )
            AppScreen.FILES -> FilesScreen(
                filesViewModel = filesViewModel,
                parentPadding = paddingValues
            )
            AppScreen.EDITOR -> com.omni.sync.ui.screen.TextEditorScreen(
                filesViewModel = filesViewModel,
                signalRClient = signalRClient,
                mainViewModel = mainViewModel,
                onBack = { mainViewModel.navigateTo(AppScreen.FILES) },
                parentPadding = paddingValues
            )
            AppScreen.SETTINGS -> com.omni.sync.ui.screen.SettingsScreen(
                mainViewModel = mainViewModel,
                signalRClient = signalRClient,
                filesViewModel = filesViewModel
            )
            AppScreen.AGENTS -> com.omni.sync.ui.screen.OmniAiModesScreen(
                signalRClient = signalRClient,
                mainViewModel = mainViewModel,
                parentPadding = paddingValues
            )
            AppScreen.AGENT_DETAIL -> com.omni.sync.ui.screen.OmniAiModesScreen(
                signalRClient = signalRClient,
                mainViewModel = mainViewModel,
                parentPadding = paddingValues,
                initialSessionId = mainViewModel.selectedAgentSessionId.collectAsState().value
            )
            AppScreen.AGENT_TERMINAL -> com.omni.sync.ui.screen.AgentTerminalScreen(
                signalRClient = signalRClient,
                onBack = { mainViewModel.navigateTo(AppScreen.AGENTS) },
                parentPadding = paddingValues
            )
            AppScreen.DOWNLOADED_VIDEOS -> com.omni.sync.ui.screen.DownloadedVideosScreen(
                filesViewModel = filesViewModel,
                onBack = { mainViewModel.goBack() }
            )
            AppScreen.ALARM -> com.omni.sync.ui.screen.AlarmScreen(
                mainViewModel = mainViewModel,
                signalRClient = signalRClient,
                onBack = { mainViewModel.goBack() }
            )
            AppScreen.MACRO_MANAGER -> com.omni.sync.ui.screen.MacroManagerScreen(
                mainViewModel = mainViewModel,
                onBack = { mainViewModel.goBack() }
            )
            AppScreen.WEB_SERVER -> com.omni.sync.ui.screen.WebServerScreen(
                signalRClient = signalRClient,
                mainViewModel = mainViewModel
            )
            AppScreen.VIDEOPLAYER -> {
                val videoUrl by mainViewModel.currentVideoUrl.collectAsState()
                val playlist by mainViewModel.videoPlaylist.collectAsState()
                val initialIndex by mainViewModel.currentVideoIndex.collectAsState()
                if (videoUrl != null) {
                    com.omni.sync.ui.screen.VideoPlayerScreen(
                        videoUrl = videoUrl!!,
                        playlist = playlist,
                        initialIndex = initialIndex,
                        onBack = { mainViewModel.goBack() },
                        parentPadding = paddingValues
                    )
                }
            }
            AppScreen.IMAGE_VIEWER -> {
                val imageUrl by mainViewModel.currentImageUrl.collectAsState()
                val playlist by mainViewModel.imagePlaylist.collectAsState()
                val initialIndex by mainViewModel.currentImageIndex.collectAsState()
                if (imageUrl != null) {
                    com.omni.sync.ui.screen.ImageViewerScreen(
                        initialImageUrl = imageUrl!!,
                        playlist = playlist,
                        initialIndex = initialIndex,
                        onBack = { mainViewModel.goBack() },
                        parentPadding = paddingValues
                    )
                }
            }
            AppScreen.TESTS -> com.omni.sync.ui.screen.TestsScreen()
            AppScreen.TASKBOARD -> com.omni.sync.ui.screen.TaskBoardScreen(
                signalR = signalRClient,
                parentPadding = paddingValues
            )
            AppScreen.BOOKS -> BooksScreen(
                mainViewModel = mainViewModel,
                booksViewModel = booksViewModel,
                onBack = { mainViewModel.goBack() }
            )
            AppScreen.PDF_VIEWER -> {
                val path = mainViewModel.appConfig.collectAsState().value.lastOpenedFilePath ?: ""
                val name = path.substringAfterLast('\\').substringAfterLast('/')
                PdfViewerScreen(
                    booksViewModel = booksViewModel,
                    bookPath = path,
                    bookName = name,
                    onBack = { mainViewModel.goBack() }
                )
            }
            AppScreen.EPUB_VIEWER -> {
                val path = mainViewModel.appConfig.collectAsState().value.lastOpenedFilePath ?: ""
                val name = path.substringAfterLast('\\').substringAfterLast('/')
                EpubViewerScreen(
                    booksViewModel = booksViewModel,
                    bookPath = path,
                    bookName = name,
                    baseUrl = mainViewModel.getBaseUrl(),
                    onBack = { mainViewModel.goBack() }
                )
            }
            else -> {} 
        }
    }
}
