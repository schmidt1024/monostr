package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Spec 2026-10-23 §5.2/§5.3: the thread with stage 3 — the chain in parts, replies from the root author's read relays. */
class ThreadForeignTest {
    @TempDir lateinit var dir: Path
    /** Real dispatcher: leases, linger and the engine's IO run in real time. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private val carol = Keys.generate()
    private var clock = 1000L
    private val any: (String) -> Boolean = { true }

    private fun Event.hex() = id().toHex()
    private fun root(text: String) = EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(alice)

    /** A NIP-10 reply to [parent] in thread [root] with the relay hint [parentRelay] (and [rootRelay]) and author hints. */
    private fun hinted(keys: Keys, text: String, parent: Event, parentRelay: String, root: Event, rootRelay: String = ""): Event {
        val tags = if (parent.id() == root.id()) {
            listOf(listOf("e", root.hex(), parentRelay, "root", root.author().toHex()))
        } else {
            listOf(
                listOf("e", root.hex(), rootRelay, "root", root.author().toHex()),
                listOf("e", parent.hex(), parentRelay, "reply", parent.author().toHex()),
            )
        }
        return EventBuilder.textNote(text).tags(tags.map { Tag.parse(it) })
            .customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)
    }

    /** Of [events], what a REQ asks for: by `ids`, or by an `#e` value, and only the kinds it names. */
    private fun answer(req: String, events: List<Event>): List<String> {
        val kinds = Regex("\"kinds\":\\[([0-9,]*)]").find(req)?.groupValues?.get(1)?.split(",")?.mapNotNull { it.toIntOrNull() }
        return events.filter { ev ->
            val j = NoteMapper.decoded(ev)
            (kinds == null || j.kind in kinds) &&
                (("\"ids\"" in req && "\"${j.id}\"" in req) || ("\"#e\"" in req && j.tagValues("e").any { "\"$it\"" in req }))
        }.map { it.asJson() }
    }

    /** A relay that answers every REQ (then EOSE) with those of [events] it asks for; [events] may grow. */
    private fun relayFor(events: List<Event>, delayMs: Long = 0) =
        SilentWsRelay(recordRequests = true, serveFor = { req -> answer(req, events) }, serveDelayMs = delayMs)

    private class Setup(val engine: NostrEngine, val pool: ForeignRelays, val locator: NostrEventLocator, val conversations: CountingConversations) {
        fun thread() = NostrThreadRepository(engine, conversations, locator, fetchTimeout = Duration.ofSeconds(3))
    }

    /** Counts the locator rounds the thread asks for. */
    private class CountingConversations(private val inner: NostrConversationRepository) : ConversationRepository by inner {
        val rounds = AtomicInteger()
        override suspend fun resolve(pending: Collection<Slot.Pending>): Set<String> {
            rounds.incrementAndGet()
            return inner.resolve(pending)
        }
    }

    /** An engine on [own], the shared pool and a real locator; [stored] is in the database before the thread opens. */
    private suspend fun setup(own: SilentWsRelay, vararg stored: Event, lingerMs: Long = 60_000, block: suspend (Setup) -> Unit) {
        val engine = connectedEngine(dir, own)
        val pool = ForeignRelays(engine, io, lingerMs = lingerMs)
        val authors = NostrAuthorRelaysRepository(engine, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2), foreign = pool, relayFilter = any)
        val locator = NostrEventLocator(engine, authors, pool, enabled = { true }, scope = io, timeout = Duration.ofSeconds(3), relayFilter = any)
        try {
            stored.forEach { engine.save(it) }
            withContext(Dispatchers.IO) { block(Setup(engine, pool, locator, CountingConversations(NostrConversationRepository(engine, locator)))) }
        } finally {
            pool.close()
            engine.close()
        }
    }

    @Test
    fun `own relay ancestors come first, foreign later`() = runTest {
        val r = root("root")
        val p = hinted(bob, "p", r, "", r)
        relayFor(listOf(p), delayMs = 1000).use { f1 ->
            val x = hinted(carol, "x", p, f1.url, r)
            relayFor(listOf(r, x)).use { own ->
                setup(own, x) { s ->
                    val views = s.thread().observe(x.hex()).untilFirst { v -> v.ancestors.any { it is Slot.Shown && it.id == p.hex() } }
                    val first = views.first { it.ancestors.isNotEmpty() }
                    assertEquals(Slot.Shown(NoteMapper.note(r)!!), first.ancestors.first(), "the root from the own relay")
                    assertTrue(first.ancestors.last().let { it is Slot.Pending && it.id == p.hex() }, "first chain: ${first.ancestors}")
                }
            }
        }
    }

    @Test
    fun `at most three foreign hops`() = runTest {
        val r = root("root")
        val hops = ArrayList<Event>()
        val foreign = CopyOnWriteArrayList<Event>()
        relayFor(foreign).use { f1 ->
            hops += hinted(bob, "h1", r, "", r)
            repeat(4) { i -> hops += hinted(bob, "h${i + 2}", hops.last(), f1.url, r) }
            foreign += hops
            val x = hinted(carol, "x", hops.last(), f1.url, r)
            relayFor(listOf(r, x)).use { own ->
                setup(own, x) { s ->
                    // rounds find h5, h4, h3; h2 stays unavailable (no fourth foreign hop), h1 is never reached
                    val h2 = hops[1].hex()
                    val views = s.thread().observe(x.hex()).untilFirst(15_000) { v -> v.ancestors.any { it is Slot.Unavailable } }
                    val last = views.last().ancestors
                    assertEquals(listOf(r.hex(), h2) + hops.drop(2).map { it.hex() }, last.map { it.id }, "chain: $last")
                    assertEquals(Slot.Unavailable(h2), last[1])
                    assertTrue(last.none { it is Slot.Pending })
                    delay(500)
                    assertEquals(3, s.conversations.rounds.get())
                    // each hop reached through a found note is asked from the own relays once, never again after a round
                    for (h in hops.subList(1, 4)) assertEquals(1, own.requests.count { "\"ids\"" in it && h.hex() in it }, "own requests: ${own.requests}")
                }
            }
        }
    }

    @Test
    fun `replies from the root author's read relays`() = runTest {
        val r = root("root")
        val y = hinted(bob, "only on f3", r, "", r)
        relayFor(listOf(y)).use { f3 ->
            val list = EventBuilder(Kind(10002u), "").tags(listOf(Tag.parse(listOf("r", f3.url, "read")))).signWithKeys(alice)
            relayFor(listOf(r)).use { own ->
                setup(own, r, list) { s ->
                    s.thread().observe(r.hex()).untilFirst { v -> v.replies.any { it.id == y.hex() } }
                    assertTrue(f3.requests.any { "\"#e\"" in it })
                }
            }
        }
    }

    @Test
    fun `read relays are asked once a minute per root, a retry asks again`() = runTest {
        val r = root("root")
        val y = hinted(bob, "only on f3", r, "", r)
        relayFor(listOf(y)).use { f3 ->
            val list = EventBuilder(Kind(10002u), "").tags(listOf(Tag.parse(listOf("r", f3.url, "read")))).signWithKeys(alice)
            relayFor(listOf(r)).use { own ->
                setup(own, r, list) { s ->
                    val repo = s.thread()
                    fun asked() = f3.requests.count { "\"#e\"" in it }
                    repo.observe(r.hex()).untilFirst { v -> v.replies.any { it.id == y.hex() } }
                    assertTrue(awaitTrue { asked() == 1 })
                    // a second open within the minute: R/D-style gate, no new request
                    val views = CopyOnWriteArrayList<ThreadView>()
                    val job = launch(Dispatchers.Default) { repo.observe(r.hex()).collect { views += it } }
                    try {
                        assertTrue(awaitTrue { views.isNotEmpty() && views.last().repliesFetched })
                        delay(500)
                        assertEquals(1, asked(), "requests: ${f3.requests}")
                        s.locator.forget()
                        assertTrue(awaitTrue { asked() == 2 }, "requests: ${f3.requests}")
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            }
        }
    }

    @Test
    fun `a gap stage 3 did not find is not asked from the own relays again`() = runTest {
        val r = root("root")
        val p = hinted(bob, "p", r, "", r)
        relayFor(emptyList()).use { f1 ->
            val x = hinted(carol, "x", p, f1.url, r)
            relayFor(listOf(r, x)).use { own ->
                setup(own, x) { s ->
                    s.thread().observe(x.hex()).untilFirst { v -> v.ancestors.lastOrNull() == Slot.Unavailable(p.hex()) }
                    delay(300)
                    val ownIds = own.requests.filter { "\"ids\"" in it && p.hex() in it }
                    // round 1 beside R and the walk after R; none after the stage 3 round
                    assertEquals(2, ownIds.size, "own requests: $ownIds")
                }
            }
        }
    }

    @Test
    fun `closing the thread releases leases`() = runTest {
        val r = root("root")
        val p = hinted(bob, "p", r, "", r)
        relayFor(listOf(p), delayMs = 2000).use { f1 ->
            relayFor(emptyList(), delayMs = 2000).use { f2 ->
                val x = hinted(carol, "x", p, f1.url, r)
                val list = EventBuilder(Kind(10002u), "").tags(listOf(Tag.parse(listOf("r", f2.url, "read")))).signWithKeys(alice)
                relayFor(listOf(r, x)).use { own ->
                    setup(own, x, list, lingerMs = 300) { s ->
                        val views = CopyOnWriteArrayList<ThreadView>()
                        val job = launch(Dispatchers.Default) { s.thread().observe(x.hex()).collect { views += it } }
                        // stage 3 and the read relays are both asked
                        assertTrue(awaitTrue { f1.requests.any { "\"ids\"" in it } && f2.requests.any { "\"#e\"" in it } }, "f1 ${f1.requests} f2 ${f2.requests}")
                        assertTrue(views.any { v -> v.ancestors.any { it is Slot.Pending } })
                        job.cancelAndJoin()
                        // both run on, shared, in the background (the locator's round, the gated read-relay job, like R and
                        // D): each gives its lease back within the locator timeout (3 s), detached after the linger
                        assertTrue(awaitTrue(5000) { s.pool.attached().isEmpty() }, "still attached: ${s.pool.attached()}")
                        assertTrue(awaitTrue(2000) { s.engine.temporaryRelayUrls().isEmpty() }, "temporary: ${s.engine.temporaryRelayUrls()}")
                    }
                }
            }
        }
    }

    @Test
    fun `retries walk again`() = runTest {
        val r = root("root")
        val p = hinted(bob, "p", r, "", r)
        val served = CopyOnWriteArrayList<Event>()
        relayFor(served).use { f1 ->
            val x = hinted(carol, "x", p, f1.url, r)
            relayFor(listOf(r, x)).use { own ->
                setup(own, x) { s ->
                    val views = CopyOnWriteArrayList<ThreadView>()
                    val job = launch(Dispatchers.Default) { s.thread().observe(x.hex()).collect { views += it } }
                    try {
                        // asked once, not found, marked: unavailable
                        assertTrue(awaitTrue(8000) { views.lastOrNull()?.ancestors?.lastOrNull() == Slot.Unavailable(p.hex()) }, "views: ${views.map { it.ancestors }}")
                        val asked = f1.requests.count { "\"ids\"" in it }
                        assertEquals(1, asked)
                        served += p
                        s.locator.forget()
                        assertTrue(awaitTrue(8000) { views.lastOrNull()?.ancestors?.lastOrNull() == Slot.Shown(NoteMapper.note(p)!!) }, "views: ${views.map { it.ancestors }}")
                        assertEquals(2, f1.requests.count { "\"ids\"" in it })
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            }
        }
    }
}
