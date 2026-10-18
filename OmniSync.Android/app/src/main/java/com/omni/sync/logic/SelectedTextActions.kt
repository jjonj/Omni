package com.omni.sync.logic

import java.net.URI
import java.net.URISyntaxException

/**
 * Accept only an absolute web URL from Android's selected text.
 * The browser command must never interpret arbitrary text as a URL.
 */
object SelectedTextActions {
    fun webUrlOrNull(selection: String): String? {
        val candidate = selection.trim()
        if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) return null
        return try {
            val uri = URI(candidate)
            if ((uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
            ) candidate else null
        } catch (_: URISyntaxException) {
            null
        }
    }
}
