package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Tag
import java.nio.file.Path

class AuthorRelaysRepositoryTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.generate()

    private suspend fun engine(relays: List<String> = listOf("wss://mine.example")) = NostrEngine.create(dir.resolve("lmdb").toString(), null, relays)

    private fun relayList(vararg tags: List<String>) = EventBuilder(rust.nostr.sdk.Kind(10002u), "").tags(tags.map { Tag.parse(it) }).signWithKeys(alice)

    @Test
    fun `write relays from the database, markers respected, own relays removed, capped at three`() = runTest {
        val e = engine()
        e.save(relayList(
            listOf("r", "wss://mine.example"),
            listOf("r", "wss://read.example", "read"),
            listOf("r", "wss://192.168.0.2", "write"),
            listOf("r", "wss://localhost:7777"),
            listOf("r", "wss://w1.example/", "write"),
            listOf("r", "wss://w2.example"),
            listOf("r", "wss://w3.example", "write"),
            listOf("r", "wss://w4.example", "write"),
            listOf("r", "ws://plain.example"),
        ))
        val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 })
        assertEquals(listOf("wss://w1.example", "wss://w2.example", "wss://w3.example"), repo.writeRelays(alice.publicKey().toHex()))
        e.close()
    }

    @Test
    fun `no list or only own relays gives an empty result and is cached`() = runTest {
        val e = engine()
        // A real SilentWsRelay indexer, not an empty list: this exercises writeRelays' own
        // fetchFrom(indexers + relayUrls())/finally detachTemporary(indexers) cleanup path
        // (task-10 review round 1, item 5a) rather than trivially passing on an empty indexer list.
        com.monostr.nostr.SilentWsRelay().use { indexer ->
            val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, fetchTimeout = java.time.Duration.ofMillis(200))
            assertTrue(repo.writeRelays(alice.publicKey().toHex()).isEmpty())
            e.save(relayList(listOf("r", "wss://mine.example")))
            assertTrue(repo.writeRelays(alice.publicKey().toHex()).isEmpty())
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
        e.close()
    }

    @Test
    fun `write relay urls are normalised so a differently-cased own relay is excluded`() = runTest {
        val e = engine(listOf("wss://nos.lol"))
        e.save(relayList(
            listOf("r", "wss://Nos.LOL", "write"),
            listOf("r", "wss://W1.Example/", "write"),
            listOf("r", "not a url", "write"),
        ))
        val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 })
        assertEquals(listOf("wss://w1.example"), repo.writeRelays(alice.publicKey().toHex()))
        e.close()
    }

    @Test
    fun `attach and detach go through the engine's temporary set`() = runTest {
        val e = engine(emptyList())
        com.monostr.nostr.SilentWsRelay().use { r ->
            val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 })
            repo.attach(listOf(r.url))
            assertEquals(listOf(r.url), e.temporaryRelayUrls())
            repo.detach(listOf(r.url))
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
        e.close()
    }

    @Test
    fun `fetchNotes releases its own attach, repeated calls do not pile up refs`() = runTest {
        val e = engine(emptyList())
        val relay = "ws://127.0.0.1:9"
        val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 }, notesFetchTimeout = java.time.Duration.ofMillis(300))
        repo.attach(listOf(relay))
        repo.fetchNotes(listOf(relay), alice.publicKey().toHex())
        repo.fetchNotes(listOf(relay), alice.publicKey().toHex())
        assertEquals(listOf(relay), e.temporaryRelayUrls())
        repo.detach(listOf(relay))
        assertTrue(e.temporaryRelayUrls().isEmpty())
        e.close()
    }

    @Test
    fun `fetchNotes without an outer attach leaves nothing attached afterwards`() = runTest {
        val e = engine(emptyList())
        val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 }, notesFetchTimeout = java.time.Duration.ofMillis(300))
        repo.fetchNotes(listOf("ws://127.0.0.1:9"), alice.publicKey().toHex())
        assertTrue(e.temporaryRelayUrls().isEmpty())
        e.close()
    }

    @Test
    fun `fetchNotes reports the ids the relays sent`() = runTest {
        val note = EventBuilder.textNote("from the outbox").signWithKeys(alice)
        SilentWsRelay(serve = listOf(note.asJson())).use { relay ->
            val e = engine(emptyList())
            val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 }, notesFetchTimeout = java.time.Duration.ofSeconds(1))
            assertEquals(setOf(note.id().toHex()), repo.fetchNotes(listOf(relay.url), alice.publicKey().toHex()))
            assertEquals(emptySet<String>(), repo.fetchNotes(listOf("ws://127.0.0.1:9"), alice.publicKey().toHex()))
            e.close()
        }
    }

    private fun listFor(k: Keys, vararg tags: List<String>) = EventBuilder(rust.nostr.sdk.Kind(10002u), "").tags(tags.map { Tag.parse(it) }).signWithKeys(k)
    private val any: (String) -> Boolean = { true }
    private fun authorsReqs(r: SilentWsRelay) = r.requests.filter { "\"authors\"" in it }

    @Test
    fun `many authors cost one fetch`() = runTest {
        val keys = List(5) { Keys.generate() }
        val events = keys.mapIndexed { i, k -> listFor(k, listOf("r", "wss://w$i.example", "write")).asJson() }
        SilentWsRelay(serve = events, recordRequests = true).use { indexer ->
            val e = engine()
            val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, fetchTimeout = java.time.Duration.ofSeconds(2), relayFilter = any)
            val got = repo.writeRelaysMany(keys.map { it.publicKey().toHex() })
            keys.forEachIndexed { i, k -> assertEquals(listOf("wss://w$i.example"), got[k.publicKey().toHex()]) }
            val reqs = authorsReqs(indexer)
            assertEquals(1, reqs.size, reqs.toString())
            keys.forEach { assertTrue(it.publicKey().toHex() in reqs[0]) }
            e.close()
        }
    }

    @Test
    fun `stored lists need no fetch`() = runTest {
        val keys = List(3) { Keys.generate() }
        SilentWsRelay(recordRequests = true).use { indexer ->
            val e = engine()
            keys.forEach { e.save(listFor(it, listOf("r", "wss://w.example", "write"))) }
            val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, relayFilter = any)
            val got = repo.writeRelaysMany(keys.map { it.publicKey().toHex() })
            assertEquals(3, got.size)
            assertTrue(got.values.all { it == listOf("wss://w.example") })
            assertEquals(0, indexer.requests.size)
            e.close()
        }
    }

    @Test
    fun `cache is shared with writeRelays`() = runTest {
        val ev = listFor(alice, listOf("r", "wss://w.example", "write")).asJson()
        SilentWsRelay(serve = listOf(ev), recordRequests = true).use { indexer ->
            val e = engine()
            val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, fetchTimeout = java.time.Duration.ofSeconds(2), relayFilter = any)
            val pk = alice.publicKey().toHex()
            assertEquals(mapOf(pk to listOf("wss://w.example")), repo.writeRelaysMany(listOf(pk)))
            val before = indexer.requests.size
            assertEquals(listOf("wss://w.example"), repo.writeRelays(pk))
            assertEquals(before, indexer.requests.size)
            e.close()
        }
    }

    @Test
    fun `read relays take unmarked and read tags`() = runTest {
        val e = engine()
        e.save(relayList(
            listOf("r", "wss://both.example"),
            listOf("r", "wss://read.example", "read"),
            listOf("r", "wss://write.example", "write"),
        ))
        val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), now = { 1000 })
        assertEquals(listOf("wss://both.example", "wss://read.example"), repo.readRelays(alice.publicKey().toHex()))
        assertEquals(listOf("wss://both.example", "wss://write.example"), repo.writeRelays(alice.publicKey().toHex()))
        e.close()
    }

    @Test
    fun `indexers count toward the foreign budget`() = runTest {
        val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        SilentWsRelay().use { held ->
            SilentWsRelay(recordRequests = true).use { indexer ->
                val e = engine()
                val pool = ForeignRelays(e, io, cap = 1, lingerMs = 60_000)
                assertEquals(listOf(held.url), pool.lease(listOf(held.url)))
                val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, fetchTimeout = java.time.Duration.ofMillis(300), foreign = pool, relayFilter = any)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.writeRelaysMany(listOf(alice.publicKey().toHex())) }
                assertEquals(setOf(held.url), pool.attached())
                assertEquals(0, indexer.requests.size)
                pool.close()
                e.close()
            }
        }
    }

    @Test
    fun `a lookup without room for the indexers is not cached`() = runTest {
        // spec 2026-10-23 §4.4: a short lease is no answer; the next lookup with a free slot reaches the indexer
        val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val ev = listFor(alice, listOf("r", "wss://w.example", "write")).asJson()
        SilentWsRelay().use { held ->
            SilentWsRelay(serve = listOf(ev), recordRequests = true).use { indexer ->
                val e = engine()
                val pool = ForeignRelays(e, io, cap = 1, lingerMs = 60_000)
                assertEquals(listOf(held.url), pool.lease(listOf(held.url)))
                val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, fetchTimeout = java.time.Duration.ofSeconds(2), foreign = pool, relayFilter = any)
                val pk = alice.publicKey().toHex()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertEquals(emptyList<String>(), repo.writeRelays(pk))
                    assertEquals(emptyList<String>(), repo.writeRelaysExact(pk))
                    assertEquals(0, indexer.requests.size)
                    pool.release(listOf(held.url))
                    assertEquals(listOf("wss://w.example"), repo.writeRelays(pk))
                    assertTrue(indexer.requests.isNotEmpty(), "the indexer was not asked")
                }
                pool.close()
                e.close()
            }
        }
    }

    @Test
    fun `indexers are leased from free capacity and given back`() = runTest {
        val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val ev = listFor(alice, listOf("r", "wss://w.example", "write")).asJson()
        SilentWsRelay(serve = listOf(ev), recordRequests = true).use { indexer ->
            val e = engine()
            val pool = ForeignRelays(e, io, cap = 1, lingerMs = 200)
            val repo = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { 1000 }, fetchTimeout = java.time.Duration.ofSeconds(2), foreign = pool, relayFilter = any)
            val pk = alice.publicKey().toHex()
            val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.writeRelaysMany(listOf(pk)) }
            assertEquals(mapOf(pk to listOf("wss://w.example")), got)
            assertTrue(indexer.requests.isNotEmpty(), "the indexer was not asked")
            // released: it lingers, then leaves the pool and the engine
            assertTrue(com.monostr.nostr.awaitTrue(2000) { pool.attached().isEmpty() && e.temporaryRelayUrls().isEmpty() }, "still attached: ${pool.attached()}")
            pool.close()
            e.close()
        }
    }
}
