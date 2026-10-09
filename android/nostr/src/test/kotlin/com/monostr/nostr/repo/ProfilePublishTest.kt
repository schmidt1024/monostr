package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

class ProfilePublishTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val pk = me.publicKey().toHex()
    private suspend fun engine(relays: List<String> = emptyList()) =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), relays)

    @Test
    fun `an accepted profile is stored, read back raw and shown after invalidate`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val repo = NostrProfileRepository(e, now = { 1_000_000 })
            assertNull(repo.observe(pk).first().name)
            val json = """{"name":"new","x_custom":"keep"}"""
            assertTrue(NostrPublishRepository(e).profile(json).sentToAny)
            assertEquals(json, repo.rawMetadata(pk))
            assertEquals("new", repo.invalidate(pk).name)
            assertEquals("new", repo.observe(pk).first().name)
            e.close()
        }
    }

    @Test
    fun `a profile no relay accepted leaves the database unchanged`() = runTest {
        val e = engine()
        val r = NostrPublishRepository(e).profile("""{"name":"offline"}""")
        assertFalse(r.sentToAny)
        assertNull(NostrProfileRepository(e).rawMetadata(pk, Duration.ofMillis(200)))
        e.close()
    }

    @Test
    fun `a published relay list also goes to the index relays, where other clients and the watcher look`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            SilentWsRelay(acceptEvents = true).use { index ->
                val e = engine(listOf(relay.url))
                e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
                val r = NostrPublishRepository(e, indexRelays = listOf(index.url, relay.url)).publishRelayList(listOf(relay.url))
                assertTrue(r.successRelays.any { it.contains(":${index.port}") }, "sent to ${r.successRelays}, failed ${r.failedRelays}")
                assertEquals(1, index.events.size)
                assertTrue(index.events[0].contains("\"kind\":10002"), index.events[0])
                assertEquals(1, relay.events.size, "an index relay that is one of the user's relays got it already")
                assertEquals(listOf(relay.url), e.relayUrls(), "the index relay does not stay")
                e.close()
            }
        }
    }

    @Test
    fun `a relay list the user's relays refused is not sent to the index relays`() = runTest {
        SilentWsRelay(rejectEvents = true).use { relay ->
            SilentWsRelay(acceptEvents = true).use { index ->
                val e = engine(listOf(relay.url))
                e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
                val r = NostrPublishRepository(e, indexRelays = listOf(index.url)).publishRelayList(listOf(relay.url))
                assertFalse(r.sentToAny)
                assertTrue(index.events.isEmpty())
                assertTrue(e.query(Filter().kind(Kind(10002u)).author(me.publicKey())).isEmpty(), "a refused kind 10002 stayed in the database")
                e.close()
            }
        }
    }

    @Test
    fun `a rejected DM relay list is not kept in the database`() = runTest {
        SilentWsRelay(rejectEvents = true).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val r = NostrPublishRepository(e).dmRelayList(listOf("wss://relay.monostr.com"))
            assertFalse(r.sentToAny)
            val found = e.query(Filter().kind(Kind(10050u)).author(me.publicKey()))
            assertTrue(found.isEmpty(), "a refused kind 10050 stayed in the database")
            e.close()
        }
    }

    @Test
    fun `fresh metadata asks the relays even with a stored kind 0 and the newer one wins`() = runTest {
        val newer = EventBuilder(Kind(0u), """{"name":"newer"}""").customCreatedAt(Timestamp.fromSecs(2_000u)).signWithKeys(me)
        SilentWsRelay(serve = listOf(newer.asJson())).use { relay ->
            val e = engine(listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            e.save(EventBuilder(Kind(0u), """{"name":"older"}""").customCreatedAt(Timestamp.fromSecs(1_000u)).signWithKeys(me))
            val repo = NostrProfileRepository(e)
            assertEquals("""{"name":"older"}""", repo.rawMetadata(pk)) // the database answers, no fetch
            assertEquals(0, relay.requests.size)
            assertEquals("""{"name":"newer"}""", repo.freshMetadata(pk))
            assertEquals(1, relay.requests.size)
            e.close()
        }
    }

    @Test
    fun `without relays fresh metadata falls back to the database`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(0u), """{"name":"stored"}""").signWithKeys(me))
        assertEquals("""{"name":"stored"}""", NostrProfileRepository(e).freshMetadata(pk, Duration.ofMillis(200)))
        e.close()
    }
}
