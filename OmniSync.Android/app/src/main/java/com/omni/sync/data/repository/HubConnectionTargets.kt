package com.omni.sync.data.repository

import java.net.URI

internal object HubConnectionTargets {
    // Produce the exact SignalR endpoint from an IP, IP:port or full URL.
    // Preserve the LAN endpoint's scheme/path when they are not explicitly supplied.
    fun resolveWanUrl(localUrl: String, wanAddress: String): String {
        val parsed = runCatching { URI(localUrl.trim()) }.getOrNull()
        val raw = wanAddress.trim()
        return runCatching {
            if (raw.isBlank()) return@runCatching ""
            val explicitUrl = raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            val wan = URI(if (explicitUrl) raw else "http://$raw")
            if (wan.host.isNullOrBlank() || wan.userInfo != null ||
                (wan.scheme != "http" && wan.scheme != "https") ||
                wan.port > 65535) return@runCatching ""
            if (explicitUrl) {
                if (wan.path.isNullOrBlank() || wan.path == "/")
                    URI(wan.scheme, null, wan.host, wan.port, parsed?.path, parsed?.query, null).toString()
                else wan.toString()
            } else {
                if (!wan.path.isNullOrBlank() || wan.query != null || wan.fragment != null)
                    return@runCatching ""
                URI(parsed?.scheme ?: "http", null, wan.host,
                    if (wan.port >= 0) wan.port else parsed?.port ?: -1,
                    parsed?.path, parsed?.query, null).toString()
            }
        }.getOrDefault("")
    }

    // Hub discovery sends only the IP. Keep a manually chosen WAN port when
    // refreshing that IP, so a test port does not silently revert to :5000.
    fun updateDiscoveredIp(existingWanAddress: String, updatedIp: String): String {
        val previous = existingWanAddress.trim()
        if (previous.startsWith("http://", ignoreCase = true) ||
            previous.startsWith("https://", ignoreCase = true)) return previous
        val customPort = runCatching { URI("http://$previous").port }.getOrDefault(-1)
        return if (customPort in 1..65535) "$updatedIp:$customPort" else updatedIp
    }

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
        if (!legacyLan) return listOfNotNull(local.takeIf { it.isNotBlank() })

        val remote = resolveWanUrl(local, wanIp)
        // Without Wi-Fi, a private LAN address cannot be reached. Never waste
        // the mobile-data reconnect timeout attempting it after WAN fails.
        return (if (wifi) listOf(local, remote) else listOf(remote))
            .filter { it.isNotBlank() }.distinct()
    }
}
