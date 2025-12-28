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
    mainViewModel: MainViewModel,
    signalRClient: com.omni.sync.data.repository.SignalRClient,
    filesViewModel: com.omni.sync.viewmodel.FilesViewModel
) {
    val context = mainViewModel.applicationContext
    val appConfig = mainViewModel.appConfig
    val gson = remember { Gson() }
    
    var videoSkipInterval by remember { mutableIntStateOf(appConfig.videoSkipInterval) }
    var videoPlaylistRandom by remember { mutableStateOf(appConfig.videoPlaylistRandom) }
    var cortexNotificationsEnabled by remember { mutableStateOf(appConfig.cortexNotificationsEnabled) }
    
    var hubUrl by remember { mutableStateOf(appConfig.hubUrl) }
    var wanIp by remember { mutableStateOf(appConfig.wanIp) }
    var apiKey by remember { mutableStateOf(appConfig.apiKey) }

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

    val predefinedActions = remember { mainViewModel.configManager.getPredefinedActions() }
    val bookmarkActions = remember { mainViewModel.configManager.getBookmarks() }

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
            Text("Hub Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = hubUrl,
                onValueChange = { hubUrl = it },
                label = { Text("Hub URL (Local)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = wanIp,
                onValueChange = { wanIp = it },
                label = { Text("WAN IP (Public)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = {
                    appConfig.hubUrl = hubUrl
                    appConfig.wanIp = wanIp
                    appConfig.apiKey = apiKey
                    mainViewModel.saveAppConfig()
                    signalRClient.manualReconnect()
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Save & Reconnect")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
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
                            // Refresh service
                            val intent = Intent(context, ForegroundService::class.java).apply {
                                action = ForegroundService.ACTION_REFRESH_NOTIFICATION
                            }
                            context.startService(intent)
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
                        // Refresh service
                        val intent = Intent(context, ForegroundService::class.java).apply {
                            action = ForegroundService.ACTION_REFRESH_NOTIFICATION
                        }
                        context.startService(intent)
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
                        // Refresh service
                        val intent = Intent(context, ForegroundService::class.java).apply {
                            action = ForegroundService.ACTION_REFRESH_NOTIFICATION
                        }
                        context.startService(intent)
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            val isPasswordSet = mainViewModel.appConfig.globalPasswordHash != null
            var showPasswordDialog by remember { mutableStateOf(false) }
            var oldPassword by remember { mutableStateOf("") }
            var newPassword by remember { mutableStateOf("") }

            if (isPasswordSet) {
                Text("Global password is set", color = Color.Gray, fontSize = 14.sp)
                Button(
                    onClick = { showPasswordDialog = true },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Change Password")
                }
            } else {
                Text("No global password set", color = Color.Gray, fontSize = 14.sp)
                Button(
                    onClick = { showPasswordDialog = true },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Set Global Password")
                }
            }

            if (showPasswordDialog) {
                AlertDialog(
                    onDismissRequest = { showPasswordDialog = false; oldPassword = ""; newPassword = "" },
                    title = { Text(if (isPasswordSet) "Change Password" else "Set Password") },
                    text = {
                        Column {
                            if (isPasswordSet) {
                                OutlinedTextField(
                                    value = oldPassword,
                                    onValueChange = { oldPassword = it },
                                    label = { Text("Current Password") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                label = { Text("New Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newPassword.isNotBlank()) {
                                if (filesViewModel.setGlobalPassword(if (isPasswordSet) oldPassword else null, newPassword)) {
                                    showPasswordDialog = false
                                    oldPassword = ""
                                    newPassword = ""
                                } else {
                                    // Error handled in setGlobalPassword (adds log)
                                }
                            }
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPasswordDialog = false; oldPassword = ""; newPassword = "" }) {
                            Text("Cancel")
                        }
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
                        
                        if (bookmarkActions.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Bookmarks", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            bookmarkActions.take(10).forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    onClick = {
                                        val newActions = notificationActions.toMutableList()
                                        val id = java.util.UUID.randomUUID().toString()
                                        newActions.add(action.copy(id = id))
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