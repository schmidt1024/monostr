package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.model.AddressRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

class AddressableRepositoryTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private var time = 1_000_000L
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
    private fun repo(e: NostrEngine) = NostrAddressableRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = { true })
    private fun article(kind: Int = 30023, d: String = "post", at: Long = 1_000L, keys: Keys = alice): Event =
        EventBuilder(Kind(kind.toUShort()), "body").tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "T"))))
            .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)
    private fun ref(ev: Event, relays: List<String> = emptyList(), kind: Int = ev.kind().asU16().toInt()) =
        AddressRef(kind, ev.author().toHex(), "post", relays)
    private fun deletion(ev: Event, at: Long, keys: Keys = alice): Event =
        EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("a", "${ev.kind().asU16()}:${ev.author().toHex()}:post"))))
            .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    @Test
    fun `a stored event is found without any relay load`() = runTest {
        val e = engine()
        val ev = article()
        e.save(ev)
        val r = repo(e)
        val found = r.get(ref(ev)) as AddressResult.Found
        assertEquals("body", found.event.content)
        assertEquals(0, r.loads)
        assertEquals(found, r.cached(ref(ev)))
        e.close()
    }

    @Test
    fun `a missing event is fetched from the hint relay and stored`() = runTest {
        val ev = article()
        SilentWsRelay(serve = listOf(ev.asJson())).use { relay ->
            val e = engine()
            val r = repo(e)
            assertTrue(r.get(ref(ev, listOf(relay.url))) is AddressResult.Found)
            assertEquals(2, relay.requests.size) // event and deletion request, started together
            assertTrue(e.eventById(ev.id().toHex()) != null)
            assertTrue(e.temporaryRelayUrls().isEmpty())
            e.close()
        }
    }

    @Test
    fun `a miss is remembered for ten minutes`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine()
            val r = repo(e)
            val ref = AddressRef(30023, alice.publicKey().toHex(), "post", listOf(relay.url))
            assertEquals(AddressResult.Missing, r.get(ref))
            assertEquals(AddressResult.Missing, r.get(ref))
            assertEquals(1, r.loads)
            time += 601
            r.get(ref)
            assertEquals(2, r.loads)
            e.close()
        }
    }

    @Test
    fun `a deletion by the author hides the event`() = runTest {
        val ev = article(at = 1_000)
        val e = engine()
        e.save(ev)
        e.save(deletion(ev, at = 2_000))
        assertEquals(AddressResult.Deleted, repo(e).get(ref(ev)))
        e.close()
    }

    @Test
    fun `a deletion by someone else is ignored`() = runTest {
        val ev = article(at = 1_000)
        val e = engine()
        e.save(ev)
        e.save(deletion(ev, at = 2_000, keys = bob))
        assertTrue(repo(e).get(ref(ev)) is AddressResult.Found)
        e.close()
    }

    @Test
    fun `an article republished after its deletion shows again`() = runTest {
        val ev = article(at = 3_000)
        val e = engine()
        e.save(ev)
        e.save(deletion(ev, at = 2_000))
        assertTrue(repo(e).get(ref(ev)) is AddressResult.Found)
        e.close()
    }

    @Test
    fun `a deletion fetched from the relay is honoured`() = runTest {
        val ev = article(at = 1_000)
        val del = deletion(ev, at = 2_000)
        SilentWsRelay(serveFor = { req -> if (req.contains("\"#a\"")) listOf(del.asJson()) else listOf(ev.asJson()) }).use { relay ->
            val e = engine()
            assertEquals(AddressResult.Deleted, repo(e).get(ref(ev, listOf(relay.url))))
            e.close()
        }
    }

    @Test
    fun `a slow deletion fetch does not hold up the event`() = runTest {
        val ev = article()
        // EOSE comes after 6 s (timeout 3 s): the event exits early, the deletion fetch gets a short grace and finishes in the background
        SilentWsRelay(serveFor = { req -> if (req.contains("\"#a\"")) emptyList() else listOf(ev.asJson()) }, eoseDelayMs = 6_000).use { relay ->
            val e = engine()
            val started = System.nanoTime()
            assertTrue(repo(e).get(ref(ev, listOf(relay.url))) is AddressResult.Found)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 2_000, "get took $ms ms")
            e.close()
        }
    }

    @Test
    fun `a newer version from a slower relay wins over the first answer`() = runTest {
        val old = article(at = 1_000)
        val new = EventBuilder(Kind(30023u), "new body").tags(listOf(Tag.parse(listOf("d", "post"))))
            .customCreatedAt(Timestamp.fromSecs(2_000u)).signWithKeys(alice)
        SilentWsRelay(serve = listOf(old.asJson())).use { fast ->
            SilentWsRelay(serve = listOf(new.asJson()), serveDelayMs = 150).use { slow ->
                val e = engine()
                val found = repo(e).get(ref(old, listOf(fast.url, slow.url))) as AddressResult.Found
                assertEquals("new body", found.event.content)
                e.close()
            }
        }
    }

    private fun outboxList(vararg relays: String, keys: Keys = alice): Event =
        EventBuilder(Kind(10002u), "").tags(relays.map { Tag.parse(listOf("r", it)) }).signWithKeys(keys)

    @Test
    fun `without hints the author's stored outbox is asked, never for a DM card`() = runTest {
        val ev = article()
        SilentWsRelay(serve = listOf(ev.asJson())).use { outbox ->
            val e = engine()
            e.save(outboxList(outbox.url))
            // a DM card asks only the own relays (none here): no hint, no author relay
            assertEquals(AddressResult.Missing, repo(e).get(ref(ev), allowForeign = false))
            assertEquals(0, outbox.requests.size)
            assertTrue(repo(e).get(ref(ev)) is AddressResult.Found)
            assertTrue(outbox.requests.isNotEmpty())
            assertTrue(e.temporaryRelayUrls().isEmpty())
            e.close()
        }
    }

    @Test
    fun `a new hint lifts the miss mark of a hintless lookup`() = runTest {
        val ev = article()
        SilentWsRelay(serve = listOf(ev.asJson())).use { hint ->
            val e = engine()
            val r = repo(e)
            assertEquals(AddressResult.Missing, r.get(ref(ev)))
            assertEquals(AddressResult.Missing, r.cached(ref(ev)))
            assertNull(r.cached(ref(ev, listOf(hint.url))))
            assertTrue(r.get(ref(ev, listOf(hint.url))) is AddressResult.Found)
            assertEquals(2, r.loads)
            e.close()
        }
    }

    @Test
    fun `a miss without foreign relays does not stand for a lookup with them`() = runTest {
        val e = engine()
        val r = repo(e)
        val ref = AddressRef(30023, alice.publicKey().toHex(), "post", emptyList())
        assertEquals(AddressResult.Missing, r.get(ref, allowForeign = false))
        assertNull(r.cached(ref))
        assertEquals(AddressResult.Missing, r.cached(ref, allowForeign = false))
        assertEquals(AddressResult.Missing, r.get(ref))
        assertEquals(2, r.loads)
        // the wider lookup covers the narrower one
        assertEquals(AddressResult.Missing, r.get(ref, allowForeign = false))
        assertEquals(2, r.loads)
        e.close()
    }

    @Test
    fun `hints naming a loopback relay are not contacted`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine()
            val r = NostrAddressableRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3))
            assertEquals(AddressResult.Missing, r.get(AddressRef(30023, alice.publicKey().toHex(), "post", listOf(relay.url, "wss://192.168.1.5"))))
            assertEquals(0, relay.requests.size)
            e.close()
        }
    }

    @Test
    fun `a live event is fetched again after a minute, an article is not`() = runTest {
        val live = article(kind = 30311)
        val art = article(d = "post", keys = bob)
        SilentWsRelay(serveFor = { req -> listOf(live, art).filter { req.contains(it.author().toHex()) }.map { it.asJson() } }).use { relay ->
            val e = engine()
            val r = repo(e)
            val liveRef = ref(live, listOf(relay.url))
            val artRef = ref(art, listOf(relay.url))
            r.get(liveRef); r.get(artRef)
            assertEquals(2, r.loads)
            r.get(liveRef); r.get(artRef)
            assertEquals(2, r.loads)
            time += 61
            assertTrue(r.cached(liveRef) is AddressResult.Found)
            r.get(artRef)
            assertEquals(2, r.loads)
            r.get(liveRef)
            assertEquals(3, r.loads)
            e.close()
        }
    }

    @Test
    fun `a deletion naming only the event id in an e tag is never shown`() = runTest {
        val ev = article(at = 1_000)
        val e = engine()
        e.save(ev)
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", ev.id().toHex()))))
            .customCreatedAt(Timestamp.fromSecs(2_000u)).signWithKeys(alice))
        // the database purges the event itself on a kind 5 by its author, so nothing is left to show (and no #a to report Deleted)
        assertTrue(repo(e).get(ref(ev)) !is AddressResult.Found)
        e.close()
    }

    @Test
    fun `a hint without room in the foreign budget is no cached miss`() = runTest {
        val ev = article()
        SilentWsRelay().use { blocker ->
            SilentWsRelay(serve = listOf(ev.asJson())).use { hint ->
                val e = engine()
                val pool = ForeignRelays(e, io, cap = 1, lingerMs = 200)
                try {
                    val r = NostrAddressableRepository(e, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = { true }, foreign = pool)
                    assertEquals(listOf(blocker.url), pool.lease(listOf(blocker.url)))
                    val ref = ref(ev, listOf(hint.url))
                    assertEquals(AddressResult.Missing, r.get(ref))
                    assertEquals(0, hint.requests.size, "asked outside the budget")
                    assertNull(r.cached(ref))
                    pool.release(listOf(blocker.url))
                    assertTrue(r.get(ref) is AddressResult.Found)
                } finally {
                    pool.close()
                    e.close()
                }
            }
        }
    }
}
