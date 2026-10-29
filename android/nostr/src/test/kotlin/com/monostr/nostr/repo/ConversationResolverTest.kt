package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.RefHint
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
    fun `a NIP-22 comment above a reply is shown, not unavailable`() = runTest {
        // backlog 26: a kind 1 reply to a kind 1111 comment showed the stored comment as "This note is unavailable"
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root").also { e.save(it) }
            val rid = r.id().toHex()
            val comment = EventBuilder(Kind(1111u), "comment").tags(
                listOf(listOf("E", rid, "", a.publicKey().toHex()), listOf("K", "1"), listOf("P", a.publicKey().toHex()),
                    listOf("e", rid, "", a.publicKey().toHex()), listOf("k", "1"), listOf("p", a.publicKey().toHex())).map { Tag.parse(it) },
            ).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(b).also { e.save(it) }
            val answer = EventBuilder.textNote("answer").tags(
                listOf(listOf("e", rid, "", "root"), listOf("e", comment.id().toHex(), "", "reply")).map { Tag.parse(it) },
            ).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(a).also { e.save(it) }
            val note = NoteMapper.note(answer)!!
            assertEquals(listOf("root", "comment"), texts(NostrConversationRepository(e).chains(listOf(note))[note.id]))
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
            val chain = NostrConversationRepository(e, workDispatcher = counting).chains(listOf(r2))[r2.id]
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

    // spec 2026-10-23 §4.2: gaps the event locator may still search are pending

    private val c = Keys.generate()

    /** A reply to [parent] in thread [root] with relay/author hints in both `e` tags (NIP-10 marked). */
    private fun hinted(keys: Keys, text: String, parent: Event, parentRelay: String, root: Event, rootRelay: String): Event {
        val tags = if (parent.id() == root.id()) {
            listOf(listOf("e", root.id().toHex(), rootRelay, "root", root.author().toHex()))
        } else {
            listOf(
                listOf("e", root.id().toHex(), rootRelay, "root", root.author().toHex()),
                listOf("e", parent.id().toHex(), parentRelay, "reply", parent.author().toHex()),
            )
        }
        return EventBuilder.textNote(text).tags(tags.map { Tag.parse(it) } + Tag.parse(listOf("p", c.publicKey().toHex())))
            .customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)
    }

    /** Serves [serve] into the database on [find]; [open] false acts as switch off / all marked; [marks] marks asked ids. */
    private class FakeLocator(
        val engine: NostrEngine,
        val serve: Map<String, Event> = emptyMap(),
        val open: Boolean = true,
        val marks: Boolean = false,
    ) : EventLocator {
        val asked = mutableListOf<List<Wanted>>()
        val searchedIds = mutableListOf<String>()
        private val marked = HashSet<String>()
        override suspend fun searchable(id: String): Boolean { searchedIds += id; return open && id !in marked }
        /** Calls of the batched check (final review M4: one per walk, not one per id). */
        var batches = 0
        override suspend fun searchable(ids: Collection<String>): Set<String> { batches++; return ids.filterTo(HashSet()) { searchable(it) } }
        override suspend fun find(wanted: Collection<Wanted>): Set<String> {
            asked += wanted.toList()
            if (marks) marked += wanted.map { it.id }
            return wanted.mapNotNull { w -> serve[w.id]?.let { engine.save(it); w.id } }.toSet()
        }
        override suspend fun repliesFrom(author: String, tagIds: List<String>) = false
        override fun forget(notify: Boolean) {}
        override val retries = MutableSharedFlow<Unit>()
    }

    @Test
    fun `missing parent with a hint is pending`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root")
            val p = hinted(b, "p", r, "", r, "wss://root.p")
            val x = hinted(a, "x", p, "wss://parent.x", r, "wss://root.x").also { e.save(it) }
            val chain = NostrConversationRepository(e, FakeLocator(e)).chains(listOf(NoteMapper.note(x)!!))[x.id().toHex()]!!
            val pid = p.id().toHex()
            assertEquals(Slot.Pending(pid, Wanted(pid, listOf("wss://parent.x"), listOf(b.publicKey().toHex()))), chain.ancestors.last())
            e.close()
        }
    }

    @Test
    fun `missing root uses the root hint`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(4) // root, r1..r4; the root never arrives
            all.drop(1).forEach { e.save(it) }
            val x = hinted(b, "x", all[4], "wss://parent.x", all[0], "wss://root.x").also { e.save(it) }
            val locator = FakeLocator(e)
            val chain = NostrConversationRepository(e, locator).chains(listOf(NoteMapper.note(x)!!))[x.id().toHex()]!!
            val rid = all[0].id().toHex()
            assertTrue(chain.cut)
            assertEquals(Slot.Pending(rid, Wanted(rid, listOf("wss://root.x"), listOf(a.publicKey().toHex()))), chain.ancestors.first())
            assertEquals(listOf(rid), locator.searchedIds, "only the missing id is checked")
            e.close()
        }
    }

    @Test
    fun `marked or switched off is unavailable`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root")
            val p = hinted(b, "p", r, "", r, "wss://root.p")
            val x = hinted(a, "x", p, "wss://parent.x", r, "wss://root.x").also { e.save(it) }
            val chain = NostrConversationRepository(e, FakeLocator(e, open = false)).chains(listOf(NoteMapper.note(x)!!))[x.id().toHex()]!!
            assertEquals(listOf(Slot.Unavailable(r.id().toHex()), Slot.Unavailable(p.id().toHex())), chain.ancestors)
            e.close()
        }
    }

    @Test
    fun `resolve then chains shows the parent`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root")
            val p = hinted(b, "p", r, "", r, "wss://root.p")
            val x = hinted(a, "x", p, "wss://parent.x", r, "wss://root.x").also { e.save(it) }
            val pid = p.id().toHex()
            val rid = r.id().toHex()
            val repo = NostrConversationRepository(e, FakeLocator(e, serve = mapOf(pid to p)))
            val note = NoteMapper.note(x)!!
            val pending = repo.chains(listOf(note))[note.id]!!.ancestors.filterIsInstance<Slot.Pending>()
            assertEquals(setOf(pid), repo.resolve(pending + pending)) // the root is not served
            val chain = repo.chains(listOf(note))[note.id]!!
            assertEquals(Slot.Shown(NoteMapper.note(p)!!), chain.ancestors.last())
            // the hop above the found parent carries the hints of the found note
            assertEquals(Slot.Pending(rid, Wanted(rid, listOf("wss://root.p"), listOf(a.publicKey().toHex()))), chain.ancestors.first())
            e.close()
        }
    }

    @Test
    fun `resolve asks each pending id once and without a locator finds nothing`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val w = Wanted("1".repeat(64))
            val locator = FakeLocator(e)
            NostrConversationRepository(e, locator).resolve(listOf(Slot.Pending(w.id, w), Slot.Pending(w.id, w)))
            assertEquals(listOf(listOf(w)), locator.asked)
            assertEquals(emptySet<String>(), NostrConversationRepository(e).resolve(listOf(Slot.Pending(w.id, w))))
            e.close()
        }
    }

    @Test
    fun `cycle with foreign notes stops`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            // the entry A replies to B, B replies to A; neither is stored (A's id is chosen before B is signed)
            val aId = "a".repeat(64)
            val bEv = EventBuilder.textNote("B").tags(listOf(Tag.parse(listOf("e", aId, "wss://b.hint", "reply")))).signWithKeys(b)
            val bId = bEv.id().toHex()
            val entry = Note(aId, a.publicKey().toHex(), "A", 1, 1, bId, bId, null, emptyList(), replyHint = RefHint("wss://a.hint"))
            val repo = NostrConversationRepository(e, FakeLocator(e, serve = mapOf(bId to bEv)))
            // real time: the engine's reads run off the test scheduler
            withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) {
                var pending: List<Slot.Pending> = emptyList()
                for (round in 0..FEED_ROUNDS) {
                    pending = repo.chains(listOf(entry))[aId]!!.ancestors.filterIsInstance<Slot.Pending>()
                    if (pending.isEmpty()) break
                    repo.resolve(pending)
                }
                assertEquals(emptyList<Slot.Pending>(), pending, "a cycle never pends again")
            } }
            e.close()
        }
    }

    @Test
    fun `localAncestors never pends`() = runTest {
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(5)
            listOf(all[1], all[2], all[3], all[5]).forEach { e.save(it) }
            val locator = FakeLocator(e)
            val repo = NostrConversationRepository(e, locator)
            val walk = repo.localAncestors(NoteMapper.note(all[5])!!)
            assertNull(walk.ancestors)
            assertEquals(setOf(all[4].id().toHex(), all[0].id().toHex()), walk.missing)
            assertTrue(locator.searchedIds.isEmpty() && locator.asked.isEmpty())
            e.close()
        }
    }

    @Test
    fun `a found chain asks the locator nothing`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val all = chainOf(3).onEach { e.save(it) }
            val locator = FakeLocator(e)
            val note = NoteMapper.note(all.last())!!
            assertEquals(listOf("root", "r1", "r2"), texts(NostrConversationRepository(e, locator).chains(listOf(note))[note.id]))
            assertTrue(locator.searchedIds.isEmpty())
            e.close()
        }
    }

    @Test
    fun `thread ancestors pend with a locator`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root") // neither root nor parent arrives
            val p = hinted(b, "p", r, "", r, "wss://root.p")
            val x = hinted(a, "x", p, "wss://parent.x", r, "wss://root.x").also { e.save(it) }
            val note = NoteMapper.note(x)!!
            val pid = p.id().toHex()
            val rid = r.id().toHex()
            assertEquals(
                listOf(
                    Slot.Pending(rid, Wanted(rid, listOf("wss://root.x"), listOf(a.publicKey().toHex()))),
                    Slot.Pending(pid, Wanted(pid, listOf("wss://parent.x"), listOf(b.publicKey().toHex()))),
                ),
                NostrConversationRepository(e, FakeLocator(e)).ancestors(note),
            )
            // without a locator, or for the database walk, a gap stays unavailable
            assertEquals(listOf(Slot.Unavailable(rid), Slot.Unavailable(pid)), NostrConversationRepository(e).ancestors(note))
            assertEquals(setOf(rid, pid), NostrConversationRepository(e, FakeLocator(e)).localAncestors(note).missing)
            e.close()
        }
    }

    @Test
    fun `a note naming itself as parent does not break the chains`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val self = "a".repeat(64)
            val crafted = Note(self, a.publicKey().toHex(), "A", 1, 1, "b".repeat(64), self, null, emptyList())
            val chains = NostrConversationRepository(e, FakeLocator(e)).chains(listOf(crafted))
            assertTrue(chains[self]?.ancestors.orEmpty().isEmpty(), "chains: $chains")
            e.close()
        }
    }

    @Test
    fun `chains with an asked set ask the own relays once per id across rounds`() = runTest {
        // spec 2026-10-23 §10, final review M1: the feed's re-walk after a stage 3 round skips ids that missed on the own relays
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root")
            val p = hinted(b, "p", r, "", r, "wss://root.p")
            val x = hinted(a, "x", p, "wss://parent.x", r, "wss://root.x").also { e.save(it) }
            val repo = NostrConversationRepository(e, FakeLocator(e))
            val note = NoteMapper.note(x)!!
            val pid = p.id().toHex()
            val asked = HashSet<String>()
            repeat(3) { assertTrue(repo.chains(listOf(note), asked)[note.id]!!.ancestors.last() is Slot.Pending) }
            assertEquals(1, relay.requests.count { "\"ids\"" in it && pid in it }, "own relay asked: ${relay.requests}")
            assertTrue(pid in asked)
            // without the set every walk asks again (the thread page and first walks are unchanged)
            repo.chains(listOf(note))
            assertEquals(2, relay.requests.count { "\"ids\"" in it && pid in it })
            e.close()
        }
    }

    @Test
    fun `the locator check runs once per walk for all missing ids`() = runTest {
        // final review M4: the switch is read once per chains call, not once per missing id
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val r = root(a, "root")
            val p = hinted(b, "p", r, "", r, "wss://root.p")
            val x = hinted(a, "x", p, "wss://parent.x", r, "wss://root.x").also { e.save(it) }
            val locator = FakeLocator(e)
            val chain = NostrConversationRepository(e, locator).chains(listOf(NoteMapper.note(x)!!))[x.id().toHex()]!!
            assertEquals(2, chain.ancestors.count { it is Slot.Pending })
            assertEquals(1, locator.batches)
            e.close()
        }
    }

    private val articleRef = com.monostr.nostr.model.AddressRef(30023, a.publicKey().toHex(), "post")

    private fun articleComment(keys: Keys, text: String, parent: Event? = null): Event {
        val articleId = "d".repeat(64)
        val root = listOf(listOf("A", articleRef.coordinate), listOf("E", articleId), listOf("K", "30023"), listOf("P", a.publicKey().toHex()))
        val scope = if (parent == null) listOf(listOf("a", articleRef.coordinate), listOf("e", articleId), listOf("k", "30023"))
        else listOf(listOf("e", parent.id().toHex()), listOf("k", "1111"))
        return EventBuilder(Kind(1111u), text).tags((root + scope).map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)
    }

    @Test
    fun `a comment under an article has the article card above it, not an unavailable note`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val c = articleComment(b, "first").also { e.save(it) }
            val repo = NostrConversationRepository(e)
            assertEquals(listOf<Slot>(Slot.Article(articleRef)), repo.ancestors(NoteMapper.note(c)!!))
            assertTrue(relay.requests.isEmpty(), "the article id is not looked up as a note")
            val walk = repo.localAncestors(NoteMapper.note(c)!!)
            assertEquals(listOf<Slot>(Slot.Article(articleRef)), walk.ancestors)
            e.close()
        }
    }

    @Test
    fun `a comment on a comment has the article first, then the parent comment`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val c = articleComment(b, "first").also { e.save(it) }
            val d = articleComment(a, "second", parent = c).also { e.save(it) }
            val got = NostrConversationRepository(e).ancestors(NoteMapper.note(d)!!)
            assertEquals(listOf("article", "first"), got.map { if (it is Slot.Article) "article" else (it as Slot.Shown).note.content })
            e.close()
        }
    }

    @Test
    fun `feed chains of comments end at the article card, the article id is never asked for`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val c = articleComment(b, "first").also { e.save(it) }
            val d = articleComment(a, "second", parent = c).also { e.save(it) }
            val top = NoteMapper.note(c)!!
            val nested = NoteMapper.note(d)!!
            val chains = NostrConversationRepository(e).chains(listOf(top, nested))
            assertEquals(Chain(listOf(Slot.Article(articleRef)), cut = false), chains[top.id])
            assertEquals(listOf("article", "first"), chains[nested.id]!!.ancestors.map { if (it is Slot.Article) "article" else (it as Slot.Shown).note.content })
            assertTrue(relay.requests.isEmpty(), "the article id is not looked up as a note")
            e.close()
        }
    }
}
