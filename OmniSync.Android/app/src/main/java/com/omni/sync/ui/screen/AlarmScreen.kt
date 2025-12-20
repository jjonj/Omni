package com.omni.sync.ui.screen

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omni.sync.utils.AlarmScheduler
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class AlarmData(
    var enabled: Boolean = false,
    var hour: Int = 9,
    var minute: Int = 0,
    var isAM: Boolean = true,
    var quickToggleMode: Int = 0,
    var repeatDaily: Boolean = false,
    var useGradual: Boolean = true,
    var soundId: String = "gentle"
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    var alarm1 by remember { mutableStateOf(AlarmData(enabled = false, hour = 8, minute = 30, isAM = true)) }
    var alarm2 by remember { mutableStateOf(AlarmData(enabled = false, hour = 9, minute = 0, isAM = true)) }
    var config by remember { mutableStateOf(GradualConfig()) }
    
    var showTimePicker by remember { mutableStateOf<Int?>(null) } 
    var showSoundPicker by remember { mutableStateOf<Int?>(null) }
    
    val mediaPlayer = remember { MediaPlayer() }
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            previewJob?.cancel()
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.release()
        }
    }

    fun updateSchedule(id: Int, data: AlarmData) {
        if (data.enabled) {
            AlarmScheduler.scheduleAlarm(context, id, data, config)
        } else {
            AlarmScheduler.cancelAlarm(context, id)
        }
    }

    val availableAlarmSounds = listOf(
        "angel" to "Angel",
        "chime" to "Chime",
        "energy" to "Energy",
        "gentle" to "Gentle",
        "morning" to "Morning",
        "water1" to "Water 1",
        "water2" to "Water 2"
    )
    
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AlarmCard(
                alarmNumber = 1,
                alarm = alarm1,
                onAlarmChange = { alarm1 = it; updateSchedule(1, it) },
                availableSounds = availableAlarmSounds,
                timeFormat = timeFormat,
                onShowTimePicker = { showTimePicker = 1 },
                onShowSoundPicker = { showSoundPicker = 1 },
                hideOptionsWhenDisabled = false
            )
            
            AlarmCard(
                alarmNumber = 2,
                alarm = alarm2,
                onAlarmChange = { alarm2 = it; updateSchedule(2, it) },
                availableSounds = availableAlarmSounds,
                timeFormat = timeFormat,
                onShowTimePicker = { showTimePicker = 2 },
                onShowSoundPicker = { showSoundPicker = 2 },
                hideOptionsWhenDisabled = true
            )
            
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
                    
                    AdjustableSettingRow("Initial Volume: ${config.initialVolume}%", config.initialVolume, { config = config.copy(initialVolume = it) }, 0, 100)
                    AdjustableSettingRow("Alarm Duration: ${config.alarmDuration}s", config.alarmDuration, { config = config.copy(alarmDuration = it) }, 1, 300)
                    AdjustableSettingRow("Auto-Snooze: ${config.snoozeDuration}m", config.snoozeDuration, { config = config.copy(snoozeDuration = it) }, 1, 60)
                    AdjustableSettingRow("Volume Increase: +${config.volumeIncrement}%", config.volumeIncrement, { config = config.copy(volumeIncrement = it) }, 1, 20)
                    AdjustableSettingRow("Max Repetitions: ${config.maxRepetitions}", config.maxRepetitions, { config = config.copy(maxRepetitions = it) }, 1, 20)
                }
            }
        }
    }
    
    showTimePicker?.let { alarmNum ->
        val currentAlarm = if (alarmNum == 1) alarm1 else alarm2
        
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
                    quickToggleMode = 0
                )
                
                if (alarmNum == 1) { alarm1 = updated; updateSchedule(1, updated) }
                else { alarm2 = updated; updateSchedule(2, updated) }
                showTimePicker = null
            }
        )
    }
    
    showSoundPicker?.let { alarmNum ->
        val currentAlarm = if (alarmNum == 1) alarm1 else alarm2
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
                                    if (alarmNum == 1) { alarm1 = updated; updateSchedule(1, updated) }
                                    else { alarm2 = updated; updateSchedule(2, updated) }
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
    availableSounds: List<Pair<String, String>>,
    timeFormat: SimpleDateFormat,
    onShowTimePicker: () -> Unit,
    onShowSoundPicker: () -> Unit,
    hideOptionsWhenDisabled: Boolean
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
                Switch(checked = alarm.enabled, onCheckedChange = { onAlarmChange(alarm.copy(enabled = it)) })
            }
            
            val showContent = !hideOptionsWhenDisabled || alarm.enabled
            AnimatedVisibility(visible = showContent, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    
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
                            onAlarmChange(alarm.copy(quickToggleMode = newMode, hour = hour12, minute = if (newMode == 0) alarm.minute else cal.get(Calendar.MINUTE), isAM = isAM))
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

                    // Display Time (24h calculation fixed)
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
                        Switch(checked = alarm.repeatDaily, onCheckedChange = { onAlarmChange(alarm.copy(repeatDaily = it)) })
                    }
                    
                    OutlinedButton(onClick = onShowSoundPicker, modifier = Modifier.fillMaxWidth()) {
                        Text("Sound: ${availableSounds.find { it.first == alarm.soundId }?.second ?: "Select"}")
                    }
                }
            }
        }
    }
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