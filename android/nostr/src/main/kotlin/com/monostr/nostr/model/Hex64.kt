package com.monostr.nostr.model

/**
 * An event id or pubkey as the app handles it: 64 lowercase hex characters. Tag values come from strangers and went
 * unchecked into navigation routes and file names (bug bounty 2026-11-26). A loop, no regex: the mapper asks per tag.
 */
object Hex64 {
    fun isValid(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** A well-formed value that names nothing: where a bad one must still lead somewhere that only finds nothing. */
    val NONE = "0".repeat(64)

    fun orNone(value: String): String = if (isValid(value)) value else NONE
}
