package com.monostr.nostr.model

/** NIP-10 thread references from `e` tags: marked (root/reply) or positional. */
data class ThreadRefs(val rootId: String?, val replyToId: String?)

object Nip10 {
    fun parse(tags: List<List<String>>): ThreadRefs {
        val eTags = tags.filter { it.size >= 2 && it[0] == "e" && it[1].length == 64 && it.getOrNull(3) != "mention" }
        if (eTags.isEmpty()) return ThreadRefs(null, null)
        val root = eTags.firstOrNull { it.getOrNull(3) == "root" }?.get(1)
        val reply = eTags.firstOrNull { it.getOrNull(3) == "reply" }?.get(1)
        if (root != null || reply != null) {
            return ThreadRefs(rootId = root ?: reply, replyToId = reply ?: root)
        }
        // positional (deprecated): first = root, last = reply
        val first = eTags.first()[1]
        val last = eTags.last()[1]
        return ThreadRefs(rootId = first, replyToId = last)
    }
}
