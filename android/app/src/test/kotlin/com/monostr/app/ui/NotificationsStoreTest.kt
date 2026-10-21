package com.monostr.app.ui

import com.monostr.app.ui.notifications.NotificationRow
import com.monostr.app.ui.notifications.NotificationTarget
import com.monostr.app.ui.notifications.NotificationsStore
import com.monostr.app.ui.notifications.target
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

/** Spec perf 3.6: one store per session for the badge, the screen and the tip check; rows from local data at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsStoreTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val me = "9".repeat(64)
    private val mine = "1".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))
    private var clock = 10_000L

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.store(
        n: FakeNotifications,
        settings: FakeTipSettings = FakeTipSettings(),
        muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        me: String? = null,
        known: FakeProfiles = profiles,
    ) = NotificationsStore(n, known, settings, eager(), muted = muted, me = me, now = { clock })

    @Test
    fun `stored rows and the live REQ do not wait for the startup gate, the first refresh does`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100)))
        val gate = ManualGate()
        val s = NotificationsStore(n, profiles, FakeTipSettings(), eager(), now = { clock }, startupGate = gate)
        s.start(); advanceUntilIdle()
        assertEquals(1, s.state.value.rows.size)
        assertEquals(1, n.subscriptions)
        assertTrue(n.refreshCalls.isEmpty(), "refreshed before the gate")
        gate.open("test"); advanceUntilIdle()
        assertEquals(1, n.refreshCalls.size)
    }

    @Test
    fun `a user pull opens the startup gate instead of waiting for it`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100)))
        val gate = ManualGate()
        val s = NotificationsStore(n, profiles, FakeTipSettings(), eager(), now = { clock }, startupGate = gate)
        s.start(); advanceUntilIdle()
        s.pull(); advanceUntilIdle()
        assertTrue(gate.isOpen)
        assertTrue(n.refreshCalls.isNotEmpty(), "the pull refreshed without waiting")
    }

    private fun item(id: Char, kind: NotificationKind, from: String, at: Long, amount: Long? = null, text: String = "", about: String = mine) =
        NotificationItem(id.toString().repeat(64), kind, from, at, if (kind == NotificationKind.REPLY) id.toString().repeat(64) else about, about, text, amount)

    private fun note(text: String, id: String = mine, author: String = me) = Note(id, author, text, 100, 1, null, null, null, emptyList())

    private fun NotificationsStore.keys() = state.value.rows.map { it.row.key }

    @Test
    fun `rows carry names, pictures and the referenced notes, unread counts events against the read marker`() = runTest {
        val items = listOf(
            item('1', NotificationKind.REPLY, alice, 100, text = "re"),
            item('2', NotificationKind.TIP, bob, 300, amount = 5_000_000_000),
            item('3', NotificationKind.REACTION, alice, 200, text = "+"),
            item('4', NotificationKind.REACTION, bob, 250, text = "+"),
            item('5', NotificationKind.MENTION, bob, 50),
        )
        val settings = FakeTipSettings().apply { readAtState.value = 150 }
        val n = FakeNotifications(items).apply { about = mapOf(mine to AboutNote.Found(note("my note"))) }
        val s = store(n, settings)
        s.start()
        advanceUntilIdle()
        val st = s.state.value
        assertFalse(st.loading)
        assertEquals(listOf("2".repeat(64), "REACTION:$mine", "1".repeat(64), "5".repeat(64)), s.keys())
        val group = st.rows[1]
        assertEquals(listOf(bob, alice), (group.row as NotificationRow.Group).actors)
        assertEquals("Alice", group.names[alice])
        assertEquals(Profile.shortPubkey(bob), group.names[bob])
        assertEquals("my note", (group.about as AboutNote.Found).note.content)
        assertEquals("my note", (st.rows[0].about as AboutNote.Found).note.content) // the tip is about the same note
        // one parent lookup for the session, not one per emission or row
        assertEquals(listOf(setOf(mine)), n.notesCalls)
        assertEquals(3, st.unread)
        assertEquals(3, s.unread.value)
        s.markRead()
        advanceUntilIdle()
        assertEquals(10_000L, settings.readAtState.value)
        assertEquals(0, s.unread.value)
        n.arrive(items + item('6', NotificationKind.TIP, alice, 20_000, amount = 1))
        advanceUntilIdle()
        assertEquals(1, s.unread.value)
        assertEquals(20_000L, s.state.value.rows.first().row.createdAt)
    }

    @Test
    fun `rows are shown before the parent lookup finishes and get their notes afterwards`() = runTest {
        val n = FakeNotifications(listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))).apply {
            about = mapOf(mine to AboutNote.Found(note("mine"))); notesGate = CompletableDeferred()
        }
        val s = store(n)
        s.start()
        advanceUntilIdle()
        assertEquals(1, s.state.value.rows.size)
        assertNull(s.state.value.rows.single().about)
        n.notesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("mine", (s.state.value.rows.single().about as AboutNote.Found).note.content)
    }

    @Test
    fun `a stored parent shows at once, without a relay lookup`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100, text = "re"))).apply {
            stored = mapOf(mine to AboutNote.Found(note("question"))); notesGate = CompletableDeferred()
        }
        val s = store(n)
        s.start()
        advanceUntilIdle()
        assertEquals("question", (s.state.value.rows.single().about as AboutNote.Found).note.content)
        assertTrue(n.notesCalls.isEmpty(), "asked the relays for a stored note: ${n.notesCalls}")
    }

    @Test
    fun `a like of my note shows before its note is looked up`() = runTest {
        // Review Focus: "is this my note" comes from the local own-notes query, the relays may stay silent
        val n = FakeNotifications(listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))).apply {
            ownIds = setOf(mine); notesGate = CompletableDeferred()
        }
        val s = store(n, me = me)
        s.start()
        advanceUntilIdle()
        assertEquals(listOf("REACTION:$mine"), s.keys())
        assertNull(s.state.value.rows.single().about)
        assertEquals(1, s.unread.value)
    }

    @Test
    fun `a like or repost of somebody else's note is no notification once the note is known, a like of mine stays`() = runTest {
        val strangers = note("not mine", id = "2".repeat(64), author = "8".repeat(64))
        val items = listOf(
            item('1', NotificationKind.REACTION, alice, 100),                      // about mine
            item('2', NotificationKind.REPOST, alice, 200, about = "2".repeat(64)), // about a stranger's note
            item('3', NotificationKind.REPLY, bob, 300, text = "re", about = "2".repeat(64)), // a reply in a stranger's thread stays
        )
        val n = FakeNotifications(items).apply {
            ownIds = emptySet(); about = mapOf(mine to AboutNote.Found(note("mine")), "2".repeat(64) to AboutNote.Found(strangers))
        }
        val s = store(n, me = me)
        s.start()
        advanceUntilIdle()
        assertEquals(listOf("3".repeat(64), "REACTION:$mine"), s.keys())
        assertEquals(2, s.unread.value) // the dropped repost does not count either
    }

    @Test
    fun `a live event does not cancel the parent lookup that runs`() = runTest {
        // spec perf 3.6 / F4: collectLatest cancelled the lookup on every event, groups might never appear
        val first = listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))
        val n = FakeNotifications(first).apply { about = mapOf(mine to AboutNote.Found(note("mine"))); notesGate = CompletableDeferred() }
        val s = store(n)
        s.start()
        advanceUntilIdle()
        n.arrive(first + item('4', NotificationKind.REPLY, bob, 300, text = "re"))
        advanceUntilIdle()
        // the relay has not answered: the new reply is on screen anyway
        assertEquals(listOf("4".repeat(64), "REACTION:$mine"), s.keys())
        n.notesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("mine", (s.state.value.rows[1].about as AboutNote.Found).note.content)
        assertEquals(listOf(setOf(mine)), n.notesCalls, "the lookup was restarted")
    }

    @Test
    fun `excerpts already known stay on screen while the list changes`() = runTest {
        val first = listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))
        val n = FakeNotifications(first).apply { about = mapOf(mine to AboutNote.Found(note("mine"))) }
        val s = store(n)
        s.start()
        advanceUntilIdle()
        n.notesGate = CompletableDeferred()
        n.arrive(first + item('4', NotificationKind.REACTION, bob, 300, text = "+"))
        advanceUntilIdle()
        // the group grew; its excerpt did not flip back to "…"
        assertEquals("mine", (s.state.value.rows.single().about as AboutNote.Found).note.content)
        n.notesGate!!.complete(Unit)
    }

    @Test
    fun `a like whose note is unknown or not loadable is not shown, with a user set`() = runTest {
        val items = listOf(
            item('1', NotificationKind.REACTION, alice, 100),                        // about mine, only known after the lookup
            item('2', NotificationKind.REACTION, alice, 200, about = "5".repeat(64)), // Missing: nobody can say whose note it is
            item('3', NotificationKind.TIP, bob, 300, amount = 5, about = "5".repeat(64)), // a validated receipt stays
        )
        val n = FakeNotifications(items).apply { ownIds = emptySet(); about = mapOf(mine to AboutNote.Found(note("mine"))); notesGate = CompletableDeferred() }
        val s = store(n, me = me)
        s.start()
        advanceUntilIdle()
        assertEquals(listOf("3".repeat(64)), s.keys())
        assertEquals(1, s.unread.value)
        n.notesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("3".repeat(64), "REACTION:$mine"), s.keys())
        assertEquals(2, s.unread.value)
    }

    @Test
    fun `a withdrawn parent reaches the row as Withdrawn`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100, text = "re"))).apply { about = mapOf(mine to AboutNote.Withdrawn) }
        val s = store(n)
        s.start()
        advanceUntilIdle()
        assertEquals(AboutNote.Withdrawn, s.state.value.rows.single().about)
    }

    @Test
    fun `an empty local list shows the empty state at once while the first refresh asks the relays`() = runTest {
        // spec perf 3.6: the spinner stayed until the relays answered (8 s fetch + 3 s deletions on a silent relay)
        val n = FakeNotifications(emptyList()).apply { refreshGate = CompletableDeferred() }
        val s = store(n)
        s.open()
        advanceUntilIdle()
        assertFalse(s.state.value.loading, "no spinner while the relays are asked")
        assertTrue(s.state.value.rows.isEmpty())
        assertTrue(s.state.value.syncing)
        assertFalse(s.state.value.complete)
        n.items = listOf(item('1', NotificationKind.REPLY, alice, 100))
        n.refreshGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(s.state.value.syncing)
        assertEquals(1, s.state.value.items.size)
        assertTrue(s.state.value.complete)
        assertEquals(listOf<Long?>(null), n.refreshCalls, "nothing stored: everything is asked for")
    }

    @Test
    fun `tips of a muted account are kept, replies and reactions are not, and the badge agrees`() = runTest {
        val items = listOf(
            item('1', NotificationKind.REPLY, alice, 3000, text = "hi"),
            item('2', NotificationKind.REACTION, alice, 2000, text = "+"),
            item('3', NotificationKind.TIP, alice, 1000, amount = 5_000_000_000),
            item('4', NotificationKind.MENTION, bob, 500, text = "yo"),
        )
        val muted = MutableStateFlow(setOf(alice))
        val s = store(FakeNotifications(items), muted = muted)
        s.start(); advanceUntilIdle()
        assertEquals(listOf("3", "4"), s.state.value.items.map { it.id.take(1) })
        assertEquals(2, com.monostr.app.ui.notifications.Unread.count(items, readAt = 0, muted = setOf(alice)))
        assertEquals(2, s.unread.value)
        muted.value = emptySet(); advanceUntilIdle()
        assertEquals(4, s.state.value.items.size)
        assertEquals(4, s.unread.value)
    }

    @Test
    fun `the badge follows live events and the read marker`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100)))
        val settings = FakeTipSettings()
        val s = store(n, settings)
        s.start(); advanceUntilIdle()
        assertEquals(1, s.unread.value)
        n.arrive(listOf(item('1', NotificationKind.REPLY, alice, 100), item('2', NotificationKind.MENTION, bob, 200)))
        advanceUntilIdle()
        assertEquals(2, s.unread.value, "a new live item raises the badge")
        settings.setNotificationsReadAt(150)
        advanceUntilIdle()
        assertEquals(1, s.unread.value, "the read marker lowers it")
    }

    @Test
    fun `the badge leaves out likes and reposts of notes that are not the user's own`() = runTest {
        val foreign = "2".repeat(64)
        val n = FakeNotifications(listOf(
            item('1', NotificationKind.REACTION, alice, 100, about = mine),
            item('2', NotificationKind.REACTION, bob, 200, about = foreign),
            item('3', NotificationKind.REPOST, bob, 300, about = foreign),
            item('4', NotificationKind.REPLY, bob, 400, about = foreign), // a reply to a foreign note mentions me: stays
        )).apply { ownIds = setOf(mine) }
        val s = store(n, me = me)
        s.start(); advanceUntilIdle()
        assertEquals(2, s.unread.value)
        assertTrue(n.ownCalls.isNotEmpty() && n.ownCalls.first() == setOf(mine, foreign), "only the liked and reposted ids are looked up: ${n.ownCalls}")
    }

    @Test
    fun `the badge, the screen and a second open share one pipeline`() = runTest {
        // spec perf 3.6 / F5: the badge ran its own live() and refresh, restarted on every return to the feed
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 9_000)))
        val s = store(n)
        assertTrue(s.start())   // the feed's badge
        s.open()                // the screen
        s.open()                // the screen again, a minute later or not
        assertFalse(s.start())
        advanceUntilIdle()
        assertEquals(1, n.subscriptions)
        assertEquals(1, n.refreshCalls.size)
    }

    @Test
    fun `a reopen shows the last rows at once and re-reads the database behind them`() = runTest {
        // Review Focus: a note stored meanwhile by another path (a thread, the tip check) shows on the next open
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100)))
        val s = store(n)
        s.open(); advanceUntilIdle()
        n.listGate = CompletableDeferred()
        n.items = n.items + item('2', NotificationKind.REPLY, bob, 200)
        s.open(); runCurrent()
        assertFalse(s.state.value.loading)
        assertEquals(listOf("1".repeat(64)), s.keys(), "the last rows, at once")
        n.listGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("2".repeat(64), "1".repeat(64)), s.keys())
    }

    @Test
    fun `reloadLocal re-reads the database of a started store only`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.TIP, alice, 100, amount = 5)))
        val s = store(n)
        s.reloadLocal(); advanceUntilIdle()
        assertEquals(0, n.listCalls, "a worker process without a screen starts nothing")
        s.start(); advanceUntilIdle()
        val before = n.listCalls
        n.items = n.items + item('2', NotificationKind.TIP, bob, 200, amount = 7)
        s.reloadLocal(); advanceUntilIdle()
        assertEquals(before + 1, n.listCalls)
        assertEquals(2, s.state.value.items.size)
    }

    @Test
    fun `refreshes run at most every two minutes unless pulled, from the newest shown minus ten minutes`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 9_000)))
        val s = store(n)
        s.open(); advanceUntilIdle()
        assertEquals(listOf<Long?>(8_400), n.refreshCalls)
        clock += 60
        s.open(); advanceUntilIdle()
        assertEquals(1, n.refreshCalls.size, "not again within two minutes")
        clock += 61
        s.open(); advanceUntilIdle()
        assertEquals(listOf<Long?>(8_400, 8_400), n.refreshCalls)
        s.pull(); advanceUntilIdle()
        assertEquals(3, n.refreshCalls.size, "a pull always asks")
    }

    @Test
    fun `a future created_at does not move since past now`() = runTest {
        // Review Focus: a forged date would otherwise silence every later refresh
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 99_999_999), item('2', NotificationKind.REPLY, alice, 5_000)))
        val s = store(n)
        s.open(); advanceUntilIdle()
        assertEquals(listOf<Long?>(4_400), n.refreshCalls, "the forged date is left out, the newest real one counts")
    }

    @Test
    fun `only future created_at stored asks for everything`() = runTest {
        // a forged date alone must not hide what arrived between sessions
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 99_999_999)))
        val s = store(n)
        s.open(); advanceUntilIdle()
        assertEquals(listOf<Long?>(null), n.refreshCalls)
    }

    @Test
    fun `pulling down refreshes from the relays and shows what came in`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 5_000)))
        val s = store(n)
        s.open(); advanceUntilIdle()
        assertEquals(1, s.state.value.items.size)
        val gate = CompletableDeferred<Unit>()
        n.refreshGate = gate
        n.items = n.items + item('2', NotificationKind.REPLY, alice, 9_000)
        s.pull(); runCurrent()
        assertTrue(s.state.value.refreshing, "the indicator shows while the relays answer")
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(s.state.value.refreshing)
        assertEquals(2, s.state.value.items.size, "the new reply is shown without waiting for the live subscription")
        assertEquals(listOf<Long?>(4_400, 4_400), n.refreshCalls)
    }

    @Test
    fun `a second pull while one runs does not fetch twice`() = runTest {
        val n = FakeNotifications()
        val s = store(n)
        s.open(); advanceUntilIdle()
        n.refreshGate = CompletableDeferred()
        s.pull(); s.pull(); runCurrent()
        assertEquals(2, n.refreshCalls.size, "start + one pull")
    }

    @Test
    fun `a profile that lands later shows in the rows`() = runTest {
        val n = FakeNotifications(listOf(item('1', NotificationKind.REPLY, bob, 100, text = "re")))
        val s = store(n)
        s.start(); advanceUntilIdle()
        assertEquals(Profile.shortPubkey(bob), s.state.value.rows.single().names[bob])
        profiles.land(Profile(bob, "bob", "Bob", "https://x/b.png", null, null))
        advanceUntilIdle()
        assertEquals("Bob", s.state.value.rows.single().names[bob])
        assertEquals("https://x/b.png", s.state.value.rows.single().pictures[bob])
    }

    @Test
    fun `mentions inside notification texts get names`() = runTest {
        val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
        val carol = carolKeys.publicKey().toHex()
        val known = FakeProfiles(mapOf(carol to Profile(carol, "carol", "Carol", null, null, null)))
        val items = listOf(item('1', NotificationKind.REPLY, alice, 100, text = "hey nostr:${carolKeys.publicKey().toBech32()}"))
        val s = store(FakeNotifications(items), known = known)
        s.start()
        advanceUntilIdle()
        assertEquals(mapOf(carol to "Carol"), s.names.value)
    }

    @Test
    fun `an anonymous tip has no name and asks for no profile, and each tip opens the right place`() = runTest {
        val anonymousProfile = NotificationItem("1".repeat(64), NotificationKind.TIP, null, 300, null, null, "", 5)
        val publicProfile = NotificationItem("2".repeat(64), NotificationKind.TIP, alice, 200, null, null, "", 5)
        val anonymousNote = NotificationItem("3".repeat(64), NotificationKind.TIP, null, 100, "9".repeat(64), "9".repeat(64), "", 5)
        val s = store(FakeNotifications(listOf(anonymousProfile, publicProfile, anonymousNote)))
        s.start()
        advanceUntilIdle()
        val rows = s.state.value.rows
        assertTrue(rows[0].names.isEmpty())
        assertEquals("Alice", rows[1].names[alice])
        assertTrue(rows[2].names.isEmpty())
        assertEquals(setOf(alice), profiles.getAllCalls.flatten().toSet(), "no profile lookup for a sender nobody knows")
        assertEquals(NotificationTarget.None, (rows[0].row as NotificationRow.Single).item.target)
        assertEquals(NotificationTarget.Profile(alice), (rows[1].row as NotificationRow.Single).item.target)
        assertEquals(NotificationTarget.Thread("9".repeat(64)), (rows[2].row as NotificationRow.Single).item.target)
    }

    @Test
    fun `an open marks the first rows and the complete list`() = runTest {
        withPerfLines {
            val n = FakeNotifications(listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))).apply {
                about = mapOf(mine to AboutNote.Found(note("mine"))); notesGate = CompletableDeferred()
            }
            val s = store(n)
            s.open(); runCurrent()
            assertEquals(listOf("notifications first rows"), map { it.substringAfter("ms ") })
            n.notesGate!!.complete(Unit); runCurrent()
            assertEquals(listOf("notifications first rows", "notifications complete"), map { it.substringAfter("ms ") })
        }
    }

    @Test
    fun `a parent no relay had is not looked up again on each live event and complete stays`() = runTest {
        // ruling R7: only an id never seen counts as unknown; a remembered Missing does not flip complete back and forth
        val first = listOf(item('1', NotificationKind.REPLY, alice, 100, text = "re", about = "5".repeat(64)))
        val n = FakeNotifications(first)
        val s = store(n)
        s.open(); advanceUntilIdle()
        assertEquals(AboutNote.Missing, s.state.value.rows.single().about)
        assertTrue(s.state.value.complete)
        val seen = ArrayList<Boolean>()
        val watch = eager().launch { s.state.collect { seen += it.complete } }
        n.arrive(first + item('2', NotificationKind.REPLY, bob, 200, text = "me too", about = "5".repeat(64)))
        advanceUntilIdle()
        watch.cancel()
        assertEquals(listOf(setOf("5".repeat(64))), n.notesCalls, "the miss was asked for again")
        assertTrue(seen.all { it }, "complete flipped: $seen")
        assertEquals(2, s.state.value.rows.size)
    }
}
