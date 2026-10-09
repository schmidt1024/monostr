package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
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
import java.nio.file.Path
import java.time.Duration

class QuoteRepositoryTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.generate()
    private var time = 1_000_000L
    /** Real dispatcher: the load waits for a real loopback connection, virtual test time would skip it. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
    private fun repo(e: NostrEngine) = NostrQuoteRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3))
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
}
