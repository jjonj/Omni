package com.omni.sync.ui.screen

import androidx.compose.ui.graphics.Color

enum class LogType(val color: Color) {
    INFO(Color.Gray),
    SUCCESS(Color.Green),
    WARNING(Color.Yellow),
    ERROR(Color.Red)
}

data class LogEntry(
    val message: String,
    val type: LogType,
    val timestamp: Long = System.currentTimeMillis()
)
