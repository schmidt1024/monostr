package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

class PrivateListTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())

    /** Publishing "succeeds": the event is signed with the test key, stored, and one relay is reported. */
    private fun okSend(e: NostrEngine): suspend (EventBuilder) -> PublishResult = { b ->
        val ev = b.signWithKeys(me); e.save(ev); PublishResult(ev.id().toHex(), ev.kind().asU16().toInt(), listOf("wss://r"), emptyMap())
    }
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    /** An already-synced empty list, so the load does not depend on a reachable relay (no relays in these tests). */
    private suspend fun seedEmptyList(e: NostrEngine) {
        val content = signer.nip44Encrypt(signer.pubkey, "[]")
        e.save(EventBuilder(Kind(10000u), content).customCreatedAt(Timestamp.fromSecs(1u)).signWithKeys(me))
    }

    @Test
    fun `a kind 10000 list with p tags behaves like the bookmarks list does with e tags`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val seen = mutableListOf<ListState>()
        val list = NostrPrivateList(e, signer, kind = 10000, tag = "p", now = { 2000 }, send = okSend(e), onState = { seen += it })
        list.ensureLoaded(interactive = true)
        assertTrue(list.state.value.loaded); assertTrue(list.state.value.writable)
        assertEquals(ListOutcome.Ok, list.set(a, present = true))
        assertEquals(listOf(a), list.state.value.ids)
        assertEquals(list.state.value, seen.last()) // the hook saw the state after set
        val stored = e.query(Filter().kind(Kind(10000u)).author(me.publicKey())).single()
        assertTrue(stored.tags().toVec().isEmpty()) // private: nothing in the public tags
        assertFalse(stored.content().contains(a))
        assertEquals(ListOutcome.Ok, list.set(a, present = false))
        assertTrue(list.state.value.ids.isEmpty())
        assertEquals(ListOutcome.Ok, list.set(b, present = true))
        assertEquals(ListOutcome.Ok, list.set(b, present = true)) // idempotent
        assertEquals(listOf(b), list.state.value.ids)
        e.close()
    }

    @Test
    fun `foreign entries and public entries of the tag are preserved, removing takes an id out of both parts`() = runTest {
        val e = engine()
        // a list another app wrote: public p (a) and t tag, private p (b) and word
        val private = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(b))), JsonArray(listOf(JsonPrimitive("word"), JsonPrimitive("spam"))))).toString()
        val content = signer.nip44Encrypt(signer.pubkey, private)
        e.save(EventBuilder(Kind(10000u), content).tags(listOf(Tag.parse(listOf("p", a)), Tag.parse(listOf("t", "nsfw")))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me))
        val list = NostrPrivateList(e, signer, kind = 10000, tag = "p", now = { 2000 }, send = okSend(e))
        list.ensureLoaded(interactive = true)
        assertEquals(setOf(a, b), list.state.value.ids.toSet())
        // also public a → removed from the public tags
        assertEquals(ListOutcome.Ok, list.set(a, present = false))
        val stored = e.query(Filter().kind(Kind(10000u)).author(me.publicKey())).single()
        assertEquals(listOf(listOf("t", "nsfw")), stored.tags().toVec().map { it.asVec() })
        val decrypted = signer.nip44Decrypt(signer.pubkey, stored.content())
        assertTrue(decrypted.contains("spam")); assertTrue(decrypted.contains(b)); assertFalse(decrypted.contains(a))
        e.close()
    }

    @Test
    fun `a write cancelled before the relays answered takes the optimistic entry back`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val gate = CompletableDeferred<Unit>()
        val sending = CompletableDeferred<Unit>()
        val list = NostrPrivateList(e, signer, kind = 10000, tag = "p", now = { 2000 }, send = { b -> sending.complete(Unit); gate.await(); okSend(e)(b) })
        list.ensureLoaded(interactive = true)
        // e.g. mute in a thread, then back before the relay OK: the screen's scope is cancelled
        val job = launch { list.set(a, present = true) }
        sending.await()
        assertEquals(listOf(a), list.state.value.ids) // optimistic
        job.cancelAndJoin()
        assertFalse(a in list.state.value.ids)
        assertTrue(list.state.value.writable && list.state.value.loaded)
        e.close()
    }

    @Test
    fun `a write cancelled during the refresh before it takes the optimistic entry back`() = runTest {
        // a relay that never answers: the pre-write refresh of an already loaded list waits on it
        SilentWsRelay().use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), signer, listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            seedEmptyList(e)
            var sends = 0
            val list = NostrPrivateList(e, signer, kind = 10000, tag = "p", now = { 2000 }, send = { b -> sends++; okSend(e)(b) },
                fetchTimeout = Duration.ofMillis(200), refreshTimeout = Duration.ofSeconds(30))
            list.ensureLoaded(interactive = true)
            val job = launch(Dispatchers.Default) { list.set(a, present = true) }
            assertTrue(awaitTrue { a in list.state.value.ids }, "no optimistic entry")
            job.cancelAndJoin()
            assertFalse(a in list.state.value.ids)
            assertTrue(list.state.value.writable && list.state.value.loaded)
            assertEquals(0, sends)
            e.close()
        }
    }
}
