package com.monostr.app.data

import java.net.URI

/** The Blossom server the app uploads pictures to (spec 5.4). */
object MediaServer {
    const val DEFAULT = "https://media.monostr.com"

    /**
     * A server address as the app stores it: `https://host[:port]`, lowercase, nothing after the
     * host. Null for anything else (another scheme, a path, a query, credentials, whitespace).
     */
    fun normalize(input: String): String? {
        val v = input.trim().trimEnd('/')
        // an Android keyboard capitalises the first letter: the scheme counts in any case
        if (!v.startsWith("https://", ignoreCase = true) || v.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(v) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty() || !uri.path.isNullOrEmpty() || uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        return "https://$host" + if (uri.port >= 0) ":${uri.port}" else ""
    }
}
