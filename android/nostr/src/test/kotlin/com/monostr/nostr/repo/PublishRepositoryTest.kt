package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec 2026-11-06 Tor 7.4: an onion relay is the user's own business and never goes into a published relay list. */
class PublishRepositoryTest {
    @TempDir lateinit var dir: Path

    private val entries = listOf(
        RelayEntry("wss://relay.damus.io", RelayMode.BOTH),
        RelayEntry("ws://abcdefghijklmnop.onion", RelayMode.BOTH),
        RelayEntry("wss://nos.lol", RelayMode.READ),
        RelayEntry("ws://ABC.ONION:7777/", RelayMode.WRITE),
    )

    @Test
    fun `relay list event leaves out onion`() {
        assertEquals(listOf(listOf("r", "wss://relay.damus.io"), listOf("r", "wss://nos.lol", "read")), relayListTags(entries))
    }

    @Test
    fun `dm relay list leaves out onion`() {
        assertEquals(
            listOf(listOf("relay", "wss://auth.nostr1.com")),
            dmRelayTags(listOf("wss://auth.nostr1.com/", "ws://abc.onion", "wss://x.ONION.")),
        )
    }

    @Test
    fun `the stored kind 10002 event has no onion tag`() = runBlocking<Unit> {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), com.monostr.nostr.LocalSigner("0000000000000000000000000000000000000000000000000000000000000001"), emptyList())
        try {
            val result = NostrPublishRepository(engine).relayList(entries)
            val tags = engine.eventById(result.eventId)!!.tags().toVec().map { it.asVec() }
            assertEquals(2, tags.size, tags.toString())
            assertTrue(tags.none { t -> t.any { it.contains("onion", ignoreCase = true) } }, tags.toString())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a list of onion relays only publishes nothing`() = runBlocking<Unit> {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), com.monostr.nostr.LocalSigner("0000000000000000000000000000000000000000000000000000000000000001"), emptyList())
        try {
            val repo = NostrPublishRepository(engine)
            val only = listOf(RelayEntry("ws://abc.onion", RelayMode.BOTH))
            assertTrue(!repo.relayList(only).sentToAny)
            assertTrue(!repo.publishRelayList(only).sentToAny)
            assertTrue(!repo.initialRelayList(listOf("ws://abc.onion")).sentToAny)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `odd spellings of an onion are left out too`() {
        val odd = listOf("wss://a_b.onion", "ws://abc%2Eonion", "ws://foo_bar.abc.onion").map { RelayEntry(it, RelayMode.BOTH) }
        assertEquals(emptyList<List<String>>(), relayListTags(odd))
        assertEquals(emptyList<List<String>>(), dmRelayTags(odd.map { it.url }))
    }

    @Test
    fun `a list that leaves no writer or no reader once onion is out publishes nothing`() = runBlocking<Unit> {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), com.monostr.nostr.LocalSigner("0000000000000000000000000000000000000000000000000000000000000001"), emptyList())
        try {
            val repo = NostrPublishRepository(engine)
            val noWriter = listOf(RelayEntry("wss://a.example", RelayMode.READ), RelayEntry("ws://abc.onion", RelayMode.WRITE))
            val noReader = listOf(RelayEntry("wss://a.example", RelayMode.WRITE), RelayEntry("ws://abc.onion", RelayMode.READ))
            for (list in listOf(noWriter, noReader)) {
                assertEquals("", repo.relayList(list).eventId)
                assertEquals("", repo.publishRelayList(list).eventId)
            }
            assertTrue(repo.relayList(listOf(RelayEntry("wss://a.example", RelayMode.BOTH), RelayEntry("ws://abc.onion", RelayMode.WRITE))).eventId.isNotEmpty())
        } finally {
            engine.close()
        }
    }
}
