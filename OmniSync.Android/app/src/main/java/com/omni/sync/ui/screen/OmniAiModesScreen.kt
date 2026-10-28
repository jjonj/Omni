package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.sync.data.repository.AgentProviderUi
import com.omni.sync.data.repository.AddressIdentityPreview
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * New provider-agnostic Android AI view. All operations use AgentCall; this
 * component intentionally does not depend on the old Gemini/T3 method names.
 */
@Composable
fun OmniAiModesScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp),
    initialSessionId: String? = null
) {
    val agents = remember(signalRClient) { signalRClient.genericAgents }
    val scope = rememberCoroutineScope()
    val providers by agents.providers.collectAsState()
    val sessions by agents.sessions.collectAsState()
    val lastProvider by agents.lastProvider.collectAsState()
    val selectedKey by agents.selected.collectAsState()
    val history by agents.history.collectAsState()
    val olderHistoryCursor by agents.olderHistoryCursor.collectAsState()
    val archiveEnabled by agents.archiveEnabled.collectAsState()
    val archiveMatches by agents.archiveSearch.collectAsState()
    var archivePhrase by remember { mutableStateOf("") }
    val historyNotice by agents.historyNotice.collectAsState()
    val queued by agents.outbox.collectAsState()
    val remoteQueue by agents.remoteQueue.collectAsState()
    val status by agents.status.collectAsState()
    val authenticated by signalRClient.hubAuthenticated.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(true) }
    var showHumanInbox by remember { mutableStateOf(false) }
    var toAddress by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf<AgentProviderUi?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var createSession by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf("") }
    var createWorkspace by remember { mutableStateOf("") }
    var createMessage by remember { mutableStateOf("") }
    var verifyCandidate by remember { mutableStateOf<AddressIdentityPreview?>(null) }
    var initialApplied by remember(initialSessionId) { mutableStateOf(false) }
    LaunchedEffect(initialSessionId, sessions) {
        if (!initialApplied && initialSessionId != null) {
            sessions.firstOrNull { it.id == initialSessionId || it.key == initialSessionId }
                ?.let {
                    initialApplied = true
                    agents.select(it.key)
                    showSessions = false
                }
        }
    }
    val selected = sessions.firstOrNull { it.key == selectedKey }
    val active = providers.firstOrNull { it.active }
    val accent = Color(0xFF8FC7E9)
    val background = Color(0xFF182B40)
    fun queueMessage() {
        val text = draft.trim()
        if (text.isEmpty()) return
        try {
            if (toAddress) {
                agents.enqueueAddress(address.trim(),text,active?.id ?: lastProvider
                    ?: throw IllegalStateException("Connect once to select an address provider before queuing offline"))
            } else {
                val session = selected ?: throw IllegalStateException("Select a session or use a contact address")
                agents.enqueueSession(session,text)
            }
            draft = ""
            feedback = if (authenticated) "Message stored; delivery status will be shown below."
                else "Saved on this phone and queued for reconnection."
        } catch (e: Exception) {
            feedback = e.message
        }
    }
    if (showHumanInbox) {
        Box(Modifier.fillMaxSize().padding(parentPadding)) {
            HumanInboxScreen(signalRClient = signalRClient, onBack = { showHumanInbox = false })
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(parentPadding).background(background.copy(alpha = 0.2f))) {
        Row(Modifier.fillMaxWidth().background(background).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI · Direct T3 sessions", style=MaterialTheme.typography.titleLarge,
                    color=accent,fontWeight=FontWeight.Bold)
                Text(if (authenticated) "Connected to OmniHub" else "Offline · local drafts and history",
                    style=MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick={showSessions=!showSessions}) {
                Text(if(showSessions) "Conversation" else "Sessions")
            }
            TextButton(onClick={ showHumanInbox = true }) { Text("Inbox") }
            TextButton(onClick={scope.launch { agents.refresh() }},enabled=authenticated) {
                Text("Refresh")
            }
        }
        if (showSessions) {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp, vertical=6.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            providers.forEach { provider ->
                OutlinedButton(
                    onClick={ if (!provider.active) switching=provider },
                    enabled=authenticated && !provider.active,
                    contentPadding=PaddingValues(horizontal=10.dp,vertical=3.dp)
                ) { Text("${provider.name} ${if (provider.active) "· active" else "· activate"}") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=10.dp),
            verticalAlignment=Alignment.CenterVertically){
            Text("Sessions",Modifier.weight(1f),
                style=MaterialTheme.typography.titleSmall)
            if(authenticated && active?.id=="t3") {
                TextButton(onClick={scope.launch {
                    try { feedback=agents.checkLastWork() }
                    catch(e:Exception) { feedback="AI status unavailable: ${e.message}" }
                }}) { Text("Check last AI") }
            }
            if(authenticated && active?.capabilities?.contains("CreateSession")==true){
                TextButton(onClick={
                    createKind=active.kinds.firstOrNull { it=="t3/chatgpt" || (active.id!="t3" && it!="local/opencode") }.orEmpty()
                    createWorkspace=""
                    createMessage=""
                    createSession=true
                }){Text("New session")}
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(sessions,key={it.key}) { session ->
                val chosen = selectedKey==session.key
                Column(Modifier.fillMaxWidth()
                    .background(if(chosen) background else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { scope.launch { agents.select(session.key); showSessions=false } }
                    .padding(horizontal=14.dp,vertical=8.dp)) {
                    Text(session.title, fontWeight=if(chosen) FontWeight.Bold else FontWeight.Normal,
                        color=if(chosen) accent else MaterialTheme.colorScheme.onSurface)
                    Text("${session.kind} · ${session.activity} (${session.confidence})" +
                        (if (session.attention!="None") " · ${session.attention}" else "") +
                        (if (session.unread == true) " · unread" else ""),
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(sessions.isEmpty()) item {
                Text("No sessions cached. Choose a contact address or connect to refresh.",
                    Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
            }
        }
        }
        else {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(selected?.title ?: "History",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            if(olderHistoryCursor != null)
                TextButton(onClick={scope.launch {agents.loadOlderHistory()}},
                    enabled=authenticated && selectedKey!=null) {Text("Older")}
            TextButton(onClick={scope.launch { agents.select(selectedKey) }},
                enabled=authenticated && selectedKey!=null) {Text("Read latest")}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=archiveEnabled,onCheckedChange={agents.setArchiveEnabled(it)})
            Text("Save observed history for offline search (local only)",
                style=MaterialTheme.typography.labelSmall)
        }
        if(archiveEnabled) {
            OutlinedTextField(
                value=archivePhrase,
                onValueChange={
                    archivePhrase=it.take(100)
                    scope.launch {agents.searchArchive(archivePhrase)}
                },
                modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),
                singleLine=true,label={Text("Search offline archive across sessions")}
            )
            if(archivePhrase.isNotBlank() && archiveMatches.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=110.dp).padding(horizontal=12.dp)){
                    items(archiveMatches.take(30)){(sessionKey,item)->
                        Text("${sessionKey.take(20)} · ${item.text.take(120)}",
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {agents.select(sessionKey)}
                            }.padding(vertical=5.dp),
                            style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if(selected!=null && authenticated) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                if(selected.capabilities.contains("Interrupt"))
                    TextButton(onClick={scope.launch {
                        try { agents.sessionAction(selected,"Interrupt") }
                        catch(e: Exception) { feedback="Interrupt failed: ${e.message}" }
                    }}) {Text("Stop turn")}
                if(selected.capabilities.contains("RenameSession"))
                    TextButton(onClick={renameValue=selected.title;rename=true}) {Text("Rename")}
                if(selected.capabilities.contains("DeleteSession"))
                    TextButton(onClick={confirmDelete=true}) {Text("Delete")}
                if(selected.capabilities.contains("NativeQueue"))
                    TextButton(onClick={scope.launch {agents.fetchQueue(selected)}}) {Text("Queue")}
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp)) {
            if(!historyNotice.isNullOrBlank()) item {
                Text(historyNotice!!,color=MaterialTheme.colorScheme.tertiary,
                    style=MaterialTheme.typography.labelSmall)
            }
            items(history,key={it.id}) { item ->
                Column(Modifier.fillMaxWidth().padding(vertical=7.dp)) {
                    Text(item.kind + if(item.partial) " · partial" else "",
                        style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
                    Text(item.text,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        if(remoteQueue.isNotEmpty() && selected!=null){
            Text("Provider's native queue",Modifier.padding(start=12.dp,top=7.dp),
                style=MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=90.dp)) {
                items(remoteQueue,key={it.id}) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text(item.text.take(100),Modifier.weight(1f),
                            style=MaterialTheme.typography.labelSmall)
                        if(selected.capabilities.contains("EditQueue"))
                            TextButton(onClick={scope.launch {
                                try {
                                    agents.sessionAction(selected,"QueueEdit",mapOf(
                                        "edit" to mapOf("id" to item.id,"action" to "Cancel")))
                                } catch (e: Exception) {feedback="Queue edit failed: ${e.message}"}
                            }}) {Text("Cancel")}
                    }
                }
            }
        }
        if(queued.isNotEmpty()) {
            Text("Device outbox",Modifier.padding(start=12.dp,top=7.dp),
                style=MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=108.dp)) {
                items(queued,key={it.id}) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text("${item.target.take(30)}: ${item.text.take(36)} · ${item.state}",
                            Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                        if(item.needsVerification && authenticated && item.state in listOf("Pending","Blocked"))
                            TextButton(onClick={scope.launch {
                                try {
                                    verifyCandidate=agents.previewAddressRecipient(item.id)
                                }catch(e: Exception){feedback="Recipient verification failed: ${e.message}"}
                            }}) {Text("Verify")}
                        if(item.state=="Delivered") TextButton(onClick={agents.dismissDelivered(item.id)}) {
                            Text("Clear")
                        }
                    }
                    if(item.error!=null) Text(item.error,
                        Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=toAddress,onCheckedChange={toAddress=it},
                enabled=active?.capabilities?.contains("SubmitToAgent")==true)
            Text("Managed contact (OpenRig M7)",style=MaterialTheme.typography.labelMedium)
        }
        if(toAddress) OutlinedTextField(
            value=address,onValueChange={address=it.take(320)},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),
            label={Text("Contact address (e.g. myagent@system)")})
        OutlinedTextField(
            value=draft,onValueChange={draft=it.take(30_000)},
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),
            label={Text("Message (saved offline when disconnected)")},maxLines=4)
        Button(
            onClick={ queueMessage() },
            enabled=draft.isNotBlank() && (if(toAddress) address.contains("@") && (active?.id ?: lastProvider)!=null else selected != null),
            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp)
        ) { Text(if(authenticated) "Submit with delivery receipt" else "Queue on this phone") }
        if(feedback!=null) Text(feedback!!,Modifier.padding(horizontal=12.dp),
            style=MaterialTheme.typography.labelSmall)
        if(status.isNotBlank()) Text(status,Modifier.padding(horizontal=12.dp,vertical=4.dp),
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.tertiary)
        }
    }
    verifyCandidate?.let { preview ->
        AlertDialog(
            onDismissRequest={verifyCandidate=null},
            title={Text("Confirm contact identity")},
            text={Text("This queued message will follow the same verified recipient across sessions. If the address changes owners, delivery will stop.\n\n${preview.summary}")},
            confirmButton={TextButton(onClick={
                verifyCandidate=null
                try {agents.confirmAddressRecipient(preview)}
                catch(e: Exception){feedback="Verification was not saved: ${e.message}"}
            }) {Text("Confirm recipient")}},
            dismissButton={TextButton(onClick={verifyCandidate=null}){Text("Cancel")}}
        )
    }
    if(rename && selected!=null) AlertDialog(
        onDismissRequest={rename=false},
        title={Text("Rename session")},
        text={OutlinedTextField(renameValue,{renameValue=it.take(256)},label={Text("Session title")})},
        confirmButton={TextButton(onClick={
            rename=false
            scope.launch {
                try {agents.sessionAction(selected,"Rename",mapOf("title" to renameValue))}
                catch(e: Exception){feedback="Rename failed: ${e.message}"}
            }
        }){Text("Rename")}},
        dismissButton={TextButton(onClick={rename=false}){Text("Cancel")}}
    )
    if(confirmDelete && selected!=null) AlertDialog(
        onDismissRequest={confirmDelete=false},
        title={Text("Permanently delete this session?")},
        text={Text("This deletes the local provider's transcript. It cannot be undone.")},
        confirmButton={TextButton(onClick={
            confirmDelete=false
            scope.launch {
                try {
                    agents.sessionAction(selected,"Delete",mapOf("scope" to "transcript"))
                    agents.select(null)
                } catch(e: Exception){feedback="Delete failed: ${e.message}"}
            }
        }){Text("Delete")}},
        dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancel")}}
    )
    if(createSession && active!=null) AlertDialog(
        onDismissRequest={createSession=false},
        title={Text("Create AI session")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("Select execution kind",style=MaterialTheme.typography.labelMedium)
                active.kinds.filter { it=="t3/chatgpt" || (active.id!="t3" && it!="local/opencode") }.forEach { kind ->
                    Row(verticalAlignment=Alignment.CenterVertically){
                        RadioButton(selected=createKind==kind,
                            onClick={createKind=kind})
                        Text(kind,Modifier.clickable{createKind=kind},
                            style=MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedTextField(createWorkspace,{createWorkspace=it.take(4096)},
                    label={Text("Existing working folder")},singleLine=true)
                OutlinedTextField(createMessage,{createMessage=it.take(30_000)},
                    label={Text("First message")},maxLines=4)
            }
        },
        confirmButton={TextButton(
            enabled=createKind.isNotEmpty()&&createWorkspace.isNotBlank()&&createMessage.isNotBlank(),
            onClick={
                createSession=false
                scope.launch {
                    try {
                        agents.createSession(active,createKind,createWorkspace,createMessage)
                    }catch(e: Exception){feedback="Create failed: ${e.message}"}
                }
            }){Text("Create")}},
        dismissButton={TextButton(onClick={createSession=false}){Text("Cancel")}}
    )
    switching?.let { provider ->
        AlertDialog(
            onDismissRequest={switching=null},
            title={Text("Activate ${provider.name}?")},
            text={Text("Only you can switch providers. The previous provider will be deactivated; any failure aborts the switch. Local Agents activation may install Claude hooks and resume local queues.")},
            confirmButton={
                TextButton(onClick={
                    switching=null
                    scope.launch {
                        try { agents.activate(provider) }
                        catch (e: Exception) { feedback="Provider switch failed: ${e.message}" }
                    }
                }){Text("Activate")}
            },
            dismissButton={TextButton(onClick={switching=null}) {Text("Cancel")}}
        )
    }
}
