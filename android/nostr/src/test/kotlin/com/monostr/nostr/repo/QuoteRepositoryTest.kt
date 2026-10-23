package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path
import java.time.Duration

class QuoteRepositoryTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.generate()
    private var time = 1_000_000L
    /** Real dispatcher: the load waits for a real loopback connection, virtual test time would skip it. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
    /** The test relays run on loopback, which the default filter refuses: these tests let every hint through. */
    private fun repo(e: NostrEngine) = NostrQuoteRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = { true })
    private fun note(text: String) = EventBuilder.textNote(text).signWithKeys(alice)

    @Test
    fun `a stored note is found without any relay load`() = runTest {
        val e = engine()
        val ev = note("stored")
        e.save(ev)
        val r = repo(e)
        val found = r.get(ev.id().toHex()) as QuoteResult.Found
        assertEquals("stored", found.note.content)
        assertEquals(0, r.loads)
        assertEquals(found, r.cached(ev.id().toHex()))
        e.close()
    }

    @Test
    fun `a missing note is fetched from the hint relay, stored, and the hint relay is released`() = runTest {
        val ev = note("from the hint")
        SilentWsRelay(serve = listOf(ev.asJson())).use { relay ->
            val e = engine()
            val r = repo(e)
            val found = r.get(ev.id().toHex(), listOf(relay.url))
            assertEquals("from the hint", (found as QuoteResult.Found).note.content)
            assertEquals(1, relay.requests.size)
            assertTrue(e.eventById(ev.id().toHex()) != null)
            assertTrue(e.temporaryRelayUrls().isEmpty())
            e.close()
        }
    }

    @Test
    fun `hints naming a loopback or lan relay are not contacted`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine()
            val r = NostrQuoteRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3))
            assertEquals(QuoteResult.Missing, r.get("cd".repeat(32), listOf(relay.url, "wss://192.168.1.5", "ws://relay.example.com")))
            assertEquals(0, relay.requests.size)
            assertTrue(e.temporaryRelayUrls().isEmpty())
            e.close()
        }
    }

    @Test
    fun `at most three hint relays are used`() = runTest {
        val relays = (1..5).map { SilentWsRelay(answerEose = true) }
        try {
            val e = engine()
            val r = repo(e)
            assertEquals(QuoteResult.Missing, r.get("ab".repeat(32), relays.map { it.url }))
            assertEquals(listOf(1, 1, 1, 0, 0), relays.map { it.requests.size })
            assertTrue(e.temporaryRelayUrls().isEmpty())
            e.close()
        } finally {
            relays.forEach { it.close() }
        }
    }

    @Test
    fun `an unknown note is missing and remembered for ten minutes`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine()
            val r = repo(e)
            val id = "ab".repeat(32)
            assertEquals(QuoteResult.Missing, r.get(id, listOf(relay.url)))
            assertEquals(QuoteResult.Missing, r.get(id, listOf(relay.url)))
            assertEquals(1, r.loads)
            time += 601
            assertNull(r.cached(id))
            r.get(id, listOf(relay.url))
            assertEquals(2, r.loads)
            e.close()
        }
    }

    @Test
    fun `concurrent gets for one id share a single relay load`() = runTest {
        val ev = note("popular")
        SilentWsRelay(serve = listOf(ev.asJson())).use { relay ->
            val e = engine()
            val r = repo(e)
            val results = (1..5).map { async { r.get(ev.id().toHex(), listOf(relay.url)) } }.awaitAll()
            assertTrue(results.all { it is QuoteResult.Found })
            assertEquals(1, r.loads)
            assertEquals(1, relay.requests.size)
            e.close()
        }
    }

    @Test
    fun `a quoted note is mapped without its own quote card`() = runTest {
        val e = engine()
        val inner = note("inner")
        val outer = EventBuilder(Kind(1u), "outer\n\n${inner.id().toNostrUri()}").signWithKeys(alice)
        e.save(outer)
        val found = repo(e).get(outer.id().toHex()) as QuoteResult.Found
        assertNull(found.note.quotedId)
        assertEquals("outer\n\n${inner.id().toNostrUri()}", found.note.displayContent)
        e.close()
    }

    private fun relayList(k: Keys, vararg tags: List<String>) = EventBuilder(Kind(10002u), "").tags(tags.map { Tag.parse(it) }).signWithKeys(k)

    /** A real locator over an own relay that has nothing, the [indexer] serving kind 10002, and foreign relays on loopback. */
    private suspend fun withLocator(indexer: SilentWsRelay, block: suspend (NostrEngine, NostrQuoteRepository) -> Unit) {
        val own = SilentWsRelay(answerEose = true)
        val e = connectedEngine(dir, own)
        val pool = ForeignRelays(e, io, cap = 8, lingerMs = 500)
        val authors = NostrAuthorRelaysRepository(e, indexers = listOf(indexer.url), now = { time }, fetchTimeout = Duration.ofSeconds(3), foreign = pool, relayFilter = { true })
        val locator = NostrEventLocator(e, authors, pool, enabled = { true }, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = { true })
        val r = NostrQuoteRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = { true }, locator = locator)
        try {
            block(e, r)
        } finally {
            pool.close()
            e.close()
            own.shutdown()
        }
    }

    @Test
    fun `quote found on the author's write relay`() = runTest {
        val ev = note("from the outbox")
        val id = ev.id().toHex()
        SilentWsRelay(serveFor = { req -> if (id in req) listOf(ev.asJson()) else emptyList() }).use { f2 ->
            SilentWsRelay(serveFor = { req -> if ("10002" in req) listOf(relayList(alice, listOf("r", f2.url, "write")).asJson()) else emptyList() }).use { idx ->
                withLocator(idx) { _, r ->
                    val found = r.get(id, author = alice.publicKey().toHex())
                    assertEquals("from the outbox", (found as QuoteResult.Found).note.content)
                }
            }
        }
    }

    @Test
    fun `missing is asked again with a new author`() = runTest {
        val ev = note("late author")
        val id = ev.id().toHex()
        SilentWsRelay(serveFor = { req -> if (id in req) listOf(ev.asJson()) else emptyList() }).use { f2 ->
            SilentWsRelay(serveFor = { req -> if ("10002" in req) listOf(relayList(alice, listOf("r", f2.url, "write")).asJson()) else emptyList() }).use { idx ->
                withLocator(idx) { _, r ->
                    assertEquals(QuoteResult.Missing, r.get(id))
                    assertTrue(r.get(id, author = alice.publicKey().toHex()) is QuoteResult.Found)
                }
            }
        }
    }

    @Test
    fun `same inputs within ten minutes stay missing`() = runTest {
        val id = "ef".repeat(32)
        SilentWsRelay(answerEose = true).use { f2 ->
            SilentWsRelay(serveFor = { req -> if ("10002" in req) listOf(relayList(alice, listOf("r", f2.url, "write")).asJson()) else emptyList() }).use { idx ->
                withLocator(idx) { _, r ->
                    val a = alice.publicKey().toHex()
                    assertEquals(QuoteResult.Missing, r.get(id, author = a))
                    val asked = f2.requests.size
                    val loads = r.loads
                    assertEquals(QuoteResult.Missing, r.get(id, author = a))
                    assertEquals(asked, f2.requests.size)
                    assertEquals(loads, r.loads)
                }
            }
        }
    }

    @Test
    fun `hint relays are leased within the foreign budget`() = runTest {
        // spec 2026-10-23 §3/§4.4: a quote's hints count toward the one budget; a full budget asks only the own relays
        val ev = note("hinted quote")
        val id = ev.id().toHex()
        SilentWsRelay().use { blocker ->
            SilentWsRelay(serve = listOf(ev.asJson())).use { hint ->
                val e = engine()
                val pool = ForeignRelays(e, io, cap = 1, lingerMs = 200)
                try {
                    val r = NostrQuoteRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = { true }, foreign = pool)
                    assertEquals(listOf(blocker.url), pool.lease(listOf(blocker.url)))
                    assertEquals(QuoteResult.Missing, r.get(id, listOf(hint.url)))
                    assertEquals(0, hint.requests.size, "asked outside the budget")
                    assertEquals(setOf(blocker.url), pool.attached())
                    // a hint that found no room was not asked: no Missing mark, the same input asks again once a slot is free
                    assertNull(r.cached(id))
                    pool.release(listOf(blocker.url))
                    assertTrue(r.get(id, listOf(hint.url)) is QuoteResult.Found)
                    assertEquals(1, hint.requests.size)
                    // given back: it lingers, then goes
                    assertTrue(com.monostr.nostr.awaitTrue(2000) { pool.attached().isEmpty() && e.temporaryRelayUrls().isEmpty() }, "still attached: ${pool.attached()}")
                } finally {
                    pool.close()
                    e.close()
                }
            }
        }
    }
}
