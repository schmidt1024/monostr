package com.monostr.nostr

import rust.nostr.sdk.RelayUrl

/**
 * One spelling per relay, the one rust-nostr itself uses (scheme and host lower-cased, default port and trailing slash
 * dropped). Every comparison of relay URLs that decides something (the pool, the relay set, ForeignRelays, the NIP-42
 * AUTH allowlist) goes through it, so two spellings of one relay can never be told apart and two relays never share a key.
 */
object RelayKeys {
    /** The engine's key of a relay URL (pool, relay set, ForeignRelays, AUTH allowlist); null when rust cannot parse it. */
    fun of(raw: String): String? = runCatching { RelayUrl.parse(raw.trim()).toString().trimEnd('/') }.getOrNull()
}
