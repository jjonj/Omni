package com.omni.sync.ui.screen

import kotlin.math.abs
import kotlin.math.hypot

/** Coordinate frame matches FUTO's pretrained English QWERTY decoder. */
internal object SwipeGeometry {
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    fun center(char: Char): Pair<Float, Float>? {
        val c = char.lowercaseChar()
        rows.forEachIndexed { row, keys ->
            val index = keys.indexOf(c)
            if (index >= 0) {
                val x = when (row) {
                    0 -> (index + 0.5f) / 10f
                    1 -> (index + 1f) / 10f
                    else -> (index + 2f) / 10f
                }
                return x to (row + 0.5f) / 3f
            }
        }
        return null
    }

    fun keyAt(x: Float, y: Float): Char? {
        if (x !in 0f..1f || y !in 0f..1f) return null
        val row = (y * 3f).toInt().coerceIn(0, 2)
        val keys = rows[row]
        val candidate = keys.minByOrNull { abs((center(it)?.first ?: 0f) - x) } ?: return null
        return candidate.takeIf {
            val (cx, cy) = center(it) ?: return@takeIf false
            abs(cx - x) < 0.052f && abs(cy - y) < 0.165f
        }
    }

    fun isSwipe(path: List<SwipePoint>): Boolean {
        if (path.size < 3) return false
        val unique = path.mapNotNull { keyAt(it.x, it.y) }.distinct()
        if (unique.size < 2) return false
        val distance = path.zipWithNext().sumOf { (a, b) ->
            hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }
        return distance >= 0.20
    }
}
internal data class SwipePoint(val x: Float, val y: Float, val ms: Float)
