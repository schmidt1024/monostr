package com.monostr.nostr.repo

import com.monostr.nostr.ManualGate
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

/** Spec perf 3.7: the own lists come from the summary and the database at once; the relays are asked behind them. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnListsLocalFirstTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())
    private val myPk = me.publicKey().toHex()
    private val a = Keys.generate().publicKey().toHex()
    private val b = Keys.generate().publicKey().toHex()
    private val c = Keys.generate().publicKey().toHex()
    private val d = "abc123def456"

    private object NoRelays : AuthorRelaysRepository {
        override suspend fun writeRelays(pubkey: String) = emptyList<String>()
        override suspend fun attach(relays: List<String>) {}
        override suspend fun detach(relays: List<String>) {}
        override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int): Set<String> = emptySet()
    }

    private class MemoryCache : OwnListsCache {
        @Volatile var stored: List<CachedList>? = null
        override suspend fun load(pubkey: String) = stored
        override suspend fun save(pubkey: String, lists: List<CachedList>) { stored = lists }
        override suspend fun remove(pubkey: String) { stored = null }
    }

    /** Counts every decrypt: Amber answers each one through a ContentResolver call. */
    private class CountingSigner(private val inner: LocalSigner) : Signer by inner {
        var decrypts = 0
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? { decrypts++; return inner.nip44DecryptSilent(peerPubkey, payload) }
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String { decrypts++; return inner.nip44Decrypt(peerPubkey, payload) }
    }

    /** A signer whose silent decrypt waits for [gate] (Amber busy). */
    private class GatedSigner(private val inner: LocalSigner) : Signer by inner {
        val gate = CompletableDeferred<Unit>()
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? { gate.await(); return inner.nip44DecryptSilent(peerPubkey, payload) }
    }

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())

    private suspend fun connected(relay: SilentWsRelay): NostrEngine = connectedEngine(dir, relay, me.secretKey().toHex())

    private val sent = mutableListOf<Event>()
    private fun okSend(e: NostrEngine): suspend (EventBuilder) -> PublishResult = { builder ->
        val ev = builder.signWithKeys(me); e.save(ev); sent += ev
        PublishResult(ev.id().toHex(), ev.kind().asU16().toInt(), listOf("wss://r"), emptyMap())
    }

    private fun repo(e: NostrEngine, cache: OwnListsCache?, s: Signer = signer, send: suspend (EventBuilder) -> PublishResult = okSend(e), reconcile: Boolean = false) =
        NostrListsRepository(e, s, NoRelays, now = { 2000 }, send = send, fetchTimeout = Duration.ofSeconds(2), cache = cache, reconcileScope = if (reconcile) e.backgroundScope else null)

    private fun newSet(members: List<String> = listOf(a)) = FollowSet(ListAddress(30000, myPk, d), "Friends", null, members, true, myPk, 0, false)

    /** A private version of the list as another device wrote it. */
    private suspend fun privateList(members: List<String>, at: ULong): Event = EventBuilder(
        Kind(30000u),
        signer.nip44Encrypt(myPk, PrivateTags.encode(listOf(listOf("title", "Friends")) + members.map { listOf("p", it) })),
    ).tags(listOf(Tag.parse(listOf("d", d)))).customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(me)

    @Test
    fun `an unchanged private list is not decrypted again at the next start`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        assertEquals(SaveResult.Ok, repo(e, cache).save(newSet()))
        assertEquals(sent.single().id().toHex(), cache.stored!!.single().eventId)
        val counting = CountingSigner(signer)
        val next = repo(e, cache, s = counting) // the next app start
        assertEquals(listOf(a), next.localOf(myPk).single().members)
        assertEquals(0, counting.decrypts, "Amber was asked for a list it had answered before")
        e.close()
    }

    @Test
    fun `the summary shows before the signer answers for a changed list`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        repo(e, cache).save(newSet())
        e.save(privateList(listOf(a, b), 3000u)) // changed on another device: not in the summary
        val slow = GatedSigner(signer)
        val r = repo(e, cache, s = slow)
        assertEquals(listOf(a), r.own().first().single().members, "the tab waited for the signer")
        slow.gate.complete(Unit)
        assertEquals(setOf(a, b), r.localOf(myPk).single().members.toSet())
        assertEquals(setOf(a, b), cache.stored!!.single().members.toSet())
        e.close()
    }

    @Test
    fun `a newer version on the relays replaces the shown one behind it and lands in the summary`() = runTest {
        SilentWsRelay(serve = listOf(privateList(listOf(a, b), 3000u).asJson())).use { relay ->
            val e = connected(relay)
            try {
                e.save(privateList(listOf(a), 1000u))
                val cache = MemoryCache()
                val r = repo(e, cache, reconcile = true)
                assertTrue(awaitTrue { r.own().first().single().members.toSet() == setOf(a, b) }, "the relays' version never came")
                assertTrue(awaitTrue { cache.stored?.singleOrNull()?.members?.toSet() == setOf(a, b) })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a list deleted on another device leaves the lists and the summary once the relays say so`() = runTest {
        // Review Focus: the stale summary entry must not keep the list alive
        val replacement = EventBuilder(Kind(30000u), "").tags(listOf(Tag.parse(listOf("d", d)))).customCreatedAt(Timestamp.fromSecs(3000u)).signWithKeys(me)
        SilentWsRelay(serve = listOf(replacement.asJson())).use { relay ->
            val e = connected(relay)
            try {
                val old = privateList(listOf(a), 1000u)
                e.save(old)
                // the summary still holds the list, as the last start wrote it
                val cache = MemoryCache().apply { stored = listOf(CachedList(ListAddress(30000, myPk, d).toString(), old.id().toHex(), "Friends", null, listOf(a), true, 1000)) }
                val r = repo(e, cache, reconcile = true)
                assertTrue(awaitTrue { r.own().first().isEmpty() }, "the deleted list stayed")
                assertTrue(awaitTrue { cache.stored == emptyList<CachedList>() })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a reconcile that meets a member being added does not undo the addition`() = runTest {
        // Review Focus
        val e = engine()
        repo(e, null).save(newSet(members = listOf(a)))
        val gate = CompletableDeferred<Unit>()
        val ok = okSend(e)
        val r = repo(e, MemoryCache(), send = { builder -> gate.await(); ok(builder) })
        val address = r.own().first().single().address
        val adding = async { r.setMember(address, b, present = true) }
        runCurrent()
        val reconciling = async { r.reconcileOwn() }
        // the engine's fetch and query resume from native threads: give the reconcile the time to reach the list's lock
        repeat(50) { Thread.sleep(10); runCurrent() }
        assertEquals(setOf(a, b), r.own().first().single().members.toSet(), "the reconcile undid the addition in flight")
        gate.complete(Unit)
        assertEquals(SaveResult.Ok, adding.await())
        reconciling.await()
        assertEquals(setOf(a, b), r.own().first().single().members.toSet())
        e.close()
    }

    @Test
    fun `a deleted list leaves the summary`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        val r = repo(e, cache)
        r.save(newSet())
        assertEquals(SaveResult.Ok, r.delete(r.own().first().single()))
        assertEquals(emptyList<CachedList>(), cache.stored)
        e.close()
    }

    // R16 for own lists. runBlocking, not runTest: the write's wait is bounded in real time, the relay answers on real threads.

    @Test
    fun `a member added right after start builds on the newer version the relays hold`() = runBlocking {
        SilentWsRelay(serve = listOf(privateList(listOf(a, b), 3000u).asJson())).use { relay ->
            val e = connected(relay)
            try {
                e.save(privateList(listOf(a), 1000u)) // the database still has the old version
                val r = repo(e, MemoryCache(), reconcile = true)
                assertEquals(SaveResult.Ok, r.setMember(ListAddress(30000, myPk, d), c, present = true))
                val published = signer.nip44Decrypt(myPk, sent.last().content())
                assertTrue(b in published, "the change made on the other device was overwritten")
                assertTrue(c in published, "the new member is missing")
                assertEquals(setOf(a, b, c), r.own().first().single().members.toSet())
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `an own-list write without any relay reply goes through within the fetch timeout`() = runBlocking {
        SilentWsRelay().use { relay -> // connected, never answers
            val e = connected(relay)
            try {
                e.save(privateList(listOf(a), 1000u))
                val r = repo(e, MemoryCache(), reconcile = true)
                val started = System.nanoTime()
                assertEquals(SaveResult.Ok, r.setMember(ListAddress(30000, myPk, d), b, present = true))
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(tookMs < 4_000, "the offline write waited $tookMs ms")
                assertEquals(setOf(a, b), r.own().first().single().members.toSet())
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a failed write never leaves its members in the summary under the old event`() = runTest {
        // Review fix round 1: another list's write persists the summary while this one is in flight, then this one fails
        val e = engine()
        val cache = MemoryCache()
        val other = FollowSet(ListAddress(30000, myPk, "zzz999yyy888"), "Other", null, listOf(a), true, myPk, 0, false)
        repo(e, cache).save(newSet(members = listOf(a)))
        val oldId = sent.single().id().toHex()
        repo(e, cache).save(other)
        val gate = CompletableDeferred<Unit>()
        val inFlight = CompletableDeferred<Unit>()
        val ok = okSend(e)
        var calls = 0
        val r = repo(e, cache, send = { builder ->
            if (calls++ == 0) {
                inFlight.complete(Unit)
                gate.await()
                PublishResult("", 30000, emptyList(), mapOf("wss://r" to "down")) // no relay took it
            } else {
                ok(builder)
            }
        })
        val failing = async { r.setMember(ListAddress(30000, myPk, d), b, present = true) }
        // the write reads the database on the engine's thread, so runCurrent() alone may return before it got to the send: the other write could then take the gate
        inFlight.await()
        assertEquals(SaveResult.Ok, r.setMember(other.address, b, present = true))
        gate.complete(Unit)
        assertEquals(SaveResult.Failed, failing.await())
        val entry = cache.stored!!.single { it.address == ListAddress(30000, myPk, d).toString() }
        assertEquals(listOf(a), entry.members, "the summary kept the members no relay took")
        assertEquals(oldId, entry.eventId)
        val counting = CountingSigner(signer)
        val next = repo(e, cache, s = counting) // the next app start
        assertEquals(listOf(a), next.localOf(myPk).single { it.address.d == d }.members, "the decrypt cache served the failed members")
        e.close()
    }

    @Test
    fun `a write does not wait for a reconcile whose scope has ended`() = runBlocking {
        val e = engine()
        repo(e, null).save(newSet())
        val ended = CoroutineScope(Job()).apply { cancel() }
        val r = NostrListsRepository(e, signer, NoRelays, now = { 2000 }, send = okSend(e), fetchTimeout = Duration.ofSeconds(2), cache = MemoryCache(), reconcileScope = ended)
        val started = System.nanoTime()
        assertEquals(SaveResult.Ok, r.setMember(ListAddress(30000, myPk, d), b, present = true))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 1_000, "the write waited $tookMs ms for a reconcile that never ran")
        e.close()
    }

    @Test
    fun `the own lists show at once, the relays are asked after the startup gate, a write opens it`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            try {
                e.save(privateList(listOf(a), 1000u))
                val gate = ManualGate()
                val r = NostrListsRepository(e, signer, NoRelays, now = { 2000 }, send = okSend(e), fetchTimeout = Duration.ofSeconds(2), cache = MemoryCache(), reconcileScope = e.backgroundScope, gate = gate)
                assertEquals(listOf(a), r.own().first().single().members)
                Thread.sleep(300)
                assertTrue(relay.requests.none { "30000" in it }, "asked before the gate: ${relay.requests}")
                assertEquals(SaveResult.Ok, r.save(newSet(listOf(a, b))))
                assertTrue("write" in gate.reasons)
                assertTrue(awaitTrue { relay.requests.any { "30000" in it } })
            } finally {
                e.close()
            }
        }
    }
}
