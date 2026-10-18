package com.monostr.nostr.repo

import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import rust.nostr.sdk.Event

/** Kind 30000 events ↔ [FollowSet] (spec 2026-10-18 §3.1). */
internal object FollowSetCodec {
    const val KIND = ListAddress.FOLLOW_SET

    /** Mute lists some clients keep as a follow set: never shown as a list. */
    private val HIDDEN_D = setOf("mute", "muted")

    /** The tags monostr edits; everything else goes to [FollowSet.extra]. */
    private val EDITED = setOf("d", "title", "name", "description", "p")
    private val HEX64 = Regex("[0-9a-f]{64}")

    /**
     * The list behind [event], or null when it is hidden: mute list, empty replacement of a deleted list,
     * or a foreign list with nothing readable. [privateTags] decrypts the private part of an own list; null
     * for foreign lists, whose private part is never read (only their public part shows).
     */
    suspend fun decode(event: Event, privateTags: PrivateTags?, interactive: Boolean): FollowSet? {
        val tags = event.tags().toVec().map { it.asVec() }.filter { it.isNotEmpty() }
        val d = tags.firstOrNull { it[0] == "d" }?.getOrNull(1) ?: return null
        if (d in HIDDEN_D) return null
        val content = event.content()
        if (tags.all { it[0] == "d" } && content.isBlank()) return null
        val author = event.author().toHex()
        val public = tags.filter { it[0] != "d" }
        var secret: List<List<String>> = emptyList()
        var locked = false
        val ownPrivate = privateTags != null && content.isNotBlank()
        if (privateTags != null && content.isNotBlank()) {
            when (val r = privateTags.decrypt(content, interactive)) {
                is Decrypted.Ok -> secret = r.value
                else -> locked = true
            }
        }
        fun first(name: String, from: List<List<String>>) = from.firstOrNull { it[0] == name && it.size >= 2 }?.get(1)?.takeIf { it.isNotBlank() }
        val title = first("title", public) ?: first("name", public) ?: first("title", secret) ?: first("name", secret) ?: ""
        val description = first("description", public) ?: first("description", secret)
        val all = public + secret
        val members = all.filter { it[0] == "p" && it.size >= 2 }.map { it[1].lowercase() }.filter { it.matches(HEX64) }.distinct()
        if (privateTags == null && title.isEmpty() && members.isEmpty()) return null
        return FollowSet(
            address = ListAddress(KIND, author, d),
            title = title,
            description = description,
            members = members,
            private = ownPrivate,
            author = author,
            createdAt = event.createdAt().asSecs().toLong(),
            locked = locked,
            extra = all.filter { it[0] !in EDITED },
        )
    }

    /** Clear tags and content of [set]: private = only `d` in clear, everything else NIP-44 to self. */
    suspend fun encode(set: FollowSet, privateTags: PrivateTags): Pair<List<List<String>>, String> {
        val items = buildList {
            if (set.title.isNotBlank()) add(listOf("title", set.title))
            set.description?.takeIf { it.isNotBlank() }?.let { add(listOf("description", it)) }
            set.members.forEach { add(listOf("p", it)) }
            addAll(set.extra)
        }
        val d = listOf("d", set.address.d)
        return if (set.private) listOf(d) to privateTags.encrypt(items) else (listOf(d) + items) to ""
    }
}
