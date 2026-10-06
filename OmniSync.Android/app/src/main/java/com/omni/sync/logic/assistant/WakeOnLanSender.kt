package com.omni.sync.logic.assistant

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class WakeOnLanResult(
    val messages: List<String>,
    val anySuccess: Boolean
)

object WakeOnLanSender {
    fun send(
        macAddress: String,
        broadcastIp: String,
        wanIp: String,
        port: Int = 9
    ): WakeOnLanResult {
        val macBytes = parseMac(macAddress)
        val payload = ByteArray(6 + 16 * macBytes.size)
        repeat(6) { payload[it] = 0xff.toByte() }
        for (offset in 6 until payload.size step macBytes.size) {
            System.arraycopy(macBytes, 0, payload, offset, macBytes.size)
        }

        val messages = mutableListOf<String>()
        var anySuccess = false

        DatagramSocket().use { socket ->
            socket.broadcast = true

            fun trySend(label: String, host: String) {
                if (host.isBlank()) return
                try {
                    val address = InetAddress.getByName(host)
                    socket.send(DatagramPacket(payload, payload.size, address, port))
                    messages += "$label WOL sent to $host"
                    anySuccess = true
                } catch (error: Exception) {
                    messages += "$label WOL failed: ${error.message}"
                }
            }

            trySend("Subnet", broadcastIp)
            trySend("Global", "255.255.255.255")
            trySend("Remote", wanIp)
        }

        return WakeOnLanResult(messages, anySuccess)
    }

    private fun parseMac(macAddress: String): ByteArray {
        val hex = macAddress.replace(Regex("[:\\-]"), "")
        require(hex.length == 12) { "Invalid MAC address: $macAddress" }
        return ByteArray(6) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
