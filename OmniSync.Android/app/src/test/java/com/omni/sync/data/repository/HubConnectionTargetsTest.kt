package com.omni.sync.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class HubConnectionTargetsTest {
    private val local = "http://192.168.1.37:5000/signalrhub"
    @Test fun wifiTriesLanBeforeWan() {
        assertEquals(listOf(local, "http://89.150.166.124:5000/signalrhub"), HubConnectionTargets.resolve(local, "89.150.166.124", true))
    }
    @Test fun cellularTriesWanBeforeLan() {
        assertEquals(listOf("http://89.150.166.124:5000/signalrhub", local), HubConnectionTargets.resolve(local, "89.150.166.124", false))
    }
    @Test fun remoteKeepsTheConfiguredSchemePortAndHubPath() {
        assertEquals("https://89.150.166.124:9443/custom/hub", HubConnectionTargets.resolve("https://192.168.1.37:9443/custom/hub", "89.150.166.124", false).first())
    }
    @Test fun emptyWanAddressKeepsTheLanFallback() {
        assertEquals(listOf(local), HubConnectionTargets.resolve(local, "", false))
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
