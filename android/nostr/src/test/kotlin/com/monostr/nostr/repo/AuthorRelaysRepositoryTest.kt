package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
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
}
