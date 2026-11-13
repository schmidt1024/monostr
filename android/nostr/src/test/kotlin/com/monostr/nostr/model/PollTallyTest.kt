package com.monostr.nostr.model

import com.monostr.tips.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** NIP-88 counting: kind 1018 parsing and the one-vote-per-pubkey tally. */
class PollTallyTest {
    private val poll = Poll(listOf(PollOption("a", "A"), PollOption("b", "B"), PollOption("c", "C")), false, null, emptyList())
    private val multi = poll.copy(multiple = true)

    private fun vote(pk: String, at: Long, vararg ids: String, id: String = "id-$pk-$at") =
        PollVote(id, pk, at, ids.toList())

    private fun ev(kind: Int = 1018, tags: List<List<String>>) = Event("i", "p", 1L, kind, tags, "", "s")

    @Test
    fun `parse reads responses in order`() {
        val v = PollVotes.parse(ev(tags = listOf(listOf("e", "poll"), listOf("response", "b"), listOf("response", "a"))), "poll")
        assertNotNull(v)
        assertEquals(listOf("b", "a"), v!!.optionIds)
    }

    @Test
    fun `parse rejects wrong kind, other poll and no response`() {
        val tags = listOf(listOf("e", "poll"), listOf("response", "a"))
        assertNull(PollVotes.parse(ev(kind = 1, tags = tags), "poll"))
        assertNull(PollVotes.parse(ev(tags = tags), "other"))
        assertNull(PollVotes.parse(ev(tags = listOf(listOf("e", "poll"), listOf("response", " "))), "poll"))
    }

    @Test
    fun `newest vote per pubkey counts`() {
        val t = PollTally.count(poll, listOf(vote("x", 1, "a"), vote("x", 5, "b"), vote("y", 2, "a")), null, emptySet())
        assertEquals(2, t.total)
        assertEquals(1, t.counts["a"])
        assertEquals(1, t.counts["b"])
    }

    @Test
    fun `a vote after endsAt never counts, one at endsAt does`() {
        val p = poll.copy(endsAt = 100)
        val t = PollTally.count(p, listOf(vote("x", 50, "a"), vote("x", 100, "b"), vote("y", 101, "b"), vote("z", 150, "c")), null, emptySet())
        assertEquals(1, t.total)
        assertEquals(0, t.counts["a"])
        assertEquals(1, t.counts["b"])
        assertEquals(0, t.counts["c"])
    }

    @Test
    fun `single choice counts only the first response`() {
        val t = PollTally.count(poll, listOf(vote("x", 1, "b", "a")), null, emptySet())
        assertEquals(1, t.counts["b"])
        assertEquals(0, t.counts["a"])
    }

    @Test
    fun `multiple choice counts each option once per voter`() {
        val t = PollTally.count(multi, listOf(vote("x", 1, "a", "b", "a")), null, emptySet())
        assertEquals(1, t.total)
        assertEquals(1, t.counts["a"])
        assertEquals(1, t.counts["b"])
    }

    @Test
    fun `unknown option ids are ignored`() {
        val t = PollTally.count(poll, listOf(vote("x", 1, "zzz"), vote("y", 1, "zzz", "c")), null, emptySet())
        assertEquals(1, t.total)
        assertEquals(1, t.counts["c"])
    }

    @Test
    fun `muted voters do not count`() {
        val t = PollTally.count(poll, listOf(vote("x", 1, "a"), vote("y", 1, "a")), null, setOf("x"))
        assertEquals(1, t.total)
    }

    @Test
    fun `only limits to the given pubkeys (Following)`() {
        val t = PollTally.count(poll, listOf(vote("x", 1, "a"), vote("y", 1, "b"), vote("z", 1, "b")), null, emptySet(), setOf("y"))
        assertEquals(1, t.total)
        assertEquals(1, t.counts["b"])
        assertEquals(0, t.counts["a"])
    }

    @Test
    fun `same second - the higher event id wins`() {
        val t = PollTally.count(poll, listOf(vote("x", 5, "a", id = "ffff"), vote("x", 5, "b", id = "0001")), null, emptySet())
        assertEquals(1, t.counts["a"])
        assertEquals(0, t.counts["b"])
    }

    @Test
    fun `mine is the own counted choice, null without a vote`() {
        val votes = listOf(vote("me", 1, "c"), vote("y", 1, "a"))
        assertEquals(listOf("c"), PollTally.count(poll, votes, "me", emptySet()).mine)
        assertEquals(listOf("c"), PollTally.count(poll, votes, "me", emptySet(), setOf("y")).mine)
        assertNull(PollTally.count(poll, votes, "nobody", emptySet()).mine)
        assertNull(PollTally.count(poll, votes, null, emptySet()).mine)
    }

    @Test
    fun `percent rounds and leading holds ties`() {
        val t = PollTally.count(poll, listOf(vote("x", 1, "a"), vote("y", 1, "a"), vote("z", 1, "b")), null, emptySet())
        assertEquals(67, t.percent("a"))
        assertEquals(33, t.percent("b"))
        assertEquals(0, t.percent("c"))
        assertEquals(setOf("a"), t.leading)
        val tie = PollTally.count(poll, listOf(vote("x", 1, "a"), vote("y", 1, "b")), null, emptySet())
        assertEquals(setOf("a", "b"), tie.leading)
        val none = PollTally.count(poll, emptyList(), null, emptySet())
        assertEquals(0, none.percent("a"))
        assertEquals(emptySet<String>(), none.leading)
    }
}
