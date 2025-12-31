package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CortexBlock(
    var name: String = "",
    var dur: Int = 0,
    var type: String = "flex"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CortexTemplatesEditor(
    initialJson: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var jsonError by remember { mutableStateOf<String?>(null) }
    var templates by remember { mutableStateOf<MutableMap<String, MutableList<CortexBlock>>>(mutableMapOf()) }
    var selectedTemplateKey by remember { mutableStateOf<String?>(null) }
    var showAddTemplateDialog by remember { mutableStateOf(false) }
    var newTemplateName by remember { mutableStateOf("") }

    LaunchedEffect(initialJson) {
        try {
            if (initialJson.isNotBlank()) {
                val gson = Gson()
                val type = object : TypeToken<Map<String, List<CortexBlock>>>() {}.type
                val parsed: Map<String, List<CortexBlock>> = gson.fromJson(initialJson, type)
                // Convert to mutable structures for editing
                templates = parsed.mapValues { it.value.toMutableList() }.toMutableMap()
            } else {
                // Default empty structure if blank
                templates = mutableMapOf("standard" to mutableListOf())
            }
            if (templates.isNotEmpty()) {
                selectedTemplateKey = templates.keys.first()
            }
        } catch (e: Exception) {
            jsonError = "Error parsing JSON: ${e.message}"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false) // Full screen-ish
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                SmallTopAppBar(
                    title = { Text("Edit Block Definitions") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            try {
                                val gson = Gson()
                                val json = gson.toJson(templates)
                                onSave(json)
                                onDismiss()
                            } catch (e: Exception) {
                                jsonError = "Error saving: ${e.message}"
                            }
                        }) {
                            Text("Save")
                        }
                    }
                )

                if (jsonError != null) {
                    Text(
                        text = jsonError!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                // Template Selector
                ScrollableTabRow(
                    selectedTabIndex = templates.keys.indexOf(selectedTemplateKey).coerceAtLeast(0),
                    edgePadding = 16.dp
                ) {
                    templates.keys.forEach { key ->
                        Tab(
                            selected = key == selectedTemplateKey,
                            onClick = { selectedTemplateKey = key },
                            text = { Text(key.uppercase()) }
                        )
                    }
                    Tab(
                        selected = false,
                        onClick = { showAddTemplateDialog = true },
                        icon = { Icon(Icons.Default.Add, "Add Template") }
                    )
                }

                // Block List
                if (selectedTemplateKey != null && templates.containsKey(selectedTemplateKey)) {
                    val blocks = templates[selectedTemplateKey]!!
                    
                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 80.dp), // Bottom padding for FAB
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(blocks) { index, block ->
                                BlockEditorRow(
                                    block = block,
                                    onUpdate = { updated ->
                                        blocks[index] = updated
                                        // Force recomposition hack if needed, strictly speaking mutable state inside list might need help
                                        // But here we rely on the fact that we're mutating the list which is inside a MutableState map? 
                                        // Actually, deep mutation usually doesn't trigger Compose updates unless using SnapshotStateList.
                                        // Let's copy the map to force update.
                                        templates = templates.toMutableMap()
                                    },
                                    onDelete = {
                                        blocks.removeAt(index)
                                        templates = templates.toMutableMap()
                                    }
                                )
                            }
                        }

                        FloatingActionButton(
                            onClick = {
                                blocks.add(CortexBlock("New Block", 60, "flex"))
                                templates = templates.toMutableMap()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(Icons.Default.Add, "Add Block")
                        }
                    }
                }
            }
        }
    }

    if (showAddTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showAddTemplateDialog = false },
            title = { Text("New Template Name") },
            text = {
                OutlinedTextField(
                    value = newTemplateName,
                    onValueChange = { newTemplateName = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTemplateName.isNotBlank()) {
                        templates[newTemplateName] = mutableListOf()
                        selectedTemplateKey = newTemplateName
                        templates = templates.toMutableMap()
                        newTemplateName = ""
                        showAddTemplateDialog = false
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTemplateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BlockEditorRow(
    block: CortexBlock,
    onUpdate: (CortexBlock) -> Unit,
    onDelete: () -> Unit
) {
    val typeColors = mapOf(
        "boot" to Color(0xFFE0E0E0),
        "work" to Color(0xFFEF9A9A), // Red-ish
        "rest" to Color(0xFFA5D6A7), // Green-ish
        "flex" to Color(0xFF90CAF9), // Blue-ish
        "league" to Color(0xFFCE93D8), // Purple-ish
        "bed" to Color(0xFFB0BEC5),
        "sleep" to Color(0xFF78909C),
        "chaos" to Color(0xFFFFCC80) // Orange-ish
    )
    
    val types = listOf("boot", "work", "rest", "flex", "league", "bed", "sleep", "chaos")
    var expanded by remember { mutableStateOf(false) }

    Card(
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Color Dot
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(typeColors[block.type] ?: Color.Gray, RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                )
                
                Spacer(Modifier.width(8.dp))

                // Name
                OutlinedTextField(
                    value = block.name,
                    onValueChange = { onUpdate(block.copy(name = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Name") },
                    singleLine = true
                )

                Spacer(Modifier.width(8.dp))

                // Duration
                OutlinedTextField(
                    value = block.dur.toString(),
                    onValueChange = { 
                        if (it.all { char -> char.isDigit() }) {
                            onUpdate(block.copy(dur = it.toIntOrNull() ?: 0)) 
                        }
                    },
                    modifier = Modifier.width(70.dp),
                    label = { Text("Min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            
            // Type Selector (Dropdown)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                 Box {
                     TextButton(onClick = { expanded = true }) {
                         Text("Type: ${block.type.uppercase()}")
                         Icon(Icons.Default.ExpandMore, null)
                     }
                     DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                         types.forEach { type ->
                             DropdownMenuItem(
                                 text = { 
                                     Row(verticalAlignment = Alignment.CenterVertically) {
                                         Box(Modifier.size(12.dp).background(typeColors[type] ?: Color.Gray, RoundedCornerShape(6.dp)))
                                         Spacer(Modifier.width(8.dp))
                                         Text(type.uppercase())
                                     }
                                 },
                                 onClick = {
                                     onUpdate(block.copy(type = type))
                                     expanded = false
                                 }
                             )
                         }
                     }
                 }
            }
        }
    }
}
