package com.omni.sync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.omni.sync.ui.screen.NoteViewerScreen
import com.omni.sync.ui.screen.ProcessScreen
import com.omni.sync.ui.screen.TrackpadScreen
import com.omni.sync.ui.theme.OmniSyncTheme
import com.omni.sync.viewmodel.AppScreen
import com.omni.sync.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private lateinit var omniSyncApplication: OmniSyncApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        omniSyncApplication = application as OmniSyncApplication
        mainViewModel = omniSyncApplication.mainViewModel
        
        // Start the SignalR connection (if not already started)
        omniSyncApplication.signalRClient.startConnection()

        // Start the Foreground Service
        Intent(this, ForegroundService::class.java).also { intent ->
            startService(intent)
        }

        setContent {
            OmniSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val currentScreen by mainViewModel.currentScreen.collectAsState()
                    val signalRClient = omniSyncApplication.signalRClient

                    Column {
                        Row {
                            Button(onClick = { mainViewModel.navigateTo(AppScreen.DASHBOARD) }) {
                                Text("Dashboard")
                            }
                            Button(onClick = { mainViewModel.navigateTo(AppScreen.TRACKPAD) }) {
                                Text("Trackpad")
                            }
                            Button(onClick = { mainViewModel.navigateTo(AppScreen.NOTEVIEWER) }) {
                                Text("Notes")
                            }
                            Button(onClick = { mainViewModel.navigateTo(AppScreen.PROCESS) }) {
                                Text("Process")
                            }
                        }

                        when (currentScreen) {
                            AppScreen.DASHBOARD -> DashboardScreen(signalRClient = signalRClient)
                            AppScreen.TRACKPAD -> TrackpadScreen(signalRClient = signalRClient)
                            AppScreen.NOTEVIEWER -> NoteViewerScreen(signalRClient = signalRClient)
                            AppScreen.PROCESS -> ProcessScreen(signalRClient = signalRClient)
                        }
                    }
                }
            }
        }
    }
}
