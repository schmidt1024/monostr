package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteCounts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Filter
import rust.nostr.sdk.NostrSdkException
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class CountsRepositoryTest {
    @TempDir lateinit var dir: Path
    private val id = "a".repeat(64)
    private val other = "b".repeat(64)
    private val TIMEOUT = java.time.Duration.ofSeconds(5)

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())

    /** The kind a COUNT filter asks for, so a fake counter can answer per kind. */
    private fun kindOf(f: Filter): Int = f.asRecord().kinds!!.first().asU16().toInt()

    /** The note id behind the filter's `#e` tag (rust-nostr 0.44.8: `FilterRecord.genericTags`, one entry with key `e`; its values live under `GenericTag.value`, not `.values`). */
    private fun targetOf(f: Filter): String = f.asRecord().genericTags.first().value.first()

    @Test
    fun `rust-nostr's timeout error is a COUNT timeout, other errors are not`() {
        val timeout = NostrSdkException.Generic("timeout")
        val mapped = countFailure(timeout, 0, TIMEOUT)
        assertTrue(mapped is CountTimeoutException)
        assertSame(timeout, mapped.cause)
        val closed = NostrSdkException.Generic("closed: too many subscriptions")
        assertSame(closed, countFailure(closed, 0, TIMEOUT))
        // no typed error, but the failure came at the deadline
        assertTrue(countFailure(IllegalStateException("x"), TIMEOUT.toNanos(), TIMEOUT) is CountTimeoutException)
    }

    @Test
    fun `a relay whose COUNT fails with rust-nostr's timeout costs one call per round`() = runTest {
        val e = engine()
        val calls = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://dead", "wss://ok") },
            counter = { url, _ ->
                calls += url
                if (url == "wss://dead") throw countFailure(NostrSdkException.Generic("timeout"), 0, TIMEOUT) else 3L
            },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(1, calls.count { it == "wss://dead" })
        assertEquals(3L, repo.counts.value.getValue(id).likes)
    }

    @Test
    fun `max over relays per kind, three kinds per note`() = runTest {
        val e = engine()
        var time = 1000L
        val calls = ArrayList<Pair<String, Int>>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a", "wss://b") },
            counter = { url, f -> calls += url to kindOf(f); when (url to kindOf(f)) { "wss://a" to 7 -> 5L; "wss://b" to 7 -> 9L; "wss://a" to 6 -> 2L; "wss://b" to 6 -> 1L; else -> 0L } },
            now = { time }, batchDelayMs = 0,
        )
        repo.request(listOf(id))
        advanceUntilIdle()
        val c = repo.counts.value.getValue(id)
        assertEquals(9L, c.likes); assertEquals(2L, c.reposts); assertEquals(0L, c.replies)
        assertEquals(6, calls.size)
        // cached: a second request within maxAge does not count again
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(6, calls.size)
        // stale after 300 s: counted again
        time += 301
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(12, calls.size)
    }

    @Test
    fun `the replies COUNT asks for kind 1 replies and NIP-22 comments in one filter`() = runTest {
        // backlog 31: Fediverse replies bridged by Mostr are kind 1111
        val e = engine()
        val asked = ArrayList<List<Int>>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a") },
            counter = { _, f -> f.asRecord().kinds!!.map { it.asU16().toInt() }.also { synchronized(asked) { asked += listOf(it) } }.let { if (1 in it) 3L else 0L } },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request(listOf(id))
        advanceUntilIdle()
        assertEquals(3L, repo.counts.value.getValue(id).replies)
        assertEquals(3, asked.size) // still three requests per note and relay
        assertTrue(listOf(1, 1111) in asked.map { it.sorted() }, "asked: $asked")
    }

    @Test
    fun `a relay that keeps failing is left out after two failed rounds in a row and others still count`() = runTest {
        val e = engine()
        val calls = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://dead", "wss://ok") },
            counter = { url, _ -> calls += url; if (url == "wss://dead") throw IllegalStateException("timeout") else 3L },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(3L, repo.counts.value.getValue(id).likes)
        repo.request(listOf(other)); advanceUntilIdle()
        assertEquals(3L, repo.counts.value.getValue(other).replies)
        assertEquals(6, calls.count { it == "wss://dead" }) // two rounds in a row (three kinds each), then never again
        repo.request(listOf("c".repeat(64))); advanceUntilIdle()
        assertEquals(6, calls.count { it == "wss://dead" })
    }

    @Test
    fun `no relay answering leaves the kind unknown`() = runTest {
        val e = engine()
        val repo = NostrCountsRepository(e, this, relays = { emptyList() }, counter = { _, _ -> 1L }, now = { 1000 }, batchDelayMs = 0)
        repo.request(listOf(id)); advanceUntilIdle()
        assertNull(repo.counts.value[id]) // no relay answered at all: no entry, not just null fields
    }

    @Test
    fun `a relay failing mid-batch never wipes another note's known counts`() = runTest {
        val e = engine()
        val calls = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a") },
            counter = { _, f -> calls += "wss://a"; if (targetOf(f) == other && kindOf(f) == 7) throw IllegalStateException("timeout") else 4L },
            now = { 1000 }, batchDelayMs = 0, batchSize = 2,
        )
        repo.bump(other, 7, +2) // pre-seed `other` with a known (bumped) value before the batch runs
        repo.request(listOf(id, other)); advanceUntilIdle()
        assertEquals(4L, repo.counts.value.getValue(id).likes)
        assertEquals(2L, repo.counts.value.getValue(other).likes) // kept: the relay never answered for it
        val callsAfterBatch = calls.size
        repo.request(listOf("c".repeat(64))); advanceUntilIdle()
        assertEquals(callsAfterBatch + 3, calls.size) // spec perf2 A6: one failure (an overloaded relay) keeps the relay
    }

    @Test
    fun `bump adds at once and forces a recount on the next request`() = runTest {
        val e = engine()
        var answer = 4L
        val repo = NostrCountsRepository(e, this, relays = { listOf("wss://a") }, counter = { _, _ -> answer }, now = { 1000 }, batchDelayMs = 0)
        repo.request(listOf(id)); advanceUntilIdle()
        repo.bump(id, 7, +1)
        assertEquals(5L, repo.counts.value.getValue(id).likes)
        answer = 6L
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(6L, repo.counts.value.getValue(id).likes)
    }

    @Test
    fun `batches are collected for batchDelay and capped at batchSize`() = runTest {
        val e = engine()
        val seen = LinkedHashSet<String>()
        val repo = NostrCountsRepository(e, this, relays = { listOf("wss://a") }, counter = { _, f -> seen += targetOf(f); 1L }, now = { 1000 }, batchDelayMs = 300, batchSize = 2)
        repo.request((1..3).map { it.toString().repeat(64) })
        advanceTimeBy(100); assertEquals(0, seen.size)
        advanceUntilIdle()
        assertEquals(3, seen.size)
    }

    @Test
    fun `every note and kind of a batch is asked at once, at most six per relay`() = runTest {
        val e = engine()
        val inFlight = HashMap<String, Int>()
        val most = HashMap<String, Int>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a", "wss://b") },
            counter = { url, _ ->
                calls++
                inFlight[url] = (inFlight[url] ?: 0) + 1
                most[url] = maxOf(most[url] ?: 0, inFlight.getValue(url))
                try { release.await(); 1L } finally { inFlight[url] = inFlight.getValue(url) - 1 }
            },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request((1..20).map { it.toString(16).padStart(64, '0') }); runCurrent()
        assertEquals(12, calls, "six per relay in flight, the rest waits for a permit")
        release.complete(Unit); advanceUntilIdle()
        assertEquals(120, calls)
        assertEquals(mapOf("wss://a" to 6, "wss://b" to 6), most)
        assertEquals(20, repo.counts.value.size)
    }

    @Test
    fun `a fast relay's counts show before a slow one answers, which can only raise them`() = runTest {
        val e = engine()
        val slow = CompletableDeferred<Unit>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://fast", "wss://slow") },
            counter = { url, f ->
                if (url == "wss://slow") { slow.await(); if (kindOf(f) == 7) 9L else 0L } else if (kindOf(f) == 7) 4L else 1L
            },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request(listOf(id)); runCurrent()
        assertEquals(NoteCounts(4, 1, 1), repo.counts.value[id])
        slow.complete(Unit); advanceUntilIdle()
        assertEquals(NoteCounts(9, 1, 1), repo.counts.value[id])
    }

    @Test
    fun `with no relay connected yet the batch waits for one, and is not stamped when none comes`() = runTest {
        val e = engine()
        var up = emptyList<String>()
        var calls = 0
        val repo = NostrCountsRepository(e, this, relays = { up }, counter = { _, _ -> calls++; 2L }, now = { 1000 }, batchDelayMs = 0)
        repo.request(listOf(id)); advanceTimeBy(600); runCurrent()
        assertEquals(0, calls)
        up = listOf("wss://a"); advanceUntilIdle()
        assertEquals(2L, repo.counts.value.getValue(id).likes, "counted once the relay came, not five minutes later")
        up = emptyList()
        repo.request(listOf(other)); advanceUntilIdle()
        assertNull(repo.counts.value[other])
        up = listOf("wss://a")
        repo.request(listOf(other)); advanceUntilIdle()
        assertEquals(2L, repo.counts.value.getValue(other).likes, "the unstamped id was asked again")
    }

    @Test
    fun `a priority id is counted while a long normal batch still runs`() = runTest {
        val e = engine()
        val focused = "f".repeat(64)
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a") },
            counter = { _, f -> if (targetOf(f) != focused) delay(1_000); 5L },
            now = { 1000 }, batchDelayMs = 300,
        )
        repo.request((1..20).map { it.toString(16).padStart(64, '0') })
        advanceTimeBy(400); runCurrent() // the batch runs: 6 in flight, 54 waiting
        repo.request(listOf(focused), priority = true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(5L, repo.counts.value[focused]?.likes, "the focused note waited behind the batch")
        assertTrue(repo.counts.value.size < 21)
        advanceUntilIdle()
        assertEquals(21, repo.counts.value.size)
    }

    @Test
    fun `a recount never shows a lower number on the way, and the round's result lands at its end`() = runTest {
        val e = engine()
        val slow = CompletableDeferred<Unit>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://fast", "wss://slow") },
            counter = { url, f -> if (url == "wss://slow") { slow.await(); if (kindOf(f) == 7) 9L else 0L } else if (kindOf(f) == 7) 4L else 1L },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.bump(id, 7, 9)
        val seen = ArrayList<Long?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.counts.collect { seen += it[id]?.likes } }
        repo.request(listOf(id)); runCurrent()
        slow.complete(Unit); advanceUntilIdle()
        assertTrue(seen.none { it == 4L }, "shown: $seen")
        assertEquals(9L, repo.counts.value.getValue(id).likes)
    }

    @Test
    fun `a real drop shows when the round ends`() = runTest {
        val e = engine()
        val repo = NostrCountsRepository(e, this, relays = { listOf("wss://a", "wss://b") }, counter = { _, f -> if (kindOf(f) == 7) 9L else 0L }, now = { 1000 }, batchDelayMs = 0)
        repo.bump(id, 7, 10)
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(9L, repo.counts.value.getValue(id).likes)
    }

    @Test
    fun `calls failing together in one round are one failure`() = runTest {
        val e = engine()
        var fail = true
        var calls = 0
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a") },
            counter = { _, _ -> calls++; delay(10); if (fail) throw IllegalStateException("closed") else 2L },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request((1..2).map { it.toString(16).padStart(64, '0') }); advanceUntilIdle()
        assertEquals(6, calls) // six concurrent failures (all in flight before the first one fails), one round
        fail = false
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(9, calls, "the relay is still asked")
        assertEquals(2L, repo.counts.value.getValue(id).likes)
    }

    @Test
    fun `a relay that never answers costs one timeout per round, the rest of its calls in that round are skipped`() = runTest {
        val e = engine()
        val first = (1..20).map { it.toString(16).padStart(64, '0') }
        val silentTargets = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://silent", "wss://ok") },
            counter = { url, f -> if (url == "wss://silent") { silentTargets += targetOf(f); delay(5_000); throw CountTimeoutException() } else 2L },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request(first); runCurrent()
        assertEquals(20, repo.counts.value.size, "the other relay's counts land at once")
        val next = "c".repeat(64)
        repo.request(listOf(next))
        advanceTimeBy(5_500); runCurrent()
        assertEquals(2L, repo.counts.value[next]?.likes, "the next batch waited behind the silent relay")
        advanceUntilIdle()
        assertTrue(silentTargets.count { it in first } <= CountLimiter.PER_RELAY, "silent relay asked ${silentTargets.count { it in first }} times in one round")
    }

    @Test
    fun `a relay that connects after the first batch fills in the visible counts without a scroll`() = runTest {
        val e = engine()
        var time = 1000L
        var up = listOf("wss://a")
        val calls = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { up }, sessionRelays = { listOf("wss://a", "wss://b") },
            counter = { url, f -> calls += url; if (kindOf(f) != 7) 0L else if (url == "wss://a") 1L else 7L },
            now = { time }, batchDelayMs = 0,
        )
        repo.request(listOf(id)); advanceTimeBy(500); runCurrent()
        assertEquals(1L, repo.counts.value.getValue(id).likes)
        up = listOf("wss://a", "wss://b")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(7L, repo.counts.value.getValue(id).likes, "the late relay was not asked")
        advanceUntilIdle()
        assertEquals(3, calls.count { it == "wss://b" }, "one follow-up round for the late relay")
        assertEquals(3, calls.count { it == "wss://a" })
        // both relays have answered now: the normal stamp holds
        time += 21
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(6, calls.size)
    }

    @Test
    fun `a batch asked while a relay was missing gets a short stamp`() = runTest {
        val e = engine()
        var time = 1000L
        var calls = 0
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a") }, sessionRelays = { listOf("wss://a", "wss://b") },
            counter = { _, _ -> calls++; 1L }, now = { time }, batchDelayMs = 0,
        )
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(3, calls)
        time += 21
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(6, calls, "asked again after the short stamp")
    }

    @Test
    fun `with no relay at first the batch is counted when one connects, without a scroll`() = runTest {
        val e = engine()
        var up = emptyList<String>()
        val repo = NostrCountsRepository(e, this, relays = { up }, sessionRelays = { listOf("wss://a") }, counter = { _, _ -> 2L }, now = { 1000 }, batchDelayMs = 0)
        repo.request(listOf(id)); advanceTimeBy(3_000); runCurrent()
        assertNull(repo.counts.value[id])
        up = listOf("wss://a")
        advanceTimeBy(500); runCurrent()
        assertEquals(2L, repo.counts.value[id]?.likes, "not counted once the relay came")
    }

    @Test
    fun `with every session relay connected the normal stamp holds and no extra round runs`() = runTest {
        val e = engine()
        var time = 1000L
        var calls = 0
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a", "wss://b") }, sessionRelays = { listOf("wss://a", "wss://b") },
            counter = { _, _ -> calls++; 1L }, now = { time }, batchDelayMs = 0,
        )
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(6, calls)
        time += 21
        repo.request(listOf(id)); advanceUntilIdle()
        assertEquals(6, calls)
    }

    @Test
    fun `one CLOSED does not cut a relay off, it still answers the other notes of the round`() = runTest {
        val e = engine()
        val notes = (1..20).map { it.toString(16).padStart(64, '0') }
        var bCalls = 0
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a", "wss://b") },
            counter = { url, f -> if (url == "wss://a") 1L else { if (bCalls++ == 0) throw IllegalStateException("closed: too many subscriptions"); if (kindOf(f) == 7) 5L else 0L } },
            now = { 1000 }, batchDelayMs = 0,
        )
        repo.request(notes); advanceUntilIdle()
        assertEquals(60, bCalls)
        assertEquals(19, notes.count { repo.counts.value[it]?.likes == 5L })
    }

    @Test
    fun `a recount where a relay times out for some notes never drops them and asks again soon`() = runTest {
        val e = engine()
        var time = 1000L
        var stall = false
        val bTargets = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { listOf("wss://a", "wss://b") },
            counter = { url, f ->
                if (url == "wss://a") { if (kindOf(f) == 7) 1L else 0L } else {
                    bTargets += targetOf(f)
                    if (stall && targetOf(f) == other) { delay(5_000); throw CountTimeoutException() }
                    if (kindOf(f) == 7) 9L else 0L
                }
            },
            now = { time }, batchDelayMs = 0, batchSize = 2,
        )
        repo.request(listOf(id, other)); advanceUntilIdle()
        assertEquals(9L, repo.counts.value.getValue(other).likes)
        val seen = ArrayList<Long?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.counts.collect { seen += it[other]?.likes } }
        time += 301; stall = true
        repo.request(listOf(id, other)); advanceUntilIdle()
        assertEquals(9L, repo.counts.value.getValue(other).likes)
        assertTrue(seen.all { it == 9L }, "shown: $seen")
        stall = false
        val before = bTargets.toList()
        time += 21
        repo.request(listOf(id, other)); advanceUntilIdle()
        val after = bTargets.drop(before.size)
        assertEquals(3, after.count { it == other }, "the missed note was not asked again after 20 s")
        assertEquals(0, after.count { it == id }, "the fully answered note keeps its normal stamp")
    }

    @Test
    fun `a relay that connects while a missing-relay batch runs still fills in that batch`() = runTest {
        val e = engine()
        var up = listOf("wss://a")
        val bTargets = ArrayList<String>()
        val repo = NostrCountsRepository(
            e, this, relays = { up }, sessionRelays = { listOf("wss://a", "wss://b") },
            counter = { url, f ->
                if (url == "wss://a") { if (targetOf(f) == other) delay(1_000); if (kindOf(f) == 7) 1L else 0L } else { bTargets += targetOf(f); if (kindOf(f) == 7) 7L else 0L }
            },
            now = { 1000 }, batchDelayMs = 0, batchSize = 1,
        )
        repo.request(listOf(id, other)); advanceTimeBy(500); runCurrent()
        up = listOf("wss://a", "wss://b")
        advanceUntilIdle()
        assertEquals(7L, repo.counts.value.getValue(id).likes)
        assertEquals(7L, repo.counts.value.getValue(other).likes, "the batch in flight got no follow-up")
        assertEquals(3, bTargets.count { it == other })
    }
}
