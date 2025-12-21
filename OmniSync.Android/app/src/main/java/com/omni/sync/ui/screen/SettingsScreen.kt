package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel
) {
    val context = mainViewModel.applicationContext
    val appConfig = mainViewModel.appConfig
    val gson = remember { Gson() }
    
    var videoSkipInterval by remember { mutableIntStateOf(appConfig.videoSkipInterval) }
    var videoPlaylistRandom by remember { mutableStateOf(appConfig.videoPlaylistRandom) }
    var cortexNotificationsEnabled by remember { mutableStateOf(appConfig.cortexNotificationsEnabled) }

    val initialActions = appConfig.notificationActions.ifEmpty {
        listOf(
            NotificationAction("1", "Shutdown", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
            NotificationAction("2", "Sleep", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
            NotificationAction("3", "TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
            NotificationAction("4", "WOL", "", isWol = true, macAddress = "10FFE0379DAC")
        )
    }
    
    var notificationActions by remember { mutableStateOf<List<NotificationAction>>(initialActions) }
    var showAddMenu by remember { mutableStateOf(false) }
    
    var showPathPrompt by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<NotificationAction?>(null) }

    val browserPrefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val bookmarksJson = browserPrefs.getString("bookmarks", null)
    val bookmarks: List<com.omni.sync.viewmodel.Bookmark> = if (bookmarksJson != null) {
        val type = object : TypeToken<List<com.omni.sync.viewmodel.Bookmark>>() {}.type
        gson.fromJson(bookmarksJson, type)
    } else emptyList()

    val predefinedActions = listOf(
        NotificationAction("pre-1", "Shutdown PC", "B:\\GDrive\\Tools\\05 Automation\\shutdown.bat"),
        NotificationAction("pre-2", "Sleep PC", "B:\\GDrive\\Tools\\05 Automation\\sleep.bat"),
        NotificationAction("pre-3", "Toggle TV", "B:\\GDrive\\Tools\\05 Automation\\TVActive3\\tv_toggle.bat"),
        NotificationAction("pre-4", "WOL PC", "", isWol = true, macAddress = "10FFE0379DAC"),
        NotificationAction("pre-nav-dash", "Go to Dashboard", "NAV:DASHBOARD"),
        NotificationAction("pre-nav-remote", "Go to Remote", "NAV:REMOTECONTROL"),
        NotificationAction("pre-nav-browser", "Go to Browser", "NAV:BROWSER"),
        NotificationAction("pre-nav-files", "Go to Files", "NAV:FILES"),
        NotificationAction("pre-nav-ai", "Go to AI Chat", "NAV:AI_CHAT"),
        NotificationAction("pre-nav-alarm", "Go to Alarm", "NAV:ALARM"),
        NotificationAction("pre-nav-file", "Open Folder...", "NAV_FILE:PROMPT"),
        NotificationAction("pre-alarm-830", "Alarm 8h 30m", "ALARM:510"),
        NotificationAction("pre-alarm-845", "Alarm 8h 45m", "ALARM:525"),
        NotificationAction("pre-alarm-900", "Alarm 9h", "ALARM:540"),
        NotificationAction("pre-br-back", "Browser Back", "BROWSER:Back"),
        NotificationAction("pre-br-refresh", "Browser Refresh", "BROWSER:Refresh"),
        NotificationAction("pre-br-forward", "Browser Forward", "BROWSER:Forward"),
        NotificationAction("pre-br-close", "Browser Close Tab", "BROWSER:CloseTab"),
        NotificationAction("pre-br-play", "Browser Play/Pause", "BROWSER:MediaPlayPause"),
        NotificationAction("pre-br-phone", "Open Tab on Phone", "BROWSER:OpenCurrentTabOnPhone")
    )

    fun saveActions(actions: List<NotificationAction>) {
        notificationActions = actions
        appConfig.notificationActions = actions
        mainViewModel.saveAppConfig()
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
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
                            appConfig.videoSkipInterval = videoSkipInterval
                            mainViewModel.saveAppConfig()
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
                        appConfig.videoPlaylistRandom = videoPlaylistRandom
                        mainViewModel.saveAppConfig()
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
                        appConfig.cortexNotificationsEnabled = cortexNotificationsEnabled
                        mainViewModel.saveAppConfig()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Notification Actions (${notificationActions.size}/6)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Box {
                    IconButton(onClick = { showAddMenu = true }, enabled = notificationActions.size < 6) {
                        Icon(Icons.Default.Add, "Add Action")
                    }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        Text("Predefined", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        predefinedActions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                onClick = {
                                    if (action.command == "NAV_FILE:PROMPT") {
                                        pendingAction = action
                                        showPathPrompt = true
                                    } else {
                                        val newActions = notificationActions.toMutableList()
                                        val id = java.util.UUID.randomUUID().toString()
                                        newActions.add(action.copy(id = id))
                                        saveActions(newActions)
                                    }
                                    showAddMenu = false
                                }
                            )
                        }
                        
                        if (bookmarks.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Bookmarks", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            bookmarks.take(10).forEach { bookmark ->
                                DropdownMenuItem(
                                    text = { Text(bookmark.name) },
                                    onClick = {
                                        val newActions = notificationActions.toMutableList()
                                        val id = java.util.UUID.randomUUID().toString()
                                        newActions.add(NotificationAction(id, bookmark.name, "BOOKMARK:${bookmark.url}"))
                                        saveActions(newActions)
                                        showAddMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            notificationActions.forEachIndexed { index, action ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
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
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Hub Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Current Hub: ${mainViewModel.getBaseUrl()}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showPathPrompt) {
        AlertDialog(
            onDismissRequest = { showPathPrompt = false; pathInput = "" },
            title = { Text("Enter folder/file path") },
            text = {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("e.g. D:\\\\Videos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (pathInput.isNotBlank()) {
                        val newActions = notificationActions.toMutableList()
                        val id = java.util.UUID.randomUUID().toString()
                        val label = pathInput.substringAfterLast("\\\\").substringAfterLast("/").ifEmpty { pathInput }
                        newActions.add(NotificationAction(id, "Open $label", "NAV_FILE:$pathInput"))
                        saveActions(newActions)
                        showPathPrompt = false
                        pathInput = ""
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPathPrompt = false; pathInput = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}