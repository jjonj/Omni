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
import kotlinx.coroutines.launch

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
        
        omniSyncApplication.signalRClient.startConnection()

        Intent(this, ForegroundService::class.java).also { intent ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContent {
            OmniSyncTheme {
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

                if (currentScreen == AppScreen.VIDEOPLAYER) {
                    val videoUrl by mainViewModel.currentVideoUrl.collectAsState()
                    val playlist by mainViewModel.videoPlaylist.collectAsState()
                    val initialIndex by mainViewModel.currentVideoIndex.collectAsState()
                    
                    if (videoUrl != null) {
                        com.omni.sync.ui.screen.VideoPlayerScreen(
                            videoUrl = videoUrl!!,
                            playlist = playlist,
                            initialIndex = initialIndex,
                            onBack = { mainViewModel.goBack() }
                        )
                    }
                } else if (currentScreen == AppScreen.IMAGE_VIEWER) {
                    val imageUrl by mainViewModel.currentImageUrl.collectAsState()
                    val playlist by mainViewModel.imagePlaylist.collectAsState()
                    val initialIndex by mainViewModel.currentImageIndex.collectAsState()
                    
                    if (imageUrl != null) {
                        com.omni.sync.ui.screen.ImageViewerScreen(
                            initialImageUrl = imageUrl!!,
                            playlist = playlist,
                            initialIndex = initialIndex,
                            onBack = { mainViewModel.goBack() }
                        )
                    }
                } else {
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

                        LaunchedEffect(currentScreen) {
                            val index = swipeableScreens.indexOf(currentScreen)
                            if (index != -1 && pagerState.currentPage != index) {
                                pagerState.scrollToPage(index)
                            }
                        }

                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }.collect { page ->
                                val screen = swipeableScreens[page]
                                if (mainViewModel.currentScreen.value != screen) {
                                    mainViewModel.navigateTo(screen)
                                }
                            }
                        }

                        androidx.compose.material3.Scaffold(
                            modifier = if (!isLandscape) Modifier.systemBarsPadding() else Modifier,
                            bottomBar = {
                                val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                OmniBottomNavigation(
                                    currentScreen = currentScreen,
                                    onNavigate = { screen -> mainViewModel.navigateTo(screen) },
                                    onSwipe = { delta ->
                                        coroutineScope.launch {
                                            val next = (pagerState.currentPage + delta).coerceIn(0, swipeableScreens.size - 1)
                                            pagerState.animateScrollToPage(next)
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                            ) {
                                if (currentScreen == AppScreen.EDITOR || currentScreen == AppScreen.SETTINGS || 
                                    currentScreen == AppScreen.DOWNLOADED_VIDEOS) {
                                    MainScreenContent(currentScreen, signalRClient, browserViewModel, filesViewModel, mainViewModel)
                                } else {
                                    // Custom touch slop to make paging less sensitive to diagonal swipes
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
                                            userScrollEnabled = true 
                                        ) { page ->
                                            MainScreenContent(swipeableScreens[page], signalRClient, browserViewModel, filesViewModel, mainViewModel)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Global Alarm Dismiss Overlay
                if (isAlarmRinging) {
                    val context = LocalContext.current
                    AlertDialog(
                        onDismissRequest = { /* Prevent dismissal by clicking outside */ },
                        icon = { Icon(Icons.Default.AlarmOff, null, modifier = Modifier.size(48.dp)) },
                        title = { Text("Alarm Ringing!", style = MaterialTheme.typography.headlineMedium) },
                        text = { 
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text("Wake up!", style = MaterialTheme.typography.bodyLarge) 
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { AlarmService.stopAlarm(context) },
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("DISMISS", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    )
                }
            }
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
        mainViewModel: MainViewModel
    ) {
        when (currentScreen) {
            AppScreen.DASHBOARD -> DashboardScreen(
                signalRClient = signalRClient, 
                mainViewModel = mainViewModel
            )
            AppScreen.REMOTECONTROL -> RemoteControlScreen(
                signalRClient = signalRClient, 
                mainViewModel = mainViewModel
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
                filesViewModel = filesViewModel
            )
            AppScreen.EDITOR -> com.omni.sync.ui.screen.TextEditorScreen(
                filesViewModel = filesViewModel,
                signalRClient = signalRClient,
                onBack = { mainViewModel.goBack() }
            )
            AppScreen.SETTINGS -> com.omni.sync.ui.screen.SettingsScreen(
                mainViewModel = mainViewModel,
                signalRClient = signalRClient,
                filesViewModel = filesViewModel
            )
            AppScreen.AI_CHAT -> com.omni.sync.ui.screen.AiChatScreen(
                signalRClient = signalRClient,
                mainViewModel = mainViewModel
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
            AppScreen.IMAGE_VIEWER -> {
                val imageUrl by mainViewModel.currentImageUrl.collectAsState()
                val playlist by mainViewModel.imagePlaylist.collectAsState()
                val initialIndex by mainViewModel.currentImageIndex.collectAsState()
                if (imageUrl != null) {
                    com.omni.sync.ui.screen.ImageViewerScreen(
                        initialImageUrl = imageUrl!!,
                        playlist = playlist,
                        initialIndex = initialIndex,
                        onBack = { mainViewModel.goBack() }
                    )
                }
            }
            else -> {} 
        }
    }
}