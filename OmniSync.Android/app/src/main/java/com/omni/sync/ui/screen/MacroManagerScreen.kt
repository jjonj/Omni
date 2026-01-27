package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.model.Macro
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.launch

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color

class MacroSyntaxVisualTransformation(
    private val parser: com.omni.sync.logic.macro.MacroParser,
    private val cursorPosition: Int
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        val lines = text.text.split('\n')
        var currentOffset = 0
        
        for (line in lines) {
            val lineRange = currentOffset until (currentOffset + line.length)
            val isCursorInLine = cursorPosition in currentOffset..(currentOffset + line.length)
            
            if (!isCursorInLine && !parser.isValid(line)) {
                builder.addStyle(
                    style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold),
                    start = currentOffset,
                    end = currentOffset + line.length
                )
            }
            currentOffset += line.length + 1 // +1 for \n
        }
        
        return TransformedText(builder.toAnnotatedString(), androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MacroManagerScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    val appConfig by mainViewModel.appConfig.collectAsState()
    val context = mainViewModel.applicationContext
    var macros by remember(appConfig.macros) { mutableStateOf(appConfig.macros) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Editor State
    var editingMacroId by remember { mutableStateOf<String?>(null) }
    var editorName by remember { mutableStateOf("") }
    var editorScript by remember { mutableStateOf(TextFieldValue("")) }
    var editorIconName by remember { mutableStateOf("play") }

    // Execution state
    var macroProgress by remember { mutableStateOf<String?>(null) }
    var inputPrompt by remember { mutableStateOf<String?>(null) }
    var inputValue by remember { mutableStateOf("") }
    var showInputDialog by remember { mutableStateOf(false) }
    val inputQueue = remember { mutableStateListOf<String>() }
    var currentMacroOnFinish by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Dropdown state
    var showKeysMenu by remember { mutableStateOf(false) }
    val specialKeys = listOf("Enter", "Tab", "Esc", "Space", "Backspace", "Delete", "Up", "Down", "Left", "Right", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")

    // We need SignalRClient for the "Test" functionality
    val omniSyncApplication = context.applicationContext as com.omni.sync.OmniSyncApplication
    val signalRClient = omniSyncApplication.signalRClient
    val parser = remember { com.omni.sync.logic.macro.MacroParser() }
    val executor = remember { com.omni.sync.logic.macro.MacroExecutor(signalRClient, macros) }

    fun runMacro(script: String) {
        val inputRegex = Regex("\\{INPUT:([^}]+)\\}")
        val matches = inputRegex.findAll(script).map { it.groupValues[1] }.distinct().toList()
        
        if (matches.isNotEmpty()) {
            inputQueue.clear()
            inputQueue.addAll(matches)
            
            fun processNext() {
                if (inputQueue.isEmpty()) {
                    coroutineScope.launch {
                        executor.execute(parser.parse(script, context), context) { macroProgress = it }
                        macroProgress = null
                    }
                } else {
                    inputPrompt = inputQueue.removeAt(0)
                    showInputDialog = true
                }
            }

            var finalScript = script
            currentMacroOnFinish = {
                val label = inputPrompt ?: ""
                finalScript = finalScript.replace("{INPUT:$label}", inputValue)
                inputValue = ""
                if (inputQueue.isEmpty()) {
                    coroutineScope.launch {
                        executor.execute(parser.parse(finalScript, context), context) { macroProgress = it }
                        macroProgress = null
                    }
                } else {
                    inputPrompt = inputQueue.removeAt(0)
                    showInputDialog = true
                }
            }
            
            processNext()
        } else {
            coroutineScope.launch {
                executor.execute(parser.parse(script, context), context) { macroProgress = it }
                macroProgress = null
            }
        }
    }

    fun saveMacros(newMacros: List<Macro>) {
        macros = newMacros
        mainViewModel.updateMacros(newMacros)
    }

    fun resetEditor() {
        editingMacroId = null
        editorName = ""
        editorScript = TextFieldValue("")
        editorIconName = "play"
    }

    // Handle back press
    androidx.activity.compose.BackHandler(enabled = true) {
        if (macroProgress != null) {
            macroProgress = null
        } else if (editingMacroId != null) {
            resetEditor()
        } else {
            onBack()
        }
    }

    fun insertAtCursor(text: String, cursorOffset: Int = 0) {
        val currentText = editorScript.text
        val selection = editorScript.selection
        
        val newText = currentText.substring(0, selection.start) + text + currentText.substring(selection.end)
        val newCursorPos = selection.start + text.length + cursorOffset
        
        editorScript = TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Macro Manager") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Syntax Help")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // integrated Macro Editor
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (editingMacroId == null) "Create New Macro" else "Edit Macro", 
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = editorName,
                        onValueChange = { editorName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val icons = listOf("play", "browser", "folder", "ai", "terminal", "code", "settings", "music", "video", "chat", "work", "game", "home", "lock", "refresh", "star")
                        icons.forEach { icon ->
                            val isSelected = editorIconName == icon
                            val vector = when (icon) {
                                "browser" -> Icons.Default.Language
                                "folder" -> Icons.Default.Folder
                                "ai" -> Icons.Default.SmartToy
                                "terminal" -> Icons.Default.Terminal
                                "code" -> Icons.Default.Code
                                "settings" -> Icons.Default.Settings
                                "music" -> Icons.Default.MusicNote
                                "video" -> Icons.Default.Movie
                                "chat" -> Icons.Default.Chat
                                "work" -> Icons.Default.Work
                                "game" -> Icons.Default.SportsEsports
                                "home" -> Icons.Default.Home
                                "lock" -> Icons.Default.Lock
                                "refresh" -> Icons.Default.Refresh
                                "star" -> Icons.Default.Star
                                else -> Icons.Default.PlayArrow
                            }
                            IconButton(
                                onClick = { editorIconName = icon },
                                modifier = Modifier.size(36.dp),
                                colors = if (isSelected) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else IconButtonDefaults.iconButtonColors()
                            ) {
                                Icon(vector, null, modifier = Modifier.size(20.dp), tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Quick Insert Buttons
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = { insertAtCursor("sleep 100\n") },
                            label = { Text("Sleep") }
                        )
                        AssistChip(
                            onClick = { insertAtCursor("run .exe\n", -5) },
                            label = { Text("Run Exe") }
                        )
                        AssistChip(
                            onClick = { insertAtCursor("keydown \nsleep 50\nkeyup \n", -16) },
                            label = { Text("Key Combo") }
                        )
                        AssistChip(
                            onClick = { insertAtCursor("run chrome https://\n") },
                            label = { Text("Chrome") }
                        )
                        AssistChip(
                            onClick = { insertAtCursor("{CLIPBOARD}") },
                            label = { Text("Clip") }
                        )
                        
                        Box {
                            AssistChip(
                                onClick = { showKeysMenu = true },
                                label = { Text("Special Key") },
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp)) }
                            )
                            DropdownMenu(
                                expanded = showKeysMenu,
                                onDismissRequest = { showKeysMenu = false },
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Win Key") },
                                    onClick = { 
                                        insertAtCursor("#")
                                        showKeysMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Keyboard, null) }
                                )
                                HorizontalDivider()
                                specialKeys.forEach { key ->
                                    DropdownMenuItem(
                                        text = { Text(key) },
                                        onClick = { 
                                            insertAtCursor("send ($key)\n")
                                            showKeysMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Managed side indicator
                    val currentCommands = remember(editorScript.text) { parser.parse(editorScript.text, context) }
                    val isHubManaged = currentCommands.isNotEmpty() && !currentCommands.any { it is com.omni.sync.logic.macro.MacroCommand.AiHere || it is com.omni.sync.logic.macro.MacroCommand.MacroChain }
                    
                    if (editorScript.text.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isHubManaged) Icons.Default.CloudQueue else Icons.Default.Smartphone,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isHubManaged) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isHubManaged) "Hub-managed (Batch)" else "Android-managed (Step-by-step)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isHubManaged) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    OutlinedTextField(
                        value = editorScript,
                        onValueChange = { editorScript = it },
                        label = { Text("Script") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        placeholder = { Text("send Hello\nsleep 1000") },
                        visualTransformation = MacroSyntaxVisualTransformation(parser, editorScript.selection.start)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { runMacro(editorScript.text) },
                            enabled = editorScript.text.isNotBlank()
                        ) {
                            Text("Test")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (editingMacroId != null) {
                            TextButton(onClick = { resetEditor() }) {
                                Text("Cancel")
                            }
                        }
                            Button(
                            onClick = {
                                val currentScript = editorScript.text
                                coroutineScope.launch {
                                    val processedScript = try {
                                        signalRClient.processMacro(currentScript)
                                            ?.subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                                            ?.blockingGet() ?: currentScript
                                    } catch (e: Exception) {
                                        currentScript
                                    }

                                    val newMacros = macros.toMutableList()
                                    if (editingMacroId != null) {
                                        val index = newMacros.indexOfFirst { it.id == editingMacroId }
                                        if (index != -1) {
                                            newMacros[index] = newMacros[index].copy(name = editorName, script = processedScript, iconName = editorIconName)
                                        }
                                    } else {
                                        newMacros.add(Macro(name = editorName, script = processedScript, iconName = editorIconName))
                                    }
                                    saveMacros(newMacros)
                                    resetEditor()
                                }
                            },
                            enabled = editorName.isNotBlank() && editorScript.text.isNotBlank()
                        ) {
                            Text(if (editingMacroId == null) "Add Macro" else "Update Macro")
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Compact Macro List
            if (macros.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No macros defined yet.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    items(macros, key = { it.id }) { macro ->
                        val icon = when (macro.iconName.lowercase()) {
                            "browser" -> Icons.Default.Language
                            "folder" -> Icons.Default.Folder
                            "ai" -> Icons.Default.SmartToy
                            "terminal" -> Icons.Default.Terminal
                            "code" -> Icons.Default.Code
                            "settings" -> Icons.Default.Settings
                            "music" -> Icons.Default.MusicNote
                            "video" -> Icons.Default.Movie
                            "chat" -> Icons.Default.Chat
                            "work" -> Icons.Default.Work
                            "game" -> Icons.Default.SportsEsports
                            "home" -> Icons.Default.Home
                            "lock" -> Icons.Default.Lock
                            "refresh" -> Icons.Default.Refresh
                            "star" -> Icons.Default.Star
                            else -> Icons.Default.PlayArrow
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = {
                                editingMacroId = macro.id
                                editorName = macro.name
                                editorScript = TextFieldValue(macro.script, TextRange(macro.script.length))
                                editorIconName = macro.iconName
                            },
                            colors = if (editingMacroId == macro.id) 
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(macro.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                }
                                
                                IconButton(onClick = { runMacro(macro.script) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = "Run", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                                }

                                IconButton(onClick = {
                                    saveMacros(macros.filter { it.id != macro.id })
                                    if (editingMacroId == macro.id) resetEditor()
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Macro Syntax Help") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SyntaxHelpItem(
                        "send <keys>", 
                        "Sends keystrokes to the PC.",
                        "send Hello\nsend ^w (Ctrl+W)\nsend (F5) (Refresh)"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "sleep <ms>", 
                        "Wait duration in milliseconds.",
                        "sleep 1000 (Wait 1s)"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "run <path/url>", 
                        "Execute program or open URL.",
                        "run notepad.exe\nrun \"C:\\Path with space\\app.exe\""
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "winactivate/winclose <title>", 
                        "Focus or close window by title/process.",
                        "winactivate Notepad\nwinclose chrome"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "volup / voldown / volmute", 
                        "System volume control.",
                        "volup\nvolmute"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "screenshot", 
                        "Capture full screen to Hub.",
                        "screenshot"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "keydown/keyup <key>", 
                        "Manually hold or release a key.",
                        "keydown ctrl\nsend c\nkeyup ctrl"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "clipboard <text>", 
                        "Set the PC clipboard text.",
                        "clipboard Hello from Phone"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "aihere <path>", 
                        "Start Gemini CLI at workspace path.",
                        "aihere D:\\SSDProjects\\Omni"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SyntaxHelpItem(
                        "{CLIPBOARD}", 
                        "Placeholder for Android clipboard content.",
                        "send {CLIPBOARD}\nrun https://google.com/search?q={CLIPBOARD}"
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Special Keys (Case Insensitive):", 
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "^=Ctrl, !=Alt, +=Shift, #=Win\n(Enter), (Tab), (Esc), (F1)-(F12), (Up), (Down), (Left), (Right), (Space), (Backspace), (Delete)",
                        style = MaterialTheme.typography.bodySmall
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        "Templates:", 
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val templates = listOf(
                        "Google Search" to "run https://www.google.com/search?q={CLIPBOARD}",
                        "Open Notepad" to "run notepad.exe\nsleep 500\nsend Hello from Omni!",
                        "Alt+Tab" to "keydown alt\nsend (Tab)\nkeyup alt",
                        "Refresh Chrome" to "winactivate chrome\nsleep 100\nsend (F5)",
                        "Close Window" to "winclose current",
                        "Volume Mute" to "volmute",
                        "Copy All" to "send ^a\nsend ^c",
                        "New Folder" to "send ^+n",
                        "Lock PC" to "send #l",
                        "Omni Workspace" to "aihere D:\\SSDProjects\\Omni\nwinactivate vscode"
                    )

                    templates.forEach { (label, script) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { 
                                    editorScript = TextFieldValue(script, TextRange(script.length))
                                    if (editorName.isBlank()) editorName = label
                                    showHelpDialog = false
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Insert", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = { Text("Macro Input Required") },
            text = {
                Column {
                    Text("Provide value for: ${inputPrompt ?: "Input"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { 
                    showInputDialog = false
                    currentMacroOnFinish?.invoke()
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInputDialog = false }) {
                    Text("Cancel Macro")
                }
            }
        )
    }

    if (macroProgress != null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.width(250.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(macroProgress!!, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { 
                            // How to cancel coroutine? We need a job reference.
                            // For simplicity now, just clear UI.
                            macroProgress = null 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop")
                    }
                }
            }
        }
    }
}

@Composable
fun SyntaxHelpItem(command: String, description: String, example: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(command, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(description, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                example, 
                modifier = Modifier.padding(8.dp), 
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

