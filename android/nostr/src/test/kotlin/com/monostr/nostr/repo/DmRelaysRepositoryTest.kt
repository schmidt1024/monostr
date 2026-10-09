package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

class DmRelaysRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val bob = Keys.generate()
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf("wss://mine.example"))
    private fun list(keys: Keys, kind: UInt, tag: String, vararg urls: String) =
        EventBuilder(Kind(kind.toUShort()), "").tags(urls.map { Tag.parse(listOf(tag, it)) }).signWithKeys(keys)

    @Test
    fun `peer with a kind 10050 list gets those relays plus the fallback, capped at four`() = runTest {
        val e = engine()
        e.save(list(bob, 10050u, "relay", "wss://a.example", "wss://b.example/", "ws://plain.example", "wss://c.example", "wss://d.example"))
        val repo = NostrDmRelaysRepository(e, NostrAuthorRelaysRepository(e, indexers = emptyList()), indexers = emptyList(), now = { 1000 })
        assertEquals(listOf("wss://a.example", "wss://b.example", "wss://c.example", DmRelaysRepository.FALLBACK), repo.dmRelays(bob.publicKey().toHex()))
        e.close()
    }

    @Test
    fun `without 10050 the peer's write relays are used, without both only the fallback`() = runTest {
        val e = engine()
        e.save(list(bob, 10002u, "r", "wss://w.example"))
        val repo = NostrDmRelaysRepository(e, NostrAuthorRelaysRepository(e, indexers = emptyList()), indexers = emptyList(), now = { 1000 }, fetchTimeout = java.time.Duration.ofMillis(200))
        assertEquals(listOf("wss://w.example", DmRelaysRepository.FALLBACK), repo.dmRelays(bob.publicKey().toHex()))
        val nobody = Keys.generate().publicKey().toHex()
        assertEquals(listOf(DmRelaysRepository.FALLBACK), repo.dmRelays(nobody))
        e.close()
    }

    @Test
    fun `own list is unknown without a relay answer and found once a relay accepted it, with relay tags`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val e = connectedEngine(relay)
            val repo = NostrDmRelaysRepository(e, NostrAuthorRelaysRepository(e, indexers = emptyList()), indexers = emptyList(), now = { 1000 }, fetchTimeout = java.time.Duration.ofMillis(200))
            // nothing local and the relay never answers with EOSE: silence is not "no list"
            assertEquals(OwnDmRelays.Unknown, repo.ownDmRelays())
            NostrPublishRepository(e).dmRelayList(listOf("wss://relay.monostr.com", "wss://x.example"))
            assertEquals(OwnDmRelays.Found(listOf("wss://relay.monostr.com", "wss://x.example")), repo.ownDmRelays())
            val tags = e.query(rust.nostr.sdk.Filter().kind(Kind(10050u)).author(me.publicKey())).first().tags().toVec().map { it.asVec() }
            assertEquals(listOf(listOf("relay", "wss://relay.monostr.com"), listOf("relay", "wss://x.example")), tags)
            e.close()
        }
    }

    private suspend fun connectedEngine(relay: SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return e
    }

    @Test
    fun `own list is none only when a connected relay answered before the timeout`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(relay)
            val repo = NostrDmRelaysRepository(e, NostrAuthorRelaysRepository(e, indexers = emptyList()), indexers = emptyList(), fetchTimeout = java.time.Duration.ofSeconds(3))
            assertEquals(OwnDmRelays.None, repo.ownDmRelays())
            e.close()
        }
    }

    @Test
    fun `own list stays unknown when a connected relay lets the fetch run into the timeout`() = runTest {
        SilentWsRelay().use { relay ->
            val e = connectedEngine(relay)
            val repo = NostrDmRelaysRepository(e, NostrAuthorRelaysRepository(e, indexers = emptyList()), indexers = emptyList(), fetchTimeout = java.time.Duration.ofMillis(500))
            // a relay is connected, so the old rule said None and published over the real list
            assertEquals(OwnDmRelays.Unknown, repo.ownDmRelays())
            e.close()
        }
    }
}
