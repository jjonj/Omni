package com.omni.sync.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class HubConnectionTargetsTest {
    private val local = "http://192.168.1.37:5000/signalrhub"
    @Test fun wifiTriesLanBeforeWan() {
        assertEquals(listOf(local, "http://89.150.166.124:5000/signalrhub"), HubConnectionTargets.resolve(local, "89.150.166.124", true))
    }
    @Test fun cellularNeverTriesThePrivateLanFallback() {
        assertEquals(listOf("http://89.150.166.124:5000/signalrhub"), HubConnectionTargets.resolve(local, "89.150.166.124", false))
    }
    @Test fun remoteKeepsTheConfiguredSchemePortAndHubPath() {
        assertEquals("https://89.150.166.124:9443/custom/hub", HubConnectionTargets.resolve("https://192.168.1.37:9443/custom/hub", "89.150.166.124", false).first())
    }
    @Test fun emptyWanWithoutWifiDoesNotWasteTimeOnLan() {
        assertEquals(emptyList<String>(), HubConnectionTargets.resolve(local, "", false))
    }
    @Test fun fullWanUrlOverridesLanPortAndPath() {
        val full = "http://89.150.166.124:15000/signalrhub"
        assertEquals(listOf(full), HubConnectionTargets.resolve(local, full, false))
        assertEquals(listOf(local, full), HubConnectionTargets.resolve(local, full, true))
        assertEquals(listOf(full), HubConnectionTargets.resolve(local, "89.150.166.124:15000", false))
    }
    @Test fun manuallyEnteredIpAndPortDisplaysTheCompleteHubUrl() {
        assertEquals("http://89.150.166.124:15000/signalrhub",
            HubConnectionTargets.resolveWanUrl(local, "89.150.166.124:15000"))
        assertEquals("http://89.150.166.124:5000/signalrhub",
            HubConnectionTargets.resolveWanUrl(local, "89.150.166.124"))
    }
    @Test fun discoveredIpKeepsManualPortButNotAnOldFullUrl() {
        assertEquals("89.150.166.125:15000",
            HubConnectionTargets.updateDiscoveredIp("89.150.166.124:15000", "89.150.166.125"))
        assertEquals("89.150.166.125",
            HubConnectionTargets.updateDiscoveredIp("89.150.166.124", "89.150.166.125"))
        assertEquals("https://other.example:9443/signalrhub",
            HubConnectionTargets.updateDiscoveredIp("https://other.example:9443/signalrhub", "89.150.166.125"))
    }
    @Test fun explicitHttpsWanProxyRetainsItsOwnPort() {
        assertEquals(listOf("https://myhub.example:443/signalrhub"),
            HubConnectionTargets.resolve(local, "https://myhub.example:443/signalrhub", false))
    }
    @Test fun flightWifiCannotRewriteConfiguredTailscaleOrHttpsEndpoint() {
        val configured = "https://my-home.tailnet.ts.net:9443/signalrhub"
        assertEquals(listOf(configured),
            HubConnectionTargets.resolve(configured, "89.150.166.124", true))
        assertEquals(listOf(configured),
            HubConnectionTargets.resolve(configured, "89.150.166.124", false))
        val proxy = "https://10.0.2.2:15000/signalrhub"
        assertEquals(listOf(proxy), HubConnectionTargets.resolve(proxy, "89.150.166.124", true))
    }
}
