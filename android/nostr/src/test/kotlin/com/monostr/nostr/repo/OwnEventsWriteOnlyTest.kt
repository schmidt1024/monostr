package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.RelayMode
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

/**
 * Spec 2026-11-05 relay read/write, task 4: the user's own events are read from write-only relays too, and such a relay
 * hears about nobody else. A = write only, B = read only.
 */
class OwnEventsWriteOnlyTest {
    @TempDir lateinit var dir: Path

    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val pk = me.publicKey().toHex()
    private val alice = Keys.generate()
    private val aliceHex = alice.publicKey().toHex()
    private val signer = LocalSigner(secret)

    private fun empty() = SilentWsRelay(answerEose = true, acceptEvents = true)

    private suspend fun oneWay(a: SilentWsRelay, b: SilentWsRelay): NostrEngine =
        connectedEngine(dir, listOf(a, b)).also { it.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ)) }

    private fun authors(req: String): List<String>? =
        Regex("\"authors\"\\s*:\\s*\\[([^\\]]*)]").find(req)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }

    private fun SilentWsRelay.onlyMine() = requests.forEach { assertEquals(listOf(pk), authors(it), it) }

    private fun SilentWsRelay.asked(kind: Int) = requests.any { Regex("\"kinds\"\\s*:\\s*\\[[^\\]]*\\b$kind\\b").containsMatchIn(it) }

    /** Long enough for a frame that was wrongly sent to arrive. */
    private fun settle() = Thread.sleep(400)

    private fun event(kind: Int, content: String = "", tags: List<List<String>> = emptyList(), at: ULong = 1_000u, by: Keys = me): Event =
        EventBuilder(Kind(kind.toUShort()), content).tags(tags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(by)

    private suspend fun ownOf(e: NostrEngine, kind: Int) = e.query(Filter().kind(Kind(kind.toUShort())).author(me.publicKey()))

    @Test
    fun `fetchOwn finds what only a write-only relay holds`() = runTest {
        val list = event(3, tags = listOf(listOf("p", aliceHex)))
        SilentWsRelay(serve = listOf(list.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val own = e.fetchOwn(Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u), Duration.ofSeconds(3))
                assertTrue(own.found)
                assertEquals(list.id().toHex(), ownOf(e, 3).single().id().toHex())
                e.close()
            }
        }
    }

    @Test
    fun `fetchOwn is not complete while a write-only relay stays silent, and is once it answers`() = runTest {
        val filter = Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u)
        SilentWsRelay(recordRequests = true).use { silent ->
            empty().use { b ->
                val e = oneWay(silent, b)
                val own = withContext(Dispatchers.Default) { e.fetchOwn(filter, Duration.ofSeconds(1)) }
                assertTrue(silent.requests.isNotEmpty(), "the write-only relay is asked")
                assertFalse(own.completed, "no answer from the write-only relay proves nothing")
                e.close()
            }
        }
        val again = dir.resolve("second")
        empty().use { a ->
            empty().use { b ->
                val e = connectedEngine(again, listOf(a, b)).also { it.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ)) }
                val own = e.fetchOwn(filter, Duration.ofSeconds(3))
                assertFalse(own.found)
                assertTrue(own.completed, "both answered: there is none")
                e.close()
            }
        }
    }

    @Test
    fun `own relay list on a write-only relay is found`() = runTest {
        val list = event(10002, tags = listOf(listOf("r", "wss://mine.example", "write")))
        SilentWsRelay(serve = listOf(list.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                assertEquals(listOf("wss://mine.example"), e.ownRelayList(fresh = true)?.write)
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `a missing own relay list asks the write-only relay too`() = runTest {
        val list = event(10002, tags = listOf(listOf("r", "wss://mine.example")))
        SilentWsRelay(serve = listOf(list.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                assertEquals(listOf("wss://mine.example"), e.ownRelayList()?.read)
                e.close()
            }
        }
    }

    @Test
    fun `own reactions find a like on a write-only relay`() = runTest {
        val note = event(1, "a note", by = alice)
        val like = event(7, "+", listOf(listOf("e", note.id().toHex())))
        SilentWsRelay(serve = listOf(like.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val own = NostrOwnReactions(e)
                own.load()
                assertEquals(setOf(note.id().toHex()), own.liked.value)
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `a like older than the start window on a write-only relay is found when its note is on screen`() = runTest {
        val note = event(1, "a note", by = alice)
        val noteId = note.id().toHex()
        val like = event(7, "+", listOf(listOf("e", noteId)))
        SilentWsRelay(serveFor = { if (noteId in it) listOf(like.asJson()) else emptyList() }).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val scope = e.backgroundScope
                val own = NostrOwnReactions(e, scope = scope)
                own.check(listOf(noteId))
                assertTrue(awaitTrue { noteId in own.liked.value }, "like found: ${a.requests}")
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `an own reactions stamp counts only when the write-only relay replied too`() = runTest {
        val marks = SyncMarks(dir.resolve("marks.json").toFile())
        SilentWsRelay(recordRequests = true).use { silent ->
            empty().use { b ->
                val e = oneWay(silent, b)
                val key = NostrOwnReactions.relaySetKey(e.relayUrls())
                withContext(Dispatchers.Default) { NostrOwnReactions(e, marks = marks, fetchTimeout = Duration.ofSeconds(1), now = { 2_000_000 }).load() }
                assertTrue(silent.requests.isNotEmpty(), "asked")
                assertNull(marks.get(key), "a silent write-only relay: its reactions may be missing")
                e.close()
            }
        }
        val again = dir.resolve("second")
        empty().use { a ->
            empty().use { b ->
                val e = connectedEngine(again, listOf(a, b)).also { it.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ)) }
                val key = NostrOwnReactions.relaySetKey(e.relayUrls())
                NostrOwnReactions(e, marks = marks, now = { 2_000_000 }).load()
                assertEquals(2_000_000L, marks.get(key))
                e.close()
            }
        }
    }

    @Test
    fun `an own reactions stamp needs a reading relay that is up`() = runTest {
        val marks = SyncMarks(dir.resolve("marks.json").toFile())
        empty().use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                b.shutdown()
                assertTrue(awaitTrue(15_000) { e.connectedNormalRelayUrls() == setOf(a.url) }, "only A connected")
                val key = NostrOwnReactions.relaySetKey(e.relayUrls())
                NostrOwnReactions(e, marks = marks, fetchTimeout = Duration.ofSeconds(1), now = { 2_000_000 }).load()
                assertNull(marks.get(key), "the read relays were not reached")
                e.close()
            }
        }
    }

    /** A feed following alice, with [a] write only and [b] read only. */
    private suspend fun following(e: NostrEngine): NostrFeedRepository {
        e.save(event(3, tags = listOf(listOf("p", aliceHex))))
        return NostrFeedRepository(e)
    }

    @Test
    fun `the home feed asks a write-only relay for the user's notes, never with another author`() = runTest {
        val mine = event(1, "only on the write-only relay")
        SilentWsRelay(serve = listOf(mine.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                following(e).refresh(FeedSource.Following, 20)
                assertTrue(awaitTrue { ownOf(e, 1).isNotEmpty() }, "own note stored: ${a.requests}")
                assertTrue(awaitTrue { a.asked(5) }, "own deletions asked: ${a.requests}")
                settle()
                a.onlyMine()
                assertTrue(b.requests.any { authors(it)?.contains(aliceHex) == true }, "B gets the mixed filter")
                e.close()
            }
        }
    }

    @Test
    fun `a silent write-only relay does not hold the home feed's page`() = runTest {
        SilentWsRelay(recordRequests = true).use { silent ->
            empty().use { b ->
                val e = oneWay(silent, b)
                val feed = following(e)
                val took = withContext(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    feed.refresh(FeedSource.Following, 20)
                    (System.nanoTime() - t0) / 1_000_000
                }
                assertTrue(took < 4_000, "took $took ms")
                assertTrue(awaitTrue { silent.requests.isNotEmpty() }, "asked beside the page")
                e.close()
            }
        }
    }

    @Test
    fun `an older page asks a write-only relay for own deletions naming own notes only`() = runTest {
        val own = event(1, "mine, from the read relay", at = 900u)
        val hers = event(1, "hers", at = 900u, by = alice)
        SilentWsRelay(answerEose = true).use { a ->
            SilentWsRelay(serveFor = { if ("until" in it && "\"kinds\":[1,6,1068]" in it.replace(" ", "")) listOf(own.asJson(), hers.asJson()) else emptyList() }).use { b ->
                val e = oneWay(a, b)
                following(e).loadMore(FeedSource.Following, before = 1_000, limit = 20)
                assertTrue(awaitTrue { a.asked(5) }, "own deletions asked: ${a.requests}")
                settle()
                a.onlyMine()
                val deletions = a.requests.filter { "\"kinds\":[5]" in it.replace(" ", "") }
                assertTrue(deletions.any { own.id().toHex() in it }, "$deletions")
                assertFalse(a.requests.any { hers.id().toHex() in it }, "${a.requests}")
                e.close()
            }
        }
    }

    @Test
    fun `a profile of the user asks the write-only relay, another account's does not`() = runTest {
        val mine = event(1, "only on the write-only relay")
        SilentWsRelay(serve = listOf(mine.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val feed = NostrFeedRepository(e)
                feed.refreshProfile(aliceHex, 20)
                settle()
                assertEquals(emptyList<String>(), a.requests.toList(), "another account's profile")
                feed.refreshProfile(pk, 20)
                assertEquals(1, ownOf(e, 1).size, "own note is there when the refresh ends")
                assertTrue(a.asked(1) && a.asked(6) && a.asked(5), "${a.requests}")
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `own metadata reads ask the write-only relay, another account's do not`() = runTest {
        val metadata = event(0, """{"name":"me"}""")
        SilentWsRelay(serve = listOf(metadata.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val profiles = NostrProfileRepository(e)
                profiles.freshMetadata(aliceHex, Duration.ofSeconds(2))
                profiles.rawMetadata(aliceHex, Duration.ofSeconds(2))
                settle()
                assertEquals(emptyList<String>(), a.requests.toList())
                assertEquals("""{"name":"me"}""", profiles.freshMetadata(pk, Duration.ofSeconds(3)))
                e.close()
            }
        }
    }

    @Test
    fun `rawMetadata of the user finds it on a write-only relay`() = runTest {
        val metadata = event(0, """{"name":"me"}""")
        SilentWsRelay(serve = listOf(metadata.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                assertEquals("""{"name":"me"}""", NostrProfileRepository(e).rawMetadata(pk, Duration.ofSeconds(3)))
                e.close()
            }
        }
    }

    @Test
    fun `a profile batch with the user asks the write-only relay for the user alone`() = runTest {
        val metadata = event(0, """{"name":"me"}""")
        SilentWsRelay(serve = listOf(metadata.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val profiles = NostrProfileRepository(e)
                profiles.getAll(listOf(aliceHex, pk))
                assertTrue(awaitTrue { a.requests.isNotEmpty() }, "asked")
                assertTrue(awaitTrue { ownOf(e, 0).isNotEmpty() }, "stored")
                settle()
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `the first read of the own follow list finds it on a write-only relay`() = runTest {
        val list = event(3, tags = listOf(listOf("p", aliceHex)))
        SilentWsRelay(serve = listOf(list.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                assertEquals(setOf(aliceHex, pk), NostrFeedRepository(e).follows().toSet())
                e.close()
            }
        }
    }

    @Test
    fun `a private list only on a write-only relay is loaded`() = runTest {
        val id = "c".repeat(64)
        val list = event(10003, tags = listOf(listOf("e", id)))
        SilentWsRelay(serve = listOf(list.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val bookmarks = NostrPrivateList(e, signer, kind = 10003, tag = "e")
                bookmarks.ensureLoaded(interactive = false)
                assertEquals(listOf(id), bookmarks.state.value.ids)
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `a newer private list on a write-only relay replaces the stored one`() = runTest {
        val old = "c".repeat(64)
        val new = "d".repeat(64)
        SilentWsRelay(serve = listOf(event(10003, tags = listOf(listOf("e", new)), at = 2_000u).asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                e.save(event(10003, tags = listOf(listOf("e", old)), at = 1_000u))
                val bookmarks = NostrPrivateList(e, signer, kind = 10003, tag = "e")
                bookmarks.ensureLoaded(interactive = false)
                assertTrue(awaitTrue { bookmarks.state.value.ids == listOf(new) }, "${bookmarks.state.value.ids}")
                e.close()
            }
        }
    }

    @Test
    fun `the write of a private list keeps a newer version from a write-only relay`() = runTest {
        val old = "c".repeat(64)
        val new = "d".repeat(64)
        val added = "e".repeat(64)
        SilentWsRelay(serve = listOf(event(10003, tags = listOf(listOf("e", new)), at = 2_000u).asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                e.save(event(10003, tags = listOf(listOf("e", old)), at = 1_000u))
                // closed gate: the refresh behind the load waits, so the newer version comes only through the write's own fetch
                val bookmarks = NostrPrivateList(e, signer, kind = 10003, tag = "e", now = { 3_000 }, gate = com.monostr.nostr.ManualGate(), send = { builder ->
                    val ev = builder.signWithKeys(me); e.save(ev); com.monostr.nostr.PublishResult(ev.id().toHex(), 10003, listOf("wss://r"), emptyMap())
                })
                bookmarks.ensureLoaded(interactive = false)
                bookmarks.toggle(added)
                // the addition itself goes into the encrypted part, the public tags are the version it was based on
                val written = ownOf(e, 10003).maxBy { it.createdAt().asSecs() }
                assertEquals(3_000L, written.createdAt().asSecs().toLong())
                assertEquals(listOf(new), written.tags().toVec().map { it.asVec() }.filter { it[0] == "e" }.map { it[1] })
                assertEquals(setOf(new, added), bookmarks.state.value.ids.toSet())
                e.close()
            }
        }
    }

    @Test
    fun `the reconcile of the own lists and of the feed tabs asks the write-only relay`() = runTest {
        empty().use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val noRelays = object : AuthorRelaysRepository {
                    override suspend fun writeRelays(pubkey: String) = emptyList<String>()
                    override suspend fun attach(relays: List<String>) {}
                    override suspend fun detach(relays: List<String>) {}
                    override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int) = emptySet<String>()
                }
                NostrListsRepository(e, signer, noRelays, fetchTimeout = Duration.ofSeconds(2)).reconcileOwn()
                assertTrue(a.requests.isNotEmpty(), "own lists asked")
                a.onlyMine()
                a.requests.clear()
                val cache = object : FeedTabsCache {
                    override suspend fun load(pubkey: String) = null
                    override suspend fun save(pubkey: String, tabs: CachedTabs) {}
                    override suspend fun remove(pubkey: String) {}
                }
                NostrFeedTabsRepository(e, signer, cache, fetchTimeout = Duration.ofSeconds(2)).reconcile()
                assertTrue(a.requests.isNotEmpty(), "feed tabs asked")
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `older own notes only on a write-only relay continue the own profile`() = runTest {
        val older = event(1, "older, only on the write-only relay", at = 900u)
        // slower than the read relay: a leg nobody waits for would land after the page was read
        SilentWsRelay(serveFor = { if ("until" in it) listOf(older.asJson()) else emptyList() }, acceptEvents = true, serveDelayMs = 700).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val page = withContext(Dispatchers.Default) { NostrFeedRepository(e).moreProfileNotes(pk, ProfileSection.POSTS, before = 1_000) }
                assertEquals(listOf(older.id().toHex()), page.map { it.id })
                a.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `another account's older notes do not ask a write-only relay`() = runTest {
        empty().use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                NostrFeedRepository(e).moreProfileNotes(aliceHex, ProfileSection.POSTS, before = 1_000)
                settle()
                assertEquals(emptyList<String>(), a.requests.toList())
                e.close()
            }
        }
    }

    @Test
    fun `notesBy of the user returns what only a write-only relay holds`() = runTest {
        val mine = event(1, "only on the write-only relay")
        SilentWsRelay(serve = listOf(mine.asJson()), acceptEvents = true, serveDelayMs = 700).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                assertEquals(listOf(mine.id().toHex()), withContext(Dispatchers.Default) { NostrFeedRepository(e).notesBy(pk).map { it.id } })
                e.close()
            }
        }
    }

    private fun sentCounter(e: NostrEngine, kind: Int, sent: java.util.concurrent.atomic.AtomicInteger): suspend (EventBuilder) -> com.monostr.nostr.PublishResult = { builder ->
        sent.incrementAndGet()
        val ev = builder.signWithKeys(me); e.save(ev); com.monostr.nostr.PublishResult(ev.id().toHex(), kind, listOf("wss://r"), emptyMap())
    }

    @Test
    fun `a silent write-only relay proves no private list, so a toggle writes nothing`() = runTest {
        SilentWsRelay(recordRequests = true).use { silent ->
            empty().use { b ->
                val e = oneWay(silent, b)
                val sent = java.util.concurrent.atomic.AtomicInteger()
                val bookmarks = NostrPrivateList(e, signer, kind = 10003, tag = "e", send = sentCounter(e, 10003, sent), fetchTimeout = Duration.ofSeconds(1))
                withContext(Dispatchers.Default) {
                    bookmarks.ensureLoaded(interactive = false)
                    assertFalse(bookmarks.state.value.writable, "the only copy may be on the silent relay")
                    assertFalse(bookmarks.state.value.loaded)
                    assertEquals(ListOutcome.NotLoaded, bookmarks.toggle("c".repeat(64)))
                }
                assertTrue(silent.requests.isNotEmpty(), "asked")
                assertEquals(0, sent.get())
                e.close()
            }
        }
    }

    @Test
    fun `a write-only relay that answers empty proves no private list`() = runTest {
        empty().use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val bookmarks = NostrPrivateList(e, signer, kind = 10003, tag = "e")
                bookmarks.ensureLoaded(interactive = false)
                assertTrue(bookmarks.state.value.writable)
                assertTrue(bookmarks.state.value.loaded)
                e.close()
            }
        }
    }

    private val tabAddress = ListAddress(30000, aliceHex, "a-list")

    private class NoTabsCache : FeedTabsCache {
        override suspend fun load(pubkey: String): CachedTabs? = null
        override suspend fun save(pubkey: String, tabs: CachedTabs) {}
        override suspend fun remove(pubkey: String) {}
    }

    @Test
    fun `a silent write-only relay and no known tabs refuse the first pin`() = runTest {
        SilentWsRelay(recordRequests = true).use { silent ->
            empty().use { b ->
                val e = oneWay(silent, b)
                val sent = java.util.concurrent.atomic.AtomicInteger()
                val tabs = NostrFeedTabsRepository(e, signer, NoTabsCache(), send = sentCounter(e, 30078, sent), fetchTimeout = Duration.ofSeconds(1))
                val result = withContext(Dispatchers.Default) { tabs.pin(tabAddress) }
                assertEquals(com.monostr.nostr.model.SaveResult.Failed, result)
                assertEquals(0, sent.get())
                e.close()
            }
        }
    }

    @Test
    fun `tabs only on a write-only relay are kept by the first pin`() = runTest {
        val existing = ListAddress(30000, aliceHex, "kept").toString()
        val onRelay = EventBuilder(Kind(30078u), signer.nip44Encrypt(pk, TabsPayload.encode(listOf(existing))))
            .tags(listOf(Tag.parse(listOf("d", NostrFeedTabsRepository.D_TAG)))).customCreatedAt(Timestamp.fromSecs(1_000u)).signWithKeys(me)
        SilentWsRelay(serve = listOf(onRelay.asJson()), acceptEvents = true).use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val sent = java.util.concurrent.atomic.AtomicInteger()
                val tabs = NostrFeedTabsRepository(e, signer, NoTabsCache(), now = { 2_000 }, send = sentCounter(e, 30078, sent))
                assertEquals(com.monostr.nostr.model.SaveResult.Ok, tabs.pin(tabAddress))
                assertEquals(1, sent.get())
                val written = e.query(Filter().kind(Kind(30078u)).author(me.publicKey())).maxBy { it.createdAt().asSecs() }
                val payload = signer.nip44Decrypt(pk, written.content())
                assertTrue(existing in payload && tabAddress.toString() in payload, payload)
                e.close()
            }
        }
    }

    @Test
    fun `a write-only relay that answers empty lets the first pin through`() = runTest {
        empty().use { a ->
            empty().use { b ->
                val e = oneWay(a, b)
                val sent = java.util.concurrent.atomic.AtomicInteger()
                val tabs = NostrFeedTabsRepository(e, signer, NoTabsCache(), send = sentCounter(e, 30078, sent))
                assertEquals(com.monostr.nostr.model.SaveResult.Ok, tabs.pin(tabAddress))
                assertEquals(1, sent.get())
                e.close()
            }
        }
    }

    @Test
    fun `a silent write-only relay does not hold the first follow list read when the read relay has it`() = runTest {
        val list = event(3, tags = listOf(listOf("p", aliceHex)))
        SilentWsRelay(recordRequests = true).use { silent ->
            SilentWsRelay(serveFor = { if (Regex("\"kinds\"\\s*:\\s*\\[3]").containsMatchIn(it)) listOf(list.asJson()) else emptyList() }).use { b ->
                val e = oneWay(silent, b)
                val feed = NostrFeedRepository(e)
                val took = withContext(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    val follows = feed.follows()
                    assertEquals(setOf(aliceHex, pk), follows.toSet())
                    (System.nanoTime() - t0) / 1_000_000
                }
                assertTrue(took < 4_000, "took $took ms")
                assertTrue(awaitTrue { silent.requests.isNotEmpty() }, "asked beside the page")
                silent.onlyMine()
                e.close()
            }
        }
    }

    @Test
    fun `without a write-only relay no extra request goes out`() = runTest {
        empty().use { r ->
            val e = connectedEngine(dir, r)
            val feed = following(e)
            feed.refresh(FeedSource.Following, 20)
            settle()
            assertEquals(2, r.requests.size, "notes and deletions: ${r.requests}")
            r.requests.clear()
            feed.refreshProfile(pk, 20)
            settle()
            assertEquals(3, r.requests.size, "kind 1, kind 6, kind 5: ${r.requests}")
            r.requests.clear()
            NostrOwnReactions(e).load()
            settle()
            assertEquals(1, r.requests.size, "${r.requests}")
            r.requests.clear()
            e.fetchOwn(Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u), Duration.ofSeconds(3))
            settle()
            assertEquals(1, r.requests.size, "${r.requests}")
            e.close()
        }
    }
}
