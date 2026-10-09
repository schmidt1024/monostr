package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip44Version
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.giftWrap
import rust.nostr.sdk.giftWrapFromSeal
import rust.nostr.sdk.nip44Encrypt
import java.nio.file.Path

class DmRepositoryTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val bob = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private val carol = Keys.generate()
    private suspend fun engine(signer: Signer = LocalSigner(alice.secretKey().toHex())) = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())
    private fun Unwrap.ok(): DmIncoming = (this as? Unwrap.Ok)?.message ?: error("expected Ok, got $this")

    /** Hand-builds a kind 1059 wrap from a raw (possibly malicious) rumor JSON, bypassing `privateMsgRumor`/`giftWrap`'s own id computation. */
    private fun wrapOf(rumorJson: String, sealer: Keys, receiver: PublicKey): Event {
        val ciphertext = nip44Encrypt(sealer.secretKey(), receiver, rumorJson, Nip44Version.V2)
        val seal = EventBuilder(Kind(13u), ciphertext).signWithKeys(sealer)
        return giftWrapFromSeal(receiver, seal, emptyList())
    }

    @Test
    fun `a wrap from bob unwraps to an incoming message with bob as peer`() = runTest {
        val e = engine()
        val rumor = EventBuilder.privateMsgRumor(alice.publicKey(), "hi alice").build(bob.publicKey())
        val wrap = giftWrap(NostrSigner.keys(bob), alice.publicKey(), rumor, emptyList())
        val m = NostrDmRepository(e).unwrap(wrap, silent = false).ok()
        assertEquals(rumor.id()!!.toHex(), m.rumorId); assertEquals(bob.publicKey().toHex(), m.peer)
        assertFalse(m.outgoing); assertEquals("hi alice", m.content); assertEquals(rumor.createdAt().asSecs().toLong(), m.createdAt); assertEquals(wrap.id().toHex(), m.wrapId)
        e.close()
    }

    @Test
    fun `self copy and peer wrap share the rumor id and the self copy is outgoing with bob as peer`() = runTest {
        val e = engine()
        val rumor = EventBuilder.privateMsgRumor(bob.publicKey(), "hi bob").build(alice.publicKey())
        val selfCopy = giftWrap(NostrSigner.keys(alice), alice.publicKey(), rumor, emptyList())
        val m = NostrDmRepository(e).unwrap(selfCopy, silent = false).ok()
        assertTrue(m.outgoing); assertEquals(bob.publicKey().toHex(), m.peer); assertEquals(rumor.id()!!.toHex(), m.rumorId)
        e.close()
    }

    @Test
    fun `foreign p and wrong kind are rejected`() = runTest {
        val e = engine()
        val toCarol = EventBuilder.privateMsgRumor(carol.publicKey(), "not for alice").build(bob.publicKey())
        val wrapForAlice = giftWrap(NostrSigner.keys(bob), alice.publicKey(), toCarol, emptyList()) // decryptable by alice, but p = carol
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(wrapForAlice, silent = false))
        val note = EventBuilder.textNote("kind 1 inside a wrap").build(bob.publicKey())
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(giftWrap(NostrSigner.keys(bob), alice.publicKey(), note, emptyList()), silent = true))
        e.close()
    }

    @Test
    fun `silent unwrap is locked without a silent answer and interactive unwrap still works`() = runTest {
        val mute = object : Signer by LocalSigner(alice.secretKey().toHex()) {
            override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        }
        val e = engine(mute)
        val rumor = EventBuilder.privateMsgRumor(alice.publicKey(), "psst").build(bob.publicKey())
        val wrap = giftWrap(NostrSigner.keys(bob), alice.publicKey(), rumor, emptyList())
        val repo = NostrDmRepository(e)
        assertEquals(Unwrap.Locked, repo.unwrap(wrap, silent = true))
        assertEquals("psst", repo.unwrap(wrap, silent = false).ok().content)
        e.close()
    }

    @Test
    fun `a rumor without an id is rejected`() = runTest {
        val e = engine()
        val json = """{"pubkey":"${bob.publicKey().toHex()}","created_at":1700000000,"kind":14,"tags":[["p","${alice.publicKey().toHex()}"]],"content":"no id here"}"""
        val wrap = wrapOf(json, bob, alice.publicKey())
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(wrap, silent = true))
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(wrap, silent = false))
        e.close()
    }

    @Test
    fun `a rumor with a forged id is rejected`() = runTest {
        val e = engine()
        val forged = "ab".repeat(32)
        val json = """{"id":"$forged","pubkey":"${bob.publicKey().toHex()}","created_at":1700000000,"kind":14,"tags":[["p","${alice.publicKey().toHex()}"]],"content":"forged"}"""
        val wrap = wrapOf(json, bob, alice.publicKey())
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(wrap, silent = false))
        e.close()
    }

    @Test
    fun `a seal by bob wrapping a rumor claiming carol as author is rejected`() = runTest {
        val e = engine()
        // A validly-idd rumor (author = carol) sealed by bob's key instead of carol's: neither the
        // no-id nor the forged-id check fires, so this exercises the sender/author mismatch path
        // (rust-nostr's own "sender public key mismatch" check inside fromGiftWrap, and/or our
        // rumor.author() != sender check).
        val rumor = EventBuilder.privateMsgRumor(alice.publicKey(), "spoof").build(carol.publicKey())
        val wrap = wrapOf(rumor.asJson(), bob, alice.publicKey())
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(wrap, silent = false))
        e.close()
    }

    @Test
    fun `a wrap our key cannot decrypt is rejected, a signer refusal is locked`() = runTest {
        val e = engine()
        // addressed to alice but its content is no NIP-44 payload: a local key can never open it
        val garbage = EventBuilder(Kind(1059u), "not a payload").tags(listOf(rust.nostr.sdk.Tag.parse(listOf("p", alice.publicKey().toHex())))).signWithKeys(Keys.generate())
        assertEquals(Unwrap.Rejected, NostrDmRepository(e).unwrap(garbage, silent = true))
        e.close()

        val declining = object : Signer by LocalSigner(alice.secretKey().toHex()) {
            override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = throw SigningRejectedException()
        }
        val e2 = NostrEngine.create(dir.resolve("lmdb2").toString(), declining, emptyList())
        val mine = giftWrap(NostrSigner.keys(bob), alice.publicKey(), EventBuilder.privateMsgRumor(alice.publicKey(), "hi").build(bob.publicKey()), emptyList())
        assertEquals(Unwrap.Locked, NostrDmRepository(e2).unwrap(mine, silent = false))
        e2.close()
    }

    private suspend fun connectedEngine(relay: com.monostr.nostr.SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(alice.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(com.monostr.nostr.awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return e
    }

    @Test
    fun `a fetch every relay answered is complete`() = runTest {
        com.monostr.nostr.SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofSeconds(3)).fetchWraps(listOf(relay.url), since = 0)
            assertTrue(fetch.completed)
            assertTrue(fetch.wraps.isEmpty())
            e.close()
        }
    }

    @Test
    fun `a fetch that ran into the timeout is not complete`() = runTest {
        com.monostr.nostr.SilentWsRelay().use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofMillis(500)).fetchWraps(listOf(relay.url), since = 0)
            assertFalse(fetch.completed)
            e.close()
        }
    }

    @Test
    fun `a fetch an own relay refused is not complete`() = runTest {
        // CLOSED, then EOSE so the fetch itself returns in time: only the refusal makes it incomplete
        com.monostr.nostr.SilentWsRelay(closeWith = "auth-required: we only serve members", answerEose = true).use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofSeconds(3)).fetchWraps(listOf(relay.url), since = 0)
            assertFalse(fetch.completed)
            e.close()
        }
    }

    @Test
    fun `an own relay's auth refusal travels with the fetch, another refusal only makes it incomplete`() = runTest {
        com.monostr.nostr.SilentWsRelay(closeWith = "auth-required: we only serve members", answerEose = true).use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofSeconds(3)).fetchWraps(listOf(relay.url), since = 0)
            assertFalse(fetch.completed)
            assertEquals(setOf(relay.url), fetch.authRefused.keys)
            assertTrue(fetch.authRefused.values.all { it > 0 }) // the refusal's own CLOSED sequence
            e.close()
        }
    }

    private val strfryRefusal = "ERROR: auth-required: requested filter requires authentication"

    private suspend fun wrapForAlice(): String =
        giftWrap(NostrSigner.keys(bob), alice.publicKey(), EventBuilder.privateMsgRumor(alice.publicKey(), "hi").build(bob.publicKey()), emptyList()).asJson()

    @Test
    fun `a fetch refused before the relay accepted our AUTH is repeated after the AUTH OK`() = runTest {
        // strfry: the REQ that overtakes the AUTH gets CLOSED, the OK follows later; the second REQ is served
        com.monostr.nostr.SilentWsRelay(authChallenge = "c", refuseUntilAuth = strfryRefusal, authOkDelayMs = 1500, serve = listOf(wrapForAlice())).use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofSeconds(3), authWait = java.time.Duration.ofSeconds(5))
                .fetchWraps(listOf(relay.url), since = 0)
            assertEquals(2, relay.requests.size, "expected the refused REQ and one more after the AUTH OK")
            assertEquals(1, fetch.wraps.size)
            assertTrue(fetch.completed)
            assertTrue(fetch.authRefused.isEmpty())
            e.close()
        }
    }

    @Test
    fun `a fetch refused for AUTH stays refused when the relay never accepts our AUTH`() = runTest {
        com.monostr.nostr.SilentWsRelay(authChallenge = "c", refuseUntilAuth = strfryRefusal, answerAuth = false, serve = listOf(wrapForAlice())).use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofSeconds(3), authWait = java.time.Duration.ofSeconds(1))
                .fetchWraps(listOf(relay.url), since = 0)
            assertEquals(1, relay.requests.size)
            assertTrue(fetch.wraps.isEmpty())
            assertFalse(fetch.completed)
            assertEquals(setOf(relay.url), fetch.authRefused.keys)
            assertTrue(fetch.authRefused.values.all { it > 0 }) // the refusal's own CLOSED sequence
            e.close()
        }
    }

    @Test
    fun `a restricted refusal makes the fetch incomplete but is no auth refusal`() = runTest {
        com.monostr.nostr.SilentWsRelay(closeWith = "restricted: paid relay", answerEose = true).use { relay ->
            val e = connectedEngine(relay)
            val fetch = NostrDmRepository(e, fetchTimeout = java.time.Duration.ofSeconds(3)).fetchWraps(listOf(relay.url), since = 0)
            assertFalse(fetch.completed)
            assertTrue(fetch.authRefused.isEmpty())
            e.close()
        }
    }

    @Test
    fun `send stores both wraps locally and reports no relay when none is reachable`() = runTest {
        val e = engine()
        val r = NostrDmRepository(e).send(bob.publicKey().toHex(), "hello", peerRelays = emptyList(), ownRelays = emptyList())
        assertFalse(r.sentToPeer); assertFalse(r.sentToSelf); assertTrue(r.rumorId.length == 64)
        val wraps = e.query(rust.nostr.sdk.Filter().kind(rust.nostr.sdk.Kind(1059u)))
        assertEquals(2, wraps.size)
        // our own copy unwraps back to the same rumor id
        val own = wraps.first { it.tags().toVec().map { t -> t.asVec() }.any { t -> t[0] == "p" && t[1] == alice.publicKey().toHex() } }
        assertEquals(r.rumorId, NostrDmRepository(e).unwrap(own, silent = false).ok().rumorId)
        e.close()
    }
}
