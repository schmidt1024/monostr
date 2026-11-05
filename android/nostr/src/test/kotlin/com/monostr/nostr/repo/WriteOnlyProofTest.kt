package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec 2026-11-05 goal 5, ruling of the final review: with a write-only relay a read-before-write of the user's own data
 * needs that relay to have answered, or the write is refused. v2 sits only on the write-only relay A, which is silent at
 * first; the read relay B and the database hold v1. Answering, A gives v2 and the write builds on it.
 */
class WriteOnlyProofTest {
    @TempDir lateinit var dir: Path

    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val pk = me.publicKey().toHex()
    private val signer = LocalSigner(secret)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val timeout: Duration = Duration.ofSeconds(1)

    private fun event(kind: Int, tags: List<List<String>> = emptyList(), content: String = "", at: ULong = 1_000u): Event =
        EventBuilder(Kind(kind.toUShort()), content).tags(tags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(me)

    /** [v1] in the database and on B; [v2] on A, which starts silent. */
    private suspend fun scene(v1: Event, v2: Event, block: suspend (NostrEngine, SilentWsRelay, SilentWsRelay) -> Unit) {
        SilentWsRelay(serve = listOf(v2.asJson()), acceptEvents = true).use { a ->
            a.muted = true
            SilentWsRelay(serve = listOf(v1.asJson()), acceptEvents = true).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                e.save(v1)
                try {
                    withContext(Dispatchers.Default) { block(e, a, b) }
                } finally {
                    e.close()
                }
            }
        }
    }

    private fun counted(e: NostrEngine, sent: AtomicInteger): suspend (EventBuilder) -> PublishResult = { builder ->
        sent.incrementAndGet()
        val ev = builder.signWithKeys(me)
        e.save(ev)
        PublishResult(ev.id().toHex(), ev.kind().asU16().toInt(), listOf("wss://r"), emptyMap())
    }

    private suspend fun newest(e: NostrEngine, kind: Int) = e.query(Filter().kind(Kind(kind.toUShort())).author(me.publicKey())).maxBy { it.createdAt().asSecs() }

    private fun Event.pTags() = tags().toVec().map { it.asVec() }.filter { it[0] == "p" }.map { it[1] }

    private suspend fun failure(block: suspend () -> Unit): Throwable? = try { block(); null } catch (e: Exception) { e }

    @Test
    fun `a follow is refused while the write-only relay is silent and builds on its list once it answers`() = runTest {
        val v1 = event(3, listOf(listOf("p", alice)), at = 1_000u)
        val v2 = event(3, listOf(listOf("p", alice), listOf("p", bob)), at = 2_000u)
        scene(v1, v2) { e, a, _ ->
            val sent = AtomicInteger()
            val repo = NostrFollowRepository(e, NostrFeedRepository(e), now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
            assertTrue(failure { repo.follow(carol) } is FollowError.NoList, "refused")
            assertEquals(0, sent.get())
            assertEquals(v1.id().toHex(), newest(e, 3).id().toHex())
            assertTrue(a.requests.isNotEmpty(), "asked")
            a.muted = false
            repo.follow(carol)
            assertEquals(1, sent.get())
            assertEquals(listOf(alice, bob, carol), newest(e, 3).pTags())
        }
    }

    @Test
    fun `a recent contacts stamp does not replace the answer of the write-only relay`() = runTest {
        val v1 = event(3, listOf(listOf("p", alice)), at = 1_000u)
        scene(v1, event(3, listOf(listOf("p", bob)), at = 2_000u)) { e, _, _ ->
            val sent = AtomicInteger()
            val feed = NostrFeedRepository(e).also { it.markContactsFetched(3_000) }
            val repo = NostrFollowRepository(e, feed, now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
            assertTrue(failure { repo.unfollow(alice) } is FollowError.NoList)
            assertEquals(0, sent.get())
        }
    }

    @Test
    fun `one of two write-only relays silent refuses the follow`() = runTest {
        val v1 = event(3, listOf(listOf("p", alice)), at = 1_000u)
        SilentWsRelay(recordRequests = true).use { silent ->
            SilentWsRelay(answerEose = true).use { answering ->
                SilentWsRelay(serve = listOf(v1.asJson())).use { b ->
                    val e = connectedEngine(dir, listOf(silent, answering, b))
                    e.setRelayModes(mapOf(silent.url to RelayMode.WRITE, answering.url to RelayMode.WRITE, b.url to RelayMode.READ))
                    try {
                        val sent = AtomicInteger()
                        val repo = NostrFollowRepository(e, NostrFeedRepository(e), now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
                        assertTrue(withContext(Dispatchers.Default) { failure { repo.follow(carol) } } is FollowError.NoList)
                        assertEquals(0, sent.get())
                    } finally { e.close() }
                }
            }
        }
    }

    @Test
    fun `a write-only relay that is down refuses the follow`() = runTest {
        val v1 = event(3, listOf(listOf("p", alice)), at = 1_000u)
        SilentWsRelay(answerEose = true).use { a ->
            SilentWsRelay(serve = listOf(v1.asJson())).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    a.shutdown()
                    assertTrue(awaitTrue(15_000) { e.connectedNormalRelayUrls() == setOf(b.url) }, "only B connected")
                    val sent = AtomicInteger()
                    val repo = NostrFollowRepository(e, NostrFeedRepository(e), now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
                    assertTrue(withContext(Dispatchers.Default) { failure { repo.follow(carol) } } is FollowError.NoList)
                    assertEquals(0, sent.get())
                } finally { e.close() }
            }
        }
    }

    @Test
    fun `a write-only relay that refuses until AUTH counts once it served the request after AUTH`() = runTest {
        val filter = Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u)
        SilentWsRelay(answerEose = true, authChallenge = "c", refuseUntilAuth = "auth-required: paid relay", authOkDelayMs = 1_500).use { a ->
            SilentWsRelay(answerEose = true).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    assertTrue(withContext(Dispatchers.Default) { e.askWriteOnly(filter, Duration.ofSeconds(3)).answered })
                } finally { e.close() }
            }
        }
    }

    @Test
    fun `a write-only relay that refuses the request has not answered`() = runTest {
        val filter = Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u)
        SilentWsRelay(closeWith = "restricted: writes only").use { a ->
            SilentWsRelay(answerEose = true).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    assertFalse(withContext(Dispatchers.Default) { e.askWriteOnly(filter, Duration.ofSeconds(2)).answered })
                } finally { e.close() }
            }
        }
    }

    @Test
    fun `a private list write is refused while the write-only relay is silent and builds on its list once it answers`() = runTest {
        val v1 = event(10003, listOf(listOf("e", alice)), at = 1_000u)
        val v2 = event(10003, listOf(listOf("e", bob)), at = 2_000u)
        scene(v1, v2) { e, a, _ ->
            val sent = AtomicInteger()
            val list = NostrPrivateList(e, signer, kind = 10003, tag = "e", now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout, refreshTimeout = timeout, writeDelay = Duration.ZERO)
            list.ensureLoaded(interactive = false)
            assertEquals(listOf(alice), list.state.value.ids)
            assertEquals(ListOutcome.NotLoaded, list.toggle(carol))
            assertEquals(0, sent.get())
            assertEquals(listOf(alice), list.state.value.ids, "the tap is taken back")
            assertTrue(list.state.value.writable && list.state.value.loaded, "the list stays usable")
            a.muted = false
            assertEquals(ListOutcome.Ok, list.toggle(carol))
            assertEquals(1, sent.get())
            assertEquals(setOf(bob, carol), list.state.value.ids.toSet())
            assertEquals(3_000L, newest(e, 10003).createdAt().asSecs().toLong())
            assertEquals(listOf(listOf("e", bob)), newest(e, 10003).tags().toVec().map { it.asVec() }.filter { it[0] == "e" })
        }
    }

    @Test
    fun `a list first read from the read relay is not written while the write-only relay is silent`() = runTest {
        val v1 = event(10003, listOf(listOf("e", alice)), at = 1_000u)
        SilentWsRelay(recordRequests = true).use { a ->
            SilentWsRelay(serve = listOf(v1.asJson())).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    val sent = AtomicInteger()
                    val list = NostrPrivateList(e, signer, kind = 10003, tag = "e", now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout, refreshTimeout = timeout, writeDelay = Duration.ZERO)
                    assertEquals(ListOutcome.NotLoaded, withContext(Dispatchers.Default) { list.toggle(carol) })
                    assertEquals(0, sent.get())
                } finally { e.close() }
            }
        }
    }

    private suspend fun tabsEvent(tabs: List<String>, at: ULong): Event =
        EventBuilder(Kind(30078u), signer.nip44Encrypt(pk, TabsPayload.encode(tabs)))
            .tags(listOf(Tag.parse(listOf("d", NostrFeedTabsRepository.D_TAG)))).customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(me)

    @Test
    fun `a tab write is refused while the write-only relay is silent and builds on its tabs once it answers`() = runTest {
        val tabV1 = ListAddress(30000, alice, "one").toString()
        val tabV2 = ListAddress(30000, alice, "two").toString()
        val added = ListAddress(30000, alice, "three")
        val v1 = tabsEvent(listOf(tabV1), 1_000u)
        scene(v1, tabsEvent(listOf(tabV2), 2_000u)) { e, a, _ ->
            val sent = AtomicInteger()
            val cache = object : FeedTabsCache {
                override suspend fun load(pubkey: String) = CachedTabs(listOf(tabV1), 1_000)
                override suspend fun save(pubkey: String, tabs: CachedTabs) {}
                override suspend fun remove(pubkey: String) {}
            }
            val tabs = NostrFeedTabsRepository(e, signer, cache, now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
            assertEquals(SaveResult.Failed, tabs.pin(added))
            assertEquals(0, sent.get())
            a.muted = false
            assertEquals(SaveResult.Ok, tabs.pin(added))
            assertEquals(1, sent.get())
            val payload = signer.nip44Decrypt(pk, newest(e, 30078).content())
            assertTrue(tabV2 in payload && added.toString() in payload, payload)
            assertFalse(tabV1 in payload, payload)
        }
    }

    private val noRelays = object : AuthorRelaysRepository {
        override suspend fun writeRelays(pubkey: String) = emptyList<String>()
        override suspend fun attach(relays: List<String>) {}
        override suspend fun detach(relays: List<String>) {}
        override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int) = emptySet<String>()
    }

    private fun followSet(members: List<String>, at: ULong) = event(30000, listOf(listOf("d", "l"), listOf("title", "list")) + members.map { listOf("p", it) }, at = at)

    @Test
    fun `an own list write is refused while the write-only relay is silent and builds on its list once it answers`() = runTest {
        scene(followSet(listOf(alice), 1_000u), followSet(listOf(bob), 2_000u)) { e, a, _ ->
            val sent = AtomicInteger()
            val lists = NostrListsRepository(e, signer, noRelays, now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
            val address = ListAddress(30000, pk, "l")
            assertEquals(SaveResult.Failed, lists.setMember(address, carol, present = true))
            assertEquals(SaveResult.Failed, lists.edit(address, "new title", null, private = false))
            assertEquals(0, sent.get())
            assertEquals(listOf(alice), lists.localOf(pk).single().members)
            a.muted = false
            assertEquals(SaveResult.Ok, lists.setMember(address, carol, present = true))
            assertEquals(1, sent.get())
            assertEquals(setOf(bob, carol), lists.localOf(pk).single().members.toSet())
            assertEquals(setOf(bob, carol), newest(e, 30000).pTags().toSet())
        }
    }

    @Test
    fun `an own list is not deleted while the write-only relay is silent`() = runTest {
        scene(followSet(listOf(alice), 1_000u), followSet(listOf(bob), 2_000u)) { e, _, _ ->
            val sent = AtomicInteger()
            val lists = NostrListsRepository(e, signer, noRelays, now = { 3_000 }, send = counted(e, sent), fetchTimeout = timeout)
            val set = lists.localOf(pk).single()
            assertEquals(SaveResult.Failed, lists.delete(set))
            assertEquals(0, sent.get())
        }
    }

    @Test
    fun `the profile base is refused while the write-only relay is silent and is its version once it answers`() = runTest {
        val v1 = event(0, content = """{"name":"v1"}""", at = 1_000u)
        scene(v1, event(0, content = """{"name":"v2"}""", at = 2_000u)) { e, a, _ ->
            val profiles = NostrProfileRepository(e)
            assertTrue(failure { profiles.freshMetadata(pk, timeout) } is OwnDataNotAnswered)
            a.muted = false
            assertEquals("""{"name":"v2"}""", profiles.freshMetadata(pk, Duration.ofSeconds(3)))
        }
    }

    @Test
    fun `another account's profile read does not depend on the write-only relay`() = runTest {
        SilentWsRelay(recordRequests = true).use { a ->
            SilentWsRelay(answerEose = true).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    assertNull(NostrProfileRepository(e).freshMetadata(alice, timeout))
                    assertEquals(emptyList<String>(), a.requests.toList())
                } finally { e.close() }
            }
        }
    }

    private val sub = "4" + "A".repeat(94)

    private fun payto(address: String, at: ULong) = event(10133, listOf(listOf("payto", "monero", address)), at = at)

    @Test
    fun `the payto write is refused while the write-only relay is silent`() = runTest {
        scene(payto("old", 1_000u), payto("newer", 2_000u)) { e, a, _ ->
            val repo = NostrTipsRepository(e, fetchTimeout = timeout) { 3_000 }
            assertEquals(PaytoWrite.Unknown, repo.setOwnPayto(sub))
            assertEquals(emptyList<String>(), a.events.toList())
            a.muted = false
            assertEquals(PaytoWrite.Published, repo.setOwnPayto(sub))
            assertTrue(awaitTrue { a.events.isNotEmpty() })
        }
    }

    @Test
    fun `own DM relays are not none while a write-only relay is down`() = runTest {
        SilentWsRelay(answerEose = true).use { a ->
            SilentWsRelay(answerEose = true).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    a.shutdown()
                    assertTrue(awaitTrue(15_000) { e.connectedNormalRelayUrls() == setOf(b.url) }, "only B connected")
                    val authors = NostrAuthorRelaysRepository(e, indexers = emptyList())
                    val repo = NostrDmRelaysRepository(e, authors, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2))
                    assertEquals(OwnDmRelays.Unknown, withContext(Dispatchers.Default) { repo.ownDmRelays() })
                } finally { e.close() }
            }
        }
    }

    @Test
    fun `without a write-only relay a write asks nothing extra`() = runTest {
        SilentWsRelay(answerEose = true, acceptEvents = true).use { r ->
            val e = connectedEngine(dir, r)
            try {
                val sent = AtomicInteger()
                val list = NostrPrivateList(e, signer, kind = 10003, tag = "e", now = { 3_000 }, send = counted(e, sent), writeDelay = Duration.ZERO)
                e.save(event(10003, listOf(listOf("e", alice))))
                list.ensureLoaded(interactive = false)
                assertTrue(awaitTrue { r.requests.isNotEmpty() }, "the refresh behind the load")
                Thread.sleep(300)
                r.requests.clear()
                assertEquals(ListOutcome.Ok, list.toggle(carol))
                assertEquals(1, r.requests.size, "the refresh before the write: ${r.requests}")
                r.requests.clear()

                val tabs = NostrFeedTabsRepository(e, signer, object : FeedTabsCache {
                    override suspend fun load(pubkey: String): CachedTabs? = null
                    override suspend fun save(pubkey: String, tabs: CachedTabs) {}
                    override suspend fun remove(pubkey: String) {}
                }, send = counted(e, sent))
                assertEquals(SaveResult.Ok, tabs.pin(ListAddress(30000, alice, "x")))
                assertEquals(emptyList<String>(), r.requests.toList(), "a pin reads nothing")

                val lists = NostrListsRepository(e, signer, noRelays, send = counted(e, sent))
                e.save(followSet(listOf(alice), 1_000u))
                assertEquals(SaveResult.Ok, lists.setMember(ListAddress(30000, pk, "l"), carol, present = true))
                assertEquals(emptyList<String>(), r.requests.toList(), "a list write without a reconcile reads nothing")
            } finally { e.close() }
        }
    }

    // M5: a connected write-only relay is not asked for other people's data, so it proves nothing about that

    /** A = write only and up, B = read only and gone. */
    private suspend fun readRelaysDown(block: suspend (NostrEngine) -> Unit) {
        SilentWsRelay(answerEose = true).use { a ->
            SilentWsRelay(answerEose = true).use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                try {
                    b.shutdown()
                    assertTrue(awaitTrue(15_000) { e.connectedNormalRelayUrls() == setOf(a.url) }, "only A connected")
                    withContext(Dispatchers.Default) { block(e) }
                } finally { e.close() }
            }
        }
    }

    @Test
    fun `the deletion mark stays while only a write-only relay is connected`() = runTest {
        readRelaysDown { e ->
            val marks = SyncMarks(dir.resolve("marks.json").toFile())
            e.save(event(3, listOf(listOf("p", alice))))
            NostrFeedRepository(e, marks = marks).refresh(FeedSource.Following, 20)
            assertEquals(emptyMap<String, Long>(), marks.all())
        }
    }

    @Test
    fun `a thread does not stamp its fetches while only a write-only relay is connected`() = runTest {
        readRelaysDown { e ->
            val root = EventBuilder.textNote("root").signWithKeys(Keys.generate()).also { e.save(it) }
            val id = root.id().toHex()
            val gate = com.monostr.nostr.RefreshGate<String>(e.backgroundScope, 600)
            NostrThreadRepository(e, fetchTimeout = timeout, gate = gate).observe(id).untilFirst { it.repliesFetched }
            assertNull(gate.fetchedAt("replies:$id:$id"), "nobody was asked for the replies")
            Thread.sleep(1_500)
            assertNull(gate.fetchedAt("deletions:$id"), "nobody was asked for the deletions")
        }
    }

    @Test
    fun `a bookmark lookup is not remembered as missing while only a write-only relay is connected`() = runTest {
        readRelaysDown { e ->
            e.save(event(10003, listOf(listOf("e", alice))))
            val bookmarks = NostrBookmarksRepository(e, signer, entriesTimeout = timeout)
            bookmarks.ensureLoaded(interactive = false)
            bookmarks.entries()
            assertEquals(emptySet<String>(), bookmarks.missMarked())
        }
    }

    @Test
    fun `a wide fetch is no reply while only a write-only relay is connected`() = runTest {
        readRelaysDown { e ->
            val filter = Filter().kind(Kind(1u)).author(rust.nostr.sdk.PublicKey.parse(alice)).limit(1u)
            assertFalse(fetchWide(e, filter, emptyList(), Duration.ofSeconds(3)))
        }
    }
}
