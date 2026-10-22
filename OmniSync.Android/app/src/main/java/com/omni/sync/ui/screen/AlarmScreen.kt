package com.omni.sync.ui.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.omni.sync.service.AlarmService
import com.omni.sync.utils.AlarmScheduler
import com.omni.sync.utils.AlarmStore
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.data.repository.ReminderData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

data class AlarmData(
    var id: Int = 0,
    var enabled: Boolean = false,
    var hour: Int = 9,
    var minute: Int = 0,
    var isAM: Boolean = true,
    var quickToggleMode: Int = 0,
    var isWakeAlarm: Boolean = false,
    var autoStillAwake: Boolean = false,
    var latestWakeMinutes: Int? = 720,
    var repeatDaily: Boolean = false,
    var useGradual: Boolean = true,
    var soundId: String = "gentle",
    var macroOnTrigger: String? = null,
    var macroOnDismiss: String? = null,
    var quickActionOnTriggerId: String? = null,
    var quickActionOnDismissId: String? = null,
    var dismissActionDelaySeconds: Int = 0,
    var scheduledDate: Long? = null,
    var dismissText: String? = null,
    var applyChangesToNextTriggerOnly: Boolean = false,
    var nextTriggerOverride: AlarmData? = null
) {
    /** Returns the one-off settings when a daily alarm has a pending next-trigger change. */
    fun resolvedForNextTrigger(): AlarmData {
        val override = nextTriggerOverride ?: return this
        return override.copy(
            id = id,
            enabled = enabled,
            isWakeAlarm = isWakeAlarm,
            autoStillAwake = autoStillAwake,
            latestWakeMinutes = latestWakeMinutes,
            repeatDaily = repeatDaily,
            applyChangesToNextTriggerOnly = applyChangesToNextTriggerOnly,
            nextTriggerOverride = nextTriggerOverride
        )
    }

    fun withNextTriggerOverride(changed: AlarmData): AlarmData = copy(
        enabled = changed.enabled,
        isWakeAlarm = changed.isWakeAlarm,
        autoStillAwake = changed.autoStillAwake,
        latestWakeMinutes = changed.latestWakeMinutes,
        applyChangesToNextTriggerOnly = true,
        nextTriggerOverride = changed.copy(
            id = id,
            enabled = true,
            repeatDaily = false,
            applyChangesToNextTriggerOnly = false,
            nextTriggerOverride = null
        )
    )
}

data class GradualConfig(
    var initialVolume: Int = 5,
    var alarmDuration: Int = 3,
    var snoozeDuration: Int = 10,
    var volumeIncrement: Int = 5,
    var maxRepetitions: Int = 6
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    mainViewModel: MainViewModel,
    signalRClient: SignalRClient,
    onBack: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF8BAAC4), onPrimary = Color(0xFF07111B),
        primaryContainer = Color(0xFF20394F), onPrimaryContainer = Color(0xFFCDDCE8),
        secondaryContainer = Color(0xFF192B3B), onSecondaryContainer = Color(0xFFBCCDDC),
        background = Color(0xFF05080C), surface = Color(0xFF0C1118),
        surfaceVariant = Color(0xFF141D28), onSurface = Color(0xFFD0D6DF),
        onSurfaceVariant = Color(0xFF9BA8B7),
        error = Color(0xFF74363F), onError = Color(0xFFF4DADD)
    )) {
        AlarmScreenContent(mainViewModel, signalRClient, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmScreenContent(
    mainViewModel: MainViewModel,
    signalRClient: SignalRClient,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            currentTime = System.currentTimeMillis()
        }
    }
    val appConfig by mainViewModel.appConfig.collectAsState()
    val prefs = remember { context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE) }
    val gson = remember { com.google.gson.Gson() }

    var alarms by remember { mutableStateOf(AlarmStore.load(prefs, gson)) }
    val omniReminders by signalRClient.reminders.collectAsState()
    val activeOmniReminders = remember(omniReminders) {
        omniReminders.filterNot { it.isTerminal }
    }
    val omniTimeSensitive = remember(activeOmniReminders) {
        activeOmniReminders.filter { it.kind != 0 }.sortedBy { it.dueAt }
    }
    val omniTasks = remember(activeOmniReminders) {
        activeOmniReminders.filter { it.kind == 0 }.sortedBy { it.dueAt }
    }

    LaunchedEffect(signalRClient) {
        while (true) {
            signalRClient.refreshReminders()
            delay(30_000)
        }
    }

    var config by remember { 
        val json = prefs.getString("config", null)
        mutableStateOf(if (json != null) gson.fromJson(json, GradualConfig::class.java) else GradualConfig())
    }
    
    fun saveAlarms(updatedAlarms: List<AlarmData> = alarms) {
        prefs.edit().apply {
            putString(AlarmStore.ALARMS_KEY, gson.toJson(updatedAlarms))
            putString("config", gson.toJson(config))
            apply()
        }
    }

    var showTimePicker by remember { mutableStateOf<Int?>(null) } 
    var editingTarget by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf<Int?>(null) }
    var showSoundPicker by remember { mutableStateOf<Int?>(null) }
    var showStatistics by remember { mutableStateOf(false) }
    
    val mediaPlayer = remember { MediaPlayer() }
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }

    // Listen for Changes in SharedPreferences
    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == AlarmStore.ALARMS_KEY || key == "config") {
                val jsonC = sharedPrefs.getString("config", null)

                alarms = AlarmStore.load(sharedPrefs, gson)
                if (jsonC != null) config = gson.fromJson(jsonC, GradualConfig::class.java)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            previewJob?.cancel()
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.release()
        }
    }

    fun updateSchedule(id: Int, data: AlarmData) {
        val existing = alarms.firstOrNull { it.id == id }
        val normalizedData = if (data.isWakeAlarm) data.copy(repeatDaily = true, scheduledDate = null) else data
        val updatedData = if (existing != null && existing.isWakeAlarm && data.isWakeAlarm) {
            normalizedData.copy(
                hour = existing.hour, minute = existing.minute, isAM = existing.isAM,
                applyChangesToNextTriggerOnly = false,
                nextTriggerOverride = data.copy(repeatDaily = false, applyChangesToNextTriggerOnly = false, nextTriggerOverride = null)
            )
        } else if (
            existing?.repeatDaily == true &&
            normalizedData.repeatDaily &&
            normalizedData.applyChangesToNextTriggerOnly
        ) {
            existing.withNextTriggerOverride(data)
        } else {
            normalizedData.copy(id = id, nextTriggerOverride = null)
        }
        val updatedAlarms = alarms.map { if (it.id == id) updatedData else it }
        alarms = updatedAlarms
        saveAlarms(updatedAlarms)
        mainViewModel.addLog("[Wake alarm $id] Settings saved: enabled=${updatedData.enabled}, wake=${updatedData.isWakeAlarm}, daily=${updatedData.repeatDaily}, nextOnly=${updatedData.nextTriggerOverride != null}", LogType.INFO)
        if (updatedData.enabled) {
            AlarmScheduler.scheduleAlarm(context, id, updatedData, config)
        } else {
            AlarmScheduler.cancelAlarm(context, id)
        }
    }

    fun updateRepeatChangeScope(id: Int, nextTriggerOnly: Boolean) {
        val updated = alarms.map { alarm ->
            if (alarm.id == id) alarm.copy(applyChangesToNextTriggerOnly = nextTriggerOnly) else alarm
        }
        alarms = updated
        saveAlarms(updated)
    }

    fun dismissUpcomingAlarm(alarm: AlarmData) {
        AlarmScheduler.cancelAlarm(context, alarm.id)
        val updated = if (alarm.isWakeAlarm) {
            val nextDay = Calendar.getInstance().apply {
                timeInMillis = com.omni.sync.utils.WakeAlarmActions.nextTrigger(alarm, System.currentTimeMillis())
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val target = alarm.copy(nextTriggerOverride = null)
            target.withNextTriggerOverride(target.copy(scheduledDate = nextDay.timeInMillis))
        } else if (alarm.repeatDaily) {
            alarm.copy(nextTriggerOverride = null)
        } else {
            alarm.copy(enabled = false, nextTriggerOverride = null)
        }
        alarms = alarms.map { if (it.id == alarm.id) updated else it }
        saveAlarms(alarms)
        if (updated.enabled) {
            AlarmScheduler.scheduleAlarm(context, updated.id, updated, config, forceNextDay = !alarm.isWakeAlarm)
        }
    }

    val isRinging by AlarmService.isRinging.collectAsState()
    val isSnoozing by AlarmService.isSnoozing.collectAsState()

    // Use the actual triggered alarm ID if ringing or snoozing
    val activeAlarmId = if (isRinging || isSnoozing) {
        AlarmService.lastTriggeredAlarmId
    } else {
        alarms.firstOrNull { it.enabled }?.id ?: 0
    }
    val activeAlarm = alarms.firstOrNull { it.id == activeAlarmId }
    val isActiveAlarmRepeating = activeAlarm?.repeatDaily ?: AlarmService.lastTriggeredAlarmRepeating

    val availableAlarmSounds = listOf(
        "angel" to "Angel",
        "chime" to "Chime",
        "energy" to "Energy",
        "gentle" to "Gentle",
        "morning" to "Morning",
        "water1" to "Water 1",
        "water2" to "Water 2"
    )
    
    // 24 Hour Format for Display
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    fun playPreview(soundId: String) {
        previewJob?.cancel()
        if (mediaPlayer.isPlaying) mediaPlayer.stop()
        mediaPlayer.reset()
        
        previewJob = scope.launch {
            try {
                val resId = context.resources.getIdentifier(soundId, "raw", context.packageName)
                if (resId != 0) {
                    val uri = Uri.parse("android.resource://${context.packageName}/$resId")
                    mediaPlayer.setDataSource(context, uri)
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    delay(5000)
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.stop()
                        mediaPlayer.prepare() 
                        mediaPlayer.seekTo(0)
                    }
                } else {
                     val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                     mediaPlayer.setDataSource(context, uri)
                     mediaPlayer.prepare()
                     mediaPlayer.start()
                     delay(5000)
                     if (mediaPlayer.isPlaying) mediaPlayer.stop()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // Refresh alarms from prefs when ringing/snoozing state changes to false
    // This ensures the UI updates even if the SharedPreferences listener missed an update
    LaunchedEffect(isRinging, isSnoozing) {
        if (!isRinging && !isSnoozing) {
            alarms = AlarmStore.load(prefs, gson)
        }
    }
    
    if (showStatistics) {
        val sessions = com.omni.sync.utils.AlarmStatistics.load(context)
        AlertDialog(
            onDismissRequest = { showStatistics = false },
            title = { Text("Alarm 1 statistics") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (sessions.isEmpty()) Text("No dismissed alarms yet")
                    sessions.reversed().forEach { session ->
                        Text("${SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(session.firstRing))}: ${session.rings} rings · ${(session.dismissedAt - session.firstRing) / 60_000} min until dismissal")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStatistics = false }) { Text("Close") } }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alarm") },
                actions = { TextButton(onClick = { showStatistics = true }) { Text("Statistics") } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Dismiss Button when ringing or snoozing - PINNED AT TOP
            if (isRinging || isSnoozing) {
                val dismissText = activeAlarm?.dismissText
                
                Column(
                    modifier = Modifier.padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!dismissText.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = dismissText,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Button(
                        onClick = { 
                            // Try to find the actual ringing alarm from service if possible, 
                            // or fallback to the one enabled in UI
                            AlarmService.stopAlarm(context, activeAlarmId, isActiveAlarmRepeating)
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isRinging) "DISMISS ALARM" else "CANCEL SNOOZE", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }

            val wakeAlarms = alarms.filter { it.enabled && it.isWakeAlarm }
            if (wakeAlarms.isNotEmpty() && !isRinging) {
                Button(
                    onClick = { com.omni.sync.utils.WakeAlarmActions.stillAwake(context) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) { Text("STILL AWAKE", style = MaterialTheme.typography.headlineSmall) }

                val todayWakeAlarm = wakeAlarms.firstOrNull { alarm ->
                    val trigger = Calendar.getInstance().apply {
                        timeInMillis = com.omni.sync.utils.WakeAlarmActions.nextTrigger(alarm, currentTime)
                    }
                    val today = Calendar.getInstance().apply { timeInMillis = currentTime }
                    trigger.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        trigger.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                }
                if (todayWakeAlarm != null) {
                    Button(
                        onClick = { dismissUpcomingAlarm(todayWakeAlarm) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Default.AlarmOff, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("DISMISS TODAY", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(if (isRinging || isSnoozing) 0.dp else 16.dp))
                
                alarms.forEachIndexed { index, storedAlarm ->
                    val alarm = storedAlarm.resolvedForNextTrigger()
                    key(alarm.id) {
                        AlarmCard(
                            alarmNumber = index + 1,
                            alarm = alarm,
                            targetAlarm = storedAlarm,
                            onAlarmChange = { updateSchedule(alarm.id, it) },
                            onRepeatChangeScopeChange = { updateRepeatChangeScope(alarm.id, it) },
                            onDelete = {
                                AlarmScheduler.cancelAlarm(context, alarm.id)
                                val updatedAlarms = alarms.filterNot { it.id == alarm.id }
                                alarms = updatedAlarms
                                saveAlarms(updatedAlarms)
                            },
                            availableSounds = availableAlarmSounds,
                            timeFormat = timeFormat,
                            onShowTimePicker = { editingTarget = false; showTimePicker = alarm.id },
                            onShowTargetPicker = { editingTarget = true; showTimePicker = alarm.id },
                            onShowDatePicker = { showDatePicker = alarm.id },
                            onShowSoundPicker = { showSoundPicker = alarm.id },
                            hideOptionsWhenDisabled = index > 0,
                            macros = appConfig.macros,
                            quickActions = appConfig.notificationActions.ifEmpty {
                                mainViewModel.configManager.getDefaultNotificationActions()
                            }
                        )
                    }
                }

                if (omniTimeSensitive.isNotEmpty()) {
                    Text(
                        "Omni reminders",
                        style = MaterialTheme.typography.titleMedium
                    )
                    omniTimeSensitive.forEach { reminder ->
                        key("omni-reminder-${reminder.id}") {
                            OmniReminderCard(
                                reminder = reminder,
                                onDone = { signalRClient.completeReminder(reminder.id) },
                                onDismiss = { signalRClient.dismissReminder(reminder.id) },
                                onSnooze = { signalRClient.snoozeReminder(reminder.id, 10) },
                                onPostpone = {
                                    val tomorrow = OffsetDateTime.now()
                                        .plusDays(1)
                                        .withSecond(0)
                                        .withNano(0)
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                    signalRClient.postponeReminder(reminder.id, tomorrow)
                                }
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val id = AlarmStore.nextId(alarms)
                        val updatedAlarms = alarms + AlarmData(id = id)
                        alarms = updatedAlarms
                        saveAlarms(updatedAlarms)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Alarm")
                }
                
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Settings")
                            Text("Global Gradual Settings", style = MaterialTheme.typography.titleMedium)
                        }
                        HorizontalDivider()
                        
                        AdjustableSettingRow("Initial Volume: ${config.initialVolume}%", config.initialVolume, { config = config.copy(initialVolume = it); saveAlarms() }, 0, 100)
                        AdjustableSettingRow("Alarm Duration: ${config.alarmDuration}s", config.alarmDuration, { config = config.copy(alarmDuration = it); saveAlarms() }, 1, 300)
                        AdjustableSettingRow("Auto-Snooze: ${config.snoozeDuration}m", config.snoozeDuration, { config = config.copy(snoozeDuration = it); saveAlarms() }, 1, 60)
                        AdjustableSettingRow("Volume Increase: +${config.volumeIncrement}%", config.volumeIncrement, { config = config.copy(volumeIncrement = it); saveAlarms() }, 1, 20)
                        AdjustableSettingRow("Max Repetitions: ${config.maxRepetitions}", config.maxRepetitions, { config = config.copy(maxRepetitions = it); saveAlarms() }, 1, 20)
                    }
                }

                if (omniTasks.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "Tasks",
                        style = MaterialTheme.typography.titleMedium
                    )
                    omniTasks.forEach { reminder ->
                        key("omni-task-${reminder.id}") {
                            OmniReminderCard(
                                reminder = reminder,
                                onDone = { signalRClient.completeReminder(reminder.id) },
                                onDismiss = { signalRClient.dismissReminder(reminder.id) },
                                onSnooze = { signalRClient.snoozeReminder(reminder.id, 10) },
                                onPostpone = {
                                    val tomorrow = OffsetDateTime.now()
                                        .plusDays(1)
                                        .withSecond(0)
                                        .withNano(0)
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                    signalRClient.postponeReminder(reminder.id, tomorrow)
                                }
                            )
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Calendar alert tiers", style = MaterialTheme.typography.titleSmall)
                        Text("Omni task — a task notification; no ringing alarm.", style = MaterialTheme.typography.bodySmall)
                        Text("Omni reminder — gradual alarm: starts quiet and repeats louder until dismissed.", style = MaterialTheme.typography.bodySmall)
                        Text("Omni alarm — immediate full-volume alarm.", style = MaterialTheme.typography.bodySmall)
                        Text("Temporary trial: every calendar event is treated as an Omni reminder, including all-day events. This requires the updated Hub to be running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // --- Permissions Check ---
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (!android.provider.Settings.canDrawOverlays(context)) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Permission Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "To force open the alarm screen when it triggers, please grant 'Display over other apps' permission.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Grant Permission")
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    
    showTimePicker?.let { alarmId ->
        val storedAlarm = alarms.firstOrNull { it.id == alarmId }
        val currentAlarm = if (editingTarget) storedAlarm else storedAlarm?.resolvedForNextTrigger()
        if (currentAlarm == null) {
            showTimePicker = null
            return@let
        }
        
        // Convert internal 12h to 24h for initialization
        val initHour = if (currentAlarm.isAM) {
             if (currentAlarm.hour == 12) 0 else currentAlarm.hour
        } else {
             if (currentAlarm.hour == 12) 12 else currentAlarm.hour + 12
        }

        MaterialTimePickerDialogWrapper(
            initialHour = initHour,
            initialMinute = currentAlarm.minute,
            onDismiss = { showTimePicker = null },
            onConfirm = { hour24, minute ->
                // Convert selected 24h back to internal 12h
                val isAM = hour24 < 12
                val hour12 = if (hour24 == 0 || hour24 == 12) 12 else hour24 % 12
                
                val updated = currentAlarm.copy(
                    hour = hour12, 
                    minute = minute, 
                    isAM = isAM, 
                    quickToggleMode = 0,
                    enabled = true // Auto-enable on time change
                )
                
                if (editingTarget && storedAlarm != null) {
                    val target = updated.copy(applyChangesToNextTriggerOnly = false, repeatDaily = true, scheduledDate = null,
                        nextTriggerOverride = storedAlarm.nextTriggerOverride)
                    alarms = alarms.map { if (it.id == alarmId) target else it }
                    saveAlarms(alarms)
                    AlarmScheduler.scheduleAlarm(context, alarmId, target, config)
                } else {
                    updateSchedule(alarmId, updated.copy(applyChangesToNextTriggerOnly = currentAlarm.isWakeAlarm || currentAlarm.applyChangesToNextTriggerOnly))
                }
                showTimePicker = null
            }
        )
    }
    
    showDatePicker?.let { alarmId ->
        val currentAlarm = alarms.firstOrNull { it.id == alarmId }
        if (currentAlarm == null) {
            showDatePicker = null
            return@let
        }
        val cal = Calendar.getInstance()
        if (currentAlarm.scheduledDate != null) cal.timeInMillis = currentAlarm.scheduledDate!!
        
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                val newCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    // Keep existing time if possible, or default to alarm time
                    set(Calendar.HOUR_OF_DAY, if (currentAlarm.isAM) (if (currentAlarm.hour == 12) 0 else currentAlarm.hour) else (if (currentAlarm.hour == 12) 12 else currentAlarm.hour + 12))
                    set(Calendar.MINUTE, currentAlarm.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val updated = currentAlarm.copy(
                    scheduledDate = newCal.timeInMillis,
                    repeatDaily = currentAlarm.repeatDaily && currentAlarm.applyChangesToNextTriggerOnly,
                    enabled = true
                )
                updateSchedule(alarmId, updated)
                showDatePicker = null
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            setOnDismissListener { showDatePicker = null }
            show()
        }
    }
    
    showSoundPicker?.let { alarmId ->
        val currentAlarm = alarms.firstOrNull { it.id == alarmId }?.resolvedForNextTrigger()
        if (currentAlarm == null) {
            showSoundPicker = null
            return@let
        }
        AlertDialog(
            onDismissRequest = { 
                showSoundPicker = null 
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
            },
            title = { Text("Select Alarm Sound") },
            text = {
                Column {
                    availableAlarmSounds.forEach { (soundId, soundName) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentAlarm.soundId == soundId,
                                onClick = {
                                    val updated = currentAlarm.copy(soundId = soundId)
                                    updateSchedule(alarmId, updated)
                                    playPreview(soundId)
                                }
                            )
                            Text(text = soundName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    showSoundPicker = null 
                    if (mediaPlayer.isPlaying) mediaPlayer.stop()
                }) { Text("Done") }
            }
        )
    }
}

@Composable
fun AlarmCard(
    alarmNumber: Int,
    alarm: AlarmData,
    targetAlarm: AlarmData,
    onAlarmChange: (AlarmData) -> Unit,
    onRepeatChangeScopeChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    availableSounds: List<Pair<String, String>>,
    timeFormat: SimpleDateFormat,
    onShowTimePicker: () -> Unit,
    onShowTargetPicker: () -> Unit,
    onShowDatePicker: () -> Unit,
    onShowSoundPicker: () -> Unit,
    hideOptionsWhenDisabled: Boolean,
    macros: List<com.omni.sync.data.model.Macro>,
    quickActions: List<com.omni.sync.data.model.NotificationAction>
) {
    val context = LocalContext.current
    var hoursClicked by remember { mutableStateOf(false) }
    var latestPicker by remember { mutableStateOf(false) }
    var accessibilityHelpUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(accessibilityHelpUntil) {
        if (accessibilityHelpUntil != 0L) {
            delay((accessibilityHelpUntil - System.currentTimeMillis()).coerceAtLeast(0))
            accessibilityHelpUntil = 0L
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    if (latestPicker) {
        val minutes = alarm.latestWakeMinutes ?: 720
        MaterialTimePickerDialogWrapper(minutes / 60, minutes % 60,
            onDismiss = { latestPicker = false },
            onConfirm = { h, m -> onAlarmChange(alarm.copy(latestWakeMinutes = h * 60 + m)); latestPicker = false })
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (alarm.enabled) Icons.Default.Alarm else Icons.Default.AlarmOff,
                        contentDescription = null,
                        tint = if (alarm.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text("Alarm $alarmNumber", style = MaterialTheme.typography.titleLarge)
                }
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      IconButton(onClick = onDelete) {
                          Icon(Icons.Default.Delete, "Delete Alarm")
                      }
                      Switch(checked = alarm.enabled, onCheckedChange = { onAlarmChange(alarm.copy(enabled = it)) })
                  }
            }
            
            val showContent = !hideOptionsWhenDisabled || alarm.enabled
            AnimatedVisibility(visible = showContent, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Specific Date")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onShowDatePicker) {
                                val dateText = if (alarm.scheduledDate != null) {
                                    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(alarm.scheduledDate!!))
                                } else "Any Day"
                                Text(dateText)
                            }
                            if (alarm.scheduledDate != null) {
                                IconButton(onClick = { onAlarmChange(alarm.copy(scheduledDate = null)) }) {
                                    Icon(Icons.Default.Close, "Clear Date", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val newMode = if (!hoursClicked) 1 else (alarm.quickToggleMode + 1) % 4
                            hoursClicked = true
                            val cal = Calendar.getInstance()
                            when (newMode) {
                                1 -> cal.add(Calendar.HOUR_OF_DAY, 8).also { cal.add(Calendar.MINUTE, 30) }
                                2 -> cal.add(Calendar.HOUR_OF_DAY, 8).also { cal.add(Calendar.MINUTE, 45) }
                                3 -> cal.add(Calendar.HOUR_OF_DAY, 9)
                            }
                            val hour12 = if (newMode == 0) alarm.hour else {
                                val h24 = cal.get(Calendar.HOUR_OF_DAY)
                                if (h24 == 0) 12 else if (h24 > 12) h24 - 12 else h24
                            }
                            val isAM = if (newMode == 0) alarm.isAM else cal.get(Calendar.AM_PM) == Calendar.AM
                            onAlarmChange(alarm.copy(
                                quickToggleMode = newMode, 
                                hour = hour12, 
                                minute = if (newMode == 0) alarm.minute else cal.get(Calendar.MINUTE), 
                                isAM = isAM,
                                scheduledDate = if (newMode == 0) alarm.scheduledDate else null,
                                enabled = true
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        val text = when (alarm.quickToggleMode) {
                            0 -> "Off"
                            1 -> "8h 30m"
                            2 -> "8h 45m"
                            3 -> "9h"
                            else -> "Off"
                        }
                        Text("Hours from now: $text")
                    }

                    // Display Time (24h)
                    val hour24 = if (alarm.isAM) {
                        if (alarm.hour == 12) 0 else alarm.hour
                    } else {
                        if (alarm.hour == 12) 12 else alarm.hour + 12
                    }

                    val displayCal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour24)
                        set(Calendar.MINUTE, alarm.minute)
                        if (alarm.isWakeAlarm) timeInMillis = com.omni.sync.utils.WakeAlarmActions.nextTrigger(targetAlarm, now)
                    }
                    
                    Button(
                        onClick = onShowTimePicker,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (alarm.isWakeAlarm) Text("Today's wake time")
                            Text(timeFormat.format(displayCal.time), style = MaterialTheme.typography.headlineMedium)
                            if (alarm.isWakeAlarm) Text(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(displayCal.time), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Wake alarm")
                        Switch(alarm.isWakeAlarm, onCheckedChange = { onAlarmChange(alarm.copy(isWakeAlarm = it, applyChangesToNextTriggerOnly = false)) })
                    }
                    if (alarm.isWakeAlarm) {
                        OutlinedButton(onClick = onShowTargetPicker, modifier = Modifier.fillMaxWidth()) {
                            val targetHour = targetAlarm.hour % 12 + if (targetAlarm.isAM) 0 else 12
                            Text("Target wake time: %02d:%02d · daily".format(targetHour, targetAlarm.minute))
                        }
                        OutlinedButton(onClick = { latestPicker = true }, modifier = Modifier.fillMaxWidth()) {
                            val latest = alarm.latestWakeMinutes ?: 720
                            Text("Latest wake time: %02d:%02d".format(latest / 60, latest % 60))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto Still awake", modifier = Modifier.weight(1f))
                            Switch(alarm.autoStillAwake, onCheckedChange = {
                                accessibilityHelpUntil = if (it) System.currentTimeMillis() + 10_000 else 0L
                                onAlarmChange(alarm.copy(autoStillAwake = it))
                            })
                        }
                        if (accessibilityHelpUntil != 0L) {
                            val service = remember(now) { com.omni.sync.service.OmniAccessibilityService.getInstance() }
                            Text(if (service != null) "Phone activity detection: connected" else "Phone activity detection: not connected", style = MaterialTheme.typography.bodySmall)
                            Text("Enable OmniSync in Android Accessibility → Installed apps.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open accessibility settings") }
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Use Gradual Wake")
                        Switch(checked = alarm.useGradual, onCheckedChange = { onAlarmChange(alarm.copy(useGradual = it)) })
                    }
                    
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                      ) {
                          Text("Repeat Daily")
                        Switch(
                            checked = alarm.repeatDaily,
                            enabled = !alarm.isWakeAlarm,
                            onCheckedChange = {
                                onAlarmChange(alarm.copy(repeatDaily = it, scheduledDate = if (it) null else alarm.scheduledDate))
                            }
                          )
                      }

                      if (alarm.repeatDaily && !alarm.isWakeAlarm) {
                          Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.SpaceBetween,
                              verticalAlignment = Alignment.CenterVertically
                          ) {
                              Column(modifier = Modifier.weight(1f)) {
                                  Text("Change next trigger only")
                                  Text(
                                      if (alarm.applyChangesToNextTriggerOnly) "The next occurrence only" else "All daily occurrences",
                                      style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant
                                  )
                              }
                              Switch(
                                  checked = alarm.applyChangesToNextTriggerOnly,
                                  onCheckedChange = onRepeatChangeScopeChange
                              )
                          }
                      }

                      OutlinedButton(onClick = onShowSoundPicker, modifier = Modifier.fillMaxWidth()) {
                        Text("Sound: ${availableSounds.find { it.first == alarm.soundId }?.second ?: "Select"}")
                    }

                    HorizontalDivider()
                    Text("Automation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    
                    AutomationActionPicker(
                        label = "On Trigger",
                        selectedMacroScript = alarm.macroOnTrigger,
                        selectedActionId = alarm.quickActionOnTriggerId,
                        macros = macros,
                        actions = quickActions,
                        dismissDelaySeconds = null,
                        onSave = { macroScript, actionId, _ ->
                            onAlarmChange(
                                alarm.copy(
                                    macroOnTrigger = macroScript,
                                    quickActionOnTriggerId = actionId
                                )
                            )
                        }
                    )

                    AutomationActionPicker(
                        label = "On Dismiss",
                        selectedMacroScript = alarm.macroOnDismiss,
                        selectedActionId = alarm.quickActionOnDismissId,
                        macros = macros,
                        actions = quickActions,
                        dismissDelaySeconds = alarm.dismissActionDelaySeconds,
                        onSave = { macroScript, actionId, delaySeconds ->
                            onAlarmChange(
                                alarm.copy(
                                    macroOnDismiss = macroScript,
                                    quickActionOnDismissId = actionId,
                                    dismissActionDelaySeconds = delaySeconds
                                )
                            )
                        }
                    )

                    OutlinedTextField(
                        value = alarm.dismissText ?: "",
                        onValueChange = { onAlarmChange(alarm.copy(dismissText = it)) },
                        label = { Text("Custom Dismiss Message") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. Leave for airport") }
                    )
                }
            }
        }
    }
}

private enum class AutomationActionKind {
    NONE,
    MACRO,
    QUICK_ACTION
}

private enum class DelayUnit(val multiplierSeconds: Int) {
    SECONDS(1),
    MINUTES(60)
}

@Composable
private fun AutomationActionPicker(
    label: String,
    selectedMacroScript: String?,
    selectedActionId: String?,
    macros: List<com.omni.sync.data.model.Macro>,
    actions: List<com.omni.sync.data.model.NotificationAction>,
    dismissDelaySeconds: Int?,
    onSave: (macroScript: String?, actionId: String?, delaySeconds: Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedMacro = macros.find { it.script == selectedMacroScript }
    val selectedAction = actions.find { it.id == selectedActionId }
    val selectionName = selectedMacro?.name ?: selectedAction?.label ?: "None"
    val delaySummary = dismissDelaySeconds
        ?.takeIf { it > 0 }
        ?.let { " · ${formatAutomationDelay(it)}" }
        .orEmpty()

    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = selectionName + delaySummary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }

    if (!showDialog) return

    var pendingKind by remember {
        mutableStateOf(
            when {
                selectedMacroScript != null -> AutomationActionKind.MACRO
                selectedActionId != null -> AutomationActionKind.QUICK_ACTION
                else -> AutomationActionKind.NONE
            }
        )
    }
    var pendingMacroScript by remember { mutableStateOf(selectedMacroScript) }
    var pendingActionId by remember { mutableStateOf(selectedActionId) }
    val initialDelay = dismissDelaySeconds ?: 0
    var delayUnit by remember {
        mutableStateOf(
            if (initialDelay >= 60 && initialDelay % 60 == 0) DelayUnit.MINUTES
            else DelayUnit.SECONDS
        )
    }
    var delayAmount by remember {
        mutableStateOf(
            (initialDelay / if (initialDelay >= 60 && initialDelay % 60 == 0) 60 else 1).toString()
        )
    }
    val parsedDelayAmount = delayAmount.toLongOrNull()
    val calculatedDelaySeconds = parsedDelayAmount?.times(delayUnit.multiplierSeconds)
    val delayIsValid = dismissDelaySeconds == null ||
        (calculatedDelaySeconds != null && calculatedDelaySeconds in 0..86_400)

    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(label) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AutomationChoiceRow(
                    label = "None",
                    selected = pendingKind == AutomationActionKind.NONE,
                    onClick = {
                        pendingKind = AutomationActionKind.NONE
                        pendingMacroScript = null
                        pendingActionId = null
                    }
                )

                if (macros.isNotEmpty()) {
                    Text(
                        "Macros",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    macros.forEach { macro ->
                        AutomationChoiceRow(
                            label = macro.name,
                            selected = pendingKind == AutomationActionKind.MACRO &&
                                pendingMacroScript == macro.script,
                            onClick = {
                                pendingKind = AutomationActionKind.MACRO
                                pendingMacroScript = macro.script
                                pendingActionId = null
                            }
                        )
                    }
                }

                if (actions.isNotEmpty()) {
                    Text(
                        "Quick actions",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    actions.forEach { action ->
                        AutomationChoiceRow(
                            label = action.label,
                            selected = pendingKind == AutomationActionKind.QUICK_ACTION &&
                                pendingActionId == action.id,
                            onClick = {
                                pendingKind = AutomationActionKind.QUICK_ACTION
                                pendingActionId = action.id
                                pendingMacroScript = null
                            }
                        )
                    }
                }

                if (dismissDelaySeconds != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Delay after dismissal", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = delayAmount,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) delayAmount = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Delay") },
                        isError = !delayIsValid,
                        supportingText = {
                            Text(if (delayIsValid) "Use 0 for no delay" else "Enter a delay up to 24 hours")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = delayUnit == DelayUnit.SECONDS,
                            onClick = { delayUnit = DelayUnit.SECONDS },
                            label = { Text("Seconds") }
                        )
                        FilterChip(
                            selected = delayUnit == DelayUnit.MINUTES,
                            onClick = { delayUnit = DelayUnit.MINUTES },
                            label = { Text("Minutes") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = delayIsValid,
                onClick = {
                    onSave(
                        pendingMacroScript.takeIf { pendingKind == AutomationActionKind.MACRO },
                        pendingActionId.takeIf { pendingKind == AutomationActionKind.QUICK_ACTION },
                        calculatedDelaySeconds?.toInt() ?: 0
                    )
                    showDialog = false
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) { Text("Cancel") }
        }
    )
}

@Composable
private fun OmniReminderCard(
    reminder: ReminderData,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onPostpone: () -> Unit
) {
    val kindLabel = when (reminder.kind) {
        2 -> "Alarm"
        1 -> "Reminder"
        else -> "Task"
    }
    val stateLabel = when (reminder.state) {
        1 -> "Due"
        2 -> "Postponed"
        else -> null
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(kindLabel)
                            append(" · ")
                            append(formatReminderDue(reminder.nextFireAt ?: reminder.dueAt))
                            stateLabel?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (reminder.source == 1) {
                    Text(
                        "Google",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (reminder.kind == 0) {
                    Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                        Text("Done")
                    }
                    OutlinedButton(onClick = onPostpone, modifier = Modifier.weight(1f)) {
                        Text("Tomorrow")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Remove")
                    }
                } else {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Dismiss")
                    }
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f)) {
                        Text("Snooze 10m")
                    }
                }
            }
        }
    }
}

private fun formatReminderDue(value: String): String {
    return runCatching {
        OffsetDateTime.parse(value)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
    }.getOrElse {
        value
    }
}

@Composable
private fun AutomationChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun formatAutomationDelay(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600}h delay"
    seconds % 60 == 0 -> "${seconds / 60}m delay"
    else -> "${seconds}s delay"
}

@Composable
fun AdjustableSettingRow(label: String, value: Int, onValueChange: (Int) -> Unit, minValue: Int, maxValue: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(onClick = { if (value > minValue) onValueChange(value - 1) }, modifier = Modifier.size(32.dp)) { Text("-") }
            FilledIconButton(onClick = { if (value < maxValue) onValueChange(value + 1) }, modifier = Modifier.size(32.dp)) { Text("+") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialTimePickerDialogWrapper(initialHour: Int, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } }
    )
}
