package com.omni.sync.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.sync.data.repository.AgentSessionUi
import kotlin.math.ceil

/**
 * Exact icon geometry from T3 desktop's Lucide AlarmClockCheck / BookmarkCheck and
 * CaffyCoffeePotIcon. Keep the 24-unit paths and 1.8-unit stroke together with T3.
 * The desktop Caffy badge uses both the coffee-pot glyph AND its semantic mode code.
 */
internal enum class T3SidebarGlyph { Snooze, PersistentAttention, Caffy }

private fun svgPath(value: String): Path = PathParser().parsePathString(value).toPath()

private val snoozePaths by lazy {
    listOf(
        svgPath("M5 3 2 6"),
        svgPath("m22 6-3-3"),
        svgPath("M6.38 18.7 4 21"),
        svgPath("M17.64 18.67 20 21"),
        svgPath("m9 13 2 2 4-4")
    )
}
private val attentionPaths by lazy {
    listOf(
        svgPath("M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"),
        svgPath("m9 10 2 2 4-4")
    )
}
private val coffeePaths by lazy {
    listOf(
        svgPath("M4 8h12v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5V8Z"),
        svgPath("M16 9h1.4a3 3 0 0 1 0 6H16"),
        svgPath("M2.8 20h16.8"),
        svgPath("M8 5c-.8-1 .7-1.5.1-2.4"),
        svgPath("M12 5c-.8-1 .7-1.5.1-2.4")
    )
}

@Composable
internal fun T3SidebarStatusGlyph(
    glyph: T3SidebarGlyph,
    color: Color,
    label: String,
    modifier: Modifier = Modifier.size(13.dp)
) {
    Canvas(modifier.semantics { contentDescription = label }) {
        val scaleBy = size.minDimension / 24f
        withTransform({ scale(scaleBy, scaleBy, pivot = Offset.Zero) }) {
            val line = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (glyph == T3SidebarGlyph.Snooze) {
                drawCircle(color, radius = 8f, center = Offset(12f, 13f), style = line)
            }
            val paths = when (glyph) {
                T3SidebarGlyph.Snooze -> snoozePaths
                T3SidebarGlyph.PersistentAttention -> attentionPaths
                T3SidebarGlyph.Caffy -> coffeePaths
            }
            paths.forEach { drawPath(it, color, style = line) }
        }
    }
}

internal data class T3CaffyCompactBadge(val code: String, val color: Color, val label: String)

private fun shortCaffyInterval(minutes: Int?): String {
    if (minutes == null || minutes < 1) return "?"
    return when {
        minutes % 10080 == 0 -> "${minutes / 10080}w"
        minutes % 1440 == 0 -> "${minutes / 1440}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

/** Mirrors packages/contracts/src/caffyStatusPresentation.ts caffyBadge(). */
internal fun desktopCaffyBadge(session: AgentSessionUi, now: Long): T3CaffyCompactBadge? {
    val mode = session.caffyMode ?: return null
    val period = shortCaffyInterval(session.caffyIntervalMinutes)
    if (session.caffyPaused) {
        return T3CaffyCompactBadge(
            if (mode == "periodic") period else "Ⅱ",
            Color(0xFFFDBA74), "Caffy paused")
    }
    val begin = session.caffyBeginAt
    if (begin != null && begin > now) {
        val minutesAhead = ceil((begin - now).toDouble() / 60_000).toInt().coerceAtLeast(1)
        return T3CaffyCompactBadge("+${shortCaffyInterval(minutesAhead)}",
            Color(0xFF93C5FD), "Caffy delayed start")
    }
    if (session.caffyOnceOnly) {
        return T3CaffyCompactBadge("1×", Color(0xFFA5F3FC), "Caffy continue once")
    }
    return when (mode) {
        "periodic" -> T3CaffyCompactBadge(period, Color(0xFFC4B5FD), "Caffy every $period")
        "startup" -> T3CaffyCompactBadge("↻", Color(0xFF6EE7B7), "Caffy on startup")
        "idle" -> T3CaffyCompactBadge("∞", Color(0xFF6EE7B7), "Caffy keep running")
        else -> null
    }
}

/** A single desktop-style mini badge: coffee pot + the T3 mode/time code. */
@Composable
internal fun T3CaffyCompactBadgeView(badge: T3CaffyCompactBadge) {
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.border(0.75.dp, badge.color.copy(alpha = 0.65f), shape)
        .background(badge.color.copy(alpha = 0.14f), shape)
        .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        T3SidebarStatusGlyph(T3SidebarGlyph.Caffy, badge.color, badge.label,
            modifier = Modifier.size(12.dp))
        Text(badge.code, color = badge.color, fontSize = 9.sp,
            lineHeight = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
