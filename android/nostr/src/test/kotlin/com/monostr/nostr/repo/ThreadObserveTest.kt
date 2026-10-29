package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.untilFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.time.Duration

/** Spec perf2 §3.C: the thread end to end, against loopback relays and a real LMDB. */
class ThreadObserveTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private var clock = 1000L

    private fun Event.hex() = id().toHex()
    private fun root(text: String) = EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(alice)
    private fun reply(text: String, to: Event, root: Event, keys: Keys = bob) =
        EventBuilder.textNoteReply(text, to, root, null).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)

    /** root, r1 … r[n]: NIP-10 replies, each to the one before, all tagging the root. */
    private fun chainOf(n: Int): List<Event> {
        val r = root("root")
        val out = mutableListOf(r)
        repeat(n) { i -> out += reply("r${i + 1}", out.last(), r) }
        return out
    }

    /** Of [events], what a REQ asks for: by `ids`, or by an `#e` value, and only the kinds it names. */
    private fun answer(req: String, events: List<Event>): List<String> {
        val kinds = Regex("\"kinds\":\\[([0-9,]*)]").find(req)?.groupValues?.get(1)?.split(",")?.mapNotNull { it.toIntOrNull() }
        return events.filter { ev ->
            val j = NoteMapper.decoded(ev)
            (kinds == null || j.kind in kinds) &&
                (("\"ids\"" in req && "\"${j.id}\"" in req) || ("\"#e\"" in req && j.tagValues("e").any { "\"$it\"" in req }) ||
                    ("\"#E\"" in req && j.tagValues("E").any { "\"$it\"" in req }))
        }.map { it.asJson() }
    }

    private fun relayFor(events: List<Event>) = SilentWsRelay(recordRequests = true, serveFor = { req -> answer(req, events) })

    /** Job R's request: replies and deletions by tag, not the live one (it has `since`), not job D's (kinds [5]). */
    private fun isRepliesRequest(req: String) = "\"#e\"" in req && "\"since\"" !in req && "\"kinds\":[1,5,1111]" in req

    @Test
    fun `a shorter fetch timeout ends the lookup of a note no relay answers sooner`() = runTest {
        SilentWsRelay().use { silent ->
            val e = connectedEngine(dir, silent)
            try {
                val repo = NostrThreadRepository(e, fetchTimeout = Duration.ofSeconds(1))
                val view = withContext(Dispatchers.Default) { withTimeout(3_000) { repo.observe("f".repeat(64)).firstOrNull() } }
                assertNull(view)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `the first view comes at once while the relays are silent, without a root that is not stored`() = runTest {
        val (r, r1) = chainOf(1)
        SilentWsRelay(recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(r1)
                val repo = NostrThreadRepository(e, fetchTimeout = Duration.ofSeconds(3))
                val started = System.nanoTime()
                val first = withContext(Dispatchers.Default) { withTimeout(2_000) { repo.observe(r1.hex()).first() } }
                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue(ms < 1_000, "first view after $ms ms")
                assertEquals(r1.hex(), first.focused.id)
                assertNull(first.root, "root ${r.hex()} is not stored")
                assertTrue(first.ancestors.isEmpty(), "a pending hop is never shown as unavailable")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a stored chain is complete in the first view and needs no ids request`() = runTest {
        val all = chainOf(5)
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                all.forEach { e.save(it) }
                val views = NostrThreadRepository(e).observe(all.last().hex()).untilFirst { it.repliesFetched }
                assertEquals(listOf("root", "r1", "r2", "r3", "r4"), views.first().ancestors.map { (it as Slot.Shown).note.content })
                assertTrue(relay.requests.none { "\"ids\"" in it }, "requests: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `with only the focused note stored the replies request names root and focused and brings the chain`() = runTest {
        val all = chainOf(5)
        val root = all.first()
        val focused = all.last()
        val under = listOf(reply("a1", focused, root, alice), reply("a2", focused, root, alice))
        relayFor(all + under).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(focused)
                val views = NostrThreadRepository(e).observe(focused.hex()).untilFirst { v ->
                    v.repliesFetched && v.ancestors.size == 5 && v.ancestors.all { it is Slot.Shown } &&
                        v.replies.map { it.content }.containsAll(listOf("a1", "a2"))
                }
                assertTrue(views.first().ancestors.isEmpty())
                assertEquals("root", views.last().root?.content)
                val tagRequest = relay.requests.first(::isRepliesRequest)
                assertTrue(root.hex() in tagRequest && focused.hex() in tagRequest, "replies request: $tagRequest")
                // round 1 (root and r4) at most; r1 … r3 came with the replies request (NIP-10: they tag the root)
                val idRequests = relay.requests.filter { "\"ids\"" in it }
                assertTrue(idRequests.size <= 1, "ids requests: $idRequests")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a chain without root tags completes through ids rounds`() = runTest {
        val top = EventBuilder.textNote("top").signWithKeys(alice)
        val one = EventBuilder.textNote("one").tags(listOf(Tag.parse(listOf("e", top.hex())))).signWithKeys(bob)
        val two = EventBuilder.textNote("two").tags(listOf(Tag.parse(listOf("e", one.hex())))).signWithKeys(alice)
        val focused = EventBuilder.textNote("focused").tags(listOf(Tag.parse(listOf("e", two.hex())))).signWithKeys(bob)
        relayFor(listOf(top, one, two, focused)).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(focused)
                val views = NostrThreadRepository(e).observe(focused.hex()).untilFirst { v -> v.ancestors.size == 3 && v.ancestors.all { it is Slot.Shown } }
                assertEquals(listOf("top", "one", "two"), views.last().ancestors.map { (it as Slot.Shown).note.content })
            } finally {
                e.close()
            }
        }
    }

    /** NIP-22 comment on [root] (uppercase scope) whose lowercase `e` is [parent]. */
    private fun comment(text: String, parent: Event, root: Event, keys: Keys = bob) = EventBuilder(Kind(1111u), text).tags(
        listOf(
            Tag.parse(listOf("E", root.hex(), "", root.author().toHex())), Tag.parse(listOf("K", "1")),
            Tag.parse(listOf("e", parent.hex(), "", parent.author().toHex())), Tag.parse(listOf("k", parent.kind().asU16().toString())),
        ),
    ).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)

    @Test
    fun `NIP-22 comments on the root and comments on those comments are replies`() = runTest {
        // backlog 31: Fediverse replies bridged by Mostr (ditto.pub) are kind 1111, the nested ones name the root only as `E`
        val root = root("root")
        val c1 = comment("c1", root, root)
        val c2 = comment("c2", c1, root, alice)
        relayFor(listOf(c1, c2)).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root)
                val views = NostrThreadRepository(e).observe(root.hex()).untilFirst { v -> v.repliesFetched && v.replies.map { it.content } == listOf("c1", "c2") }
                assertEquals(listOf("c1", "c2"), views.last().replies.map { it.content })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a reply that tags only the focused note is shown, a mention of it is not`() = runTest {
        val root = root("root")
        val focused = reply("focused", root, root)
        val onlyToFocused = EventBuilder.textNote("only to focused").tags(listOf(Tag.parse(listOf("e", focused.hex(), "", "reply")))).signWithKeys(alice)
        val quoting = EventBuilder.textNote("quoting").tags(listOf(Tag.parse(listOf("e", focused.hex(), "", "mention")))).signWithKeys(alice)
        relayFor(listOf(onlyToFocused, quoting)).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root); e.save(focused)
                val views = NostrThreadRepository(e).observe(focused.hex()).untilFirst { v -> v.repliesFetched && v.replies.any { it.id == onlyToFocused.hex() } }
                assertTrue(e.eventById(quoting.hex()) != null, "the quote was fetched and stored")
                assertTrue(views.last().replies.none { it.id == quoting.hex() }, "a mention is no reply")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `an own reply sent while the thread is open shows without the relay echoing it`() = runTest {
        // backlog 40: the relay confirms the write but sends the event to no subscription (or the echo came before the save)
        val root = root("root")
        SilentWsRelay(answerEose = true, acceptEvents = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root)
                val views = java.util.concurrent.CopyOnWriteArrayList<ThreadView>()
                val job = launch(Dispatchers.Default) { NostrThreadRepository(e).observe(root.hex()).collect { views += it } }
                assertTrue(awaitTrue { views.any { it.repliesFetched } }, "views: $views")
                // the thread's own jobs (deletions, read relays) are done: no later read can pick the reply up by chance
                withContext(Dispatchers.Default) { kotlinx.coroutines.delay(1_500) }
                val sent = withContext(Dispatchers.Default) { e.signAndSend(EventBuilder.textNoteReply("mine", root, root, null)) }
                assertTrue(sent.sentToAny, "sent: $sent")
                assertTrue(awaitTrue { views.lastOrNull()?.replies?.any { it.content == "mine" } == true }, "views: ${views.map { v -> v.replies.map { it.content } }}")
                job.cancelAndJoin()
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a live deletion of the focused note marks it gone`() = runTest {
        val root = root("root")
        val focused = reply("focused", root, root, alice)
        val deletion = EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", focused.hex())), Tag.parse(listOf("k", "1")))).signWithKeys(alice)
        // only the live request (it has `since`) gets the deletion
        SilentWsRelay(recordRequests = true, serveFor = { req -> if ("\"since\"" in req) listOf(deletion.asJson()) else emptyList() }).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root); e.save(focused)
                val views = NostrThreadRepository(e).observe(focused.hex()).untilFirst { it.gone }
                assertFalse(views.first().gone)
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `the live request starts a minute back, names root and focused, and leaving the thread closes it`() = runTest {
        val root = root("root")
        val focused = reply("focused", root, root)
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root); e.save(focused)
                val before = System.currentTimeMillis() / 1000
                val job = launch(Dispatchers.Default) { NostrThreadRepository(e).observe(focused.hex()).collect { } }
                assertTrue(awaitTrue { relay.requests.any { "\"since\"" in it } }, "requests: ${relay.requests}")
                val live = relay.requests.first { "\"since\"" in it }
                val since = Regex("\"since\":(\\d+)").find(live)!!.groupValues[1].toLong()
                assertTrue(since in (before - 61)..(before - 58), "since $since, now $before")
                assertTrue(root.hex() in live && focused.hex() in live, "live request: $live")
                assertTrue(awaitTrue { e.activeSubscriptionIds().isNotEmpty() })
                job.cancelAndJoin()
                assertTrue(awaitTrue { e.activeSubscriptionIds().isEmpty() }, "the live subscription stayed: ${e.activeSubscriptionIds()}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `offline a stored note shows at once and the walk ends with what it could not find`() = runTest {
        val root = root("root")
        val p = reply("p", root, root)
        val x = reply("x", p, root)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        try {
            e.save(x)
            val views = NostrThreadRepository(e).observe(x.hex()).untilFirst { it.ancestors.isNotEmpty() }
            assertTrue(views.first().ancestors.isEmpty())
            assertNull(views.first().root)
            assertEquals(listOf(Slot.Unavailable(root.hex()), Slot.Unavailable(p.hex())), views.last().ancestors)
        } finally {
            e.close()
        }
    }

    @Test
    fun `a withdrawn but stored root is in the first view as unavailable`() = runTest {
        val root = root("root")
        val focused = reply("focused", root, root)
        val deletion = EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", root.hex())), Tag.parse(listOf("k", "1")))).signWithKeys(alice)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
        try {
            e.save(deletion); e.save(root); e.save(focused) // the database accepts a note after its deletion request
            val first = NostrThreadRepository(e).observe(focused.hex()).first()
            assertEquals(listOf<Slot>(Slot.Unavailable(root.hex())), first.ancestors)
        } finally {
            e.close()
        }
    }

    @Test
    fun `another reply of the same root asks for its own replies`() = runTest {
        val root = root("root")
        val a = reply("a", root, root)
        val b = reply("b", root, root, alice)
        val onlyToB = EventBuilder.textNote("only to b").tags(listOf(Tag.parse(listOf("e", b.hex(), "", "reply")))).signWithKeys(alice)
        relayFor(listOf(onlyToB)).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                listOf(root, a, b).forEach { e.save(it) }
                val repo = NostrThreadRepository(e)
                repo.observe(a.hex()).untilFirst { it.repliesFetched }
                val views = repo.observe(b.hex()).untilFirst { v -> v.repliesFetched && v.replies.any { it.id == onlyToB.hex() } }
                assertEquals(b.hex(), views.last().focused.id)
                val asked = relay.requests.filter(::isRepliesRequest)
                assertEquals(2, asked.size, "requests: ${relay.requests}")
                assertTrue(b.hex() in asked.last(), "the second request names B: ${asked.last()}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `leaving during the replies fetch and reopening the same note joins it`() = runTest {
        val root = root("root")
        val first = reply("first", root, root)
        val second = reply("second", root, root, alice)
        val late = reply("late", root, root)
        SilentWsRelay(recordRequests = true, serveDelayMs = 1_000, serveFor = { req -> if (isRepliesRequest(req)) listOf(late.asJson()) else emptyList() }).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                listOf(root, first, second).forEach { e.save(it) }
                val repo = NostrThreadRepository(e)
                // the screen opens and is left at once, job R still waits for the relay
                withContext(Dispatchers.Default) { withTimeout(2_000) { repo.observe(first.hex()).first() } }
                val views = repo.observe(first.hex()).untilFirst { v -> v.replies.any { it.id == late.hex() } }
                assertEquals(first.hex(), views.first().focused.id)
                assertEquals(1, relay.requests.count(::isRepliesRequest), "requests: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a thread opened without a relay asks for the replies again once a relay is there`() = runTest {
        val root = root("root")
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())
            try {
                e.save(root)
                val repo = NostrThreadRepository(e)
                repo.observe(root.hex()).untilFirst { it.repliesFetched }
                e.addRelays(listOf(relay.url)); e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
                repo.observe(root.hex()).untilFirst { it.repliesFetched }
                assertTrue(relay.requests.any(::isRepliesRequest), "an unanswered fetch must not stamp the root: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a reopen within a minute shows the replies without asking again`() = runTest {
        val root = root("root")
        val one = reply("one", root, root)
        relayFor(listOf(root, one)).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root)
                val repo = NostrThreadRepository(e)
                repo.observe(root.hex()).untilFirst { it.repliesFetched && it.replies.any { r -> r.id == one.hex() } }
                // the first screen is closed (untilFirst ends its collection); the same root opens again, the gate is fresh
                val views = repo.observe(root.hex()).untilFirst { it.repliesFetched }
                assertTrue(views.last().replies.any { it.id == one.hex() }, "views: $views")
                assertEquals(1, relay.requests.count(::isRepliesRequest), "requests: ${relay.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `no view ever carries a partial ancestor chain`() = runTest {
        val all = chainOf(5)
        val focused = all.last()
        // all[2] (r2) is the missing middle hop; the relay has it
        relayFor(all).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                all.filter { it != all[2] }.forEach { e.save(it) }
                val views = NostrThreadRepository(e).observe(focused.hex()).untilFirst { v -> v.ancestors.size == 5 && v.ancestors.all { it is Slot.Shown } }
                views.forEach { v ->
                    assertTrue(v.ancestors.isEmpty() || (v.ancestors.size == 5 && v.ancestors.all { it is Slot.Shown }), "partial chain: ${v.ancestors}")
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `twenty live replies in a burst come in a few views, the last holds all of them`() = runTest {
        val root = root("root")
        // created now: the live request starts a minute back
        val burst = (1..20).map { EventBuilder.textNoteReply("live $it", root, root, null).signWithKeys(bob) }
        SilentWsRelay(recordRequests = true, serveFor = { req -> if ("\"since\"" in req) burst.map { it.asJson() } else emptyList() }).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(root)
                val views = NostrThreadRepository(e).observe(root.hex()).untilFirst { v -> v.replies.count { it.content.startsWith("live") } == 20 }
                val withBurst = views.count { v -> v.replies.any { it.content.startsWith("live") } }
                // one window read, one more for a signal during it, and the milestones of jobs R and D at most
                assertTrue(withBurst <= 4, "views with live replies: $withBurst")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a comment under an article opens with the article slot, and its siblings are no replies`() = runTest {
        val articleId = "d".repeat(64)
        val coordinate = "30023:${alice.publicKey().toHex()}:post"
        fun top(text: String, keys: Keys) = EventBuilder(Kind(1111u), text).tags(
            listOf(
                listOf("A", coordinate), listOf("E", articleId), listOf("K", "30023"), listOf("P", alice.publicKey().toHex()),
                listOf("a", coordinate), listOf("e", articleId), listOf("k", "30023"), listOf("p", alice.publicKey().toHex()),
            ).map { Tag.parse(it) },
        ).customCreatedAt(Timestamp.fromSecs((clock++).toULong())).signWithKeys(keys)
        val mine = top("mine", bob)
        val sibling = top("sibling", alice)
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(mine); e.save(sibling)
                val views = NostrThreadRepository(e).observe(mine.hex()).untilFirst { it.repliesFetched }
                val first = views.first()
                assertEquals(listOf<Slot>(Slot.Article(com.monostr.nostr.model.AddressRef(30023, alice.publicKey().toHex(), "post"))), first.ancestors)
                assertTrue(views.last().replies.isEmpty(), "replies: ${views.last().replies.map { it.content }}")
            } finally {
                e.close()
            }
        }
    }
}
