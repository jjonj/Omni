package com.omni.sync.data.model

data class Macro(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String,
    var iconName: String = "Macro", // For mapping to an icon later
    var script: String,
    var isQuickAction: Boolean = false
)
