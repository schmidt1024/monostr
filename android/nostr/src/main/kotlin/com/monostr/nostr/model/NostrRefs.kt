package com.monostr.nostr.model

/**
 * NIP-19 references in text. NIP-21 writes them `nostr:…`, but some clients (Nostur) post the bech32 alone. A bare
 * one counts only as a free-standing token of plausible length, so a path, a query value or a word stays what it is;
 * a token that does not decode stays plain text at the caller.
 */
object NostrRefs {
    /** Shorter than any note1/npub1 (63 characters): no decode for words like `note12`. */
    private const val MIN_BARE_DATA = 50
    private const val NOT_AFTER = "(?<![\\p{L}\\p{N}_/.:=@?&#%+~-])" // regex
    private const val NOT_BEFORE = "(?![\\p{L}\\p{N}_-])" // regex

    /** [prefixes]: bech32 prefixes joined by `|`, e.g. `note1|nevent1`; matched in any case, with or without `nostr:`. */
    fun pattern(prefixes: String): String =
        "(?:(?i:nostr:(?:$prefixes))[0-9a-zA-Z]+|$NOT_AFTER(?i:$prefixes)[0-9a-zA-Z]{$MIN_BARE_DATA,}$NOT_BEFORE)" // regex
}
