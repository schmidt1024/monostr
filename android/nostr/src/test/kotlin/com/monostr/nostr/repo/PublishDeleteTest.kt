package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

class PublishDeleteTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val other = Keys.generate()

    private suspend fun engine(name: String, relays: List<String> = emptyList()) =
        NostrEngine.create(dir.resolve(name).toString(), LocalSigner(me.secretKey().toHex()), relays)

    private fun deletion(keys: Keys, id: String): Event =
        EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", id)), Tag.parse(listOf("k", "1")))).signWithKeys(keys)

    @Test
    fun `a stored kind 5 of the author removes the note and keeps later copies out`() = runTest {
        val e = engine("db1")
        val note = EventBuilder.textNote("gone soon").signWithKeys(me)
        e.save(note)
        e.save(deletion(me, note.id().toHex()))
        assertNull(e.eventById(note.id().toHex()))
        runCatching { e.save(note) } // a relay may serve the note again
        assertNull(e.eventById(note.id().toHex()))
        e.close()
    }

    @Test
    fun `a kind 5 of someone else changes nothing`() = runTest {
        val e = engine("db2")
        val note = EventBuilder.textNote("mine").signWithKeys(me)
        e.save(note)
        runCatching { e.save(deletion(other, note.id().toHex())) }
        assertNotNull(e.eventById(note.id().toHex()))
        e.close()
    }

    @Test
    fun `delete sends exactly the NIP-09 tags and the note is gone once a relay accepted`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val e = engine("db3", listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val note = EventBuilder.textNote("delete me").signWithKeys(me)
            e.save(note)
            val result = NostrPublishRepository(e).delete(NoteMapper.note(note)!!)
            assertTrue(result.sentToAny)
            assertEquals(5, result.kind)
            val sent = Event.fromJson(relay.events.single().substringAfter(",").dropLast(1))
            assertEquals("", sent.content())
            assertEquals(listOf(listOf("e", note.id().toHex()), listOf("k", "1")), sent.tags().toVec().map { it.asVec() })
            assertNull(e.eventById(note.id().toHex()))
            e.close()
        }
    }

    @Test
    fun `a request no relay accepted leaves the note and stores no kind 5`() = runTest {
        SilentWsRelay(rejectEvents = true).use { relay ->
            val e = engine("db4", listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val note = EventBuilder.textNote("stays").signWithKeys(me)
            e.save(note)
            assertFalse(NostrPublishRepository(e).delete(NoteMapper.note(note)!!).sentToAny)
            assertNotNull(e.eventById(note.id().toHex()))
            assertTrue(e.query(Filter().kind(Kind(5u)).author(me.publicKey())).isEmpty())
            e.close()
        }
    }

    @Test
    fun `an unstored publish to a relay that answers nothing stores nothing and reports no success`() = runTest {
        SilentWsRelay().use { relay ->
            val e = engine("db6", listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val note = EventBuilder.textNote("quiet").signWithKeys(me)
            e.save(note)
            val builder = EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", note.id().toHex()))))
            val result = e.signAndPublishUnstored(builder, java.time.Duration.ofMillis(300))
            assertFalse(result.sentToAny)
            assertTrue(result.failedRelays.values.any { it == com.monostr.nostr.NO_ANSWER }, result.failedRelays.toString())
            assertNotNull(e.eventById(note.id().toHex()))
            assertTrue(e.query(Filter().kind(Kind(5u))).isEmpty())
            e.close()
        }
    }

    @Test
    fun `a relay that wants AUTH for writes gets the request once more after the AUTH`() = runTest {
        SilentWsRelay(acceptEvents = true, refuseEventsUntilAuth = "auth-required: authenticate first").use { relay ->
            val e = engine("db7", listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val note = EventBuilder.textNote("auth first").signWithKeys(me)
            e.save(note)
            val result = NostrPublishRepository(e).delete(NoteMapper.note(note)!!)
            assertTrue(result.sentToAny, result.failedRelays.toString())
            assertEquals(2, relay.events.size)
            assertNull(e.eventById(note.id().toHex()))
            e.close()
        }
    }

    @Test
    fun `one silent relay does not hold the request back once another accepted`() = runTest {
        SilentWsRelay(acceptEvents = true).use { good ->
            SilentWsRelay().use { silent ->
                val e = engine("db8", listOf(good.url, silent.url))
                e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().size == 2 }, "loopback relays never connected")
                val note = EventBuilder.textNote("grace").signWithKeys(me)
                e.save(note)
                val builder = EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", note.id().toHex()))))
                val start = System.nanoTime()
                val result = e.signAndPublishUnstored(builder, java.time.Duration.ofSeconds(8))
                val seconds = (System.nanoTime() - start) / 1e9
                assertTrue(result.sentToAny)
                assertTrue(seconds < 4, "took $seconds s")
                assertEquals(listOf(com.monostr.nostr.NO_ANSWER), result.failedRelays.values.toList())
                assertNull(e.eventById(note.id().toHex()))
                e.close()
            }
        }
    }

    @Test
    fun `the note link names the author and the kind`() = runTest {
        val e = engine("db5")
        val note = NoteMapper.note(EventBuilder.textNote("link me").signWithKeys(me))!!
        val link = NostrPublishRepository(e).noteLink(note)
        assertTrue(link.startsWith("nostr:nevent1"))
        val parsed = rust.nostr.sdk.Nip19Event.fromNostrUri(link)
        assertEquals(note.id, parsed.eventId().toHex())
        assertEquals(note.author, parsed.author()?.toHex())
        e.close()
    }
}
