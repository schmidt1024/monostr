package com.monostr.nostr.model

/** NIP-10 thread references from `e` tags: marked (root/reply) or positional, with the relay/author hints of those tags (spec 2026-10-23 §4.1). */
data class ThreadRefs(val rootId: String?, val replyToId: String?, val rootHint: RefHint? = null, val replyHint: RefHint? = null)

object Nip10 {
    private val HEX64 = Regex("^[0-9a-f]{64}$") // regex

    /** Relay (3rd field) and author (5th field) of an `e` tag; junk is dropped, all-null is null. */
    internal fun hint(tag: List<String>): RefHint? {
        val relay = tag.getOrNull(2)?.trim()?.trimEnd('/')?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        val author = tag.getOrNull(4)?.lowercase()?.takeIf { HEX64.matches(it) }
        return if (relay == null && author == null) null else RefHint(relay, author)
    }

    fun parse(tags: List<List<String>>): ThreadRefs {
        val eTags = tags.filter { it.size >= 2 && it[0] == "e" && it[1].length == 64 && it.getOrNull(3) != "mention" }
        if (eTags.isEmpty()) return ThreadRefs(null, null)
        val rootTag = eTags.firstOrNull { it.getOrNull(3) == "root" }
        val replyTag = eTags.firstOrNull { it.getOrNull(3) == "reply" }
        if (rootTag != null || replyTag != null) {
            val rootHint = (rootTag ?: replyTag)?.let(::hint)
            val replyHint = (replyTag ?: rootTag)?.let(::hint)
            return ThreadRefs((rootTag ?: replyTag)!![1], (replyTag ?: rootTag)!![1], rootHint, replyHint)
        }
        // positional (deprecated): first = root, last = reply
        val first = eTags.first()
        val last = eTags.last()
        return ThreadRefs(rootId = first[1], replyToId = last[1], rootHint = hint(first), replyHint = hint(last))
    }
}
