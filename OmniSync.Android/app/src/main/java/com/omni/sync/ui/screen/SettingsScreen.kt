package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.viewmodel.MainViewModel
import android.content.Context
import android.content.Intent
import com.omni.sync.service.ForegroundService
import com.omni.sync.data.model.NotificationAction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel
) {
    val context = mainViewModel.applicationContext
    val prefs = remember { context.getSharedPreferences("omni_settings", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }
    
    var videoSkipInterval by remember { mutableIntStateOf(prefs.getInt("video_skip_interval", 10)) }
    var videoPlaylistRandom by remember { mutableStateOf(prefs.getBoolean("video_playlist_random", false)) }
    var cortexNotificationsEnabled by remember { mutableStateOf(prefs.getBoolean("cortex_notifications_enabled", true)) }

    val initialActionsJson = prefs.getString("notification_actions", null)
    val initialActions = if (initialActionsJson == null) {
        listOf(
            NotificationAction("1", "Shutdown", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
            NotificationAction("2", "Sleep", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
            NotificationAction("3", "TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
            NotificationAction("4", "WOL", "", isWol = true, macAddress = "10FFE0379DAC")
        )
    } else {
        val type = object : TypeToken<List<NotificationAction>>() {}.type
        gson.fromJson(initialActionsJson, type)
    }
    
    var notificationActions by remember { mutableStateOf<List<NotificationAction>>(initialActions) }

    fun saveActions(actions: List<NotificationAction>) {
        notificationActions = actions
        prefs.edit().putString("notification_actions", gson.toJson(actions)).apply()
        // Refresh service
        val intent = Intent(context, ForegroundService::class.java).apply {
            action = ForegroundService.ACTION_REFRESH_NOTIFICATION
        }
        context.startService(intent)
    }

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

            Text("Notification Quick Actions (Max 3)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            notificationActions.forEachIndexed { index, action ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(action.label, fontWeight = FontWeight.Bold)
                        Text(if (action.isWol) "WOL: ${action.macAddress}" else "CMD: ${action.command.takeLast(30)}", fontSize = 10.sp, color = Color.Gray)
                    }
                    IconButton(onClick = {
                        val newActions = notificationActions.toMutableList()
                        newActions.removeAt(index)
                        saveActions(newActions)
                    }) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (notificationActions.size < 3) {
                Button(
                    onClick = {
                        val newActions = notificationActions.toMutableList()
                        val id = java.util.UUID.randomUUID().toString()
                        newActions.add(NotificationAction(id, "Demo Action", "calc.exe"))
                        saveActions(newActions)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Demo Action")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Hub Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Current Hub: ${mainViewModel.getBaseUrl()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}