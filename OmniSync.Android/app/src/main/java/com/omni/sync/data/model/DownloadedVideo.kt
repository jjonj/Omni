package com.omni.sync.data.model

import java.util.Date

data class DownloadedVideo(
    val id: String,  // Unique identifier
    val originalPath: String,  // Original path on PC
    val fileName: String,  // Original filename
    val localPath: String,  // Path to the downloaded file in app data
    val fileSize: Long,  // Size in bytes
    val downloadDate: Date,  // When it was downloaded
    val isEncrypted: Boolean,  // Whether the file is encrypted
    val thumbnailPath: String? = null  // Optional thumbnail
)
