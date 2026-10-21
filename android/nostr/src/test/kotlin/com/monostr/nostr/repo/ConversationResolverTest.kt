package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

/** Spec 2026-10-16 §2.2. */
class ConversationResolverTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val a = Keys.generate()
    private val b = Keys.generate()
    private var clock = 1000L

    private fun root(keys: Keys, text: String) =
        EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)

    private fun reply(keys: Keys, text: String, to: Event, root: Event) =
        EventBuilder.textNoteReply(text, to, root, null).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)

    private suspend fun engine(relay: SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return e
    }

    /** A chain root → r1 → … → r[n]; returns all events, root first. */
    private fun chainOf(n: Int): List<Event> {
        val r = root(a, "root")
        val out = mutableListOf(r)
        repeat(n) { i -> out += reply(if (i % 2 == 0) b else a, "r${i + 1}", out.last(), r) }
        return out
    }

    private fun texts(chain: Chain?) = chain!!.ancestors.map { (it as Slot.Shown).note.content }

    @Test
    fun `one to three ancestors are shown complete`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(3).onEach { e.save(it) } // root, r1, r2, r3
            val repo = NostrConversationRepository(e)
            val notes = all.drop(1).map { NoteMapper.note(it)!! }
            val chains = repo.chains(notes)
            assertEquals(listOf("root"), texts(chains[notes[0].id]))
            assertEquals(listOf("root", "r1"), texts(chains[notes[1].id]))
            assertEquals(listOf("root", "r1", "r2"), texts(chains[notes[2].id]))
            assertFalse(chains[notes[2].id]!!.cut)
            e.close()
        }
    }

    @Test
    fun `four or more ancestors are cut to root, gap, parent`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(5).onEach { e.save(it) } // root, r1..r5
            val repo = NostrConversationRepository(e)
            val r5 = NoteMapper.note(all.last())!!
            val chain = repo.chains(listOf(r5))[r5.id]!!
            assertEquals(listOf("root", "r4"), texts(chain))
            assertTrue(chain.cut)
            e.close()
        }
    }

    @Test
    fun `a root note has no chain`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            assertEquals(emptyMap<String, Chain>(), NostrConversationRepository(e).chains(listOf(NoteMapper.note(r)!!)))
            e.close()
        }
    }

    @Test
    fun `missing parents are fetched in one batch per round`() = runTest {
        val r = root(a, "root")
        val p1 = reply(b, "p1", r, r)
        val p2 = reply(b, "p2", r, r)
        val x1 = reply(a, "x1", p1, r)
        val x2 = reply(a, "x2", p2, r)
        SilentWsRelay(serve = listOf(r.asJson(), p1.asJson(), p2.asJson())).use { relay ->
            val e = engine(relay)
            e.save(x1); e.save(x2)
            val repo = NostrConversationRepository(e)
            val notes = listOf(NoteMapper.note(x1)!!, NoteMapper.note(x2)!!)
            val chains = repo.chains(notes)
            assertEquals(listOf("root", "p1"), texts(chains[notes[0].id]))
            assertEquals(listOf("root", "p2"), texts(chains[notes[1].id]))
            val idRequests = relay.requests.filter { "\"ids\"" in it }
            assertTrue(idRequests.first().contains(p1.id().toHex()) && idRequests.first().contains(p2.id().toHex()), "both parents in the first batch: $idRequests")
            e.close()
        }
    }

    @Test
    fun `missing parent after fetch is unavailable`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root")
            val p = reply(b, "p", r, r)
            val x = reply(a, "x", p, r).also { e.save(it) } // root and p never arrive
            val chain = NostrConversationRepository(e).chains(listOf(NoteMapper.note(x)!!))[x.id().toHex()]!!
            assertEquals(listOf(Slot.Unavailable(r.id().toHex()), Slot.Unavailable(p.id().toHex())), chain.ancestors)
            e.close()
        }
    }

    @Test
    fun `repost of a reply gets the chain of the reposted note`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            val x = reply(b, "x", r, r).also { e.save(it) }
            val rp = EventBuilder.repost(x, null).signWithKeys(a).also { e.save(it) }
            val repost = NoteMapper.note(rp) { id -> if (id == x.id().toHex()) x else null }!!
            assertEquals(listOf("root"), texts(NostrConversationRepository(e).chains(listOf(repost))[repost.id]))
            e.close()
        }
    }

    @Test
    fun `cycle stops`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            // y says its parent is x, x says its parent is y (ids are known up front: build x with a fake parent id first)
            val fakeParent = "f".repeat(64)
            val x = EventBuilder.textNote("x").tags(listOf(Tag.parse(listOf("e", fakeParent, "", "reply")))).signWithKeys(a).also { e.save(it) }
            val y = EventBuilder.textNote("y").tags(listOf(Tag.parse(listOf("e", x.id().toHex(), "", "reply")))).signWithKeys(b).also { e.save(it) }
            val repo = NostrConversationRepository(e)
            val chain = repo.chains(listOf(NoteMapper.note(y)!!))[y.id().toHex()]!!
            assertEquals("x", (chain.ancestors.last() as Slot.Shown).note.content)
            assertTrue(repo.ancestors(NoteMapper.note(y)!!).size <= MAX_HOPS)
            e.close()
        }
    }

    @Test
    fun `ancestors for the thread page are complete`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(5).onEach { e.save(it) }
            val got = NostrConversationRepository(e).ancestors(NoteMapper.note(all.last())!!)
            assertEquals(listOf("root", "r1", "r2", "r3", "r4"), got.map { (it as Slot.Shown).note.content })
            e.close()
        }
    }

    @Test
    fun `ancestor cycle stops`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val self = "e".repeat(64)
            val x = EventBuilder.textNote("x").tags(listOf(Tag.parse(listOf("e", self, "", "reply")))).signWithKeys(a).also { e.save(it) }
            // x points at an id that is never found: the walk ends after one unavailable hop
            assertEquals(listOf(Slot.Unavailable(self)), NostrConversationRepository(e).ancestors(NoteMapper.note(x)!!))
            e.close()
        }
    }

    @Test
    fun `thread walk that stops at a missing parent still loads the root`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            val p = reply(b, "p", r, r) // never arrives
            val x = reply(a, "x", p, r).also { e.save(it) }
            val got = NostrConversationRepository(e).ancestors(NoteMapper.note(x)!!)
            assertEquals(listOf(Slot.Shown(NoteMapper.note(r)!!), Slot.Unavailable(p.id().toHex())), got)
            e.close()
        }
    }

    private class FakeConversations(val result: () -> List<Slot>) : ConversationRepository {
        override suspend fun chains(notes: List<com.monostr.nostr.model.Note>): Map<String, Chain> = emptyMap()
        override suspend fun ancestors(note: com.monostr.nostr.model.Note): List<Slot> = result()
    }

    @Test
    fun `thread still emits when the ancestor walk fails`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            val views = NostrThreadRepository(e, FakeConversations { error("boom") }).observe(r.id().toHex()).untilFirst { it.repliesFetched }
            assertEquals(r.id().toHex(), views.first().focused.id)
            assertTrue(views.all { it.ancestors.isEmpty() })
            e.close()
        }
    }

    @Test
    fun `a later emission carries the ancestors`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            val slots = listOf<Slot>(Slot.Unavailable("9".repeat(64)))
            val views = NostrThreadRepository(e, FakeConversations { slots }).observe(r.id().toHex()).untilFirst { it.ancestors.isNotEmpty() }
            assertTrue(views.first().ancestors.isEmpty())
            assertEquals(slots, views.last().ancestors)
            e.close()
        }
    }

    @Test
    fun `the walk through the database gives the whole stored chain and asks no relay`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(5).onEach { e.save(it) }
            val repo = NostrConversationRepository(e)
            val focused = NoteMapper.note(all.last())!!
            val walk = repo.localAncestors(focused)
            assertEquals(listOf("root", "r1", "r2", "r3", "r4"), walk.ancestors!!.map { (it as Slot.Shown).note.content })
            assertEquals(repo.ancestors(focused), walk.ancestors)
            assertTrue(walk.missing.isEmpty())
            assertTrue(relay.requests.isEmpty(), "requests: ${relay.requests}")
            e.close()
        }
    }

    @Test
    fun `the walk through the database stops at the first hop it does not hold and names it and the root`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(5) // root, r1 ... r5
            listOf(all[1], all[2], all[3], all[5]).forEach { e.save(it) } // neither the root nor r4
            val walk = NostrConversationRepository(e).localAncestors(NoteMapper.note(all[5])!!)
            assertNull(walk.ancestors, "a pending hop is never shown as unavailable")
            assertEquals(setOf(all[4].id().toHex(), all[0].id().toHex()), walk.missing)
            assertTrue(relay.requests.isEmpty(), "requests: ${relay.requests}")
            e.close()
        }
    }

    @Test
    fun `a withdrawn ancestor is unavailable and not asked of the relays`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            val p = reply(b, "p", r, r)
            // the request first, then the note: the database keeps a note that arrives after its deletion request
            e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", p.id().toHex())))).signWithKeys(b))
            e.save(p)
            val x = reply(a, "x", p, r).also { e.save(it) }
            val repo = NostrConversationRepository(e)
            assertEquals(listOf(Slot.Shown(NoteMapper.note(r)!!), Slot.Unavailable(p.id().toHex())), repo.ancestors(NoteMapper.note(x)!!))
            val walk = repo.localAncestors(NoteMapper.note(x)!!)
            assertEquals(repo.ancestors(NoteMapper.note(x)!!), walk.ancestors, "a withdrawn but stored hop completes the local walk")
            assertTrue(walk.missing.isEmpty(), "the withdrawn note is stored: nothing to fetch")
            assertTrue(relay.requests.none { "\"ids\"" in it }, "requests: ${relay.requests}")
            e.close()
        }
    }

    @Test
    fun `the mapping of the chains runs on the work dispatcher, not on the caller`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(2).onEach { e.save(it) }
            var dispatches = 0
            val counting = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    dispatches++
                    kotlinx.coroutines.Dispatchers.Default.dispatch(context, block)
                }
            }
            val r2 = NoteMapper.note(all.last())!!
            val chain = NostrConversationRepository(e, counting).chains(listOf(r2))[r2.id]
            assertEquals(listOf("root", "r1"), texts(chain))
            assertTrue(dispatches > 0, "the work stayed on the caller")
            e.close()
        }
    }

    @Test
    fun `a malformed parent id does not keep the other parents of the round from the relays`() = runTest {
        val r = root(a, "root")
        val p1 = reply(b, "p1", r, r)
        val x1 = reply(a, "x1", p1, r)
        // Nip10 checks only the length: a foreign tag can carry 64 characters that are no id
        val bad = "g".repeat(64)
        val x2 = Note("9".repeat(64), a.publicKey().toHex(), "x2", 1, 1, bad, bad, null, emptyList())
        SilentWsRelay(serve = listOf(r.asJson(), p1.asJson())).use { relay ->
            val e = engine(relay)
            e.save(x1)
            val chains = NostrConversationRepository(e).chains(listOf(NoteMapper.note(x1)!!, x2))
            assertEquals(listOf("root", "p1"), texts(chains[x1.id().toHex()]))
            assertEquals(listOf<Slot>(Slot.Unavailable(bad)), chains[x2.id]!!.ancestors)
            e.close()
        }
    }

    @Test
    fun `fetchMissing asks only for what the database does not hold, in one request`() = runTest {
        val r = root(a, "root")
        val p = reply(b, "p", r, r)
        SilentWsRelay(serve = listOf(p.asJson()), recordRequests = true).use { relay ->
            val e = engine(relay)
            e.save(r)
            val repo = NostrConversationRepository(e)
            assertFalse(repo.fetchMissing(setOf(r.id().toHex())))
            assertTrue(relay.requests.isEmpty(), "requests: ${relay.requests}")
            assertTrue(repo.fetchMissing(setOf(r.id().toHex(), p.id().toHex(), "g".repeat(64))))
            val ids = relay.requests.filter { "\"ids\"" in it }
            assertEquals(1, ids.size, "requests: ${relay.requests}")
            assertTrue(p.id().toHex() in ids.single() && r.id().toHex() !in ids.single())
            assertNotNull(e.eventById(p.id().toHex()))
            e.close()
        }
    }
}
