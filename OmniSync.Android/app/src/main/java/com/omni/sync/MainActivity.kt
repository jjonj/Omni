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

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private lateinit var omniSyncApplication: OmniSyncApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // This is crucial for making the IME (keyboard) work correctly with padding
        WindowCompat.setDecorFitsSystemWindows(window, false)

        omniSyncApplication = application as OmniSyncApplication
        mainViewModel = omniSyncApplication.mainViewModel
        
        // Start the SignalR connection (if not already started)
        omniSyncApplication.signalRClient.startConnection()

        // Start the Foreground Service
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

                if (currentScreen == AppScreen.VIDEOPLAYER) {
                    // Truly Fullscreen for video player - Bypass everything
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
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // Handle global back navigation
                        BackHandler(enabled = true) {
                            mainViewModel.handleBackPress { finish() }
                        }
                        
                        val filesViewModel: FilesViewModel = viewModel(
                            factory = FilesViewModelFactory(application, signalRClient, mainViewModel)
                        )
                        val browserViewModel: BrowserViewModel = viewModel(
                            factory = BrowserViewModelFactory(application, signalRClient)
                        )

                        // Using Scaffold to organize the layout professionally
                        androidx.compose.material3.Scaffold(
                            bottomBar = {
                                OmniBottomNavigation(
                                    currentScreen = currentScreen,
                                    onNavigate = { screen -> mainViewModel.navigateTo(screen) }
                                )
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                            ) {
                                MainScreenContent(currentScreen, signalRClient, browserViewModel, filesViewModel, mainViewModel)
                            }
                        }
                    }
                }
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
                onBack = { mainViewModel.goBack() }
            )
            else -> {} // Handled at top level
        }
    }
}