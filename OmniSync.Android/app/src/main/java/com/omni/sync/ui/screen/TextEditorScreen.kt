package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.viewmodel.FilesViewModel

import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

class MarkdownVisualTransformation(private val colorScheme: ColorScheme) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            highlightMarkdown(text.text, colorScheme),
            OffsetMapping.Identity
        )
    }

    private fun highlightMarkdown(text: String, colorScheme: ColorScheme): AnnotatedString {
        return buildAnnotatedString {
            val lines = text.split("\n")
            lines.forEachIndexed { index, line ->
                when {
                    line.startsWith("#") -> {
                        withStyle(SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.Bold)) {
                            append(line)
                        }
                    }
                    line.startsWith(">") -> {
                        withStyle(SpanStyle(color = colorScheme.tertiary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                            append(line)
                        }
                    }
                    line.contains("`") -> {
                        // Simple code highlight
                        val parts = line.split("`")
                        parts.forEachIndexed { pIndex, part ->
                            if (pIndex % 2 == 1) {
                                withStyle(SpanStyle(background = colorScheme.surfaceVariant, color = colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)) {
                                    append(part)
                                }
                            } else {
                                append(part)
                            }
                        }
                    }
                    else -> {
                        append(line)
                    }
                }
                if (index < lines.size - 1) append("\n")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    filesViewModel: FilesViewModel,
    onBack: () -> Unit
) {
    val editingFile by filesViewModel.editingFile.collectAsState()
    val editingContent by filesViewModel.editingContent.collectAsState()
    val isSaving by filesViewModel.isSaving.collectAsState()
    val hasUnsavedChanges by filesViewModel.hasUnsavedChanges.collectAsState()
    val autoSaveEnabled by filesViewModel.autoSaveEnabled.collectAsState()
    
    var showMenu by remember { mutableStateOf(false) }
    var lastContent by remember { mutableStateOf(editingContent) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showRemoteChangeDialog by remember { mutableStateOf<String?>(null) }
    
    val scrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val visualTransformation = remember(colorScheme) { MarkdownVisualTransformation(colorScheme) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        filesViewModel.remoteChangeDetected.collect { fileName: String ->
            showRemoteChangeDialog = fileName
        }
    }

    val exitHandler = {
        if (hasUnsavedChanges) {
            if (autoSaveEnabled) {
                filesViewModel.saveEditingContent()
            } else {
                showUnsavedDialog = true
            }
        } else {
            onBack()
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        exitHandler()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    val fileName = editingFile?.name ?: "Unknown"
                    val displayName = if (hasUnsavedChanges) "$fileName *" else fileName
                    Text(text = "Editing: $displayName") 
                },
                navigationIcon = {
                    IconButton(onClick = exitHandler) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text.toString()
                            lastContent = editingContent
                            filesViewModel.updateEditingContent(editingContent + text)
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                    
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        // Custom Save Button with long-press for autosave
                        Box {
                            IconButton(
                                onClick = { filesViewModel.saveEditingContent() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Save, 
                                        contentDescription = "Save",
                                        modifier = Modifier.pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = {
                                                    filesViewModel.setAutoSaveEnabled(!autoSaveEnabled)
                                                    android.widget.Toast.makeText(context, "Autosave: ${if (!autoSaveEnabled) "ON" else "OFF"}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onTap = {
                                                    filesViewModel.saveEditingContent()
                                                }
                                            )
                                        }
                                    )
                                    if (autoSaveEnabled) {
                                        Text(
                                            "A", 
                                            color = Color.Green, 
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Undo") },
                                onClick = {
                                    val temp = editingContent
                                    filesViewModel.updateEditingContent(lastContent)
                                    lastContent = temp
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Copy All") },
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("OmniEditor", editingContent)
                                    clipboard.setPrimaryClip(clip)
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Insert Timestamp") },
                                onClick = {
                                    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                    lastContent = editingContent
                                    filesViewModel.updateEditingContent(editingContent + "\n" + ts + "\n")
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Add H1 Header (#)") },
                                onClick = {
                                    lastContent = editingContent
                                    filesViewModel.updateEditingContent("# " + editingContent)
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add Bullet Point (-)") },
                                onClick = {
                                    lastContent = editingContent
                                    filesViewModel.updateEditingContent("- " + editingContent)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("To Upper Case") },
                                onClick = {
                                    lastContent = editingContent
                                    filesViewModel.updateEditingContent(editingContent.uppercase())
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("To Lower Case") },
                                onClick = {
                                    lastContent = editingContent
                                    filesViewModel.updateEditingContent(editingContent.lowercase())
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear All") },
                                onClick = {
                                    lastContent = editingContent
                                    filesViewModel.updateEditingContent("")
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .imePadding()
        ) {
            // Line numbers column
            val lines = editingContent.split("\n")
            val lineCount = lines.size
            val lineNumbers = (1..lineCount).joinToString("\n")
            
            Text(
                text = lineNumbers,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 4.dp),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.End
                ),
                lineHeight = 20.sp
            )

            TextField(
                value = editingContent,
                onValueChange = { filesViewModel.updateEditingContent(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                visualTransformation = if (editingFile?.name?.endsWith(".md") == true) visualTransformation else VisualTransformation.None,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                )
            )
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. What would you like to do?") },
            confirmButton = {
                Button(onClick = {
                    showUnsavedDialog = false
                    filesViewModel.saveEditingContent()
                }) {
                    Text("Save & Exit")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        filesViewModel.markSaved()
                        onBack()
                    }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showRemoteChangeDialog != null) {
        AlertDialog(
            onDismissRequest = { showRemoteChangeDialog = null },
            title = { Text("Remote Change Detected") },
            text = { Text("The file '${showRemoteChangeDialog}' was modified on the Hub. Your local changes may conflict.") },
            confirmButton = {
                Button(onClick = { showRemoteChangeDialog = null }) {
                    Text("OK")
                }
            }
        )
    }
}
