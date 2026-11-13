package com.monostr.nostr.model

import com.monostr.tips.event.Event as JsonEvent
import com.monostr.tips.Kinds

/** One NIP-88 response (kind 1018): who answered at [createdAt] with which option ids, in tag order. */
data class PollVote(val id: String, val pubkey: String, val createdAt: Long, val optionIds: List<String>)

/** NIP-88 response reading (2026-11-12). */
object PollVotes {
    /** The vote in [event] for the poll [pollId], or null for another kind, another poll or no usable `response` tag. */
    fun parse(event: JsonEvent, pollId: String): PollVote? {
        if (event.kind != Kinds.POLL_RESPONSE) return null
        if (event.tags.none { it.size >= 2 && it[0] == "e" && it[1] == pollId }) return null
        val ids = event.tags.filter { it.size >= 2 && it[0] == "response" && it[1].isNotBlank() }.map { it[1] }
        if (ids.isEmpty()) return null
        return PollVote(event.id, event.pubkey, event.createdAt, ids)
    }
}

/** The counted result: [total] voters, [counts] per option id, [mine] the own counted choice (null without a vote). */
data class Tally(val total: Int, val counts: Map<String, Int>, val mine: List<String>?) {
    /** Share of [total] in whole percent, rounded; 0 when nobody voted. */
    fun percent(optionId: String): Int =
        if (total == 0) 0 else Math.round((counts[optionId] ?: 0) * 100.0 / total).toInt()

    /** All option ids sharing the highest count; empty while no option has a vote. */
    val leading: Set<String> = run {
        val max = counts.values.maxOrNull() ?: 0
        if (max == 0) emptySet() else counts.filterValues { it == max }.keys
    }
}

/** NIP-88 counting (2026-11-12): one vote per pubkey, the newest one. */
object PollTally {
    /**
     * Counts [votes] for [poll]. Votes after [Poll.endsAt] drop, then each pubkey keeps its newest vote (the
     * higher event id on a tie in the same second). Unknown option ids are ignored; a single-choice poll takes the
     * first known one, a multiple-choice poll each known one once. Voters in [muted] drop; with [only] set, only those
     * pubkeys count. [Tally.mine] is the counted choice of [me], independent of [only].
     */
    fun count(poll: Poll, votes: List<PollVote>, me: String?, muted: Set<String>, only: Set<String>? = null): Tally {
        val known = poll.options.map { it.id }.toSet()
        val newest = HashMap<String, PollVote>()
        for (v in votes) {
            if (poll.endsAt != null && v.createdAt > poll.endsAt) continue
            val cur = newest[v.pubkey]
            if (cur == null || v.createdAt > cur.createdAt || (v.createdAt == cur.createdAt && v.id > cur.id)) newest[v.pubkey] = v
        }
        val counts = LinkedHashMap<String, Int>().apply { poll.options.forEach { put(it.id, 0) } }
        var total = 0
        var mine: List<String>? = null
        for (v in newest.values) {
            val chosen = v.optionIds.filter { it in known }.distinct().let { if (poll.multiple) it else it.take(1) }
            if (chosen.isEmpty()) continue
            if (v.pubkey == me) mine = chosen
            if (v.pubkey in muted || (only != null && v.pubkey !in only)) continue
            total++
            chosen.forEach { counts[it] = counts.getValue(it) + 1 }
        }
        return Tally(total, counts, mine)
    }
}
