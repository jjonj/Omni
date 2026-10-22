package com.omni.sync.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.delay

private fun JsonElement?.text(key: String): String? = runCatching {
    this?.asJsonObject?.get(key)?.takeIf { !it.isJsonNull }?.asString
}.getOrNull()

private fun parseT3Sessions(raw: String?): List<Pair<String,String>> = runCatching {
    val root = JsonParser.parseString(raw ?: "{}")
    val rows = when {
        root.isJsonArray -> root.asJsonArray
        root.isJsonObject -> {
            val obj = root.asJsonObject
            val array = obj.get("sessions") ?: obj.get("items") ?: obj.get("results")
            array?.takeIf { it.isJsonArray }?.asJsonArray
        }
        else -> null
    }
    rows?.mapNotNull { entry ->
        val id = entry.text("sessionId") ?: entry.text("id")
        if (id.isNullOrBlank()) null else id to
            (entry.text("title") ?: entry.text("name") ?: entry.text("latestMessageFirstFiveWords")
                ?: "ChatGPT ${id.take(8)}")
    } ?: emptyList()
}.getOrDefault(emptyList())

@Composable
fun OmniAiModesScreen(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    parentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val legacy by signalRClient.legacyAiEnabled.collectAsState()
    val connection by signalRClient.connectionState.collectAsState()
    val legacySessions by signalRClient.aiSessions.collectAsState()
    val selectedLegacyPid by signalRClient.selectedPid.collectAsState()
    val legacyMessages by signalRClient.aiMessages.collectAsState()
    var legacyDraft by remember { mutableStateOf("") }
    val sessionsJson by signalRClient.t3SessionsJson.collectAsState()
    val detailJson by signalRClient.t3DetailJson.collectAsState()
    val t3Status by signalRClient.t3LastResult.collectAsState()
    val chosen by signalRClient.selectedT3Id.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val online = connection == "Connected"
    val background = if (legacy) Color(0xFF362A21) else Color(0xFF182B40)
    val accent = if (legacy) Color(0xFFD9A35E) else Color(0xFF8FC7E9)

    LaunchedEffect(online, legacy) {
        if (!online || legacy) return@LaunchedEffect
        signalRClient.requestT3Sessions()
        while (true) {
            delay(30_000)
            signalRClient.requestT3Sessions()
        }
    }
    Column(Modifier.fillMaxSize().padding(parentPadding)) {
        Row(Modifier.fillMaxWidth().background(background).padding(start=16.dp,end=5.dp,top=5.dp,bottom=5.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (legacy) "AI · Legacy" else "AI · T3 Code", color=accent,
                    fontWeight=FontWeight.Bold, style=MaterialTheme.typography.titleLarge)
                Text(if (legacy) "Legacy Omni agents are active" else "T3 desktop sessions · ${if (online) "Hub connected" else "Hub offline"}",
                    color=Color.White.copy(alpha=.75f), style=MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert,"AI mode menu",tint=accent)
                }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(
                        text={Text(if (legacy) "Deactivate Legacy · Return to T3" else "Activate Legacy AI")},
                        onClick={
                            menu=false
                            signalRClient.setLegacyAiEnabled(!legacy)
                        }
                    )
                    DropdownMenuItem(text={Text("Refresh mode from Hub")},onClick={
                        menu=false;signalRClient.refreshLegacyAiMode()
                    })
                }
            }
        }
        if (legacy) {
            if (legacySessions.isNotEmpty()) {
                Text("Gemini sessions", Modifier.padding(start=12.dp,top=8.dp),
                    color=accent, style=MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.heightIn(max=150.dp)) {
                    items(legacySessions.toList()) { (pid,name) ->
                        Text(if (selectedLegacyPid == pid) "● $name" else name,
                            Modifier.fillMaxWidth().clickable {
                                signalRClient.setSelectedPid(pid)
                                signalRClient.requestAiHistory(pid)
                            }.padding(horizontal=12.dp, vertical=8.dp))
                    }
                }
                Text(legacyMessages.takeLast(4).joinToString("\n\n") { "${it.sender}: ${it.text.take(500)}" },
                    Modifier.fillMaxWidth().heightIn(max=110.dp).padding(horizontal=12.dp),
                    style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(legacyDraft, {legacyDraft=it.take(30_000)},
                    Modifier.fillMaxWidth().padding(horizontal=8.dp),
                    label={Text("Message to selected Legacy Gemini session")},maxLines=3)
                Button(onClick={
                    signalRClient.sendAiMessage(legacyDraft,selectedLegacyPid)
                    legacyDraft=""
                },enabled=online && selectedLegacyPid>=0 && legacyDraft.isNotBlank(),
                    modifier=Modifier.fillMaxWidth().padding(8.dp)){Text("Send to Legacy")}
            }
            Box(Modifier.weight(1f)) {
                AgentInboxScreen(signalRClient,mainViewModel)
            }
        } else {
            val sessions = remember(sessionsJson) { parseT3Sessions(sessionsJson) }
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("T3 sessions",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                TextButton(onClick={ signalRClient.requestT3Sessions() }, enabled=online){Text("Refresh")}
            }
            if (sessions.isEmpty()) {
                Text("No T3 sessions returned. Make sure T3 Code is running on the PC. Legacy remains off.",
                    Modifier.padding(horizontal=16.dp, vertical=12.dp),style=MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(Modifier.heightIn(max=190.dp)) {
                    items(sessions,key={it.first}) { (id,name) ->
                        val selected = chosen == id
                        Text(if(selected) "● $name" else name,
                            modifier=Modifier.fillMaxWidth()
                                .background(if(selected) background else Color.Transparent,RoundedCornerShape(8.dp))
                                .clickable { signalRClient.selectT3Session(id) }
                                .padding(horizontal=16.dp,vertical=11.dp),
                            color=if(selected) accent else MaterialTheme.colorScheme.onSurface,
                            maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider()
            if (chosen != null) {
                Text("Session ${chosen!!.take(12)}",Modifier.padding(10.dp),
                    style=MaterialTheme.typography.bodySmall,color=accent)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                    TextButton(onClick={signalRClient.refreshT3Session()},enabled=online){Text("Read latest")}
                }
            }
            val detail = remember(detailJson) {
                runCatching {
                    JsonParser.parseString(detailJson ?: "").asJsonObject.let { root ->
                        val messages = root.get("messages")
                            ?: root.get("session")?.takeIf {it.isJsonObject}?.asJsonObject?.get("messages")
                        if(messages?.isJsonArray==true)
                            messages.asJsonArray.joinToString("\n\n") {
                                val role=it.text("role") ?: it.text("author") ?: "Message"
                                val body=it.text("text") ?: it.text("content") ?: it.toString()
                                "$role: $body"
                            }
                        else root.toString()
                    }
                }.getOrDefault("Select a T3 session to read messages.")
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal=12.dp)) {
                item { Text(detail.take(20_000),style=MaterialTheme.typography.bodySmall) }
            }
            if (!t3Status.isNullOrBlank()) Text(t3Status!!,Modifier.padding(8.dp),
                color=MaterialTheme.colorScheme.tertiary,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=draft,onValueChange={draft=it.take(30_000)},
                modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp),
                label={Text("Message to selected T3 ChatGPT session")},maxLines=4)
            Button(onClick={
                if (signalRClient.sendT3Message(draft)) draft=""
            }, enabled=online && chosen!=null && draft.isNotBlank(),
                modifier=Modifier.fillMaxWidth().padding(8.dp)) {Text("Send to T3 session")}
        }
    }
}
