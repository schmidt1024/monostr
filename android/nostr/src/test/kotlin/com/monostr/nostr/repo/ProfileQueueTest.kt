package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Spec perf 3.2: profiles answer from memory or the database at once; a batched queue asks the relays behind them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileQueueTest {
    @TempDir lateinit var dir: Path

    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private val carol = Keys.generate()
    private var clock = 1_000_000L

    private fun profile(keys: Keys, name: String): Event = EventBuilder.metadata(Metadata.fromJson("""{"name":"$name"}""")).signWithKeys(keys)

    private suspend fun engine(relay: SilentWsRelay): NostrEngine = connectedEngine(dir, relay, me.secretKey().toHex())

    private fun kind0Requests(relay: SilentWsRelay) = relay.requests.filter { "\"kinds\":[0]" in it }

    @Test
    fun `a stored profile answers at once although it was not fetched in this process and the relay never answers`() = runTest {
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = engine(relay)
            try {
                e.save(profile(alice, "alice"))
                val repo = NostrProfileRepository(e, now = { clock })
                val started = System.currentTimeMillis()
                val p = repo.get(alice.publicKey().toHex())
                val took = System.currentTimeMillis() - started
                assertEquals("alice", p.name)
                assertTrue(took < 1_000, "get waited $took ms for the relay")
                assertTrue(awaitTrue { kind0Requests(relay).isNotEmpty() }, "the refresh behind it was never sent")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `lookups of the same moment go out as one request`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            try {
                val repo = NostrProfileRepository(e, now = { clock })
                val keys = listOf(alice, bob, carol).map { it.publicKey().toHex() }
                keys.forEach { repo.get(it) }
                assertTrue(awaitTrue { kind0Requests(relay).any { r -> keys.all { it in r } } }, "no batched request: ${relay.requests}")
                withContext(Dispatchers.IO) { Thread.sleep(300) }
                assertEquals(1, kind0Requests(relay).size, "one request for the three: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a profile asked for again while its batch waits on the relay is not fetched twice`() = runTest {
        // Review Focus: the stamp is set when the batch starts
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = engine(relay)
            try {
                val repo = NostrProfileRepository(e, now = { clock })
                val hex = alice.publicKey().toHex()
                repo.get(hex)
                assertTrue(awaitTrue { kind0Requests(relay).isNotEmpty() })
                repo.get(hex); repo.prefetch(listOf(hex))
                withContext(Dispatchers.IO) { Thread.sleep(400) }
                assertEquals(1, kind0Requests(relay).size, "requests: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a profile the relay brings later is announced through updates`() = runTest {
        SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { relay ->
            val e = engine(relay)
            try {
                val repo = NostrProfileRepository(e, now = { clock })
                val seen = java.util.concurrent.CopyOnWriteArrayList<Map<String, Profile>>()
                val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.updates.collect { seen += it } }
                val hex = alice.publicKey().toHex()
                assertNull(repo.get(hex).name, "nothing stored yet: the empty profile at once")
                assertTrue(awaitTrue { seen.any { it[hex]?.name == "alice" } }, "updates never brought alice")
                assertEquals("alice", repo.get(hex).name)
                job.cancel()
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a background worker can wait for the lookup`() = runTest {
        SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { relay ->
            val e = engine(relay)
            try {
                assertEquals("alice", NostrProfileRepository(e, now = { clock }).fetched(alice.publicKey().toHex()).name)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a profile a screen read while its batch waited on the index relay is still announced through updates`() = runTest {
        // the own relay brings alice at once, bob is missing and the index relay keeps the batch waiting
        SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { own ->
            SilentWsRelay().use { index ->
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url), indexTimeout = java.time.Duration.ofSeconds(2))
                    val seen = java.util.concurrent.CopyOnWriteArrayList<Map<String, Profile>>()
                    val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.updates.collect { seen += it } }
                    val a = alice.publicKey().toHex()
                    repo.getAll(listOf(a, bob.publicKey().toHex()))
                    assertTrue(awaitTrue { repo.local(listOf(a)).isNotEmpty() }, "alice never reached the database")
                    assertTrue(seen.none { a in it }, "the batch was not waiting on the index relay: $seen")
                    assertTrue(awaitTrue(6_000) { seen.any { it[a]?.name == "alice" } }, "updates never brought alice: $seen")
                    job.cancel()
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a worker that stops waiting leaves the lookup running, and what it finds lands`() = runTest {
        // the own relay has nothing, the index relay has alice: the lookup outlives the worker's 1 ms
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { index ->
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url))
                    val hex = alice.publicKey().toHex()
                    assertNull(repo.fetched(hex, java.time.Duration.ofMillis(1)).name, "the worker waited for the whole lookup")
                    assertTrue(awaitTrue { repo.observe(hex).first().name == "alice" }, "the lookup was cut off with the wait")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `getAll reads the stored profiles with one query and keeps unknown and malformed keys apart`() = runTest {
        // Review Focus: a malformed key from a foreign tag never breaks the read or the batch
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        e.save(profile(alice, "alice")); e.save(profile(bob, "bob"))
        val repo = NostrProfileRepository(e, now = { clock })
        val a = alice.publicKey().toHex(); val b = bob.publicKey().toHex(); val c = carol.publicKey().toHex()
        val all = repo.getAll(listOf(a, b, c, "not-a-key", a))
        assertEquals(listOf(a, b, c, "not-a-key"), all.keys.toList())
        assertEquals(listOf("alice", "bob", null, null), all.values.map { it.name })
        assertEquals(listOf("alice", "bob"), repo.local(listOf(a, "not-a-key", b, c)).map { it.name })
        e.close()
    }

    private fun profileAt(keys: Keys, name: String, at: ULong): Event =
        EventBuilder.metadata(Metadata.fromJson("""{"name":"$name"}""")).customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(keys)

    /**
     * A newer profile lands in the cache (a batch, here [NostrProfileRepository.invalidate]) while [read]'s database
     * query has returned the older one but not yet come back: the caller must get the landed one, not the stale read.
     */
    private suspend fun landsDuringRead(read: suspend (NostrProfileRepository, String) -> Profile?) {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        try {
            val a = alice.publicKey().toHex()
            e.save(profileAt(alice, "old", 1_000u))
            val repo = NostrProfileRepository(e, now = { clock })
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var gated = false
            repo.afterLocalRead = { if (!gated) { gated = true; reached.complete(Unit); release.await() } }
            coroutineScope {
                val call = async(Dispatchers.IO) { read(repo, a) }
                reached.await()
                e.save(profileAt(alice, "new", 2_000u))
                assertEquals("new", repo.invalidate(a).name)
                release.complete(Unit)
                assertEquals("new", call.await()?.name)
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun `getAll returns a profile a batch landed while its database read was running`() = runTest {
        landsDuringRead { repo, pk -> repo.getAll(listOf(pk)).getValue(pk) }
    }

    @Test
    fun `local returns a profile a batch landed while its database read was running`() = runTest {
        landsDuringRead { repo, pk -> repo.local(listOf(pk)).singleOrNull() }
    }
}
