package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.ManualGate
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import rust.nostr.sdk.Timestamp
import java.time.Duration
import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path

/** v0.13.9: the own likes and reposts come from the user's own kind 7 and kind 6 events, not from what one screen remembers. */
class OwnReactionsTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())

    @Test
    fun `likes and reposts of the user count, a dislike, a withdrawn like and someone else's like do not`() = runTest {
        val e = engine()
        val notes = (0 until 5).map { EventBuilder.textNote("note $it").signWithKeys(alice).also { n -> e.save(n) } }
        e.save(EventBuilder.reaction(notes[0], "+").signWithKeys(me))
        e.save(EventBuilder.reaction(notes[1], "-").signWithKeys(me))
        e.save(EventBuilder.repost(notes[2], null).signWithKeys(me))
        val withdrawn = EventBuilder.reaction(notes[3], "🤙").signWithKeys(me).also { e.save(it) }
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", withdrawn.id().toHex())))).signWithKeys(me))
        e.save(EventBuilder.reaction(notes[4], "+").signWithKeys(alice))
        val own = NostrOwnReactions(e)
        own.load(fetch = false)
        assertEquals(setOf(notes[0].id().toHex()), own.liked.value)
        assertEquals(setOf(notes[2].id().toHex()), own.reposted.value)
        assertEquals(mapOf(notes[0].id().toHex() to "+"), own.reactions.value)
        e.close()
    }

    @Test
    fun `an article repost (kind 16) counts as reposted, a withdrawn one does not`() = runTest {
        val e = engine()
        val kept = EventBuilder.textNote("article stand-in 1").signWithKeys(alice).also { e.save(it) }
        val gone = EventBuilder.textNote("article stand-in 2").signWithKeys(alice).also { e.save(it) }
        fun generic(target: rust.nostr.sdk.Event) = EventBuilder(Kind(16u), "").tags(listOf(Tag.parse(listOf("e", target.id().toHex())), Tag.parse(listOf("k", "30023")))).signWithKeys(me)
        e.save(generic(kept))
        val withdrawn = generic(gone).also { e.save(it) }
        e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", withdrawn.id().toHex())))).signWithKeys(me))
        val own = NostrOwnReactions(e)
        own.load(fetch = false)
        assertEquals(setOf(kept.id().toHex()), own.reposted.value)
        e.close()
    }

    @Test
    fun `an emoji reaction is kept with its content, the newest one wins`() = runTest {
        val e = engine()
        val note = EventBuilder.textNote("note").signWithKeys(alice).also { e.save(it) }
        e.save(EventBuilder.reaction(note, "🤙").customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(1000u)).signWithKeys(me))
        e.save(EventBuilder.reaction(note, "🔥").customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(2000u)).signWithKeys(me))
        val own = NostrOwnReactions(e)
        own.load(fetch = false)
        assertEquals(mapOf(note.id().toHex() to "🔥"), own.reactions.value)
        assertEquals(setOf(note.id().toHex()), own.liked.value)
        e.close()
    }

    @Test
    fun `an optimistic mark shows at once and goes again when it is taken back`() = runTest {
        val own = MemoryOwnReactions()
        val id = EventId.parse("a".repeat(64)).toHex()
        assertEquals(true, own.add(7, id))
        assertEquals(false, own.add(7, id))
        assertEquals(setOf(id), own.liked.value)
        own.remove(7, id)
        assertEquals(emptySet<String>(), own.liked.value)
        assertEquals(true, own.add(6, id))
        assertEquals(setOf(id), own.reposted.value)
        assertEquals(true, own.add(7, id, "🔥"))
        assertEquals(false, own.add(7, id, "🤙"))
        assertEquals(mapOf(id to "🔥"), own.reactions.value)
    }

    @Test
    fun `a reaction older than the start covered is found once its note is on screen`() = runTest {
        val e = engine()
        val old = EventBuilder.textNote("old").signWithKeys(alice).also { e.save(it) }
        val new = EventBuilder.textNote("new").signWithKeys(alice).also { e.save(it) }
        e.save(EventBuilder.reaction(old, "🔥").customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(1000u)).signWithKeys(me))
        e.save(EventBuilder.reaction(new, "+").customCreatedAt(rust.nostr.sdk.Timestamp.fromSecs(2000u)).signWithKeys(me))
        val own = NostrOwnReactions(e, limit = 1, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
        own.load(fetch = false)
        assertEquals(setOf(new.id().toHex()), own.liked.value) // only the newest one fits the start
        own.check(listOf(old.id().toHex(), new.id().toHex()))
        assertTrue(com.monostr.nostr.awaitTrue { old.id().toHex() in own.liked.value })
        assertEquals("🔥", own.reactions.value[old.id().toHex()])
        e.close()
    }

    private suspend fun connected(relay: SilentWsRelay) = connectedEngine(dir, relay, me.secretKey().toHex())

    /** One own like in the database (so it is not empty); returns the liked note's id. */
    private suspend fun storedLike(e: NostrEngine): String {
        val note = EventBuilder.textNote("liked").signWithKeys(alice).also { e.save(it) }
        e.save(EventBuilder.reaction(note, "+").customCreatedAt(Timestamp.fromSecs(900_000u)).signWithKeys(me))
        return note.id().toHex()
    }

    /** The start fetches (the check fetches carry `#e`, not the limit). */
    private fun starts(relay: SilentWsRelay) = relay.requests.filter { "\"limit\":2000" in it }

    @Test
    fun `stored likes show before the startup gate opens, the relays are asked only after it`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            try {
                val liked = storedLike(e)
                val gate = ManualGate()
                val own = NostrOwnReactions(e, gate = gate)
                val load = launch(Dispatchers.IO) { own.load() }
                assertTrue(awaitTrue { liked in own.liked.value })
                Thread.sleep(300)
                assertTrue(relay.requests.isEmpty(), "asked before the gate: ${relay.requests}")
                gate.open("test")
                load.join()
                assertEquals(1, starts(relay).size)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `with a stamp for this relay set the start fetch asks from a day before it, and moves the stamp`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            try {
                storedLike(e)
                val marks = SyncMarks(dir.resolve("marks.json").toFile())
                val key = NostrOwnReactions.relaySetKey(e.relayUrls())
                marks.put(key, 1_000_000)
                NostrOwnReactions(e, marks = marks, now = { 2_000_000 }).load()
                val req = starts(relay).single()
                assertTrue("\"since\":${1_000_000 - 86_400}" in req, req)
                assertEquals(2_000_000L, marks.get(key))
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a stamp from a clock that was ahead never puts since in the future`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            try {
                storedLike(e)
                val marks = SyncMarks(dir.resolve("marks.json").toFile())
                val key = NostrOwnReactions.relaySetKey(e.relayUrls())
                marks.put(key, 2_000_000 + 3 * 86_400)
                NostrOwnReactions(e, marks = marks, now = { 2_000_000 }).load()
                val req = starts(relay).single()
                assertTrue("\"since\":${2_000_000 - 86_400}" in req, req)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `without stored reactions or for a new relay set the whole window is fetched`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            try {
                val key = NostrOwnReactions.relaySetKey(e.relayUrls())
                val marks = SyncMarks(dir.resolve("marks.json").toFile()).apply { put(key, 1_000_000) }
                // a stamp, but the database holds nothing (cleared): the stamp does not count
                NostrOwnReactions(e, marks = marks, now = { 2_000_000 }).load()
                assertFalse("\"since\"" in starts(relay).last(), starts(relay).last())
                storedLike(e)
                // a stamp of another relay set only: the new relays may hold older reactions
                val other = SyncMarks(dir.resolve("other.json").toFile()).apply { put(NostrOwnReactions.relaySetKey(listOf("wss://other.example")), 1_000_000) }
                NostrOwnReactions(e, marks = other, now = { 2_000_000 }).load()
                assertFalse("\"since\"" in starts(relay).last(), starts(relay).last())
                assertEquals(2_000_000L, other.get(key), "the completed full fetch stamps this set")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a fetch that ran into its timeout does not move the stamp`() = runTest {
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = connected(relay)
            try {
                storedLike(e)
                val marks = SyncMarks(dir.resolve("marks.json").toFile())
                NostrOwnReactions(e, marks = marks, fetchTimeout = Duration.ofSeconds(1), now = { 2_000_000 }).load()
                assertEquals(1, starts(relay).size)
                assertNull(marks.get(NostrOwnReactions.relaySetKey(e.relayUrls())))
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `check reads the database at once and asks the relays after the gate`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            try {
                val liked = storedLike(e)
                val gate = ManualGate()
                val own = NostrOwnReactions(e, scope = CoroutineScope(Dispatchers.IO), gate = gate)
                own.check(listOf(liked))
                assertTrue(awaitTrue { liked in own.liked.value })
                Thread.sleep(300)
                assertTrue(relay.requests.isEmpty(), "asked before the gate: ${relay.requests}")
                gate.open("test")
                assertTrue(awaitTrue { relay.requests.isNotEmpty() })
            } finally {
                e.close()
            }
        }
    }
}
