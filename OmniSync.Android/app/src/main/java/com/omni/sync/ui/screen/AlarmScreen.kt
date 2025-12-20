package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omni.sync.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    var alarmEnabled by remember { mutableStateOf(false) }
    var useQuickToggle by remember { mutableStateOf(false) }
    var selectedTime by remember { mutableStateOf(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
    }) }
    
    // Quick toggle options in hours from now
    var quickToggleHours by remember { mutableStateOf(8) }
    var quickToggleMinutes by remember { mutableStateOf(30) }
    
    // Alarm settings
    var useGradualAlarm by remember { mutableStateOf(true) }
    var repeatDaily by remember { mutableStateOf(false) }
    
    // Gradual alarm settings (from shared preferences or defaults)
    var initialVolume by remember { mutableStateOf(5) }
    var alarmDuration by remember { mutableStateOf(3) }
    var snoozeDuration by remember { mutableStateOf(10) }
    var volumeIncrement by remember { mutableStateOf(5) }
    var maxRepetitions by remember { mutableStateOf(6) }
    
    // Alarm sound selection
    var selectedAlarmSound by remember { mutableStateOf("gentle") }
    var showSoundPicker by remember { mutableStateOf(false) }
    
    val availableAlarmSounds = listOf(
        "angel" to "Angel",
        "chime" to "Chime",
        "energy" to "Energy",
        "gentle" to "Gentle",
        "morning" to "Morning",
        "water1" to "Water 1",
        "water2" to "Water 2"
    )
    
    // Show time picker dialog
    var showTimePicker by remember { mutableStateOf(false) }
    
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alarm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
            // Alarm enabled toggle
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (alarmEnabled) Icons.Default.Alarm else Icons.Default.AlarmOff,
                            contentDescription = "Alarm Status",
                            tint = if (alarmEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Alarm Active",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Switch(
                        checked = alarmEnabled,
                        onCheckedChange = { alarmEnabled = it }
                    )
                }
            }
            
            if (alarmEnabled) {
                // Time selection mode toggle
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Time Selection",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !useQuickToggle,
                                onClick = { useQuickToggle = false },
                                label = { Text("Specific Time") },
                                leadingIcon = if (!useQuickToggle) {
                                    { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = useQuickToggle,
                                onClick = { useQuickToggle = true },
                                label = { Text("Hours from Now") },
                                leadingIcon = if (useQuickToggle) {
                                    { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                        
                        if (useQuickToggle) {
                            // Quick toggle buttons
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Select time from now:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        Pair(8, 30),
                                        Pair(8, 45),
                                        Pair(9, 0)
                                    ).forEach { (hours, minutes) ->
                                        val isSelected = quickToggleHours == hours && quickToggleMinutes == minutes
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { 
                                                quickToggleHours = hours
                                                quickToggleMinutes = minutes
                                            },
                                            label = { Text("${hours}h ${minutes}m") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                
                                // Calculate and show actual alarm time
                                val calculatedTime = Calendar.getInstance().apply {
                                    add(Calendar.HOUR_OF_DAY, quickToggleHours)
                                    add(Calendar.MINUTE, quickToggleMinutes)
                                }
                                Text(
                                    text = "Alarm will ring at: ${timeFormat.format(calculatedTime.time)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            // Specific time selection
                            Button(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Schedule, "Select time", modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Set Time: ${timeFormat.format(selectedTime.time)}")
                            }
                        }
                    }
                }
                
                // Alarm type toggle
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.VolumeUp, "Gradual alarm")
                                Text(
                                    text = "Gradual Alarm",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Switch(
                                checked = useGradualAlarm,
                                onCheckedChange = { useGradualAlarm = it }
                            )
                        }
                        
                        Text(
                            text = if (useGradualAlarm) {
                                "Starts quiet and gradually increases volume with auto-snooze"
                            } else {
                                "Uses Android alarm volume, no auto-snooze"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Repeat daily toggle
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Repeat, "Repeat daily")
                            Text(
                                text = "Repeat Daily",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Switch(
                            checked = repeatDaily,
                            onCheckedChange = { repeatDaily = it }
                        )
                    }
                }
                
                // Alarm sound selection
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Alarm Sound",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Button(
                            onClick = { showSoundPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(availableAlarmSounds.find { it.first == selectedAlarmSound }?.second ?: "Select Sound")
                        }
                    }
                }
                
                // Gradual alarm settings (only show if gradual alarm is enabled)
                if (useGradualAlarm) {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Gradual Alarm Settings",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            // Initial volume
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Initial Volume: $initialVolume%")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(onClick = { if (initialVolume > 0) initialVolume-- }) {
                                        Text("-")
                                    }
                                    Button(onClick = { if (initialVolume < 100) initialVolume++ }) {
                                        Text("+")
                                    }
                                }
                            }
                            
                            // Alarm duration
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Alarm Duration: ${alarmDuration}s")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(onClick = { if (alarmDuration > 1) alarmDuration-- }) {
                                        Text("-")
                                    }
                                    Button(onClick = { if (alarmDuration < 60) alarmDuration++ }) {
                                        Text("+")
                                    }
                                }
                            }
                            
                            // Snooze duration
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Auto-Snooze: ${snoozeDuration}m")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(onClick = { if (snoozeDuration > 1) snoozeDuration-- }) {
                                        Text("-")
                                    }
                                    Button(onClick = { if (snoozeDuration < 60) snoozeDuration++ }) {
                                        Text("+")
                                    }
                                }
                            }
                            
                            // Volume increment
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Volume Increase: +${volumeIncrement}%")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(onClick = { if (volumeIncrement > 1) volumeIncrement-- }) {
                                        Text("-")
                                    }
                                    Button(onClick = { if (volumeIncrement < 20) volumeIncrement++ }) {
                                        Text("+")
                                    }
                                }
                            }
                            
                            // Max repetitions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Max Repetitions: $maxRepetitions")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(onClick = { if (maxRepetitions > 1) maxRepetitions-- }) {
                                        Text("-")
                                    }
                                    Button(onClick = { if (maxRepetitions < 20) maxRepetitions++ }) {
                                        Text("+")
                                    }
                                }
                            }
                            
                            Divider()
                            
                            Text(
                                text = "Summary: Alarm plays for ${alarmDuration}s at ${initialVolume}%, " +
                                      "then auto-snoozes for ${snoozeDuration}m and increases volume by ${volumeIncrement}% " +
                                      "up to ${maxRepetitions} times.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Time picker dialog
    if (showTimePicker) {
        // For now, we'll use a simple dialog with hour and minute selection
        // In a production app, you'd use a proper time picker dialog
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Set Alarm Time") },
            text = {
                Column {
                    Text("Selected time: ${timeFormat.format(selectedTime.time)}")
                    Spacer(Modifier.height(8.dp))
                    Text("Use device time picker or select below:")
                    
                    // Simple hour/minute selectors
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Hour")
                            Row {
                                Button(onClick = { 
                                    selectedTime.add(Calendar.HOUR_OF_DAY, -1)
                                }) { Text("-") }
                                Text(
                                    text = String.format("%02d", selectedTime.get(Calendar.HOUR_OF_DAY)),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Button(onClick = { 
                                    selectedTime.add(Calendar.HOUR_OF_DAY, 1)
                                }) { Text("+") }
                            }
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Minute")
                            Row {
                                Button(onClick = { 
                                    selectedTime.add(Calendar.MINUTE, -1)
                                }) { Text("-") }
                                Text(
                                    text = String.format("%02d", selectedTime.get(Calendar.MINUTE)),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Button(onClick = { 
                                    selectedTime.add(Calendar.MINUTE, 1)
                                }) { Text("+") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("OK")
                }
            }
        )
    }
    
    // Sound picker dialog
    if (showSoundPicker) {
        AlertDialog(
            onDismissRequest = { showSoundPicker = false },
            title = { Text("Select Alarm Sound") },
            text = {
                Column {
                    availableAlarmSounds.forEach { (soundId, soundName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedAlarmSound == soundId,
                                onClick = {
                                    selectedAlarmSound = soundId
                                    showSoundPicker = false
                                }
                            )
                            Text(
                                text = soundName,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSoundPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
