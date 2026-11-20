package com.omni.sync.ui.screen

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private val BLUE = Color(0xFF4DA3FF)
private val ORANGE = Color(0xFFFF8A3D)
private val GOLD = Color(0xFFFFC078)
private fun millis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
private data class Window(val id: String, val kind: String, val minutes: Int, val used: Float, val reset: Long?)
private data class Account(val name: String, val driver: String, val id: String, val checked: Long?,
                           val windows: List<Window>, val credits: Int?, val error: String?)
private data class Point(val at: Long, val used: Float)
private data class Series(val account: Account, val window: Window) {
    val key: String get() = JSONArray().put(account.driver).put(account.id).put(window.id)
        .put(((window.reset ?: 0L) + 30_000) / MIN).put(window.minutes).toString()
}
private fun parse(json: String): List<Account> {
    val array = JSONObject(json).optJSONArray("accounts") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val windows = item.optJSONArray("windows")
        Account(item.optString("name", "Provider"), item.optString("driver"),
            item.optString("instanceId", item.optString("name")), millis(item.optString("checkedAt")),
            (0 until (windows?.length() ?: 0)).mapNotNull window@ { j ->
                val w = windows?.optJSONObject(j) ?: return@window null
                Window(w.optString("id"), w.optString("kind"), w.optInt("windowDurationMins"),
                    w.optDouble("usedPercent").toFloat().coerceIn(0f, 100f), millis(w.optString("resetsAt")))
            }, item.optJSONObject("resetCredits")?.optInt("availableCount"),
            item.optJSONObject("unavailable")?.optString("message")?.ifBlank { null })
    }
}
/** Sample timestamps come from T3's provider checks, not from sheet renders. */
private class History(context: Context) {
    private val prefs = context.getSharedPreferences("omni-quota-pacing-v1", Context.MODE_PRIVATE)
    fun read(): Map<String, List<Point>> {
        val stored = runCatching { JSONObject(prefs.getString("history", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return stored.keys().asSequence().mapNotNull { key ->
            val list = stored.optJSONArray(key) ?: return@mapNotNull null
            key to (0 until list.length()).mapNotNull sample@ { i ->
                val point = list.optJSONObject(i) ?: return@sample null
                Point(point.optLong("at"), point.optDouble("used").toFloat())
            }.sortedBy { it.at }
        }.toMap()
    }
    fun add(accounts: List<Account>, now: Long): Map<String, List<Point>> {
        val data = read().toMutableMap()
        for (account in accounts) for (window in account.windows) {
            if (window.minutes !in listOf(300, 10080)) continue
            val reset = window.reset ?: continue
            val at = account.checked ?: continue
            if (reset <= now || at !in (reset - window.minutes * MIN)..now) continue
            val key = Series(account, window).key
            val points = data[key].orEmpty().filter { it.at >= reset - window.minutes * MIN }.toMutableList()
            if (points.isEmpty() || at > points.last().at) points += Point(at, window.used)
            data[key] = if (points.size > 256) points.filterIndexed { index, _ ->
                index % 2 == 0 || index == points.lastIndex
            } else points
        }
        val recent = data.filterValues { (it.lastOrNull()?.at ?: 0L) >= now - 7 * 24 * HOUR }
            .entries.sortedByDescending { it.value.lastOrNull()?.at ?: 0L }.take(32)
        val json = JSONObject()
        recent.forEach { (key, points) ->
            val samples = JSONArray()
            points.forEach { samples.put(JSONObject().put("at", it.at).put("used", it.used.toDouble())) }
            json.put(key, samples)
        }
        prefs.edit().putString("history", json.toString()).apply()
        return recent.associate { it.key to it.value }
    }
}
private suspend fun load(base: String, token: String, refresh: Boolean): List<Account> =
    withContext(Dispatchers.IO) {
        require(base.isNotBlank()) { "Connect to OmniHub to view quota pacing." }
        val url = base.trimEnd('/') + "/api/t3/usage" + if (refresh) "?refresh=1" else ""
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 25000
            conn.setRequestProperty("x-omni-api-key", token)
            if (conn.responseCode != 200) error("Quota data unavailable (" + conn.responseCode + ")")
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
private fun pick(accounts: List<Account>, driver: String, duration: Int, now: Long, fable: Boolean = false): Series? =
    accounts.asSequence().filter { it.driver == driver }.flatMap { account ->
        account.windows.asSequence().filter { w ->
            w.minutes == duration && w.reset?.let { it > now } == true &&
                (if (fable) w.id == "seven_day_fable" else
                    driver != "claudeAgent" || !w.id.startsWith("seven_day_") || w.id == "seven_day")
        }.map { Series(account, it) }
    }.sortedWith(compareByDescending<Series> { it.window.used }.thenBy { it.account.id }).firstOrNull()
private fun elapsed(w: Window, at: Long): Float {
    val reset = w.reset ?: return 0f
    return ((at - (reset - w.minutes * MIN)).toFloat() / (w.minutes * MIN)).coerceIn(0f, 1f)
}
private fun resetLabel(reset: Long?, now: Long): String {
    if (reset == null) return "unknown"
    val minutes = ((reset - now + MIN - 1) / MIN).coerceAtLeast(0)
    return when {
        minutes >= 1440 -> (minutes / 1440).toString() + "d " + ((minutes % 1440) / 60) + "h"
        minutes >= 60 -> (minutes / 60).toString() + "h " + (minutes % 60) + "m"
        else -> minutes.toString() + "m"
    }
}
private fun lastHour(points: List<Point>, now: Long): Float? {
    val last = points.lastOrNull() ?: return null
    if (last.at <= now - HOUR) return null
    val before = points.lastOrNull { it.at <= now - HOUR } ?: return null
    return (last.used - before.used).coerceAtLeast(0f)
}
@Composable private fun QuotaChart(label: String, now: Long, rows: List<Pair<Series, Color>>,
                                    history: Map<String, List<Point>>) {
    val p = agentPalette()
    val duration = if (label == "5h") 300 else 10080
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(label, color = p.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        rows.forEach { (row, color) ->
            val diff = row.window.used - elapsed(row.window, now) * 100f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.account.name + ": " + "%.0f%%".format(Locale.US, row.window.used),
                    color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Reset " + resetLabel(row.window.reset, now), color = p.muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (diff > 5) "Above ideal +" + "%.1fpp".format(Locale.US, diff)
                    else if (diff < -5) "Below ideal " + "%.1fpp".format(Locale.US, diff)
                    else "On ideal pace", color = if (diff > 5) p.yellow else p.muted, fontSize = 11.sp)
                Text("Last 1h: " + (lastHour(history[row.key].orEmpty(), now)
                    ?.let { "%.1f%%".format(Locale.US, it) } ?: "—"), color = p.muted, fontSize = 11.sp)
            }
            row.account.error?.let { Text("Last available reading: " + it, color = p.yellow, fontSize = 10.sp) }
        }
        Canvas(Modifier.fillMaxWidth().height(104.dp).padding(top = 6.dp)
            .background(p.surfaceAlt, RoundedCornerShape(7.dp))
            .border(1.dp, p.line, RoundedCornerShape(7.dp))) {
            val left = 12f; val right = size.width - 12f
            val top = 8f; val bottom = size.height - 12f
            val w = right - left; val h = bottom - top
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                drawLine(p.line, Offset(left, bottom - f * h), Offset(right, bottom - f * h), 1f)
            }
            drawLine(p.text.copy(alpha = .7f), Offset(left, bottom), Offset(right, top), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f)))
            val beforeReset = if (duration == 300) .8f else 6f / 7f
            val marker = left + beforeReset * w
            drawLine(p.text.copy(alpha = .4f), Offset(marker, top), Offset(marker, bottom), 2f)
            rows.forEach { (row, color) ->
                val xNow = left + elapsed(row.window, now) * w
                drawLine(color.copy(alpha = .35f), Offset(xNow, top), Offset(xNow, bottom), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
                val points = history[row.key].orEmpty().filter {
                    it.at >= (row.window.reset ?: 0L) - duration * MIN
                }
                if (points.isNotEmpty()) {
                    val path = Path()
                    points.forEachIndexed { i, point ->
                        val x = left + elapsed(row.window, point.at) * w
                        val y = bottom - point.used / 100f * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 3f))
                    val last = points.last()
                    drawCircle(color, 4f, Offset(left + elapsed(row.window, last.at) * w,
                        bottom - last.used / 100f * h))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (duration == 300) "5h ago" else "7d ago", color = p.muted, fontSize = 10.sp)
            Text("Dashed = ideal", color = p.muted, fontSize = 10.sp)
            Text("Reset", color = p.muted, fontSize = 10.sp)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = p.line)
    }
}

/** Same paired 5h-to-weekly observation model as desktop: no risk estimate from a single sample. */
private fun weeklyRisk(week: Series, history: Map<String, List<Point>>, now: Long): String? {
    val short = week.account.windows.firstOrNull { it.minutes == 300 && (it.reset ?: 0L) > now }
        ?: return null
    val shortPoints = history[Series(week.account, short).key].orEmpty()
    val weeklyAt = history[week.key].orEmpty().associate { it.at to it.used }
    var shortGain = 0f
    var weeklyGain = 0f
    for (i in 1 until shortPoints.size) {
        val previous = shortPoints[i - 1]; val current = shortPoints[i]
        val a = weeklyAt[previous.at] ?: continue
        val b = weeklyAt[current.at] ?: continue
        val ds = current.used - previous.used
        val dw = b - a
        if (ds > 0 && dw >= 0) { shortGain += ds; weeklyGain += dw }
    }
    if (shortGain < 5f || weeklyGain < .5f) return null
    val ratio = weeklyGain / shortGain
    val remaining = (98f - week.window.used).coerceAtLeast(0f)
    val equivalents = remaining / (100f * ratio)
    val reset = week.window.reset ?: return null
    val hoursLeft = (reset - now) / 3_600_000f
    val nextReset = ((short.reset ?: return null) - now) / 3_600_000f
    if (hoursLeft <= 0 || nextReset <= 0) return null
    val currentCapacity = minOf((100f - short.used).coerceAtLeast(0f) / 100f, nextReset / 2f)
    val first = minOf(equivalents, currentCapacity)
    val later = (equivalents - first).coerceAtLeast(0f)
    val future = kotlin.math.ceil(later.toDouble()).toInt()
    val minimumElapsed = if (future > 0)
        maxOf(first * 2f, nextReset) + (future - 1) * 5f + (later - future + 1) * 2f
        else first * 2f
    val activeHours = equivalents * 2f
    val awakeHours = hoursLeft * 16f / 24f
    if (remaining <= 0 || (activeHours <= awakeHours * .8f && minimumElapsed <= hoursLeft * .8f))
        return null
    val name = if (week.account.driver == "codex") "Codex" else "Claude"
    return name + " weekly quota risk: approximately " + "%.1f".format(Locale.US, equivalents) +
        " full 5h quota equivalents in " + "%.1f".format(Locale.US, hoursLeft) +
        "h until reset. Estimated from paired measured 5h/week changes."
}

@Composable fun T3QuotaPacingSheet(base: String, apiKey: String, onDismiss: () -> Unit) {
    val p = agentPalette()
    val context = LocalContext.current
    val historyStore = remember(context) { History(context) }
    var history by remember { mutableStateOf(historyStore.read()) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(base, apiKey, refresh) {
        var cycle = 0
        while (true) {
            loading = true
            try {
                val data = load(base, apiKey, refresh > 0 || cycle > 0)
                now = System.currentTimeMillis()
                history = historyStore.add(data, now)
                accounts = data
                error = null
            } catch (e: Exception) { error = e.message ?: "Quota pacing unavailable" }
            finally { loading = false }
            delay(5 * MIN)
            cycle++
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(MIN); now = System.currentTimeMillis() } }
    val short = listOfNotNull(pick(accounts, "codex", 300, now)?.let { it to BLUE },
        pick(accounts, "claudeAgent", 300, now)?.let { it to ORANGE })
    val weekly = listOfNotNull(pick(accounts, "codex", 10080, now)?.let { it to BLUE },
        pick(accounts, "claudeAgent", 10080, now)?.let { it to ORANGE },
        pick(accounts, "claudeAgent", 10080, now, true)?.let { it to GOLD })
    AgentSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Quota pacing", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { refresh++ }, enabled = !loading) {
                Text(if (loading) "Updating…" else "Refresh", color = p.blue)
            }
        }
        if (loading && accounts.isEmpty()) CircularProgressIndicator(
            Modifier.padding(22.dp).size(24.dp), color = p.blue)
        error?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            color = p.yellow, fontSize = 12.sp) }
        if (!loading && accounts.isEmpty()) Text("No quota windows reported by T3.",
            Modifier.padding(18.dp), color = p.muted)
        if (accounts.isNotEmpty()) Column(Modifier.padding(horizontal = 14.dp)) {
            val resetAccounts = accounts.filter { it.driver in listOf("codex", "claudeAgent") }
            if (resetAccounts.any { it.credits != null }) {
                Text("Banked resets: Codex " + resetAccounts.filter { it.driver == "codex" }
                    .sumOf { it.credits ?: 0 } + " · Claude " +
                    resetAccounts.filter { it.driver == "claudeAgent" }.sumOf { it.credits ?: 0 },
                    color = p.muted, fontSize = 12.sp)
            }
            if (short.isNotEmpty()) QuotaChart("5h", now, short, history)
            if (weekly.isNotEmpty()) QuotaChart("week", now, weekly, history)
            weekly.mapNotNull { (series, _) -> weeklyRisk(series, history, now) }.forEach { warning ->
                Text(warning, Modifier.padding(vertical = 5.dp), color = p.yellow, fontSize = 12.sp)
            }
            Text("History starts with actual Android observations; existing desktop chart history is not yet synchronised.",
                Modifier.padding(top = 8.dp, bottom = 10.dp), color = p.muted, fontSize = 11.sp)
        }
    }
}
