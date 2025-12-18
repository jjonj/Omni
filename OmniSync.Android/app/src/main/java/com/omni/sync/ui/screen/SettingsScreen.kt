package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omni.sync.viewmodel.MainViewModel
import android.content.Context

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel
) {
    val context = mainViewModel.applicationContext
    val prefs = remember { context.getSharedPreferences("omni_settings", Context.MODE_PRIVATE) }
    
    var videoSkipInterval by remember { mutableIntStateOf(prefs.getInt("video_skip_interval", 10)) }
    var videoPlaylistRandom by remember { mutableStateOf(prefs.getBoolean("video_playlist_random", false)) }
    var cortexNotificationsEnabled by remember { mutableStateOf(prefs.getBoolean("cortex_notifications_enabled", true)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { mainViewModel.goBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Video Player", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Double tap skip interval (seconds)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(videoSkipInterval.toString(), modifier = Modifier.padding(horizontal = 8.dp))
                    Slider(
                        value = videoSkipInterval.toFloat(),
                        onValueChange = { 
                            videoSkipInterval = it.toInt()
                            prefs.edit().putInt("video_skip_interval", videoSkipInterval).apply()
                        },
                        valueRange = 5f..60f,
                        steps = 11,
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Randomize playlist order")
                Switch(
                    checked = videoPlaylistRandom,
                    onCheckedChange = { 
                        videoPlaylistRandom = it
                        prefs.edit().putBoolean("video_playlist_random", videoPlaylistRandom).apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Scheduling", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Cortex activity notifications")
                Switch(
                    checked = cortexNotificationsEnabled,
                    onCheckedChange = { 
                        cortexNotificationsEnabled = it
                        prefs.edit().putBoolean("cortex_notifications_enabled", cortexNotificationsEnabled).apply()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Hub Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Current Hub: ${mainViewModel.getBaseUrl()}", style = MaterialTheme.typography.bodySmall)
            // Future: Add Hub IP configuration here
        }
    }
}
