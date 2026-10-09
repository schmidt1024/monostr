package com.monostr.nostr

import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

class NostrEngineTest {
    @TempDir lateinit var dir: Path

    private val sk1 = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun `relay urls list and remove, bad urls do not abort startup`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://a.example", "wss://b.example/"))
        assertEquals(setOf("wss://a.example", "wss://b.example"), engine.relayUrls().toSet())
        engine.removeRelays(listOf("wss://a.example"))
        assertEquals(listOf("wss://b.example"), engine.relayUrls())
        // must not throw
        engine.addRelays(listOf("not a url"))
    }

    @Test
    fun `a rejection in the signer propagates unchanged and stores nothing`() = runTest {
        val rejecting = object : Signer {
            override val pubkey: String = LocalSigner(sk1).pubkey
            override suspend fun sign(event: UnsignedEvent): Event = throw SigningRejectedException()
            override suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String = throw Nip44UnsupportedException()
            override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = throw Nip44UnsupportedException()
            override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        }
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), rejecting, emptyList())
        val thrown = runCatching { engine.signAndSend(EventBuilder.textNote("hi")) }.exceptionOrNull()
        assertTrue(thrown is SigningRejectedException, "got $thrown")
        assertTrue(engine.query(Filter().kind(Kind(1u))).isEmpty())
    }

    @Test
    fun `signAndPublish stores nothing when no relay accepts the event`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk1), emptyList())
        val result = engine.signAndPublish(EventBuilder.textNote("hi"))
        assertFalse(result.sentToAny)
        assertTrue(engine.query(Filter().kind(Kind(1u))).isEmpty())
    }

    private suspend fun connected(relay: SilentWsRelay): NostrEngine {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk1), listOf(relay.url))
        engine.connect()
        assertTrue(awaitTrue { engine.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return engine
    }

    @Test
    fun `a rejected replaceable event is not kept and the previous one is restored`() = runTest {
        SilentWsRelay(rejectEvents = true).use { relay ->
            val engine = connected(relay)
            val old = EventBuilder(Kind(3u), "old").tags(listOf(Tag.parse(listOf("p", Keys.generate().publicKey().toHex()))))
                .customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(1_000u)).signWithKeys(Keys.parse(sk1))
            engine.save(old)
            val result = engine.signAndPublish(EventBuilder(Kind(3u), "new").customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(2_000u)))
            assertFalse(result.sentToAny)
            val stored = engine.query(Filter().kind(Kind(3u)))
            assertEquals(listOf(old.id().toHex()), stored.map { it.id().toHex() })
            engine.close()
        }
    }

    @Test
    fun `a rejected note is not stored`() = runTest {
        SilentWsRelay(rejectEvents = true).use { relay ->
            val engine = connected(relay)
            assertFalse(engine.signAndPublish(EventBuilder.textNote("no")).sentToAny)
            assertTrue(engine.query(Filter().kind(Kind(1u))).isEmpty())
            engine.close()
        }
    }

    @Test
    fun `a publish cancelled mid-send leaves only the previous event in the database`() = runTest {
        SilentWsRelay(answerEose = true).use { relay -> // records every EVENT, never answers one
            val engine = connected(relay)
            val old = EventBuilder(Kind(10050u), "").tags(listOf(Tag.parse(listOf("relay", "wss://old.example"))))
                .customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(1_000u)).signWithKeys(Keys.parse(sk1))
            engine.save(old)
            val publish = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                engine.signAndPublish(EventBuilder(Kind(10050u), "").tags(listOf(Tag.parse(listOf("relay", "wss://new.example")))))
            }
            // the EVENT reached the relay: the pool has stored the new list and waits for an OK that never comes
            assertTrue(awaitTrue { relay.events.isNotEmpty() }, "the event never reached the relay")
            publish.cancelAndJoin()
            val stored = engine.query(Filter().kind(Kind(10050u)))
            assertEquals(listOf(old.id().toHex()), stored.map { it.id().toHex() })
            engine.close()
        }
    }

    @Test
    fun `rust-nostr calls run on the injected ffi dispatcher`() = runTest {
        val dispatches = java.util.concurrent.atomic.AtomicInteger(0)
        val counting = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatches.incrementAndGet()
                kotlinx.coroutines.Dispatchers.IO.dispatch(context, block)
            }
        }
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk1), listOf("wss://a.example"), ffi = counting)
        val before = dispatches.get()
        engine.query(Filter().kind(Kind(1u)))
        assertTrue(dispatches.get() > before, "query did not hop to the ffi dispatcher")
        val beforeUrls = dispatches.get()
        assertEquals(listOf("wss://a.example"), engine.relayUrls())
        assertTrue(dispatches.get() > beforeUrls, "relayUrls did not hop to the ffi dispatcher")
        engine.close()
    }

    @Test
    fun `an accepted event is stored`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val engine = connected(relay)
            assertTrue(engine.signAndPublish(EventBuilder.textNote("yes")).sentToAny)
            assertEquals(1, engine.query(Filter().kind(Kind(1u))).size)
            engine.close()
        }
    }

    @Test
    fun `resend sends the stored event again instead of signing a new one`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk1), emptyList())
        val first = engine.signAndSend(EventBuilder.textNote("once"))
        assertFalse(first.sentToAny)
        val again = engine.resend(first.eventId)
        assertEquals(first.eventId, again.eventId)
        assertEquals(1, again.kind)
        assertEquals(1, engine.query(Filter().kind(Kind(1u))).size)
        val missing = runCatching { engine.resend("f".repeat(64)) }.exceptionOrNull()
        assertTrue(missing is IllegalArgumentException, "got $missing")
        assertEquals("Note nicht in der Datenbank", missing!!.message)
    }

    @Test
    fun `own relay list reads kind 10002 markers and is null without one`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk1), emptyList())
        assertNull(engine.ownRelayList())
        val keys = Keys.parse(sk1)
        val list = EventBuilder(Kind(10002u), "").tags(
            listOf(
                Tag.parse(listOf("r", "wss://both.example/")),
                Tag.parse(listOf("r", "wss://read.example", "read")),
                Tag.parse(listOf("r", "wss://write.example", "write")),
            ),
        ).signWithKeys(keys)
        engine.save(list)
        val relays = engine.ownRelayList()!!
        assertEquals(listOf("wss://both.example", "wss://read.example"), relays.read)
        assertEquals(listOf("wss://both.example", "wss://write.example"), relays.write)
        // sendTo with nothing usable returns an empty result and never throws
        val stored = engine.signAndSend(EventBuilder.textNote("x"))
        val out = engine.sendTo(listOf("garbage"), stored.eventId)
        assertFalse(out.sentToAny)
        assertTrue(out.failedRelays.isEmpty())
    }

    @Test
    fun `temporary relays stay out of relayUrls and a shared url is never detached`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://feed.example"))
        engine.attachTemporary(listOf("wss://search.example", "wss://feed.example", "garbage"))
        assertEquals(listOf("wss://feed.example"), engine.relayUrls())
        assertEquals(listOf("wss://search.example"), engine.temporaryRelayUrls())
        assertTrue(engine.client.relays().keys.map { it.toString().trimEnd('/') }.contains("wss://search.example"))
        engine.detachTemporary(listOf("wss://search.example", "wss://feed.example"))
        assertEquals(listOf("wss://feed.example"), engine.relayUrls())
        assertTrue(engine.temporaryRelayUrls().isEmpty())
        assertTrue(engine.client.relays().keys.map { it.toString().trimEnd('/') }.contains("wss://feed.example"))
    }

    @Test
    fun `temporary relays are reference counted`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        SilentWsRelay().use { r ->
            engine.attachTemporary(listOf(r.url))
            engine.attachTemporary(listOf(r.url))
            engine.detachTemporary(listOf(r.url))
            assertEquals(listOf(r.url), engine.temporaryRelayUrls())
            engine.detachTemporary(listOf(r.url))
            assertTrue(engine.temporaryRelayUrls().isEmpty())
        }
        engine.close()
    }

    @Test
    fun `relayStates lists normal relays only`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://normal.example"))
        SilentWsRelay().use { r ->
            engine.attachTemporary(listOf(r.url))
            val urls = engine.relayStates().first().map { it.url.trimEnd('/') }
            assertEquals(listOf("wss://normal.example"), urls)
        }
        engine.close()
    }

    @Test
    fun `fetchFrom attaches missing relays and a dead relay yields empty or a relay error`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        val result = runCatching {
            engine.fetchFrom(listOf("ws://127.0.0.1:9"), Filter().kind(Kind(1u)).search("x"), java.time.Duration.ofSeconds(1))
        }
        // Dead relay either returns empty or throws a non-cancellation exception
        val out = result.getOrElse { emptyList() }
        assertTrue(out.isEmpty() || result.isFailure)
        if (result.isFailure) {
            val failure = result.exceptionOrNull()!!
            assertTrue(failure !is java.util.concurrent.CancellationException, "Should not throw CancellationException")
        }
        assertEquals(listOf("ws://127.0.0.1:9"), engine.temporaryRelayUrls())
        assertTrue(engine.relayUrls().isEmpty())
        engine.detachTemporary(listOf("ws://127.0.0.1:9"))
        assertTrue(engine.temporaryRelayUrls().isEmpty())
    }

    @Test
    fun `fetch never targets a temporary relay`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        engine.attachTemporary(listOf("ws://127.0.0.1:9"))
        val out = engine.fetch(Filter().kind(Kind(1u)), java.time.Duration.ofMillis(300))
        assertTrue(out.isEmpty())
        assertEquals(listOf("ws://127.0.0.1:9"), engine.temporaryRelayUrls())
    }

    @Test
    fun `a temporary relay promoted via addRelays survives detachTemporary`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        engine.attachTemporary(listOf("wss://x.example"))
        engine.addRelays(listOf("wss://x.example"))
        assertEquals(listOf("wss://x.example"), engine.relayUrls())
        assertTrue(engine.temporaryRelayUrls().isEmpty())
        engine.detachTemporary(listOf("wss://x.example"))
        assertEquals(listOf("wss://x.example"), engine.relayUrls())
        assertTrue(engine.client.relays().keys.map { it.toString().trimEnd('/') }.contains("wss://x.example"))
    }

    @Test
    fun `a promotion that fails to re-add rolls back to temporary instead of losing the relay`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        engine.attachTemporary(listOf("wss://x.example"))
        // Forces client.addRelay to throw ("relay pool is shutdown") so the promotion in
        // addRelays must roll back rather than silently drop the relay from every set.
        engine.client.disconnect()
        engine.client.shutdown()
        engine.addRelays(listOf("wss://x.example"))
        assertEquals(listOf("wss://x.example"), engine.temporaryRelayUrls())
    }

    /** Subscription ids a pool relay currently holds (kept by rust-nostr even while the relay cannot connect). */
    private suspend fun NostrEngine.subscriptionsOn(url: String): Set<String> =
        client.relay(rust.nostr.sdk.RelayUrl.parse(url)).subscriptions().keys

    @Test
    fun `later-added normal relays inherit live subscriptions, temporary relays never hold one`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://first.example"))
        // Like the session does; without a connected pool rust-nostr records no relay subscription at all (offline probe).
        engine.connect()
        engine.attachTemporary(listOf("wss://before.example"))
        val id = engine.subscribe(Filter().kind(Kind(1u)))
        assertTrue(id in engine.subscriptionsOn("wss://first.example"))
        engine.addRelays(listOf("wss://later.example"))
        assertTrue(id in engine.subscriptionsOn("wss://later.example"), "later-added relay has no live subscription")
        engine.attachTemporary(listOf("wss://after.example"))
        assertTrue(engine.subscriptionsOn("wss://before.example").isEmpty())
        assertTrue(engine.subscriptionsOn("wss://after.example").isEmpty())
        // A promoted temporary relay becomes a normal one and inherits the subscription too.
        engine.addRelays(listOf("wss://before.example"))
        assertTrue(id in engine.subscriptionsOn("wss://before.example"), "promoted relay has no live subscription")
        // Once unsubscribed, a relay added afterwards gets nothing.
        engine.unsubscribe(id)
        engine.addRelays(listOf("wss://late2.example"))
        assertTrue(engine.subscriptionsOn("wss://late2.example").isEmpty())
    }

    @Test
    fun `connectedNormalRelayUrls never contains a connected temporary relay`() = runTest {
        SilentWsRelay().use { search ->
            val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("ws://127.0.0.1:9"))
            engine.attachTemporary(listOf(search.url))
            assertTrue(awaitTrue { search.url in engine.connectedRelayUrls() }, "loopback relay never connected")
            val normal = engine.connectedNormalRelayUrls()
            assertFalse(search.url in normal)
            assertTrue(engine.relayUrls().toSet().containsAll(normal))
            engine.close()
        }
    }

    @Test
    fun `subscribeTo targets only the given relays and is not replayed`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://normal.example"))
        SilentWsRelay().use { r ->
            engine.attachTemporary(listOf(r.url))
            val id = engine.subscribeTo(listOf(r.url), Filter().kind(Kind(1059u)))
            assertTrue(id.isNotBlank())
            // a relay added to the normal set later must NOT receive this subscription: `active` stays empty
            assertTrue(engine.activeSubscriptionIds().isEmpty())
            engine.unsubscribe(id)
        }
        engine.close()
    }

    /** An external signer without a remembered permission for anything: every interactive request is counted and declined. */
    private class PromptingSigner : Signer {
        private val local = LocalSigner("0000000000000000000000000000000000000000000000000000000000000001")
        override val pubkey: String = local.pubkey
        val prompts = ArrayList<Int>()
        override suspend fun sign(event: UnsignedEvent): Event { prompts += event.kind; return local.sign(event) }
        override suspend fun signEventSilent(event: UnsignedEvent): Event = throw SilentSignUnavailable()
        override suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String = local.nip44Encrypt(peerPubkey, plaintext)
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = local.nip44Decrypt(peerPubkey, payload)
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
    }

    @Test
    fun `attachTemporary returns exactly the references it took`() = runTest {
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://normal.example"))
        assertEquals(listOf("wss://temp.example"), engine.attachTemporary(listOf("wss://normal.example", "wss://temp.example/", "not a url")))
        // a second holder takes its own reference, and releasing it leaves the first one's
        assertEquals(listOf("wss://temp.example"), engine.attachTemporary(listOf("wss://temp.example")))
        engine.detachTemporary(listOf("wss://temp.example"))
        assertEquals(listOf("wss://temp.example"), engine.temporaryRelayUrls())
        engine.detachTemporary(listOf("wss://temp.example"))
        assertTrue(engine.temporaryRelayUrls().isEmpty())
        engine.close()
    }

    @Test
    fun `the client signer signs NIP-42 AUTH silently and never prompts for it`() = runTest {
        val signer = PromptingSigner()
        val adapter = SignerAdapter(signer)
        val me = rust.nostr.sdk.PublicKey.parse(signer.pubkey)
        val auth = EventBuilder(Kind(22242u), "").tags(listOf(Tag.parse(listOf("relay", "wss://inbox.example")), Tag.parse(listOf("challenge", "c1")))).build(me)
        val thrown = runCatching { adapter.signEvent(auth) }.exceptionOrNull()
        assertTrue(thrown is SilentSignUnavailable, "got $thrown")
        assertTrue(signer.prompts.isEmpty(), "AUTH opened the signer: ${signer.prompts}")

        // anything else still goes through the interactive path
        val note = adapter.signEvent(EventBuilder.textNote("hi").build(me))!!
        assertEquals(1, note.kind().asU16().toInt())
        assertEquals(listOf(1), signer.prompts)

        // a local key signs AUTH as before
        val local = SignerAdapter(LocalSigner(sk1)).signEvent(auth)!!
        assertEquals(22242, local.kind().asU16().toInt())
        assertTrue(local.verify())
    }

    @Test
    fun `sendTo reaches a relay held as a temporary one and keeps it out of normal sends`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val engine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk1), emptyList())
            engine.connect()
            // the DM sync holds its inbox relays this way (read-only, outside the normal set)
            engine.attachTemporary(listOf(relay.url))
            assertTrue(awaitTrue { engine.connectedRelayUrls().isNotEmpty() }, "loopback relay never connected")
            val wrap = EventBuilder.textNote("a stored event").signWithKeys(Keys.parse(sk1))
            engine.save(wrap)

            val result = engine.sendTo(listOf(relay.url), wrap.id().toHex())
            assertTrue(result.sentToAny, "failed: ${result.failedRelays}")
            assertEquals(listOf(relay.url), result.successRelays.map { it.trimEnd('/') })

            // still temporary and still read-only: a pool-wide send (a normal post) never reaches it
            assertEquals(listOf(relay.url), engine.temporaryRelayUrls())
            assertTrue(engine.relayUrls().isEmpty())
            val post = engine.signAndSend(EventBuilder.textNote("a normal post"))
            assertFalse(post.sentToAny)
            engine.close()
        }
    }

    @Test
    fun `silent signer refuses when the silent decrypt has no answer`() = runTest {
        val mute = object : Signer by LocalSigner(sk1) {
            override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        }
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), mute, emptyList())
        val silent = engine.silentNostrSigner!!
        val ex = runCatching { silent.nip44Decrypt(rust.nostr.sdk.PublicKey.parse(mute.pubkey), "payload") }.exceptionOrNull()
        assertTrue(ex != null)
        // the interactive signer still works for the local key
        val plain = engine.nostrSigner!!.nip44Decrypt(rust.nostr.sdk.PublicKey.parse(mute.pubkey), engine.nostrSigner!!.nip44Encrypt(rust.nostr.sdk.PublicKey.parse(mute.pubkey), "hi"))
        assertEquals("hi", plain)
        engine.close()
    }

    @org.junit.jupiter.api.Disabled("network probe, run with MONOSTR_NET=1")
    @Test
    fun `net probe - fetchFrom with read false reaches a live search relay`() = runTest {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("MONOSTR_NET") == "1")
        val engine = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        engine.connect()
        val out = engine.fetchFrom(listOf("wss://search.nos.today"), Filter().kind(Kind(0u)).search("jack").limit(5u), java.time.Duration.ofSeconds(10))
        assertTrue(out.isNotEmpty(), "search relay answered nothing")
    }
}
