package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Filter
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class CountsRepositoryTest {
    @TempDir lateinit var dir: Path
    private val id = "a".repeat(64)
    private val other = "b".repeat(64)

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())

    /** The kind a COUNT filter asks for, so a fake counter can answer per kind. */
    private fun kindOf(f: Filter): Int = f.asRecord().kinds!!.first().asU16().toInt()

    /** The note id behind the filter's `#e` tag (rust-nostr 0.44.8: `FilterRecord.genericTags`, one entry with key `e`; its values live under `GenericTag.value`, not `.values`). */
    private fun targetOf(f: Filter): String = f.asRecord().genericTags.first().value.first()

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
    fun `a relay that throws is excluded for the session and others still count`() = runTest {
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
        assertEquals(1, calls.count { it == "wss://dead" }) // never asked again
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
        assertEquals(callsAfterBatch, calls.size) // wss://a excluded for the session: no further calls
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
}
