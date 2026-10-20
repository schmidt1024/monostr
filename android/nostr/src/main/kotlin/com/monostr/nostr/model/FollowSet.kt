package com.monostr.nostr.model

/**
 * The address of an addressable event (NIP-01): `kind:pubkey:d`. Lists are kind 30000 follow sets
 * (spec 2026-10-18 §3.1); the string form is what the pinned tabs store and the route `list/{address}` carries.
 */
data class ListAddress(val kind: Int, val pubkey: String, val d: String) {
    override fun toString(): String = "$kind:$pubkey:$d"

    companion object {
        const val FOLLOW_SET = 30000
        private val HEX64 = Regex("[0-9a-f]{64}")

        /** Null unless [s] is `30000:<64 lower-case hex>:<d>`; the d is everything after the second ':' (it may hold more). */
        fun parse(s: String): ListAddress? {
            val parts = s.split(':', limit = 3)
            if (parts.size != 3) return null
            if (parts[0].toIntOrNull() != FOLLOW_SET) return null
            if (!parts[1].matches(HEX64)) return null
            return ListAddress(FOLLOW_SET, parts[1], parts[2])
        }
    }
}

/**
 * A NIP-51 follow set (kind 30000) as the app shows it (spec 2026-10-18 §3.1). [title] is empty for a list
 * without one (the UI says "Untitled list"). [members]: 64-hex pubkeys, public and (own lists) decrypted
 * private ones together. [private]: written NIP-44-encrypted. [locked]: an own list whose private part could
 * not be decrypted; it is never written then. [extra]: tags of a list made elsewhere that monostr does not
 * edit (e.g. `image`), written back as they were.
 * [eventId]: the event the list was read from; empty for one that was not read from an event yet (being created).
 * The decrypt cache and the persisted summary key on it (spec perf 3.7).
 */
data class FollowSet(
    val address: ListAddress,
    val title: String,
    val description: String?,
    val members: List<String>,
    val private: Boolean,
    val author: String,
    val createdAt: Long,
    val locked: Boolean,
    val extra: List<List<String>> = emptyList(),
    val eventId: String = "",
)

/** How a list or pin change went (spec 2026-10-18 §3.3, §3.4). */
sealed interface SaveResult {
    data object Ok : SaveResult
    /** No relay accepted it, or the signer refused: the change was rolled back. */
    data object Failed : SaveResult
    /** A sixth pin: nothing changed (spec §2.2). */
    data object TooMany : SaveResult
    /** The list's private part is unreadable: writing it would lose members, so nothing was written. */
    data object Locked : SaveResult
}
