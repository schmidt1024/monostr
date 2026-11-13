package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.PollLoader
import com.monostr.app.ui.common.PollSettings
import com.monostr.app.ui.common.PollUi
import com.monostr.app.ui.common.pollTimeLeft
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Poll
import com.monostr.nostr.model.PollOption
import com.monostr.nostr.model.PollVote
import com.monostr.nostr.repo.PollRepository
import com.monostr.tips.Kinds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PollLoaderTest {
    private val me = "e".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val now = 10_000L

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun poll(id: Char = '1', author: String = alice, endsAt: Long? = now + 3_600, multiple: Boolean = false) = Note(
        id.toString().repeat(64), author, "Which wallet?", now - 100, Kinds.POLL, null, null, null, emptyList(),
        poll = Poll(listOf(PollOption("o1", "Cake"), PollOption("o2", "Feather")), multiple, endsAt, emptyList()),
    )

    private fun vote(pubkey: String, option: String, at: Long = now - 50) = PollVote(pubkey.take(8) + option, pubkey, at, listOf(option))

    private class FakePolls(var votes: List<PollVote> = emptyList(), var sent: Boolean = true) : PollRepository {
        var fetches = 0
        val voted = ArrayList<List<String>>()
        /** When set, a vote waits for it (a vote still out). */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun votes(poll: Note): List<PollVote> { fetches++; return votes }
        override suspend fun vote(poll: Note, optionIds: List<String>): PublishResult {
            voted += optionIds
            gate?.await()
            return PublishResult("f".repeat(64), Kinds.POLL_RESPONSE, if (sent) listOf("wss://r.example") else emptyList(), emptyMap())
        }
        override suspend fun create(text: String, choices: List<String>, endsAt: Long, noPreview: Boolean): PublishResult = error("unused")
    }

    private class FakeSettings : PollSettings {
        override val firstVoteShown = MutableStateFlow(false)
        override suspend fun setFirstVoteShown() { firstVoteShown.value = true }
    }

    private fun TestScope.loader(
        polls: PollRepository,
        follows: Flow<Set<String>?> = MutableStateFlow(null),
        muted: Set<String> = emptySet(),
        clock: Long = now,
    ) = loader(polls, follows, muted) { clock }

    private fun TestScope.loader(
        polls: PollRepository,
        follows: Flow<Set<String>?> = MutableStateFlow(null),
        muted: Set<String> = emptySet(),
        clock: () -> Long,
    ) = PollLoader(polls, follows, MutableStateFlow(muted), me, FakeSettings(), clock, eager(), UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `open poll shows pills and total`() = runTest {
        val repo = FakePolls(listOf(vote(bob, "o1"), vote(carol, "o2")))
        val l = loader(repo)
        val p = poll()
        assertEquals(PollUi.Open(total = 2, endsAt = now + 3_600), l.state(p).value)
        l.state(p)
        assertEquals(1, repo.fetches) // once per poll, then from the cache
    }

    @Test
    fun `after own vote results with mine`() = runTest {
        val repo = FakePolls(listOf(vote(bob, "o1"), vote(carol, "o2")))
        val l = loader(repo)
        val p = poll()
        l.state(p)
        assertTrue(l.vote(p, listOf("o1")))
        val r = l.state(p).value as PollUi.Results
        assertEquals(listOf("o1"), r.tally.mine)
        assertEquals(3, r.tally.total)
        assertEquals(2, r.tally.counts["o1"])
        assertFalse(r.ended)
        assertEquals(listOf(listOf("o1")), repo.voted)
        assertEquals(1, repo.fetches) // merged locally, no refetch
    }

    @Test
    fun `ended poll shows final results`() = runTest {
        val l = loader(FakePolls(listOf(vote(bob, "o1"))), clock = now + 7_200)
        val r = l.state(poll()).value as PollUi.Results
        assertTrue(r.ended)
        assertNull(r.tally.mine)
        assertEquals(1, r.tally.total)
    }

    @Test
    fun `own poll shows results`() = runTest {
        val l = loader(FakePolls(listOf(vote(bob, "o2"))))
        val r = l.state(poll(author = me)).value as PollUi.Results
        assertFalse(r.ended)
        assertEquals(1, r.tally.counts["o2"])
    }

    @Test
    fun `following switch counts only follows`() = runTest {
        val follows = MutableStateFlow<Set<String>?>(null)
        val l = loader(FakePolls(listOf(vote(bob, "o1"), vote(carol, "o2"))), follows = follows, clock = now + 7_200)
        val p = poll()
        val before = l.state(p).value as PollUi.Results
        assertNull(before.followingTally) // follows not loaded yet: no switch
        follows.value = setOf(bob)
        l.showFollowing(p.id, true)
        val r = l.state(p).value as PollUi.Results
        assertTrue(r.followingOn)
        assertEquals(2, r.tally.total)
        assertEquals(1, r.followingTally!!.total)
        assertEquals(1, r.followingTally!!.counts["o1"])
        l.showFollowing(p.id, false)
        assertFalse((l.state(p).value as PollUi.Results).followingOn)
    }

    @Test
    fun `a failed vote keeps the pills and reports an error`() = runTest {
        val repo = FakePolls(listOf(vote(bob, "o1")), sent = false)
        val l = loader(repo)
        val p = poll()
        l.state(p)
        assertFalse(l.vote(p, listOf("o2")))
        assertEquals(PollUi.Open(total = 1, endsAt = now + 3_600, failed = true), l.state(p).value)
        repo.sent = true
        assertTrue(l.vote(p, listOf("o2")))
        assertEquals(listOf("o2"), (l.state(p).value as PollUi.Results).tally.mine)
    }

    @Test
    fun `a vote that throws counts as failed`() = runTest {
        val repo = object : PollRepository by FakePolls() {
            override suspend fun vote(poll: Note, optionIds: List<String>): PublishResult = throw IllegalStateException("offline")
        }
        val l = loader(repo)
        val p = poll()
        l.state(p)
        assertFalse(l.vote(p, listOf("o1")))
        assertEquals(PollUi.Open(total = 0, endsAt = now + 3_600, failed = true), l.state(p).value)
    }

    @Test
    fun `a double tap sends one vote only`() = runTest {
        val repo = FakePolls(listOf(vote(bob, "o1"))).apply { gate = CompletableDeferred() }
        val l = loader(repo)
        val p = poll()
        l.state(p)
        l.send(p, listOf("o1"))
        assertEquals(PollUi.Open(total = 1, endsAt = now + 3_600, sending = true), l.state(p).value)
        l.send(p, listOf("o2"))
        l.sendFirst(p, listOf("o2"))
        assertFalse(l.vote(p, listOf("o2")))
        repo.gate!!.complete(Unit)
        assertEquals(listOf(listOf("o1")), repo.voted)
        assertEquals(listOf("o1"), (l.state(p).value as PollUi.Results).tally.mine)
        l.send(p, listOf("o2")) // voted already: final
        assertEquals(listOf(listOf("o1")), repo.voted)
    }

    @Test
    fun `a poll that ended on screen sends no vote and shows the final results`() = runTest {
        var clock = now
        val repo = FakePolls(listOf(vote(bob, "o1")))
        val l = loader(repo) { clock }
        val p = poll()
        assertTrue(l.state(p).value is PollUi.Open)
        clock = now + 7_200
        assertFalse(l.vote(p, listOf("o2")))
        l.send(p, listOf("o2"))
        l.sendFirst(p, listOf("o2"))
        assertTrue(repo.voted.isEmpty())
        val r = l.state(p).value as PollUi.Results
        assertTrue(r.ended)
        assertNull(r.tally.mine)
    }

    @Test
    fun `no endsAt shows no time left`() = runTest {
        val l = loader(FakePolls(listOf(vote(bob, "o1"))), clock = Long.MAX_VALUE / 2)
        assertEquals(PollUi.Open(total = 1, endsAt = null), l.state(poll(endsAt = null)).value)
    }

    @Test
    fun `muted voters do not count`() = runTest {
        val l = loader(FakePolls(listOf(vote(bob, "o1"), vote(carol, "o2"))), muted = setOf(carol))
        assertEquals(PollUi.Open(total = 1, endsAt = now + 3_600), l.state(poll()).value)
    }

    @Test
    fun `time left is compact`() {
        assertEquals(uiText(R.string.time_days, 2L), pollTimeLeft(now + 2 * 86_400 + 5, now))
        assertEquals(uiText(R.string.time_hours, 5L), pollTimeLeft(now + 5 * 3_600 + 30, now))
        assertEquals(uiText(R.string.time_minutes, 12L), pollTimeLeft(now + 12 * 60, now))
        assertEquals(uiText(R.string.time_minutes, 1L), pollTimeLeft(now + 20, now))
    }
}
