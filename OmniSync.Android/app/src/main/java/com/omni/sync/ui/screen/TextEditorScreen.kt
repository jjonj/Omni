package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
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
    
    val scrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val visualTransformation = remember(colorScheme) { MarkdownVisualTransformation(colorScheme) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Editing: ${editingFile?.name ?: "Unknown"}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text.toString()
                            filesViewModel.updateEditingContent(editingContent + text)
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                    IconButton(onClick = { filesViewModel.updateEditingContent("") }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                    }
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { filesViewModel.saveEditingContent() }) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
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
}
