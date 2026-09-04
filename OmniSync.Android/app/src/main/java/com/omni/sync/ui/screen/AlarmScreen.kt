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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.omni.sync.service.AlarmService
import com.omni.sync.utils.AlarmScheduler
import com.omni.sync.utils.AlarmStore
import com.omni.sync.viewmodel.MainViewModel
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class AlarmData(
    var id: Int = 0,
    var enabled: Boolean = false,
    var hour: Int = 9,
    var minute: Int = 0,
    var isAM: Boolean = true,
    var quickToggleMode: Int = 0,
    var repeatDaily: Boolean = false,
    var useGradual: Boolean = true,
    var soundId: String = "gentle",
    var macroOnTrigger: String? = null,
    var macroOnDismiss: String? = null,
    var quickActionOnTriggerId: String? = null,
    var quickActionOnDismissId: String? = null,
    var dismissActionDelaySeconds: Int = 0,
    var scheduledDate: Long? = null,
    var dismissText: String? = null
)

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
    val context = LocalContext.current
    val appConfig by mainViewModel.appConfig.collectAsState()
    val prefs = remember { context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE) }
    val gson = remember { com.google.gson.Gson() }

    var alarms by remember { mutableStateOf(AlarmStore.load(prefs, gson)) }
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
    var showDatePicker by remember { mutableStateOf<Int?>(null) }
    var showSoundPicker by remember { mutableStateOf<Int?>(null) }
    
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
        val updatedAlarms = alarms.map { if (it.id == id) data.copy(id = id) else it }
        alarms = updatedAlarms
        saveAlarms(updatedAlarms)
        if (data.enabled) {
            AlarmScheduler.scheduleAlarm(context, id, data.copy(id = id), config)
            mainViewModel.startSleep()
        } else {
            AlarmScheduler.cancelAlarm(context, id)
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alarm") },
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
                            mainViewModel.resetSleep()
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isRinging) "DISMISS ALARM" else "CANCEL SNOOZE", style = MaterialTheme.typography.headlineSmall)
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
                
                alarms.forEachIndexed { index, alarm ->
                    key(alarm.id) {
                        AlarmCard(
                            alarmNumber = index + 1,
                            alarm = alarm,
                            onAlarmChange = { updateSchedule(alarm.id, it) },
                            onDelete = {
                                AlarmScheduler.cancelAlarm(context, alarm.id)
                                val updatedAlarms = alarms.filterNot { it.id == alarm.id }
                                alarms = updatedAlarms
                                saveAlarms(updatedAlarms)
                            },
                            availableSounds = availableAlarmSounds,
                            timeFormat = timeFormat,
                            onShowTimePicker = { showTimePicker = alarm.id },
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
        val currentAlarm = alarms.firstOrNull { it.id == alarmId }
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
                
                updateSchedule(alarmId, updated)
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
                    repeatDaily = false, // Auto-disable repeat if specific date set
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
        val currentAlarm = alarms.firstOrNull { it.id == alarmId }
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
    onAlarmChange: (AlarmData) -> Unit,
    onDelete: () -> Unit,
    availableSounds: List<Pair<String, String>>,
    timeFormat: SimpleDateFormat,
    onShowTimePicker: () -> Unit,
    onShowDatePicker: () -> Unit,
    onShowSoundPicker: () -> Unit,
    hideOptionsWhenDisabled: Boolean,
    macros: List<com.omni.sync.data.model.Macro>,
    quickActions: List<com.omni.sync.data.model.NotificationAction>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                            val newMode = (alarm.quickToggleMode + 1) % 4
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
                        modifier = Modifier.fillMaxWidth()
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
                    }
                    
                    Button(
                        onClick = onShowTimePicker,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(timeFormat.format(displayCal.time), style = MaterialTheme.typography.headlineMedium)
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
                            onCheckedChange = {
                                onAlarmChange(alarm.copy(repeatDaily = it, scheduledDate = if (it) null else alarm.scheduledDate))
                            }
                        )
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
