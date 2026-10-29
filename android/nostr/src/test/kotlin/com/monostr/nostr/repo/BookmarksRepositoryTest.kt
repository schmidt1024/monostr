package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.monostr.nostr.model.AddressRef
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookmarksRepositoryTest {
    @TempDir lateinit var dir: Path
    private val keys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val signer = LocalSigner(keys.secretKey().toHex())
    private val alice = Keys.generate()
    private val n1 = "1".repeat(64); private val n2 = "2".repeat(64); private val n3 = "3".repeat(64)

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), signer, emptyList())

    /** Publishing "succeeds": the event is signed with the test key, stored, and one relay is reported. */
    private fun okSend(e: NostrEngine): suspend (EventBuilder) -> PublishResult = { b ->
        val ev = b.signWithKeys(keys); e.save(ev); PublishResult(ev.id().toHex(), 10003, listOf("wss://r"), emptyMap())
    }
    private val failSend: suspend (EventBuilder) -> PublishResult = { PublishResult("0".repeat(64), 10003, emptyList(), mapOf("wss://r" to "closed")) }

    private suspend fun latest(e: NostrEngine): Event = e.query(Filter().kind(Kind(10003u)).author(keys.publicKey()).limit(1u)).first()
    private suspend fun privateTags(e: NostrEngine): List<List<String>> =
        Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, latest(e).content())).jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }

    /**
     * Seeds an already-synced empty list (as if the user had loaded it from a relay before, in
     * an earlier session). Tests that only care about `toggle` mechanics use this so they are not
     * also exercising the "no local copy, is a relay actually reachable" load path (covered
     * separately below).
     */
    private suspend fun seedEmptyList(e: NostrEngine, createdAt: ULong = 1u) {
        val content = signer.nip44Encrypt(signer.pubkey, "[]")
        e.save(EventBuilder(Kind(10003u), content).customCreatedAt(Timestamp.fromSecs(createdAt)).signWithKeys(keys))
    }

    @Test
    fun `toggle adds a private e tag newest first, keeps public and foreign private tags, removes again`() = runTest {
        val e = engine()
        val existing = signer.nip44Encrypt(signer.pubkey, """[["a","30023:abc:post"],["e","$n1"]]""")
        e.save(EventBuilder(Kind(10003u), existing).tags(listOf(Tag.parse(listOf("e", n2)), Tag.parse(listOf("t", "nostr")))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(listOf(n1, n2), repo.state.value.ids)
        assertTrue(repo.state.value.writable)

        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        assertEquals(listOf(n3, n1, n2), repo.state.value.ids)
        val tags = latest(e).tags().toVec().map { it.asVec() }
        assertEquals(listOf(listOf("e", n2), listOf("t", "nostr")), tags)
        assertEquals(listOf(listOf("e", n3), listOf("a", "30023:abc:post"), listOf("e", n1)), privateTags(e))

        assertEquals(BookmarkOutcome.Ok, repo.toggle(n2))
        assertEquals(listOf(n3, n1), repo.state.value.ids)
        assertEquals(listOf(listOf("t", "nostr")), latest(e).tags().toVec().map { it.asVec() })

        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        assertEquals(listOf(n1), repo.state.value.ids)
        assertEquals(listOf(listOf("a", "30023:abc:post"), listOf("e", n1)), privateTags(e))
    }

    private val pk = "a".repeat(64)
    private val coord = "30023:$pk:my-post"

    @Test
    fun `an article bookmark goes only into the encrypted part, e entries and foreign public tags stay`() = runTest {
        val e = engine()
        val existing = signer.nip44Encrypt(signer.pubkey, """[["e","$n1"],["x","keep"]]""")
        e.save(EventBuilder(Kind(10003u), existing).tags(listOf(Tag.parse(listOf("e", n2)), Tag.parse(listOf("t", "nostr")))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()

        assertEquals(BookmarkOutcome.Ok, repo.toggleArticle(coord))
        assertEquals(listOf(coord), repo.state.value.extraIds)
        assertEquals(listOf(n1, n2), repo.state.value.ids)
        assertEquals(listOf(listOf("e", n2), listOf("t", "nostr")), latest(e).tags().toVec().map { it.asVec() })
        assertEquals(listOf(listOf("x", "keep"), listOf("a", coord), listOf("e", n1)), privateTags(e))

        assertEquals(BookmarkOutcome.Ok, repo.toggleArticle(coord))
        assertEquals(emptyList<String>(), repo.state.value.extraIds)
        assertEquals(listOf(listOf("x", "keep"), listOf("e", n1)), privateTags(e))
    }

    @Test
    fun `entries interleave articles with notes in list order, also an a entry another client added publicly`() = runTest {
        val e = engine()
        val other = "30023:$pk:public-one"
        val existing = signer.nip44Encrypt(signer.pubkey, """[["e","$n1"],["a","$coord"],["a","not-a-coordinate"]]""")
        e.save(EventBuilder(Kind(10003u), existing).tags(listOf(Tag.parse(listOf("a", other)))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        val entries = repo.localEntries()
        assertEquals(listOf(n1, coord, other), entries.map { it.id })
        assertNull(entries[0].article)
        assertEquals(AddressRef(30023, pk, "my-post"), entries[1].article)
        assertEquals(AddressRef(30023, pk, "public-one"), entries[2].article)

        // removing the other client's public entry takes it out of the public tags
        assertEquals(BookmarkOutcome.Ok, repo.toggleArticle(other))
        assertEquals(emptyList<List<String>>(), latest(e).tags().toVec().map { it.asVec() })
        assertEquals(listOf(coord, "not-a-coordinate"), repo.state.value.extraIds) // the malformed one is hidden, never rewritten
    }

    @Test
    fun `entries with hints and markers written by another client survive a write verbatim`() = runTest {
        val e = engine()
        val existing = signer.nip44Encrypt(signer.pubkey, """[["a","$coord","wss://r"],["e","$n1","wss://r","root"]]""")
        e.save(EventBuilder(Kind(10003u), existing).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(BookmarkOutcome.Ok, repo.toggleArticle("30023:$pk:other"))
        assertEquals(listOf(listOf("a", "30023:$pk:other"), listOf("a", coord, "wss://r"), listOf("e", n1, "wss://r", "root")), privateTags(e))
    }

    @Test
    fun `a failed publish reverts the optimistic change`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = failSend)
        repo.ensureLoaded()
        assertTrue(repo.state.value.writable)
        assertEquals(BookmarkOutcome.PublishFailed, repo.toggle(n1))
        assertEquals(emptyList<String>(), repo.state.value.ids)
    }

    @Test
    fun `undecryptable content shows public entries read-only`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), "not-nip44").tags(listOf(Tag.parse(listOf("e", n2)))).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(listOf(n2), repo.state.value.ids)
        assertFalse(repo.state.value.writable)
        assertEquals(BookmarkOutcome.Unsupported, repo.toggle(n1))
        assertEquals(listOf(n2), repo.state.value.ids)
    }

    @Test
    fun `toggle before load keeps remote entries`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        assertEquals(BookmarkOutcome.Ok, repo.toggle(n2))
        assertEquals(listOf(n2, n1), repo.state.value.ids)
        assertEquals(listOf(listOf("e", n2), listOf("e", n1)), privateTags(e))
    }

    @Test
    fun `two toggles serialise`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        listOf(async { repo.toggle(n1) }, async { repo.toggle(n1) }).awaitAll()
        assertEquals(emptyList<String>(), repo.state.value.ids)
        assertEquals(emptyList<List<String>>(), privateTags(e))
    }

    @Test
    fun `entries resolve local notes in list order and keep unknown ids as placeholders`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val note = EventBuilder.textNote("saved").signWithKeys(alice); e.save(note)
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        repo.toggle(n1); repo.toggle(note.id().toHex())
        val entries = repo.entries()
        assertEquals(listOf(note.id().toHex(), n1), entries.map { it.id })
        assertEquals("saved", entries[0].note?.content)
        assertNull(entries[1].note)
        repo.clear()
        assertEquals(BookmarksState(), repo.state.value)
    }

    @Test
    fun `no local copy and an unreachable relay stays not loaded, toggle does not publish`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), signer, listOf("ws://127.0.0.1:9"))
        var sendCalled = false
        val send: suspend (EventBuilder) -> PublishResult = { sendCalled = true; okSend(e)(it) }
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = send, fetchTimeout = Duration.ofMillis(300))
        repo.ensureLoaded()
        assertFalse(repo.state.value.loaded)
        assertFalse(repo.state.value.writable)
        assertEquals(BookmarkOutcome.NotLoaded, repo.toggle(n1))
        assertFalse(sendCalled)
        assertEquals(emptyList<String>(), repo.state.value.ids)
    }

    @Test
    fun `entries tolerates a malformed id, other entries still resolve`() = runTest {
        val e = engine()
        seedEmptyList(e)
        val note = EventBuilder.textNote("saved").signWithKeys(alice); e.save(note)
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        repo.toggle(note.id().toHex()); repo.toggle("zz")
        val entries = repo.entries()
        assertEquals(listOf("zz", note.id().toHex()), entries.map { it.id })
        assertEquals("saved", entries.first { it.id == note.id().toHex() }.note?.content)
        assertNull(entries.first { it.id == "zz" }.note)
    }

    @Test
    fun `one toggle removes an id that sits in both the private and the public list`() = runTest {
        val e = engine()
        val content = signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")
        e.save(EventBuilder(Kind(10003u), content).tags(listOf(Tag.parse(listOf("e", n1)))).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(listOf(n1), repo.state.value.ids)

        assertEquals(BookmarkOutcome.Ok, repo.toggle(n1))
        assertEquals(emptyList<String>(), repo.state.value.ids)
        assertEquals(emptyList<List<String>>(), latest(e).tags().toVec().map { it.asVec() })
        assertEquals(emptyList<List<String>>(), privateTags(e))
    }

    /** An external signer without a remembered permission: no silent answer, and the user dismisses every prompt. */
    private class PromptOnlyRejectingSigner(private val inner: LocalSigner) : Signer by inner {
        var prompts = 0
        var silentCalls = 0
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? { silentCalls++; return null }
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String { prompts++; throw SigningRejectedException() }
    }

    /** An external signer with a remembered permission: answers silently, never prompts. */
    private class SilentSigner(private val inner: LocalSigner) : Signer by inner {
        var prompts = 0
        var silent = true
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String { prompts++; return inner.nip44Decrypt(peerPubkey, payload) }
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = if (silent) inner.nip44DecryptSilent(peerPubkey, payload) else null
    }

    @Test
    fun `implicit load never prompts, a dismissed prompt is never sticky and toggle then reports Rejected`() = runTest {
        val e = engine()
        val content = signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")
        e.save(EventBuilder(Kind(10003u), content).tags(listOf(Tag.parse(listOf("e", n2)))).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val amber = PromptOnlyRejectingSigner(signer)
        var sendCalled = false
        val repo = NostrBookmarksRepository(e, amber, now = { 2000 }, send = { sendCalled = true; okSend(e)(it) })

        repo.ensureLoaded(interactive = false)
        assertEquals(0, amber.prompts, "an implicit load must not open the signer")
        assertEquals(listOf(n2), repo.state.value.ids)
        assertFalse(repo.state.value.loaded)
        assertFalse(repo.state.value.writable)

        repo.ensureLoaded(interactive = true)
        assertEquals(1, amber.prompts)
        assertFalse(repo.state.value.loaded, "a dismissed prompt must allow a retry")
        assertFalse(repo.state.value.writable)
        assertEquals(listOf(n2), repo.state.value.ids)

        // Toggle loads interactively again; dismissed again -> Rejected, nothing published.
        assertEquals(BookmarkOutcome.Rejected, repo.toggle(n3))
        assertEquals(2, amber.prompts)
        assertFalse(sendCalled)
        assertEquals(listOf(n2), repo.state.value.ids)
        assertFalse(repo.state.value.loaded)
    }

    @Test
    fun `a signer that answers silently makes the implicit load fully writable`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val amber = SilentSigner(signer)
        val repo = NostrBookmarksRepository(e, amber, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded(interactive = false)
        assertEquals(0, amber.prompts)
        val st = repo.state.value
        assertEquals(listOf(n1), st.ids)
        assertEquals(emptyList<String>(), st.extraIds)
        assertEquals(listOf("e" to n1), st.ordered)
        assertTrue(st.writable)
        assertTrue(st.loaded)
    }

    @Test
    fun `implicit load asks the silent signer once per session`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val amber = PromptOnlyRejectingSigner(signer)
        val repo = NostrBookmarksRepository(e, amber, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded(interactive = false)
        repo.ensureLoaded(interactive = false)
        repo.ensureLoaded(interactive = false)
        assertEquals(1, amber.silentCalls)
        assertEquals(0, amber.prompts)
        assertFalse(repo.state.value.loaded)
        repo.ensureLoaded(interactive = true) // prompts, user dismisses -> still not loaded, but the next implicit call may try silently again
        assertEquals(1, amber.prompts)
        repo.ensureLoaded(interactive = false)
        assertEquals(2, amber.silentCalls)
    }

    @Test
    fun `a newer list arriving on the subscription replaces the loaded one`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(listOf(n1), repo.state.value.ids)
        val newer = EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n2"],["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1010u)).signWithKeys(keys)
        e.save(newer)
        repo.onListEvent(newer)
        assertEquals(listOf(n2, n1), repo.state.value.ids)
        val older = EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, "[]")).customCreatedAt(Timestamp.fromSecs(900u)).signWithKeys(keys)
        repo.onListEvent(older)
        assertEquals(listOf(n2, n1), repo.state.value.ids) // older lists are ignored
    }

    @Test
    fun `a newer list that cannot be read silently leaves the loaded list untouched`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val amber = SilentSigner(signer)
        val repo = NostrBookmarksRepository(e, amber, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded(interactive = true)
        assertEquals(listOf(n1), repo.state.value.ids)
        assertTrue(repo.state.value.writable)
        amber.silent = false
        val newer = EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n2"],["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1010u)).signWithKeys(keys)
        e.save(newer)
        repo.onListEvent(newer)
        assertEquals(listOf(n1), repo.state.value.ids)
        assertTrue(repo.state.value.writable)
        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        assertTrue(privateTags(e).map { it.getOrNull(1) }.contains(n1))
        assertTrue(privateTags(e).map { it.getOrNull(1) }.contains(n3))
    }

    @Test
    fun `toggle applies the change on top of a newer list another device wrote`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        assertEquals(listOf(n1), repo.state.value.ids)
        // Another device publishes n2 + n1; it reaches our database behind the repository's back.
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n2"],["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1500u)).signWithKeys(keys))
        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        assertEquals(listOf(listOf("e", n3), listOf("e", n2), listOf("e", n1)), privateTags(e))
        assertEquals(listOf(n3, n2, n1), repo.state.value.ids)
    }

    @Test
    fun `a failed write does not hide a newer remote list from the next toggle`() = runTest {
        val e = engine()
        val nB = "b".repeat(64)
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        var online = false
        val send: suspend (EventBuilder) -> PublishResult = { if (online) okSend(e)(it) else failSend(it) }
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = send)
        repo.ensureLoaded()
        assertEquals(BookmarkOutcome.PublishFailed, repo.toggle(n2))
        assertEquals(listOf(n1), repo.state.value.ids)
        // Device B published n1 + nB at 1500, newer than what we read but older than our failed write.
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$nB"],["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1500u)).signWithKeys(keys))
        online = true
        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        assertEquals(listOf(listOf("e", n3), listOf("e", nB), listOf("e", n1)), privateTags(e))
        assertEquals(listOf(n3, nB, n1), repo.state.value.ids)
    }

    @Test
    fun `a connected search relay does not count as a relay that answered the load`() = runTest {
        SilentWsRelay().use { search ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), signer, listOf("ws://127.0.0.1:9"))
            e.attachTemporary(listOf(search.url))
            assertTrue(awaitTrue { search.url in e.connectedRelayUrls() }, "loopback relay never connected")
            var sendCalled = false
            val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = { sendCalled = true; okSend(e)(it) }, fetchTimeout = Duration.ofMillis(300))
            repo.ensureLoaded()
            assertFalse(repo.state.value.loaded)
            assertEquals(BookmarkOutcome.NotLoaded, repo.toggle(n1))
            assertFalse(sendCalled)
            e.close()
        }
    }

    @Test
    fun `toggle flips the list at once and never shows the unchanged remote list in between`() = runTest {
        val nB = "b".repeat(64)
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        // another device published a newer list behind our back
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"],["e","$nB"]]""")).customCreatedAt(Timestamp.fromSecs(1500u)).signWithKeys(keys))
        val seen = ArrayList<List<String>>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repo.state.collect { seen += it.ids } }
        val before = seen.size
        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        job.cancel()
        val during = seen.drop(before)
        assertTrue(during.isNotEmpty(), "toggle must emit")
        assertTrue(during.all { n3 in it }, "every emission during the toggle must already show n3, got $during")
        assertEquals(listOf(n3, n1, nB).toSet(), repo.state.value.ids.toSet())
        assertEquals(listOf(listOf("e", n3), listOf("e", n1), listOf("e", nB)), privateTags(e))
    }

    @Test
    fun `the toggle keeps the direction the user saw when a newer remote list already made the change`() = runTest {
        val e = engine()
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1000u)).signWithKeys(keys))
        val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
        repo.ensureLoaded()
        // device B already bookmarked n3; the user here still sees it unmarked and taps to add it
        e.save(EventBuilder(Kind(10003u), signer.nip44Encrypt(signer.pubkey, """[["e","$n3"],["e","$n1"]]""")).customCreatedAt(Timestamp.fromSecs(1500u)).signWithKeys(keys))
        assertEquals(BookmarkOutcome.Ok, repo.toggle(n3))
        assertTrue(n3 in repo.state.value.ids, "an add must not turn into a remove")
        assertEquals(listOf(listOf("e", n3), listOf("e", n1)), privateTags(e))
    }

    private fun idRequests(relay: SilentWsRelay) = relay.requests.filter { "\"ids\"" in it }

    @Test
    fun `stored bookmarks resolve without a relay, missing ones are placeholders`() = runTest {
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val note = EventBuilder.textNote("saved").signWithKeys(alice); e.save(note)
                e.save(EventBuilder(Kind(10003u), "").tags(listOf(Tag.parse(listOf("e", note.id().toHex())), Tag.parse(listOf("e", n1)))).signWithKeys(keys))
                val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e))
                repo.ensureLoaded()
                val started = System.currentTimeMillis()
                val entries = repo.localEntries()
                assertTrue(System.currentTimeMillis() - started < 1_000)
                assertEquals("saved", entries.first { it.id == note.id().toHex() }.note?.content)
                assertNull(entries.first { it.id == n1 }.note)
                assertTrue(idRequests(relay).isEmpty(), "asked: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `missing bookmarks are asked for in parallel chunks, a miss is not asked again for ten minutes`() = runTest {
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                var clock = 2_000L
                e.save(EventBuilder(Kind(10003u), "").tags((1..120).map { Tag.parse(listOf("e", "%064x".format(it))) }).signWithKeys(keys))
                val repo = NostrBookmarksRepository(e, signer, now = { clock }, send = okSend(e), entriesTimeout = Duration.ofSeconds(1))
                repo.ensureLoaded()
                assertEquals(120, repo.state.value.ids.size)
                val started = System.currentTimeMillis()
                assertTrue(repo.entries().all { it.note == null })
                val took = System.currentTimeMillis() - started
                assertTrue(took < 2_500, "three chunks one after another: $took ms")
                assertEquals(3, idRequests(relay).size)
                repo.entries()
                assertEquals(3, idRequests(relay).size, "the misses were asked again")
                clock += 601
                repo.entries()
                assertTrue(awaitTrue { idRequests(relay).size == 6 })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a changed missing set asks only the new ids, remembered misses stay quiet`() = runTest {
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val a = "%064x".format(1); val b = "%064x".format(2); val c = "%064x".format(3)
                e.save(EventBuilder(Kind(10003u), "").tags(listOf(a, b).map { Tag.parse(listOf("e", it)) }).signWithKeys(keys))
                val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e), entriesTimeout = Duration.ofSeconds(1))
                repo.ensureLoaded()
                repo.entries()
                assertEquals(1, idRequests(relay).size)
                e.save(EventBuilder(Kind(10003u), "").tags(listOf(b, c).map { Tag.parse(listOf("e", it)) }).customCreatedAt(Timestamp.fromSecs(4_000_000_000u)).signWithKeys(keys))
                repo.onListEvent(latest(e))
                assertEquals(listOf(b, c), repo.state.value.ids)
                repo.entries()
                val asked = idRequests(relay)
                assertEquals(2, asked.size)
                assertTrue(c in asked.last() && b !in asked.last(), "asked again: ${asked.last()}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `offline remembers no miss, the next call asks again`() = runTest {
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), signer, listOf(relay.url)) // not connected yet
            try {
                e.save(EventBuilder(Kind(10003u), "").tags(listOf(Tag.parse(listOf("e", n1)))).signWithKeys(keys))
                val repo = NostrBookmarksRepository(e, signer, now = { 2000 }, send = okSend(e), entriesTimeout = Duration.ofSeconds(1))
                repo.ensureLoaded()
                repo.entries(); repo.entries()
                assertTrue(idRequests(relay).isEmpty())
                e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                repo.entries()
                assertEquals(1, idRequests(relay).size, "asked while offline or not asked after coming back")
                repo.entries()
                assertEquals(1, idRequests(relay).size, "the miss was not remembered once online")
            } finally {
                e.close()
            }
        }
    }
}
