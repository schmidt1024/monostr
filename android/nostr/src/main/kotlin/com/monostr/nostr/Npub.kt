package com.monostr.nostr

import rust.nostr.sdk.Nip19
import rust.nostr.sdk.Nip19Enum
import rust.nostr.sdk.PublicKey

/** NIP-19 `npub` for display; hex stays the identity everywhere else. */
object Npub {
    fun encode(hex: String): String = PublicKey.parse(hex).toBech32()

    /** Hex of an `npub1…` (surrounding whitespace ignored); null for anything else, including hex and an empty value. */
    fun decodeOrNull(npub: String): String? {
        val s = npub.trim()
        if (!s.startsWith("npub1")) return null
        return (runCatching { Nip19.fromBech32(s).asEnum() }.getOrNull() as? Nip19Enum.Pubkey)?.npub?.toHex()
    }

    /** `npub1abcd…wxyz`: prefix plus four characters, enough to recognise a key in a list. */
    fun short(hex: String): String = encode(hex).let { it.take(9) + "…" + it.takeLast(4) }
}
