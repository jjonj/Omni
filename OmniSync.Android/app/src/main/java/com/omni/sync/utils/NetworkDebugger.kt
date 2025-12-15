package com.omni.sync.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object NetworkDebugger {
    private const val TAG = "NetworkDebugger"
    
    suspend fun debugConnection(context: Context, hubUrl: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== NETWORK DEBUG START ===")
        
        // 1. Check network connectivity
        val isConnected = isNetworkAvailable(context)
        Log.d(TAG, "Network available: $isConnected")
        
        // 2. Extract host from URL
        val host = extractHost(hubUrl)
        val port = extractPort(hubUrl)
        Log.d(TAG, "Target host: $host")
        Log.d(TAG, "Target port: $port")
        
        // 3. Test DNS resolution
        try {
            val address = InetAddress.getByName(host)
            Log.d(TAG, "DNS resolution successful: $address")
        } catch (e: Exception) {
            Log.e(TAG, "DNS resolution failed: ${e.message}")
        }
        
        // 4. Test HTTP connection
        testHttpConnection(hubUrl)
        
        // 5. Test base URL (without endpoint)
        val baseUrl = hubUrl.substringBefore("/signalrhub")
        Log.d(TAG, "Testing base URL: $baseUrl")
        testHttpConnection(baseUrl)
        
        // 6. Try alternative endpoints
        val alternatives = listOf(
            "$baseUrl/signalRhub",  // Different case
            "$baseUrl/rpcHub",      // Alternative endpoint
            "$baseUrl/",            // Root
        )
        
        alternatives.forEach { altUrl ->
            Log.d(TAG, "Testing alternative: $altUrl")
            testHttpConnection(altUrl)
        }
        
        Log.d(TAG, "=== NETWORK DEBUG END ===")
    }
    
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                Log.d(TAG, "Connected via WiFi")
                true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                Log.d(TAG, "Connected via Cellular (may not reach PC)")
                true
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                Log.d(TAG, "Connected via Ethernet")
                true
            }
            else -> false
        }
    }
    
    private fun extractHost(url: String): String {
        return try {
            URL(url).host
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract host from $url")
            "unknown"
        }
    }
    
    private fun extractPort(url: String): Int {
        return try {
            URL(url).port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract port from $url")
            -1
        }
    }
    
    private fun testHttpConnection(url: String) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP test to $url: Response code $responseCode")
            
            if (responseCode == 200) {
                Log.d(TAG, "✓ Successfully connected to $url")
            } else {
                Log.w(TAG, "⚠ Connected but got response code $responseCode")
            }
            
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to connect to $url: ${e.message}")
            Log.e(TAG, "  Error type: ${e.javaClass.simpleName}")
        }
    }
}
