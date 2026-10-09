package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
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

class MuteRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())

    /** Publishing "succeeds": the event is signed with the test key, stored, and one relay is reported. */
    private fun okSend(e: NostrEngine, onSend: () -> Unit = {}): suspend (EventBuilder) -> PublishResult = { b ->
        onSend()
        val ev = b.signWithKeys(me); e.save(ev); PublishResult(ev.id().toHex(), ev.kind().asU16().toInt(), listOf("wss://r"), emptyMap())
    }
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    /** An already-synced empty list, so the load does not depend on a reachable relay (no relays in these tests). */
    private suspend fun seedEmptyList(e: NostrEngine) {
        val content = signer.nip44Encrypt(signer.pubkey, "[]")
        e.save(EventBuilder(Kind(10000u), content).customCreatedAt(Timestamp.fromSecs(1u)).signWithKeys(me))
    }

    private fun pTag(id: String) = JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(id)))

    /** An external signer without a remembered permission: no silent answer; interactive decrypt works. */
    private class NoSilentSigner(private val inner: LocalSigner) : Signer by inner {
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
    }

    @Test
    fun `a mute is written privately, foreign entries and public p stay, unmute removes from both parts`() = runTest {
        val e = engine()
        val private = JsonArray(listOf(pTag(b), JsonArray(listOf(JsonPrimitive("word"), JsonPrimitive("spam"))))).toString()
        e.save(EventBuilder(Kind(10000u), signer.nip44Encrypt(signer.pubkey, private)).tags(listOf(Tag.parse(listOf("p", a)), Tag.parse(listOf("t", "nsfw")))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me))
        val repo = NostrMuteRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(setOf(a, b), repo.muted.value)
        assertEquals(ListOutcome.Ok, repo.mute(c))
        assertEquals(setOf(a, b, c), repo.muted.value)
        var stored = e.query(Filter().kind(Kind(10000u)).author(me.publicKey())).single()
        assertEquals(listOf(listOf("p", a), listOf("t", "nsfw")), stored.tags().toVec().map { it.asVec() })
        assertTrue(signer.nip44Decrypt(signer.pubkey, stored.content()).let { it.contains(c) && it.contains(b) && it.contains("spam") })
        assertEquals(ListOutcome.Ok, repo.unmute(a))
        stored = e.query(Filter().kind(Kind(10000u)).author(me.publicKey())).single()
        assertEquals(listOf(listOf("t", "nsfw")), stored.tags().toVec().map { it.asVec() })
        assertFalse(a in repo.muted.value)
        e.close()
    }

    @Test
    fun `an unreadable private part makes the list read-only and nothing is published`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10000u), "not-nip44-ciphertext").tags(listOf(Tag.parse(listOf("p", a)))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me))
        var sends = 0
        val repo = NostrMuteRepository(e, signer, now = { 2000 }, send = { sends++; throw IllegalStateException("must not send") })
        repo.ensureLoaded()
        assertEquals(setOf(a), repo.muted.value) // public entries work
        assertTrue(repo.state.value.loaded); assertFalse(repo.state.value.writable)
        assertEquals(ListOutcome.Unsupported, repo.mute(b))
        assertEquals(0, sends)
        e.close()
    }

    @Test
    fun `a mute merges with a list another device changed meanwhile`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val repo = NostrMuteRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(ListOutcome.Ok, repo.mute(a))
        // another device adds b (private) at a later time; it lands in the database before our next write
        val other = JsonArray(listOf(pTag(a), pTag(b))).toString()
        e.save(EventBuilder(Kind(10000u), signer.nip44Encrypt(signer.pubkey, other)).customCreatedAt(Timestamp.fromSecs(2500u)).signWithKeys(me))
        assertEquals(ListOutcome.Ok, repo.mute(c))
        assertEquals(setOf(a, b, c), repo.muted.value)
        val stored = e.query(Filter().kind(Kind(10000u)).author(me.publicKey())).single()
        assertTrue(stored.createdAt().asSecs().toLong() > 2500)
        e.close()
    }

    @Test
    fun `without a silent decrypt only the public entries apply until an interactive load`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10000u), signer.nip44Encrypt(signer.pubkey, JsonArray(listOf(pTag(b))).toString())).tags(listOf(Tag.parse(listOf("p", a)))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me))
        val repo = NostrMuteRepository(e, NoSilentSigner(signer), now = { 2000 }, send = okSend(e))
        repo.ensureLoaded(interactive = false)
        assertEquals(setOf(a), repo.muted.value)
        assertFalse(repo.state.value.loaded)
        repo.ensureLoaded(interactive = true)
        assertEquals(setOf(a, b), repo.muted.value)
        assertTrue(repo.state.value.loaded); assertTrue(repo.state.value.writable)
        e.close()
    }

    @Test
    fun `a failed publish reverts the mute`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val repo = NostrMuteRepository(e, signer, now = { 2000 }, send = { throw IllegalStateException("relay down") })
        repo.ensureLoaded()
        assertEquals(ListOutcome.PublishFailed, repo.mute(a))
        assertTrue(repo.muted.value.isEmpty())
        e.close()
    }

    @Test
    fun `a change that changes nothing is Ok without signing or publishing`() = runTest {
        val e = engine()
        seedEmptyList(e)
        var sends = 0
        val repo = NostrMuteRepository(e, signer, now = { 2000 }, send = okSend(e) { sends++ })
        repo.ensureLoaded()
        assertEquals(ListOutcome.Ok, repo.mute(a))
        assertEquals(1, sends)
        assertEquals(ListOutcome.Ok, repo.mute(a)) // already muted
        assertEquals(1, sends)
        assertEquals(ListOutcome.Ok, repo.unmute(b)) // never muted
        assertEquals(1, sends)
        assertEquals(setOf(a), repo.muted.value)
        e.close()
    }

    @Test
    fun `only foreign 64-hex pubkeys count as muted`() = runTest {
        val e = engine()
        // what another app may have written: an npub, an empty entry, upper case, the own key
        val npub = "npub1" + "q".repeat(58)
        val private = JsonArray(listOf(pTag(npub), pTag(""), pTag("C".repeat(64)), pTag(signer.pubkey), pTag(b))).toString()
        e.save(EventBuilder(Kind(10000u), signer.nip44Encrypt(signer.pubkey, private)).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me))
        val repo = NostrMuteRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(setOf(b), repo.muted.value)
        e.close()
    }
}
