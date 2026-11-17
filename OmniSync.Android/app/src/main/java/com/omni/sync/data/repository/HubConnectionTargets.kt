package com.omni.sync.data.repository

import java.net.URI

internal object HubConnectionTargets {
    fun resolve(localUrl: String, wanIp: String, wifi: Boolean): List<String> {
        val local = localUrl.trim()
        val parsed = runCatching { URI(local) }.getOrNull()
        // Only the legacy .37 LAN address opts into public-IP fallback.
        // Tailscale hosts, HTTPS proxy names, custom IPs and test endpoints
        // must never be rewritten based on a phone's (possibly airplane) Wi-Fi.
        val host = parsed?.host.orEmpty()
        val legacyLan = host.endsWith(".37") &&
            (host.startsWith("192.168.") || host.startsWith("10.") ||
                host.split('.').take(2).let {
                    it.size == 2 && it[0] == "172" && (it[1].toIntOrNull() ?: 0) in 16..31
                })
        val remote = if (!legacyLan || wanIp.isBlank()) "" else runCatching {
            URI(parsed?.scheme ?: "http", null, wanIp.trim(), parsed?.port ?: -1,
                parsed?.path, parsed?.query, null).toString()
        }.getOrDefault("")
        return (if (wifi) listOf(local, remote) else listOf(remote, local))
            .filter { it.isNotBlank() }.distinct()
    }
}
