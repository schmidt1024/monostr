package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListsRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())
    private val myPk = me.publicKey().toHex()
    private val alice = Keys.generate()
    private val a = Keys.generate().publicKey().toHex()
    private val b = Keys.generate().publicKey().toHex()
    private val c = Keys.generate().publicKey().toHex()

    private object NoAuthorRelays : AuthorRelaysRepository {
        override suspend fun writeRelays(pubkey: String) = emptyList<String>()
        override suspend fun attach(relays: List<String>) {}
        override suspend fun detach(relays: List<String>) {}
        override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int): Set<String> = emptySet()
    }

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())

    /** Every event "published", in order. */
    private val sent = mutableListOf<Event>()

    /** Publishing "succeeds": signed with the test key, stored, one relay reported. */
    private fun okSend(e: NostrEngine): suspend (EventBuilder) -> PublishResult = { builder ->
        val ev = builder.signWithKeys(me); e.save(ev); sent += ev
        PublishResult(ev.id().toHex(), ev.kind().asU16().toInt(), listOf("wss://r"), emptyMap())
    }
    private val failSend: suspend (EventBuilder) -> PublishResult = { PublishResult("0".repeat(64), 30000, emptyList(), mapOf("wss://r" to "blocked")) }

    private fun repo(e: NostrEngine, send: suspend (EventBuilder) -> PublishResult = okSend(e), s: Signer = signer) =
        NostrListsRepository(e, s, NoAuthorRelays, now = { 2000 }, send = send)

    private fun tags(ev: Event) = ev.tags().toVec().map { it.asVec() }
    private suspend fun inner(ev: Event) = PrivateTags.parse(signer.nip44Decrypt(myPk, ev.content()))
    private fun newSet(d: String = "abc123def456", members: List<String> = listOf(a), private: Boolean = true) =
        FollowSet(ListAddress(30000, myPk, d), "Friends", "close ones", members, private, myPk, 0, false)

    @Test
    fun `a new private list keeps only d in clear and encrypts title, description and members`() = runTest {
        val e = engine()
        val r = repo(e)
        assertEquals(SaveResult.Ok, r.save(newSet()))
        val ev = sent.single()
        assertEquals(30000, ev.kind().asU16().toInt())
        assertEquals(listOf(listOf("d", "abc123def456")), tags(ev))
        assertEquals(listOf(listOf("title", "Friends"), listOf("description", "close ones"), listOf("p", a)), inner(ev))
        val own = r.own().first().single()
        assertEquals("Friends", own.title); assertEquals(listOf(a), own.members); assertTrue(own.private); assertFalse(own.locked)
        e.close()
    }

    @Test
    fun `a public list has title, description and members in clear and empty content`() = runTest {
        val e = engine()
        repo(e).save(newSet(private = false))
        val ev = sent.single()
        assertEquals(listOf(listOf("d", "abc123def456"), listOf("title", "Friends"), listOf("description", "close ones"), listOf("p", a)), tags(ev))
        assertEquals("", ev.content())
        e.close()
    }

    @Test
    fun `switching private to public re-encodes the same d, strictly newer`() = runTest {
        val e = engine()
        val r = repo(e)
        r.save(newSet())
        r.save(r.own().first().single().copy(private = false))
        assertEquals(2, sent.size)
        assertEquals(listOf("d", "abc123def456"), tags(sent[1]).first())
        assertTrue(listOf("p", a) in tags(sent[1]))
        assertTrue(sent[1].createdAt().asSecs() > sent[0].createdAt().asSecs()) // same now(): still newer
        val own = r.own().first()
        assertEquals(1, own.size); assertFalse(own.single().private)
        e.close()
    }

    @Test
    fun `a foreign mixed list shows its public part only`() = runTest {
        val e = engine()
        val aliceSigner = LocalSigner(alice.secretKey().toHex())
        val secret = aliceSigner.nip44Encrypt(aliceSigner.pubkey, PrivateTags.encode(listOf(listOf("p", c))))
        e.save(EventBuilder(Kind(30000u), secret).tags(listOf(Tag.parse(listOf("d", "x")), Tag.parse(listOf("title", "Picks")), Tag.parse(listOf("p", b)))).signWithKeys(alice))
        val l = repo(e).of(alice.publicKey().toHex()).single()
        assertEquals("Picks", l.title); assertEquals(listOf(b), l.members); assertFalse(l.private); assertFalse(l.locked)
        assertEquals(l, repo(e).byAddress(l.address))
        e.close()
    }

    @Test
    fun `a foreign d with colons and spaces round-trips`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(30000u), "").tags(listOf(Tag.parse(listOf("d", "x:y z")), Tag.parse(listOf("title", "Odd")), Tag.parse(listOf("p", b)))).signWithKeys(alice))
        val address = ListAddress.parse("30000:${alice.publicKey().toHex()}:x:y z")!!
        assertEquals("Odd", repo(e).byAddress(address)?.title)
        e.close()
    }

    @Test
    fun `a foreign list with nothing readable is hidden`() = runTest {
        val e = engine()
        val aliceSigner = LocalSigner(alice.secretKey().toHex())
        val secret = aliceSigner.nip44Encrypt(aliceSigner.pubkey, PrivateTags.encode(listOf(listOf("title", "Secret"), listOf("p", c))))
        e.save(EventBuilder(Kind(30000u), secret).tags(listOf(Tag.parse(listOf("d", "s")))).signWithKeys(alice))
        assertEquals(emptyList<FollowSet>(), repo(e).of(alice.publicKey().toHex()))
        e.close()
    }

    @Test
    fun `an own mixed list reads both parts, keeps foreign tags and is saved all private`() = runTest {
        val e = engine()
        val secret = signer.nip44Encrypt(myPk, PrivateTags.encode(listOf(listOf("p", c))))
        e.save(
            EventBuilder(Kind(30000u), secret)
                .tags(listOf(Tag.parse(listOf("d", "mixed")), Tag.parse(listOf("title", "Mixed")), Tag.parse(listOf("p", b)), Tag.parse(listOf("image", "https://x/y.png"))))
                .customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(me),
        )
        val r = repo(e)
        val l = r.own().first().single()
        assertEquals(setOf(b, c), l.members.toSet()); assertTrue(l.private)
        assertEquals(SaveResult.Ok, r.setMember(l.address, a, present = true))
        val ev = sent.single()
        assertEquals(listOf(listOf("d", "mixed")), tags(ev))
        val items = inner(ev)
        assertTrue(listOf("image", "https://x/y.png") in items)
        assertEquals(setOf(a, b, c), items.filter { it[0] == "p" }.map { it[1] }.toSet())
        e.close()
    }

    @Test
    fun `mute lists and empty replacements are not lists`() = runTest {
        val e = engine()
        listOf("mute", "muted").forEach { d ->
            e.save(EventBuilder(Kind(30000u), "").tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("p", a)))).signWithKeys(me))
        }
        e.save(EventBuilder(Kind(30000u), "").tags(listOf(Tag.parse(listOf("d", "gone")))).signWithKeys(me))
        assertEquals(emptyList<FollowSet>(), repo(e).own().first())
        e.close()
    }

    @Test
    fun `delete sends kind 5 for the address, then a newer empty replacement`() = runTest {
        val e = engine()
        val r = repo(e)
        val set = newSet()
        r.save(set)
        assertEquals(SaveResult.Ok, r.delete(r.own().first().single()))
        val deletion = sent[1]
        val replacement = sent[2]
        assertEquals(5, deletion.kind().asU16().toInt())
        assertEquals(listOf(listOf("a", set.address.toString()), listOf("k", "30000")), tags(deletion))
        assertEquals(30000, replacement.kind().asU16().toInt())
        assertEquals(listOf(listOf("d", set.address.d)), tags(replacement))
        assertEquals("", replacement.content())
        assertTrue(replacement.createdAt().asSecs() > deletion.createdAt().asSecs())
        assertEquals(emptyList<FollowSet>(), r.own().first())
        assertNull(r.byAddress(set.address))
        e.close()
    }

    @Test
    fun `a failed publish rolls the change back`() = runTest {
        val e = engine()
        repo(e).save(newSet())
        val failing = repo(e, send = failSend)
        val l = failing.own().first().single()
        assertEquals(SaveResult.Failed, failing.setMember(l.address, b, present = true))
        assertEquals(listOf(a), failing.own().first().single().members)
        assertEquals(SaveResult.Failed, failing.save(newSet(d = "other")))
        assertEquals(listOf(l.address), failing.own().first().map { it.address })
        assertEquals(SaveResult.Failed, failing.delete(l))
        assertEquals(listOf(l.address), failing.own().first().map { it.address })
        e.close()
    }

    @Test
    fun `rapid ticks on one list are serialised, each builds on the last accepted state`() = runTest {
        val e = engine()
        repo(e).save(newSet(members = emptyList()))
        sent.clear()
        val gate = CompletableDeferred<Unit>()
        val ok = okSend(e)
        val r = repo(e, send = { builder -> if (sent.isEmpty()) gate.await(); ok(builder) })
        val address = r.own().first().single().address
        val first = async { r.setMember(address, a, true) }
        val second = async { r.setMember(address, b, true) }
        runCurrent()
        assertTrue(sent.isEmpty()) // the first waits for its relay, the second for the first
        gate.complete(Unit)
        assertEquals(SaveResult.Ok, first.await())
        assertEquals(SaveResult.Ok, second.await())
        assertEquals(2, sent.size)
        assertEquals(listOf(a, b), inner(sent.last()).filter { it[0] == "p" }.map { it[1] })
        e.close()
    }

    @Test
    fun `a tick builds on the last accepted state, not on a failed one in flight`() = runTest {
        val e = engine()
        repo(e).save(newSet(members = emptyList()))
        sent.clear()
        val gate = CompletableDeferred<Unit>()
        val ok = okSend(e)
        var calls = 0
        val r = repo(e, send = { builder ->
            if (calls++ == 0) { gate.await(); failSend(builder) } else ok(builder)
        })
        val address = r.own().first().single().address
        val first = async { r.setMember(address, a, true) }
        val second = async { r.setMember(address, b, true) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(SaveResult.Failed, first.await())
        assertEquals(SaveResult.Ok, second.await())
        assertEquals(listOf(b), inner(sent.single()).filter { it[0] == "p" }.map { it[1] })
        assertEquals(listOf(b), r.own().first().single().members)
        e.close()
    }

    /** An own list made on a device whose clock is ahead: stored newer than this device's now(). */
    private suspend fun futureList(e: NostrEngine, d: String, at: Long) = e.save(
        EventBuilder(Kind(30000u), "").tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "Ahead")), Tag.parse(listOf("p", a))))
            .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(me),
    )

    @Test
    fun `a write on a list learned by address is newer than its stored version`() = runTest {
        val e = engine()
        val r = repo(e)
        assertEquals(emptyList<FollowSet>(), r.own().first()) // loaded before the list exists
        futureList(e, "ahead", 5000)
        val l = r.byAddress(ListAddress(30000, myPk, "ahead"))!!
        assertEquals(SaveResult.Ok, r.setMember(l.address, b, true))
        assertTrue(sent.single().createdAt().asSecs().toLong() > 5000)
        assertEquals(SaveResult.Ok, r.save(r.own().first().single().copy(title = "Renamed")))
        assertTrue(sent.last().createdAt().asSecs() > sent.first().createdAt().asSecs())
        e.close()
    }

    @Test
    fun `a delete is newer than the version it replaces`() = runTest {
        val e = engine()
        val r = repo(e)
        r.own().first()
        futureList(e, "ahead", 5000)
        val l = r.byAddress(ListAddress(30000, myPk, "ahead"))!!
        assertEquals(SaveResult.Ok, r.delete(l))
        val (deletion, replacement) = sent
        assertTrue(deletion.createdAt().asSecs().toLong() > 5000)
        assertTrue(replacement.createdAt().asSecs() > deletion.createdAt().asSecs())
        e.close()
    }

    @Test
    fun `a lookup while the list is being deleted does not bring it back`() = runTest {
        val e = engine()
        repo(e).save(newSet())
        sent.clear()
        val gate = CompletableDeferred<Unit>()
        val ok = okSend(e)
        val r = repo(e, send = { builder -> if (sent.isEmpty()) gate.await(); ok(builder) })
        val l = r.own().first().single()
        val deleting = async { r.delete(l) }
        runCurrent() // the list is gone from own(), the kind 5 waits for its relay
        val lookup = async { r.byAddress(l.address, fetch = true) }
        // the engine's fetch and query resume from native threads: give an unguarded lookup the time to finish first
        repeat(100) { if (!lookup.isCompleted) { Thread.sleep(10); runCurrent() } }
        gate.complete(Unit)
        assertEquals(SaveResult.Ok, deleting.await())
        assertNull(lookup.await())
        assertEquals(emptyList<FollowSet>(), r.own().first())
        assertEquals(SaveResult.Failed, r.setMember(l.address, b, true))
        e.close()
    }

    @Test
    fun `edit changes title, description and private on the current members`() = runTest {
        val e = engine()
        val r = repo(e)
        r.save(newSet(members = listOf(a)))
        val l = r.own().first().single()
        r.setMember(l.address, b, true) // added after the edit screen opened
        assertEquals(SaveResult.Ok, r.edit(l.address, "Renamed", null, private = false))
        val own = r.own().first().single()
        assertEquals("Renamed", own.title); assertNull(own.description); assertFalse(own.private)
        assertEquals(listOf(a, b), own.members)
        assertEquals(listOf(listOf("d", l.address.d), listOf("title", "Renamed"), listOf("p", a), listOf("p", b)), tags(sent.last()))
        assertEquals(SaveResult.Failed, r.edit(ListAddress(30000, myPk, "nope"), "x", null, private = true))
        e.close()
    }

    /** An external signer without a remembered permission; the prompt is refused until [allow] is set. */
    private class AmberLike(private val inner: LocalSigner) : Signer by inner {
        var allow = false
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String =
            if (allow) inner.nip44Decrypt(peerPubkey, payload) else throw SigningRejectedException()
    }

    @Test
    fun `an own private list that cannot be decrypted is locked, never written, and unlock retries`() = runTest {
        val e = engine()
        repo(e).save(newSet())
        sent.clear()
        val amber = AmberLike(signer)
        val r = repo(e, s = amber)
        val l = r.own().first().single()
        assertTrue(l.locked); assertTrue(l.private); assertEquals("", l.title); assertEquals(emptyList<String>(), l.members)
        assertEquals(SaveResult.Locked, r.setMember(l.address, b, true))
        assertEquals(SaveResult.Locked, r.save(l.copy(title = "x")))
        assertTrue(sent.isEmpty())
        assertFalse(r.unlock(l.address))
        amber.allow = true
        assertTrue(r.unlock(l.address))
        val open = r.own().first().single()
        assertFalse(open.locked); assertEquals("Friends", open.title); assertEquals(listOf(a), open.members)
        e.close()
    }

    @Test
    fun `an unknown foreign list is null`() = runTest {
        val e = engine()
        assertNull(repo(e).byAddress(ListAddress(30000, alice.publicKey().toHex(), "nope")))
        e.close()
    }

    @Test
    fun `new ids are 12 characters a-z0-9`() = runTest {
        val e = engine()
        val r = repo(e)
        repeat(20) { assertTrue(r.newId().matches(Regex("[a-z0-9]{12}"))) }
        e.close()
    }
}
