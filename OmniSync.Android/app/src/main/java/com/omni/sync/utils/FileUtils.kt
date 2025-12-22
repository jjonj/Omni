package com.omni.sync.utils

fun isVideoFile(filename: String): Boolean {
    val ext = filename.lowercase()
    return ext.endsWith(".mp4") || ext.endsWith(".mkv") || ext.endsWith(".avi") || 
           ext.endsWith(".mov") || ext.endsWith(".wmv") || ext.endsWith(".flv") || ext.endsWith(".webm")
}

fun isAudioFile(filename: String): Boolean {
    val ext = filename.lowercase()
    return ext.endsWith(".mp3") || ext.endsWith(".wav") || ext.endsWith(".ogg") || 
           ext.endsWith(".m4a") || ext.endsWith(".flac") || ext.endsWith(".aac")
}

fun isImageFile(filename: String): Boolean {
    val ext = filename.lowercase()
    return ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".png") || 
           ext.endsWith(".gif") || ext.endsWith(".bmp") || ext.endsWith(".webp")
}

fun isPdfFile(filename: String): Boolean {
    return filename.lowercase().endsWith(".pdf")
}