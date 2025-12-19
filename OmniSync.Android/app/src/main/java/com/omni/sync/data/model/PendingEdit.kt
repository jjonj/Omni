package com.omni.sync.data.model

data class PendingEdit(
    val path: String,
    val content: String,
    val originalLastModified: Long, // timestamp
    val isNewFile: Boolean = false
)
