package com.omni.sync

import android.content.Intent
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
import androidx.compose.ui.unit.dp
import com.omni.sync.ui.components.OmniBottomNavigation

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

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private lateinit var omniSyncApplication: OmniSyncApplication

    private val swipeableScreens = listOf(
        AppScreen.DASHBOARD,
        AppScreen.REMOTECONTROL,
        AppScreen.BROWSER,
        AppScreen.FILES,
        AppScreen.AI_CHAT,
        AppScreen.ALARM,
        AppScreen.WEB_SERVER,
        AppScreen.PROCESS
    )

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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

        omniSyncApplication = application as OmniSyncApplication
        mainViewModel = omniSyncApplication.mainViewModel
        mainViewModel.recordActivity()
        
        omniSyncApplication.signalRClient.startConnection()

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
                val canGoBack by mainViewModel.canGoBack.collectAsState()
                val signalRClient = omniSyncApplication.signalRClient
                
                val context = LocalContext.current

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
                    BackHandler(enabled = true) {
                        mainViewModel.handleBackPress { finish() }
                    }
                    
                    val filesViewModel: FilesViewModel = viewModel(
                        factory = FilesViewModelFactory(application, signalRClient, mainViewModel)
                    )
                    val browserViewModel: BrowserViewModel = viewModel(
                        factory = BrowserViewModelFactory(application, signalRClient)
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

                    LaunchedEffect(Unit) {
                        signalRClient.isTriggeringTellPc.collect {
                            startGlobalVoiceRecognition(-1) // -1 signifies newest/TellPC session
                        }
                    }

                    LaunchedEffect(Unit) {
                        signalRClient.aiDialogAlertEvent.collect {
                            try {
                                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                            } catch (e: Exception) {
                                // Ignore sound errors
                            }
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
                                if (screen == AppScreen.FILES && filesViewModel.editingFile.value != null) {
                                    // Only auto-redirect to EDITOR if we are NOT coming from EDITOR
                                    if (currentScreen != AppScreen.EDITOR) {
                                        mainViewModel.navigateTo(AppScreen.EDITOR)
                                    } else {
                                        mainViewModel.navigateTo(screen)
                                    }
                                } else {
                                    mainViewModel.navigateTo(screen)
                                }
                            }
                        }
                    }

                    androidx.compose.material3.Scaffold(
                        modifier = if (!isLandscape) Modifier.systemBarsPadding() else Modifier,
                        bottomBar = {
                            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                            OmniBottomNavigation(
                                currentScreen = currentScreen,
                                onNavigate = { screen -> 
                                    if (screen == AppScreen.FILES) {
                                        if (filesViewModel.editingFile.value != null) {
                                            if (currentScreen == AppScreen.EDITOR) {
                                                mainViewModel.navigateTo(AppScreen.FILES)
                                            } else {
                                                mainViewModel.navigateTo(AppScreen.EDITOR)
                                            }
                                        } else {
                                            mainViewModel.navigateTo(AppScreen.FILES)
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
                    ) { innerPadding ->
                        val isSwipeable = currentScreen in swipeableScreens
                        val wordWrap by filesViewModel.wordWrap.collectAsState()
                        val canSwipe = when (currentScreen) {
                            AppScreen.EDITOR -> wordWrap
                            else -> isSwipeable
                        }
                        
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Always keep pager in composition to avoid state resets and jumpy animations
                            // Custom touch slop to make paging less sensitive
                            val viewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
                            val customViewConfig = remember {
                                object : androidx.compose.ui.platform.ViewConfiguration by viewConfig {
                                    override val touchSlop: Float get() = viewConfig.touchSlop * 2.5f
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
                                    val pageModifier = if (screenAtPage == AppScreen.REMOTECONTROL || screenAtPage == AppScreen.FILES || screenAtPage == AppScreen.AI_CHAT || screenAtPage == AppScreen.WEB_SERVER) Modifier else Modifier.padding(innerPadding)
                                    Box(modifier = pageModifier) {
                                        MainScreenContent(screenAtPage, signalRClient, browserViewModel, filesViewModel, mainViewModel, innerPadding)
                                    }
                                }
                            }

                            // Overlay non-swipeable screens on top when active
                            if (!isSwipeable) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background
                                ) {
                                    MainScreenContent(currentScreen, signalRClient, browserViewModel, filesViewModel, mainViewModel, innerPadding)
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
        // Retry connection if disconnected when app comes to foreground
        if (!mainViewModel.isConnected.value && omniSyncApplication.signalRClient.connectionState.value != "Connecting...") {
            omniSyncApplication.signalRClient.startConnection()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
        if (screenName != null) {
            try {
                val screen = AppScreen.valueOf(screenName)
                mainViewModel.navigateTo(screen)
                
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
            AppScreen.AI_CHAT -> com.omni.sync.ui.screen.AiChatScreen(
                signalRClient = signalRClient,
                mainViewModel = mainViewModel,
                filesViewModel = filesViewModel,
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
            else -> {} 
        }
    }
}