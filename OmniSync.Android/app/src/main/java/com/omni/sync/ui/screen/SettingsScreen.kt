package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.viewmodel.AppScreen
import android.content.Context
import android.content.Intent
import com.omni.sync.service.ForegroundService
import com.omni.sync.data.model.NotificationAction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.material.icons.filled.Mic
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
    val appConfig by mainViewModel.appConfig.collectAsState()
    val gson = remember { Gson() }
    
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                // Send to AI
                signalRClient.sendAiMessage(spokenText)
            }
        }
    }

    fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        voiceLauncher.launch(intent)
    }

    var videoSkipInterval by remember { mutableIntStateOf(appConfig.videoSkipInterval) }
    var videoPlaylistRandom by remember { mutableStateOf(appConfig.videoPlaylistRandom) }
                var cortexNotificationsEnabled by remember { mutableStateOf(appConfig.cortexNotificationsEnabled) }
        var cortexWakeTime by remember { mutableStateOf(appConfig.cortexWakeTime) }
        var cortexTemplatesJson by remember { mutableStateOf(appConfig.cortexTemplatesJson ?: "") }
        
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

    val cleanupPatterns by signalRClient.cleanupPatterns.collectAsState()
    
    // Sync Hub patterns to AppConfig when they change via SignalR
    LaunchedEffect(cleanupPatterns) {
        if (cleanupPatterns.isNotEmpty() && cleanupPatterns != appConfig.browserCleanupPatterns) {
            appConfig.browserCleanupPatterns = cleanupPatterns
            mainViewModel.saveAppConfig()
        }
    }

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
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Quick Actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\shutdown.bat") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Shutdown", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { signalRClient.executeCommand("B:\\GDrive\\Tools\\05 Automation\\sleep.bat") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sleep", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { 
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString()
                                    if (!text.isNullOrBlank()) {
                                        signalRClient.sendAiMessage("Summarize and analyze:\n\n$text")
                                        mainViewModel.navigateTo(AppScreen.AI_CHAT)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("AI Scan", fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                signalRClient.triggerTellPc()
                                mainViewModel.addLog("Tell PC Triggered...", LogType.INFO)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Mic, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tell PC", fontSize = 10.sp)
                        }
                    }
                }
            }

            Text("Global Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            var maxCacheSize by remember { mutableStateOf(appConfig.maxCacheFileSize / (1024 * 1024)) }
            OutlinedTextField(
                value = maxCacheSize.toString(),
                onValueChange = { 
                    val newValue = it.toLongOrNull() ?: 0
                    maxCacheSize = newValue
                    appConfig.maxCacheFileSize = newValue * 1024 * 1024
                    mainViewModel.saveAppConfig()
                },
                label = { Text("Max Cache File Size (MB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            var maxAiHistory by remember { mutableIntStateOf(appConfig.maxAiHistory) }
            OutlinedTextField(
                value = maxAiHistory.toString(),
                onValueChange = { 
                    val newValue = it.toIntOrNull() ?: 0
                    maxAiHistory = newValue
                    appConfig.maxAiHistory = newValue
                    mainViewModel.saveAppConfig()
                },
                label = { Text("Max AI History per Session (chars)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

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
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    val hubUrl = appConfig.hubUrl
                    val baseUrl = hubUrl.substringBeforeLast("/")
                    mainViewModel.openUrlOnPhone("$baseUrl/Settings.html")
                }) {
                    Text("Manage Hub (Hotkeys, Exes)")
                }

                Button(
                    onClick = {
                        appConfig.hubUrl = hubUrl
                        appConfig.wanIp = wanIp
                        appConfig.apiKey = apiKey
                        mainViewModel.saveAppConfig()
                        signalRClient.manualReconnect()
                    }
                ) {
                    Text("Save & Reconnect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { mainViewModel.fetchHubLogs(signalRClient) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Fetch Hub Log")
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

            Text("Cortex Web", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = cortexWakeTime,
                onValueChange = { cortexWakeTime = it },
                label = { Text("Wake Time (HH:mm)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            var showTemplatesEditor by remember { mutableStateOf(false) }
            
            Button(
                onClick = { showTemplatesEditor = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Edit Block Definitions")
            }
            
            if (cortexTemplatesJson.isNotBlank()) {
                Text(
                    "Custom definitions present", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            if (showTemplatesEditor) {
                CortexTemplatesEditor(
                    initialJson = cortexTemplatesJson,
                    onSave = { newJson ->
                        cortexTemplatesJson = newJson
                    },
                    onDismiss = { showTemplatesEditor = false }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    appConfig.cortexWakeTime = cortexWakeTime
                    appConfig.cortexTemplatesJson = if (cortexTemplatesJson.isBlank()) null else cortexTemplatesJson
                    mainViewModel.saveAppConfig()
                    signalRClient.sendCortexWakeTime(cortexWakeTime)
                    if (cortexTemplatesJson.isNotBlank()) {
                        signalRClient.sendCortexTemplates(cortexTemplatesJson)
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Sync to Cortex")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Browser Tab Cleanup", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            var cleanupPatternsStr by remember { mutableStateOf(appConfig.browserCleanupPatterns.joinToString("\n")) }
            
            // Local state needs to update when appConfig changes (e.g. from Hub)
            LaunchedEffect(appConfig.browserCleanupPatterns) {
                cleanupPatternsStr = appConfig.browserCleanupPatterns.joinToString("\n")
            }

            OutlinedTextField(
                value = cleanupPatternsStr,
                onValueChange = { 
                    cleanupPatternsStr = it
                    val newPatterns = it.lines().filter { line -> line.isNotBlank() }
                    if (newPatterns != appConfig.browserCleanupPatterns) {
                        appConfig.browserCleanupPatterns = newPatterns
                        mainViewModel.saveAppConfig()
                        signalRClient.sendCleanupPatterns(newPatterns)
                    }
                },
                label = { Text("Cleanup Patterns (one per line)") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("e.g. https://github.com/*\n*stack overflow*") }
            )
            Text("Patterns support * wildcard. Use 'title:text' for title matching.", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("File Caching", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            var maxCacheSizeMb by remember { mutableStateOf((appConfig.maxCacheFileSize / (1024 * 1024)).toString()) }
            OutlinedTextField(
                value = maxCacheSizeMb,
                onValueChange = { 
                    maxCacheSizeMb = it
                    it.toLongOrNull()?.let { mb ->
                        appConfig.maxCacheFileSize = mb * 1024 * 1024
                        mainViewModel.saveAppConfig()
                    }
                },
                label = { Text("Max Cache File Size (MB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            var exclusionPatternsStr by remember { mutableStateOf(appConfig.cacheExclusionPatterns.joinToString("\n")) }
            OutlinedTextField(
                value = exclusionPatternsStr,
                onValueChange = { 
                    exclusionPatternsStr = it
                    appConfig.cacheExclusionPatterns = it.lines().filter { line -> line.isNotBlank() }
                    mainViewModel.saveAppConfig()
                },
                label = { Text("Cache Exclusion Patterns (one per line)") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("e.g. G:/*\n*porn*") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            var wolMac by remember { mutableStateOf(appConfig.wakeOnLanMac) }
            OutlinedTextField(
                value = wolMac,
                onValueChange = { 
                    wolMac = it
                    appConfig.wakeOnLanMac = it
                    mainViewModel.saveAppConfig()
                },
                label = { Text("Wake-on-LAN MAC Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            var subnetIp by remember { mutableStateOf(appConfig.subnetBroadcastIp) }
            OutlinedTextField(
                value = subnetIp,
                onValueChange = { 
                    subnetIp = it
                    appConfig.subnetBroadcastIp = it
                    mainViewModel.saveAppConfig()
                },
                label = { Text("Subnet Broadcast IP (e.g. 192.168.1.255)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Monitor Streaming", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Keyboard Sound")
                Switch(
                    checked = appConfig.keyboardSoundEnabled,
                    onCheckedChange = { 
                        appConfig.keyboardSoundEnabled = it
                        mainViewModel.saveAppConfig()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show Keyboard Number Row")
                Switch(
                    checked = appConfig.showKeyboardNumberRow,
                    onCheckedChange = { 
                        appConfig.showKeyboardNumberRow = it
                        mainViewModel.saveAppConfig()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            var streamFps by remember { mutableIntStateOf(appConfig.streamFps) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Stream FPS")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(streamFps.toString(), modifier = Modifier.padding(horizontal = 8.dp))
                    Slider(
                        value = streamFps.toFloat(),
                        onValueChange = { 
                            streamFps = it.toInt()
                            appConfig.streamFps = streamFps
                            mainViewModel.saveAppConfig()
                        },
                        valueRange = 1f..30f,
                        steps = 29,
                        modifier = Modifier.width(150.dp)
                    )
                }
            }

            var streamResolution by remember { mutableIntStateOf(appConfig.streamResolution) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Resolution Scale (%)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(streamResolution.toString() + "%", modifier = Modifier.padding(horizontal = 8.dp))
                    Slider(
                        value = streamResolution.toFloat(),
                        onValueChange = { 
                            streamResolution = it.toInt()
                            appConfig.streamResolution = streamResolution
                            mainViewModel.saveAppConfig()
                        },
                        valueRange = 10f..100f,
                        steps = 17, // 10, 15, 20... 100
                        modifier = Modifier.width(150.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { filesViewModel.clearAllCaches() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear All Local Caches")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            val isPasswordSet = appConfig.globalPasswordHash != null
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

                        if (appConfig.macros.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Macros", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            appConfig.macros.forEach { macro ->
                                DropdownMenuItem(
                                    text = { Text(macro.name) },
                                    onClick = {
                                        val newActions = notificationActions.toMutableList()
                                        val id = java.util.UUID.randomUUID().toString()
                                        newActions.add(NotificationAction(id, macro.name, "MACRO:${macro.script}"))
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