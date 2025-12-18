package com.omni.sync.data.model

import com.google.gson.annotations.SerializedName

data class NotificationAction(
    @SerializedName("id") val id: String,
    @SerializedName("label") val label: String,
    @SerializedName("command") val command: String,
    @SerializedName("isWol") val isWol: Boolean = false,
    @SerializedName("macAddress") val macAddress: String? = null
)
