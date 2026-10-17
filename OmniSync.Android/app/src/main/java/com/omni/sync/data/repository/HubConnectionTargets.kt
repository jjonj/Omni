package com.omni.sync.data.repository

import java.net.URI

internal object HubConnectionTargets {
    fun resolve(localUrl: String, wanIp: String, wifi: Boolean): List<String> {
        val local = localUrl.trim()
        val remote = if (wanIp.isBlank()) "" else runCatching {
            val uri = URI(local)
            URI(uri.scheme ?: "http", null, wanIp.trim(), uri.port, uri.path, uri.query, null).toString()
        }.getOrDefault("")
        return (if (wifi) listOf(local, remote) else listOf(remote, local)).filter { it.isNotBlank() }.distinct()
    }
}
