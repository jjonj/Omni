package com.omni.sync.data.model

import java.util.Date

data class FileSystemEntry(
    val name: String,
    val path: String, // Relative path from browse root
    val isDirectory: Boolean,
    val size: Long, // For files
    val lastModified: Date
)
