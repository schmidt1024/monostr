package com.monostr.tips

import java.net.URI

/** The watcher a recipient names in kind 10037 (spec 0.4): an HTTP service, or a personal watcher reached over relays. */
sealed class Watcher {
    abstract val pubkey: String

    /** Old form `["watcher", url, pubkey]`: relays and registration over `/v1`. */
    data class Http(val url: String, override val pubkey: String) : Watcher()

    /** Pubkey form `["watcher", pubkey, relay…]`: intents go to [relays] (1 to [MAX_RELAYS], wss only). */
    data class Personal(override val pubkey: String, val relays: List<String>) : Watcher()

    companion object {
        const val MAX_RELAYS = 4

        /**
         * Canonical `wss://host[:port][/path]`, or null for anything else (other schemes, userinfo,
         * query, fragment, no host) or a LAN/loopback host (see [RelayHosts]). The relays in a kind 10037
         * are attacker-controlled.
         */
        fun normalizeRelay(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("wss", ignoreCase = true)) return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (!RelayHosts.isPublic(url.trim())) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return "wss://$host$port$path"
        }
    }
}
