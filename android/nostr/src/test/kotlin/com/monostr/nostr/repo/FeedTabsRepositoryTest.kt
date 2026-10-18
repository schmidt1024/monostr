package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class FeedTabsRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())
    private val pk = me.publicKey().toHex()
    private fun addr(d: String) = ListAddress(30000, pk, d)
    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())

    private val sent = mutableListOf<Event>()
    private fun okSend(e: NostrEngine): suspend (EventBuilder) -> PublishResult = { b ->
        val ev = b.signWithKeys(me); e.save(ev); sent += ev
        PublishResult(ev.id().toHex(), 30078, listOf("wss://r"), emptyMap())
    }
    private val failSend: suspend (EventBuilder) -> PublishResult = { PublishResult("0".repeat(64), 30078, emptyList(), mapOf("wss://r" to "blocked")) }

    private class MemoryCache : FeedTabsCache {
        val map = HashMap<String, CachedTabs>()
        override suspend fun load(pubkey: String) = map[pubkey]
        override suspend fun save(pubkey: String, tabs: CachedTabs) { map[pubkey] = tabs }
        override suspend fun remove(pubkey: String) { map.remove(pubkey) }
    }

    private fun repo(e: NostrEngine, cache: FeedTabsCache = MemoryCache(), send: suspend (EventBuilder) -> PublishResult = okSend(e)) =
        NostrFeedTabsRepository(e, signer, cache, now = { 2000 }, send = send)

    private suspend fun relayEvent(e: NostrEngine, payload: String, at: Long) = e.save(
        EventBuilder(Kind(30078u), signer.nip44Encrypt(pk, payload))
            .tags(listOf(Tag.parse(listOf("d", "monostr/feed-tabs"))))
            .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(me),
    )

    @Test
    fun `pins keep their order, at most five, and the event is encrypted to self`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        val r = repo(e, cache)
        (1..5).forEach { assertEquals(SaveResult.Ok, r.pin(addr("l$it"))) }
        assertEquals(SaveResult.TooMany, r.pin(addr("l6")))
        assertEquals(SaveResult.Ok, r.pin(addr("l1"))) // already pinned: nothing changes, nothing is sent
        assertEquals((1..5).map { addr("l$it") }, r.tabs().first())
        assertEquals(5, sent.size)
        val last = sent.last()
        assertEquals(listOf(listOf("d", "monostr/feed-tabs")), last.tags().toVec().map { it.asVec() })
        assertFalse(last.content().contains(pk)) // encrypted: not even the own key shows
        val payload = Json.parseToJsonElement(signer.nip44Decrypt(pk, last.content())).jsonObject
        assertEquals(1, payload["v"]!!.jsonPrimitive.int)
        assertEquals((1..5).map { addr("l$it").toString() }, payload["tabs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals((1..5).map { addr("l$it").toString() }, cache.map[pk]!!.tabs)
        e.close()
    }

    @Test
    fun `move and unpin`() = runTest {
        val e = engine()
        val r = repo(e)
        listOf("a", "b", "c").forEach { r.pin(addr(it)) }
        r.move(addr("c"), -1)
        assertEquals(listOf("a", "c", "b"), r.tabs().first().map { it.d })
        r.move(addr("a"), -1) // at the edge: unchanged
        assertEquals(listOf("a", "c", "b"), r.tabs().first().map { it.d })
        r.move(addr("a"), 1)
        assertEquals(listOf("c", "a", "b"), r.tabs().first().map { it.d })
        r.unpin(addr("a"))
        assertEquals(listOf("c", "b"), r.tabs().first().map { it.d })
        e.close()
    }

    @Test
    fun `a failed publish rolls the pin back`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        val r = repo(e, cache, send = failSend)
        assertEquals(SaveResult.Failed, r.pin(addr("a")))
        assertEquals(emptyList<ListAddress>(), r.tabs().first())
        assertNull(cache.map[pk])
        e.close()
    }

    @Test
    fun `the local copy shows first, a newer relay event replaces it, an older one does not`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        cache.map[pk] = CachedTabs(listOf(addr("local").toString()), 1500)
        relayEvent(e, """{"v":1,"tabs":["${addr("old")}"]}""", 1000)
        val r = repo(e, cache)
        assertEquals(listOf(addr("local")), r.tabs().first())
        r.reconcile()
        assertEquals(listOf(addr("local")), r.tabs().first())
        relayEvent(e, """{"v":1,"tabs":["${addr("remote")}","${addr("two")}"]}""", 1800)
        r.reconcile()
        assertEquals(listOf(addr("remote"), addr("two")), r.tabs().first())
        assertEquals(CachedTabs(listOf(addr("remote").toString(), addr("two").toString()), 1800), cache.map[pk])
        e.close()
    }

    @Test
    fun `a relay event with junk entries is cleaned`() = runTest {
        val e = engine()
        val junk = listOf("x", addr("1").toString(), addr("1").toString(), "30000:abc:y") + (2..7).map { addr("$it").toString() }
        relayEvent(e, """{"v":1,"tabs":[${junk.joinToString(",") { "\"$it\"" }}]}""", 1800)
        val r = repo(e)
        r.reconcile()
        assertEquals((1..5).map { addr("$it") }, r.tabs().first())
        e.close()
    }

    @Test
    fun `an unknown version is ignored and never overwritten`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        cache.map[pk] = CachedTabs(listOf(addr("local").toString()), 1500)
        relayEvent(e, """{"v":2,"tabs":[],"groups":[]}""", 1800)
        val r = repo(e, cache)
        r.reconcile()
        assertEquals(listOf(addr("local")), r.tabs().first())
        assertEquals(SaveResult.Ok, r.pin(addr("next")))
        assertEquals(listOf(addr("local"), addr("next")), r.tabs().first())
        assertTrue(sent.isEmpty()) // the newer format on the relays stays
        e.close()
    }

    @Test
    fun `the unknown version guard survives a restart`() = runTest {
        val e = engine()
        val cache = MemoryCache()
        cache.map[pk] = CachedTabs(listOf(addr("local").toString()), 1500)
        relayEvent(e, """{"v":2,"tabs":[]}""", 1800)
        val first = repo(e, cache)
        first.reconcile()
        assertEquals(SaveResult.Ok, first.pin(addr("next"))) // local, cache now newer than the relay event
        assertTrue(cache.map[pk]!!.createdAt > 1800)
        val second = repo(e, cache)
        second.reconcile()
        assertEquals(SaveResult.Ok, second.pin(addr("more")))
        assertEquals(listOf(addr("local"), addr("next"), addr("more")), second.tabs().first())
        assertTrue(sent.isEmpty())
        e.close()
    }

    /** An external signer without a remembered decrypt grant: silent reads get no answer, prompts are counted. */
    private class AmberLike(private val inner: LocalSigner) : Signer by inner {
        var allow = false
        var prompts = 0
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String {
            prompts++
            return if (allow) inner.nip44Decrypt(peerPubkey, payload) else throw SigningRejectedException()
        }
    }

    private fun amberRepo(e: NostrEngine, amber: AmberLike, cache: FeedTabsCache = MemoryCache()) =
        NostrFeedTabsRepository(e, amber, cache, now = { 2000 }, send = okSend(e))

    @Test
    fun `a relay event that cannot be read is never overwritten by a pin`() = runTest {
        val e = engine()
        relayEvent(e, """{"v":1,"tabs":["${addr("remote")}"]}""", 1800)
        val amber = AmberLike(signer)
        val r = amberRepo(e, amber)
        r.reconcile()
        assertEquals(0, amber.prompts) // the start reconcile never asks
        assertEquals(SaveResult.Ok, r.pin(addr("a")))
        assertEquals(1, amber.prompts) // the first change asks once
        assertEquals(SaveResult.Ok, r.pin(addr("b")))
        assertEquals(1, amber.prompts) // and only once
        assertEquals(listOf(addr("a"), addr("b")), r.tabs().first())
        assertTrue(sent.isEmpty())
        e.close()
    }

    @Test
    fun `the first change reads an unread relay event interactively and builds on it`() = runTest {
        val e = engine()
        relayEvent(e, """{"v":1,"tabs":["${addr("remote")}"]}""", 1800)
        val amber = AmberLike(signer).apply { allow = true }
        val r = amberRepo(e, amber)
        r.reconcile()
        assertEquals(emptyList<ListAddress>(), r.tabs().first())
        assertEquals(SaveResult.Ok, r.pin(addr("a")))
        assertEquals(listOf(addr("remote"), addr("a")), r.tabs().first())
        val payload = Json.parseToJsonElement(signer.nip44Decrypt(pk, sent.single().content())).jsonObject
        assertEquals(listOf(addr("remote").toString(), addr("a").toString()), payload["tabs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(sent.single().createdAt().asSecs().toLong() > 1800)
        e.close()
    }

    @Test
    fun `an unread relay event read at the first change in an unknown version keeps changes local`() = runTest {
        val e = engine()
        relayEvent(e, """{"v":2,"tabs":[]}""", 1800)
        val amber = AmberLike(signer).apply { allow = true }
        val r = amberRepo(e, amber)
        r.reconcile()
        assertEquals(SaveResult.Ok, r.pin(addr("a")))
        assertEquals(listOf(addr("a")), r.tabs().first())
        assertTrue(sent.isEmpty())
        e.close()
    }

    @Test
    fun `a pin before the start reconcile finished waits for it`() = runTest {
        val e = engine()
        relayEvent(e, """{"v":2,"tabs":[]}""", 1800)
        val r = NostrFeedTabsRepository(e, signer, MemoryCache(), reconcileScope = this, now = { 2000 }, send = okSend(e))
        r.tabs().first() // starts the reconcile, which has not run yet
        assertEquals(SaveResult.Ok, r.pin(addr("a")))
        assertTrue(sent.isEmpty())
        e.close()
    }
}
