package com.omni.sync.data.repository

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.omni.sync.viewmodel.MainViewModel

/**
 * The original C# Hub streamed media without authentication, but the Rust Hub
 * requires the existing SignalR API key on its REST media endpoints.
 * Keep the key in request headers, never in media URLs, and do not forward it
 * to arbitrary web URLs or another host reached through an HTTP redirect.
 */
object HubMediaAuth {
    const val HEADER = "X-Omni-Api-Key"

    private fun effectivePort(uri: Uri): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    internal fun isHubMediaUri(request: Uri, hubBaseUrl: String): Boolean {
        val hub = Uri.parse(hubBaseUrl)
        val requestScheme = request.scheme ?: return false
        val hubScheme = hub.scheme ?: return false
        if (!requestScheme.equals("http", true) && !requestScheme.equals("https", true)) return false
        if (!requestScheme.equals(hubScheme, true)) return false
        if (request.host.isNullOrBlank() || !request.host.equals(hub.host, true)) return false
        if (effectivePort(request) != effectivePort(hub)) return false
        if (request.encodedAuthority?.contains('@') == true) return false
        // Only the Hub's media endpoints are permitted to receive this key.
        return request.path == "/api/stream" || request.path == "/api/screenshot"
    }

    fun headersFor(request: Uri, model: MainViewModel): Map<String, String> {
        val key = model.appConfig.value.apiKey.takeIf { it.isNotBlank() } ?: return emptyMap()
        return if (isHubMediaUri(request, model.getBaseUrl())) mapOf(HEADER to key)
               else emptyMap()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun createPlayer(context: Context, model: MainViewModel): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
        val resolving = ResolvingDataSource.Factory(http, ResolvingDataSource.Resolver { spec ->
            val headers = headersFor(spec.uri, model)
            if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers)
        })
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, resolving)))
            .build()
    }
}
