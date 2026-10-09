package com.monostr.nostr.search

import rust.nostr.sdk.Nip19
import rust.nostr.sdk.Nip19Enum

/**
 * What a search-field input means (spec 3.1). Pure function, no relays: bech32 goes through
 * rust-nostr, everything else is string inspection. Relay hints in nprofile/nevent are ignored.
 */
sealed class SearchQuery {
    data object Empty : SearchQuery()
    data class Profile(val pubkey: String) : SearchQuery()
    data class Thread(val noteId: String) : SearchQuery()
    /** Lowercase tag without the leading `#`. */
    data class Hashtag(val tag: String) : SearchQuery()
    data class Text(val text: String) : SearchQuery()

    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        private val HASHTAG = Regex("^#([\\p{L}\\p{N}_]+)$")
        private val BECH32_PREFIXES = listOf("npub1", "nprofile1", "note1", "nevent1")

        fun parse(input: String): SearchQuery {
            var q = input.trim()
            if (q.startsWith("nostr:", ignoreCase = true)) q = q.substring(6).trim()
            if (q.length < 2) return Empty
            if (HEX64.matches(q)) return Profile(q.lowercase())
            HASHTAG.find(q)?.let { return Hashtag(it.groupValues[1].lowercase()) }
            val lower = q.lowercase()
            if (BECH32_PREFIXES.any { lower.startsWith(it) }) {
                val parsed = runCatching { Nip19.fromBech32(lower).asEnum() }.getOrNull()
                when (parsed) {
                    is Nip19Enum.Pubkey -> return Profile(parsed.npub.toHex())
                    is Nip19Enum.Profile -> return Profile(parsed.nprofile.publicKey().toHex())
                    is Nip19Enum.Note -> return Thread(parsed.eventId.toHex())
                    is Nip19Enum.Event -> return Thread(parsed.event.eventId().toHex())
                    else -> {}
                }
            }
            return Text(q)
        }
    }
}
