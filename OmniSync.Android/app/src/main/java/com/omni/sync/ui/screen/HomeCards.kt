package com.omni.sync.ui.screen

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.AgentInboxStore
import com.omni.sync.utils.AgentNotifyLevel
import com.omni.sync.utils.AlarmStore
import com.omni.sync.utils.WakeAlarmActions
import com.omni.sync.viewmodel.AppScreen
import com.omni.sync.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

// ---------- model ----------

/** What a card is about; decides its accent colour and glyph. */
enum class HomeCardKind { QUESTION, ATTENTION, ERROR, DONE, REMINDER, ALARM, MESSAGE }

data class HomeCardAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * One item in the home screen card strip. [dismissKey] identifies the *version* of the card the user
 * swiped away (e.g. session id + state time), so a card comes back when its subject changes.
 */
data class HomeCard(
    val id: String,
    val kind: HomeCardKind,
    val title: String,
    val body: String? = null,
    val source: String? = null,
    val time: Long = 0L,
    val priority: Int = 0,
    val expiresAt: Long? = null,
    val dismissKey: String = id,
    val actions: List<HomeCardAction> = emptyList(),
    val onOpen: (() -> Unit)? = null
)

/**
 * Cards pushed from outside the built-in sources (Hub messages, a future assistant, one-off
 * notices). Short-lived by default; they disappear at [HomeCard.expiresAt].
 */
object HomeCardFeed {
    private val _posted = MutableStateFlow<List<HomeCard>>(emptyList())
    val posted: StateFlow<List<HomeCard>> = _posted

    fun post(card: HomeCard, lifetimeMs: Long = 30 * MINUTE_MS) {
        val withExpiry = if (card.expiresAt == null) card.copy(expiresAt = System.currentTimeMillis() + lifetimeMs) else card
        _posted.value = _posted.value.filterNot { it.id == card.id } + withExpiry
    }

    fun remove(id: String) {
        _posted.value = _posted.value.filterNot { it.id == id }
    }
}

/** Swiped-away card versions, kept for a week. */
object HomeCardDismissals {
    private const val PREFS = "home_cards"
    private const val KEY = "dismissed"
    private const val KEEP_MS = 7 * DAY_MS
    private val type = object : TypeToken<Map<String, Long>>() {}.type
    private val _dismissed = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dismissed: StateFlow<Map<String, Long>> = _dismissed
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val now = System.currentTimeMillis()
        _dismissed.value = runCatching { Gson().fromJson<Map<String, Long>>(raw ?: "{}", type) }.getOrNull().orEmpty()
            .filterValues { now - it < KEEP_MS }
    }

    fun dismiss(context: Context, key: String) {
        _dismissed.value = _dismissed.value + (key to System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, Gson().toJson(_dismissed.value)).apply()
    }
}

// ---------- sources ----------

private fun agentCards(
    signalRClient: SignalRClient,
    mainViewModel: MainViewModel,
    sessions: List<com.omni.sync.data.repository.AgentSessionSummaryData>,
    local: com.omni.sync.utils.AgentLocalState,
    now: Long
): List<HomeCard> = sessions.mapNotNull { s ->
    if (AgentInboxStore.notifyLevel(s.id) == AgentNotifyLevel.MUTED) return@mapNotNull null
    val section = sectionOf(s, local, now) ?: return@mapNotNull null
    val since = isoMillis(s.stateSince.ifBlank { s.modified })
    val project = projectOf(s.cwd).ifBlank { s.provider }
    val source = "${s.provider} · $project"
    val open = { mainViewModel.openAgentSession(s.id) }
    val reply = { AgentUiBus.focusComposer.value = s.id; mainViewModel.openAgentSession(s.id) }
    val key = "agent:${s.id}:${s.state}:${s.stateSince}"
    when {
        s.state == "question" -> HomeCard(
            id = "agent-${s.id}", kind = HomeCardKind.QUESTION, title = s.title,
            body = s.question?.text ?: s.activity, source = source, time = since, priority = 100, dismissKey = key,
            actions = (if (s.capabilities?.canAnswerQuestions == true) s.question?.options.orEmpty().take(3).map { option ->
                HomeCardAction(option) { signalRClient.sendAgentMessage(s.id, option) }
            } else emptyList()) + HomeCardAction("Reply…", onClick = reply),
            onOpen = open
        )
        s.state == "permission" -> HomeCard(
            id = "agent-${s.id}", kind = HomeCardKind.ATTENTION, title = s.title, body = s.activity.ifBlank { "Waiting for permission" },
            source = source, time = since, priority = 95, dismissKey = key, actions = listOf(HomeCardAction("Open", primary = true, onClick = open)), onOpen = open
        )
        s.state == "error" -> HomeCard(
            id = "agent-${s.id}", kind = HomeCardKind.ERROR, title = s.title, body = s.error ?: s.activity,
            source = source, time = since, priority = 90, dismissKey = key,
            actions = listOf(HomeCardAction("Retry") { signalRClient.retryAgentTurn(s.id) }, HomeCardAction("Open", onClick = open)), onOpen = open
        )
        s.state == "limited" -> HomeCard(
            id = "agent-${s.id}", kind = HomeCardKind.ATTENTION, title = s.title, body = s.activity.ifBlank { "Usage limit reached" },
            source = source, time = since, priority = 60, dismissKey = key, onOpen = open
        )
        // Short-lived: an unseen finished turn shows for two hours.
        section == AgentSection.FINISHED && now - since < 2 * HOUR_MS -> HomeCard(
            id = "agent-${s.id}", kind = HomeCardKind.DONE, title = s.title, body = s.activity.ifBlank { "Finished" },
            source = source, time = since, priority = 30, expiresAt = since + 2 * HOUR_MS, dismissKey = key,
            actions = listOf(HomeCardAction("Reply…", onClick = reply)), onOpen = open
        )
        else -> null
    }
}

private fun reminderCards(signalRClient: SignalRClient, mainViewModel: MainViewModel, reminders: List<com.omni.sync.data.repository.ReminderData>, now: Long): List<HomeCard> =
    reminders.filter { !it.isTerminal }.mapNotNull { r ->
        val due = isoMillis(r.nextFireAt ?: r.dueAt)
        val isDue = r.state == 1 || (due in 1..now)
        if (!isDue && (due <= 0 || due - now > 3 * HOUR_MS)) return@mapNotNull null
        val label = when (r.kind) { 0 -> "Task"; 2 -> "Alarm"; else -> "Reminder" }
        HomeCard(
            id = "reminder-${r.id}", kind = HomeCardKind.REMINDER, title = r.title.ifBlank { label },
            body = if (isDue) "Due ${clockOf(due)}" else "In ${untilLabel(due - now)} · ${clockOf(due)}",
            source = if (r.source == 1) "Calendar" else label, time = due, priority = if (isDue) 85 else 45,
            dismissKey = "reminder:${r.id}:${r.revision}",
            actions = listOf(
                HomeCardAction("Done", primary = isDue) { signalRClient.completeReminder(r.id) },
                HomeCardAction("+10m") { signalRClient.snoozeReminder(r.id, 10) }
            ),
            onOpen = { mainViewModel.navigateTo(AppScreen.ALARM) }
        )
    }

private fun alarmCards(context: Context, mainViewModel: MainViewModel, now: Long): List<HomeCard> {
    val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
    val next = runCatching { AlarmStore.load(prefs, Gson()) }.getOrDefault(emptyList())
        .filter { it.enabled }
        .map { it to WakeAlarmActions.nextTrigger(it, now) }
        .filter { (_, at) -> at > now && at - now < 14 * HOUR_MS }
        .minByOrNull { it.second } ?: return emptyList()
    val (alarm, at) = next
    return listOf(
        HomeCard(
            id = "alarm-${alarm.id}", kind = HomeCardKind.ALARM, title = "${if (alarm.isWakeAlarm) "Wake alarm" else "Alarm"} ${clockOf(at)}",
            body = "In ${untilLabel(at - now)}", source = "Alarm", time = at, priority = 20,
            dismissKey = "alarm:${alarm.id}:$at", onOpen = { mainViewModel.navigateTo(AppScreen.ALARM) }
        )
    )
}

private fun clockOf(millis: Long): String = Calendar.getInstance().apply { timeInMillis = millis }.let {
    "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
}

private fun untilLabel(ms: Long): String {
    val minutes = (ms / MINUTE_MS).coerceAtLeast(1)
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

/** All live cards for the home screen, highest priority first. */
@Composable
fun rememberHomeCards(signalRClient: SignalRClient, mainViewModel: MainViewModel): List<HomeCard> {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AgentInboxStore.init(context)
        HomeCardDismissals.init(context)
    }
    val sessions by signalRClient.agentSessions.collectAsState()
    val local by AgentInboxStore.state.collectAsState()
    val reminders by signalRClient.reminders.collectAsState()
    val posted by HomeCardFeed.posted.collectAsState()
    val dismissed by HomeCardDismissals.dismissed.collectAsState()
    val now = rememberNow(30_000)
    val minute = now / MINUTE_MS
    val alarms = remember(minute) { alarmCards(context, mainViewModel, now) }
    return remember(sessions, local, reminders, posted, dismissed, minute) {
        (agentCards(signalRClient, mainViewModel, sessions, local, now) + reminderCards(signalRClient, mainViewModel, reminders, now) + alarms + posted)
            .filter { (it.expiresAt == null || it.expiresAt > now) && it.dismissKey !in dismissed }
            .sortedWith(compareByDescending<HomeCard> { it.priority }.thenByDescending { it.time })
    }
}

// ---------- UI ----------

private data class CardStyle(val accent: Color, val tint: Color, val glyph: String, val label: String)

@Composable
private fun styleOf(kind: HomeCardKind): CardStyle {
    val p = agentPalette()
    return when (kind) {
        HomeCardKind.QUESTION -> CardStyle(p.red, p.redBg, "?", "Question")
        HomeCardKind.ATTENTION -> CardStyle(p.yellow, p.yellowBg, "!", "Needs you")
        HomeCardKind.ERROR -> CardStyle(p.red, p.redBg, "×", "Error")
        HomeCardKind.DONE -> CardStyle(p.green, p.greenBg, "✓", "Finished")
        HomeCardKind.REMINDER -> CardStyle(p.blue, p.blueBg, "◷", "Reminder")
        HomeCardKind.ALARM -> CardStyle(p.muted, p.raised, "⏰", "Alarm")
        HomeCardKind.MESSAGE -> CardStyle(p.blue, p.blueBg, "✦", "Message")
    }
}

/** Compact, swipe-to-dismiss card strip for the top of the home screen. Renders nothing when empty. */
@Composable
fun HomeCardsSection(cards: List<HomeCard>, modifier: Modifier = Modifier, collapsedCount: Int = 3) {
    if (cards.isEmpty()) return
    val p = agentPalette()
    val context = LocalContext.current
    val now = rememberNow()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) cards else cards.take(collapsedCount)
    val urgent = cards.count { it.priority >= 85 }

    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOW", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = p.muted)
            Text("  ${cards.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.faint)
            if (urgent > 0) {
                Spacer(Modifier.width(8.dp))
                AgentTag("$urgent NEED YOU", p.red, p.redBg, p.redLine)
            }
            Spacer(Modifier.weight(1f))
            if (cards.size > collapsedCount) {
                Text(
                    if (expanded) "Show less" else "+${cards.size - collapsedCount} more",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.muted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { card ->
                key(card.id, card.dismissKey) {
                    SwipeableHomeCard(card, now) {
                        if (card.kind == HomeCardKind.MESSAGE) HomeCardFeed.remove(card.id)
                        HomeCardDismissals.dismiss(context, card.dismissKey)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun SwipeableHomeCard(card: HomeCard, now: Long, onDismiss: () -> Unit) {
    val p = agentPalette()
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) onDismiss()
        value != SwipeToDismissBoxValue.Settled
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(p.raised).padding(horizontal = 16.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
            ) { Text("Dismiss", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.muted) }
        }
    ) {
        HomeCardView(card, now)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeCardView(card: HomeCard, now: Long) {
    val p = agentPalette()
    val style = styleOf(card.kind)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.line, shape)
            .then(if (card.onOpen != null) Modifier.clickable { card.onOpen.invoke() } else Modifier)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(style.accent))
        Box(
            Modifier.padding(start = 9.dp, top = 9.dp).size(26.dp).background(style.tint, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(style.glyph, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = style.accent) }
        Column(Modifier.weight(1f).padding(start = 9.dp, end = 10.dp, top = 7.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                val ago = if (card.time in 1..now) agoShort(card.time, now) else ""
                if (ago.isNotEmpty()) Text(ago, fontSize = 11.sp, color = p.faint, modifier = Modifier.padding(start = 6.dp))
            }
            Text(
                listOfNotNull(style.label, card.source).joinToString(" · "),
                fontSize = 11.sp, color = style.accent, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!card.body.isNullOrBlank()) {
                Text(
                    card.body, fontSize = 12.sp, lineHeight = 16.sp, color = p.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (card.actions.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    card.actions.forEach { action ->
                        Box(
                            Modifier
                                .heightIn(min = 30.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (action.primary) p.text else p.raised)
                                .border(1.dp, if (action.primary) p.text else p.lineStrong, RoundedCornerShape(50))
                                .clickable(onClick = action.onClick)
                                .padding(horizontal = 11.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                action.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (action.primary) p.surface else p.text, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
