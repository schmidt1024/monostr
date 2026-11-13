package com.monostr.nostr.model

import kotlinx.serialization.Serializable

/** One answer of a [Poll]: [id] is what a response's `response` tag names, [label] what the voter reads. */
@Serializable
data class PollOption(val id: String, val label: String)

/**
 * A NIP-88 poll (kind 1068), read from the event's tags. [endsAt] is a unix time, null for a poll that never ends;
 * [relays] are where the poll asks for its responses.
 */
@Serializable
data class Poll(val options: List<PollOption>, val multiple: Boolean, val endsAt: Long?, val relays: List<String>) {
    fun ended(now: Long): Boolean = endsAt != null && now >= endsAt
}

/** NIP-88 reading (2026-11-12). */
internal object Polls {
    /** More options are cut off: a poll of dozens would fill the feed. */
    const val MAX_SHOWN = 10

    /**
     * The poll in [tags], or null when fewer than two usable options remain: the first of a repeated id wins, options
     * with a blank id or label drop, and only the first [MAX_SHOWN] are kept. A missing or non-numeric `endsAt` never ends.
     */
    fun parse(tags: List<List<String>>): Poll? {
        val options = LinkedHashMap<String, PollOption>()
        for (t in tags) {
            if (t.size < 3 || t[0] != "option") continue
            val label = t[2].trim()
            if (label.isEmpty() || t[1].isBlank() || t[1] in options) continue
            options[t[1]] = PollOption(t[1], label)
        }
        if (options.size < 2) return null
        return Poll(
            options = options.values.take(MAX_SHOWN),
            multiple = tags.firstOrNull { it.size >= 2 && it[0] == "polltype" }?.get(1) == "multiplechoice",
            endsAt = tags.firstOrNull { it.size >= 2 && it[0] == "endsAt" }?.get(1)?.trim()?.toLongOrNull(),
            relays = tags.filter { it.size >= 2 && it[0] == "relay" }.map { it[1] }.distinct(),
        )
    }
}
