package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

class PollRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private suspend fun engine(relays: List<String> = emptyList()) =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), relays)
    private suspend fun tags(e: NostrEngine, id: String) = e.eventById(id)!!.tags().toVec().map { it.asVec() }

    private fun poll(vararg extra: List<String>, endsAt: Long? = 2_000_000_000L): Note {
        val base = listOf(listOf("option", "a", "Yes"), listOf("option", "b", "No")) + listOfNotNull(endsAt?.let { listOf("endsAt", "$it") })
        val ev = EventBuilder(Kind(1068u), "Q?").tags((base + extra).map { Tag.parse(it) }).signWithKeys(alice)
        return NoteMapper.note(ev)!!
    }

    @Test
    fun `create publishes a 1068 with options, type, end and relays`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val r = NostrPollRepository(e, powBits = { 0 }, hintAllowed = { true }).create("Lunch? #food", listOf("Pizza", "Sushi", "Soup"), 1_900_000_000L)
            assertTrue(r.sentToAny)
            val stored = e.eventById(r.eventId)!!
            assertEquals(1068, stored.kind().asU16().toInt())
            assertEquals("Lunch? #food", stored.content())
            val t = tags(e, r.eventId)
            val options = t.filter { it[0] == "option" }
            assertEquals(listOf("Pizza", "Sushi", "Soup"), options.map { it[2] })
            options.forEach { assertTrue(Regex("[a-z0-9]{8}").matches(it[1]), it[1]) }
            assertEquals(3, options.map { it[1] }.toSet().size)
            assertTrue(listOf("polltype", "singlechoice") in t)
            assertTrue(listOf("endsAt", "1900000000") in t)
            assertEquals(listOf(listOf("relay", relay.url)), t.filter { it[0] == "relay" })
            assertTrue(listOf("t", "food") in t)
            val note = NoteMapper.note(stored)!!
            assertEquals(3, note.poll!!.options.size)
            e.close()
        }
    }

    @Test
    fun `a poll names no private relay with the default filter`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val r = NostrPollRepository(e, powBits = { 0 }).create("Q", listOf("a", "b"), 1_900_000_000L)
            assertTrue(r.sentToAny)
            assertTrue(tags(e, r.eventId).none { it[0] == "relay" })
            e.close()
        }
    }

    @Test
    fun `create carries the no-preview tag when the link card was put away`() = runTest {
        val e = engine()
        val repo = NostrPollRepository(e, powBits = { 0 })
        val off = repo.create("See https://example.com", listOf("a", "b"), 1_900_000_000L, noPreview = true)
        assertTrue(tags(e, off.eventId).any { it == com.monostr.nostr.model.NoPreview.TAG })
        val on = repo.create("See https://example.com", listOf("a", "b"), 1_900_000_000L)
        assertTrue(tags(e, on.eventId).none { it == com.monostr.nostr.model.NoPreview.TAG })
        e.close()
    }

    @Test
    fun `votes never throws for a poll with a broken id`() = runTest {
        val e = engine()
        val broken = poll().copy(id = "not-an-id")
        assertEquals(emptyList<Any>(), NostrPollRepository(e, powBits = { 0 }).votes(broken))
        e.close()
    }

    @Test
    fun `create wants two to four non blank choices`() = runTest {
        val e = engine()
        val repo = NostrPollRepository(e, powBits = { 0 })
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.create("Q", listOf("a"), 1L) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.create("Q", listOf("a", " "), 1L) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.create("Q", listOf("a", "b", "c", "d", "e"), 1L) } }
        e.close()
    }

    @Test
    fun `vote sends a 1018 with e and responses to the poll's relays`() = runTest {
        SilentWsRelay(acceptEvents = true).use { own ->
            SilentWsRelay(acceptEvents = true).use { named ->
                val e = engine(listOf(own.url))
                e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
                val p = poll(listOf("relay", named.url))
                val r = NostrPollRepository(e, powBits = { 0 }, hintAllowed = { true }).vote(p, listOf("b"))
                assertTrue(r.sentToAny)
                assertEquals(1018, r.kind)
                assertEquals(listOf(listOf("e", p.id), listOf("response", "b")), tags(e, r.eventId))
                assertTrue(awaitTrue { own.events.isNotEmpty() && named.events.isNotEmpty() }, "both relays get the vote")
                assertTrue(r.reached(listOf(named.url)))
                e.close()
            }
        }
    }

    @Test
    fun `vote without a poll field is refused`() = runTest {
        val e = engine()
        val plain = NoteMapper.note(EventBuilder.textNote("hi").signWithKeys(alice))!!
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { NostrPollRepository(e, powBits = { 0 }).vote(plain, listOf("a")) } }
        e.close()
    }

    @Test
    fun `votes reads 1018 from the poll's relays up to endsAt`() = runTest {
        var served = emptyList<String>()
        SilentWsRelay(serveFor = { served }, recordRequests = true).use { relay ->
            val p = poll(listOf("relay", relay.url))
            val vote = EventBuilder(Kind(1018u), "").tags(listOf(Tag.parse(listOf("e", p.id)), Tag.parse(listOf("response", "b")))).signWithKeys(alice)
            served = listOf(vote.asJson())
            val e = engine()
            val got = NostrPollRepository(e, powBits = { 0 }, hintAllowed = { true }).votes(p)
            assertEquals(listOf(vote.id().toHex()), got.map { it.id })
            assertEquals(listOf("b"), got.single().optionIds)
            assertTrue(relay.requests.any { it.replace(" ", "").contains("\"kinds\":[1018]") && it.contains("\"until\":2000000000") && it.contains(p.id) }, relay.requests.toString())
            e.close()
        }
    }

    @Test
    fun `votes returns what the local database has when the relays say nothing`() = runTest {
        val p = poll()
        val vote = EventBuilder(Kind(1018u), "").tags(listOf(Tag.parse(listOf("e", p.id)), Tag.parse(listOf("response", "a")))).signWithKeys(alice)
        val e = engine()
        e.save(vote)
        val got = NostrPollRepository(e, powBits = { 0 }).votes(p)
        assertEquals(listOf(vote.id().toHex()), got.map { it.id })
        assertEquals(listOf("a"), got.single().optionIds)
        e.close()
    }

    @Test
    fun `a vote that reaches no relay is not sentToAny`() = runTest {
        SilentWsRelay(rejectEvents = true).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val r = NostrPollRepository(e, powBits = { 0 }, hintAllowed = { true }).vote(poll(), listOf("a"))
            assertFalse(r.sentToAny)
            e.close()
        }
    }

    @Test
    fun `votes never asks a private relay the poll names`() = runTest {
        SilentWsRelay(serveFor = { emptyList() }, recordRequests = true).use { private ->
            val e = engine()
            try {
                val p = poll(listOf("relay", private.url))
                NostrPollRepository(e, powBits = { 0 }).votes(p)
                assertTrue(private.requests.isEmpty(), private.requests.toString())
                assertEquals(0, private.connections)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `vote never sends to a private relay the poll names`() = runTest {
        SilentWsRelay(acceptEvents = true).use { own ->
            SilentWsRelay(acceptEvents = true).use { private ->
                val e = engine(listOf(own.url))
                try {
                    e.connect()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
                    val r = NostrPollRepository(e, powBits = { 0 }).vote(poll(listOf("relay", private.url)), listOf("a"))
                    assertTrue(r.sentToAny)
                    assertTrue(awaitTrue { own.events.isNotEmpty() }, "the write relay gets the vote")
                    assertTrue(private.events.isEmpty(), private.events.toString())
                    assertEquals(0, private.connections)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `no relay a poll names stays attached after votes`() = runTest {
        SilentWsRelay(serveFor = { emptyList() }, recordRequests = true).use { named ->
            val e = engine()
            try {
                val foreign = ForeignRelays(e, lingerMs = 0)
                val p = poll(listOf("relay", named.url))
                NostrPollRepository(e, powBits = { 0 }, hintAllowed = { true }, foreign = foreign).votes(p)
                assertTrue(named.requests.isNotEmpty(), "the poll's relay was asked")
                assertTrue(awaitTrue { foreign.attached().isEmpty() && e.temporaryRelayUrls().isEmpty() }, "still attached: ${e.temporaryRelayUrls()}")
            } finally {
                e.close()
            }
        }
    }
}
