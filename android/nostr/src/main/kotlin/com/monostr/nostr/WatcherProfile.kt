package com.monostr.nostr

import com.monostr.tips.Watcher
import rust.nostr.sdk.Nip19
import rust.nostr.sdk.Nip19Enum
import rust.nostr.sdk.PublicKey

/** A personal watcher as the user pastes it: its key plus relay hints (spec 0.4). [relays] may be empty; the caller reports that. */
data class WatcherProfile(val pubkey: String, val relays: List<String>) {
    companion object {
        private val HEX64 = Regex("[0-9a-fA-F]{64}")

        /**
         * Reads `nprofile1…`, `npub1…` or 64 hex, with an optional `nostr:` prefix. Relays are the nprofile hints
         * plus [relay], each normalised to wss, without duplicates, at most [Watcher.MAX_RELAYS]. Null when no key can be read.
         */
        fun parse(input: String, relay: String = ""): WatcherProfile? {
            val s = input.trim().let { if (it.startsWith("nostr:", ignoreCase = true)) it.substring(6) else it }.trim()
            val hints = ArrayList<String>()
            val pubkey = if (HEX64.matches(s)) {
                s.lowercase()
            } else {
                when (val e = runCatching { Nip19.fromBech32(s).asEnum() }.getOrNull()) {
                    is Nip19Enum.Profile -> {
                        hints += e.nprofile.relays().map { it.toString() }
                        e.nprofile.publicKey().toHex()
                    }
                    is Nip19Enum.Pubkey -> e.npub.toHex()
                    else -> return null
                }
            }
            if (runCatching { PublicKey.parse(pubkey) }.isFailure) return null
            val relays = (hints + relay).mapNotNull { Watcher.normalizeRelay(it) }.distinct().take(Watcher.MAX_RELAYS)
            return WatcherProfile(pubkey, relays)
        }
    }
}
