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
        if (!legacyLan) return listOfNotNull(local.takeIf { it.isNotBlank() })

        // WAN accepts an IP, IP:port, or full URL. A full URL preserves its
        // own port (e.g. the FlightLink test proxy on :15000), not LAN's :5000.
        val raw = wanIp.trim()
        val remote = runCatching {
            if (raw.isBlank()) return@runCatching ""
            val explicitUrl = raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            val wan = URI(if (explicitUrl) raw else "http://$raw")
            if (wan.host.isNullOrBlank() || wan.userInfo != null ||
                (wan.scheme != "http" && wan.scheme != "https")) return@runCatching ""
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

        // Without Wi-Fi, a private LAN address cannot be reached. Never waste
        // the mobile-data reconnect timeout attempting it after WAN fails.
        return (if (wifi) listOf(local, remote) else listOf(remote))
            .filter { it.isNotBlank() }.distinct()
    }
}
