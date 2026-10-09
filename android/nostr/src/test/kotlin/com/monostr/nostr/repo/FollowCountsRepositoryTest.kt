package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

/** Follower count = the largest NIP-45 COUNT any count relay gives; following = distinct `p` tags of the profile's kind 3. */
class FollowCountsRepositoryTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.generate()
    private val pk get() = alice.publicKey().toHex()

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())

    private fun contacts(vararg p: String, at: Long? = null) = EventBuilder(Kind(3u), "").tags(p.map { Tag.parse(listOf("p", it)) })
        .let { b -> if (at != null) b.customCreatedAt(Timestamp.fromSecs(at.toULong())) else b }.signWithKeys(alice)

    @Test
    fun `the largest count wins, a refusing relay is ignored, following counts distinct p tags`() = runTest {
        val e = engine()
        val b = "b".repeat(64); val c = "c".repeat(64)
        e.save(contacts(b, c, b))
        SilentWsRelay(countWith = 1683).use { r1 -> SilentWsRelay(countWith = 3373).use { r2 -> SilentWsRelay(answerEose = true).use { none ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(r1.url, none.url, r2.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { 1000 })
            val counts = repo.counts(pk).last()
            assertEquals(3373L, counts.followers)
            assertEquals(2, counts.following)
            assertTrue(r1.counts.single().contains("\"#p\":[\"$pk\"]"))
            assertTrue(e.temporaryRelayUrls().isEmpty())
        } } }
        e.close()
    }

    @Test
    fun `no answer at all gives null followers and no list gives null following, and that is asked again`() = runTest {
        val e = engine()
        SilentWsRelay(answerEose = true).use { none -> SilentWsRelay().use { silent ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(none.url, silent.url), indexRelays = listOf(none.url), timeout = Duration.ofMillis(500), now = { 1000 })
            assertEquals(emptyList<FollowCounts>(), repo.counts(pk).toList()) // nothing known: no line
            assertEquals(1, none.counts.size)
            repo.counts(pk).toList()
            assertEquals(2, none.counts.size) // nothing known: not memoised
        } }
        e.close()
    }

    @Test
    fun `a known result is memoised for five minutes`() = runTest {
        val e = engine()
        var now = 1000L
        SilentWsRelay(countWith = 7).use { r ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(r.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { now })
            assertEquals(7L, repo.counts(pk).last().followers)
            assertNull(repo.counts(pk).last().following)
            now += 299
            repo.counts(pk).last()
            assertEquals(1, r.counts.size)
            now += 2
            repo.counts(pk).last()
            assertEquals(2, r.counts.size)
        }
        e.close()
    }

    @Test
    fun `following comes from the index relay when nothing is stored`() = runTest {
        val e = engine()
        val list = contacts("d".repeat(64))
        SilentWsRelay(serve = listOf(list.asJson())).use { index ->
            val repo = NostrFollowCountsRepository(e, countRelays = emptyList(), indexRelays = listOf(index.url), timeout = Duration.ofSeconds(3), now = { 1000 })
            assertEquals(FollowCounts(following = 1, followers = null), repo.counts(pk).last())
            assertTrue(index.requests.single().contains("\"kinds\":[3]"))
        }
        e.close()
    }

    @Test
    fun `an absurd count is no answer`() = runTest {
        val e = engine()
        SilentWsRelay(countWith = Long.MAX_VALUE).use { liar -> SilentWsRelay(countWith = 7).use { r ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(liar.url, r.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { 1000 })
            assertEquals(7L, repo.counts(pk).last().followers)
            val only = NostrFollowCountsRepository(e, countRelays = listOf(liar.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { 1000 })
            assertNull(only.counts(pk).lastOrNull())
        } }
        e.close()
    }

    @Test
    fun `an index relay that is one of the user's relays is still asked, and stays`() = runTest {
        val list = contacts("d".repeat(64), "e".repeat(64))
        SilentWsRelay(serve = listOf(list.asJson())).use { index ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf(index.url))
            e.connect()
            val repo = NostrFollowCountsRepository(e, countRelays = emptyList(), indexRelays = listOf(index.url), timeout = Duration.ofSeconds(3), now = { 1000 })
            assertEquals(2, repo.counts(pk).last().following)
            assertEquals(listOf(index.url), e.relayUrls())
            e.close()
        }
    }

    @Test
    fun `a stored list older than a day is refreshed from the index, a fresh one is not`() = runTest {
        val e = engine()
        val day = 86_400L
        e.save(contacts("d".repeat(64), at = 1_000_000 - day - 1))
        val newer = contacts("d".repeat(64), "e".repeat(64), "f".repeat(64), at = 1_000_000 - 10)
        SilentWsRelay(serve = listOf(newer.asJson())).use { index ->
            val repo = NostrFollowCountsRepository(e, countRelays = emptyList(), indexRelays = listOf(index.url), timeout = Duration.ofSeconds(3), now = { 1_000_000 })
            assertEquals(3, repo.counts(pk).last().following)
            assertEquals(1, index.requests.size)
            val fresh = NostrFollowCountsRepository(e, countRelays = emptyList(), indexRelays = listOf(index.url), timeout = Duration.ofSeconds(3), now = { 1_000_000 })
            assertEquals(3, fresh.counts(pk).last().following)
            assertEquals(1, index.requests.size) // the stored list is ten seconds old: no fetch
        }
        e.close()
    }

    @Test
    fun `a malformed p tag does not count`() = runTest {
        val e = engine()
        e.save(contacts("d".repeat(64), "not-a-key", ""))
        val repo = NostrFollowCountsRepository(e, countRelays = emptyList(), indexRelays = emptyList(), timeout = Duration.ofSeconds(1), now = { 1000 })
        assertEquals(1, repo.counts(pk).last().following)
        e.close()
    }

    @Test
    fun `invalidateAll forgets every memoised result`() = runTest {
        val e = engine()
        SilentWsRelay(countWith = 7).use { r ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(r.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { 1000 })
            repo.counts(pk).last()
            repo.invalidateAll()
            repo.counts(pk).last()
            assertEquals(2, r.counts.size)
        }
        e.close()
    }

    @Test
    fun `the number shows with the first answer and grows, a silent relay never holds it up`() = runTest {
        val e = engine()
        e.save(contacts("d".repeat(64)))
        SilentWsRelay(countWith = 5).use { fast -> SilentWsRelay().use { silent -> SilentWsRelay(countWith = 9, handshakeDelayMs = 800).use { slow ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(silent.url, fast.url, slow.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { 1000 })
            val started = System.currentTimeMillis()
            val first = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(2_500) { repo.counts(pk).first { it.followers != null } } }
            assertEquals(5L, first.followers)
            assertTrue(System.currentTimeMillis() - started < 2_000)
            val all = repo.counts(pk).toList() // memo is only written at the end: this run emits again
            assertEquals(FollowCounts(1, 9), all.last())
            assertTrue(all.map { it.followers ?: -1 }.zipWithNext().all { (a, b) -> b >= a }) // never shrinks
        } } }
        e.close()
    }

    @Test
    fun `the user's own relays are asked too, one without NIP-45 is no answer`() = runTest {
        SilentWsRelay(countWith = 50).use { own -> SilentWsRelay(answerEose = true).use { plain -> SilentWsRelay(countWith = 7).use { r ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf(own.url, plain.url))
            e.connect()
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(r.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(3), now = { 1000 })
            assertEquals(50L, repo.counts(pk).last().followers)
            assertEquals(1, own.counts.size); assertEquals(1, plain.counts.size); assertEquals(1, r.counts.size)
            assertEquals(2, e.relayUrls().size)
            e.close()
        } } }
    }

    @Test
    fun `an indexer's followers beat every relay count, its following fills in only when no list is known, a failing one is ignored`() = runTest {
        val e = engine()
        val primal = object : FollowStatsIndexer { override suspend fun stats(pubkey: String) = FollowCounts(following = 315, followers = 2282) }
        val broken = object : FollowStatsIndexer { override suspend fun stats(pubkey: String): FollowCounts? = throw IllegalStateException("down") }
        SilentWsRelay(countWith = 603).use { r ->
            val repo = NostrFollowCountsRepository(e, countRelays = listOf(r.url), indexRelays = emptyList(), indexers = listOf(broken, primal), timeout = Duration.ofSeconds(3), now = { 1000 })
            assertEquals(FollowCounts(315, 2282), repo.counts(pk).last())
            e.save(contacts("d".repeat(64), "e".repeat(64)))
            repo.invalidateAll()
            assertEquals(FollowCounts(2, 2282), repo.counts(pk).last()) // the list itself is exact
        }
        e.close()
    }
}
