package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.ui.notifications.NotificationsController
import org.junit.jupiter.api.Assertions.assertNull
import com.monostr.app.ui.notifications.target
import com.monostr.app.ui.notifications.NotificationTarget
import com.monostr.app.ui.notifications.Unread
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.AboutNote
import com.monostr.app.ui.notifications.NotificationRow
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsControllerTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun item(id: Char, kind: NotificationKind, from: String, at: Long, amount: Long? = null, text: String = "", about: String = "1".repeat(64)) =
        NotificationItem(id.toString().repeat(64), kind, from, at, if (kind == NotificationKind.REPLY) id.toString().repeat(64) else about, about, text, amount)

    private fun note(text: String) = Note("1".repeat(64), "9".repeat(64), text, 100, 1, null, null, null, emptyList())

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
        val notifications = FakeNotifications(items).apply { about = mapOf("1".repeat(64) to AboutNote.Found(note("my note"))) }
        val c = NotificationsController(notifications, profiles, settings, eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(listOf("2".repeat(64), "REACTION:${"1".repeat(64)}", "1".repeat(64), "5".repeat(64)), s.rows.map { it.row.key })
        val group = s.rows[1]
        assertEquals(listOf(bob, alice), (group.row as NotificationRow.Group).actors)
        assertEquals("Alice", group.names[alice])
        assertEquals(Profile.shortPubkey(bob), group.names[bob])
        assertEquals("my note", (group.about as AboutNote.Found).note.content)
        assertEquals("my note", (s.rows[0].about as AboutNote.Found).note.content) // the tip is about the same note
        // one lookup and one prefetch per emitted list (the fake's live() emits the local list, then the refreshed one), never per row
        assertEquals(2, notifications.notesCalls.size)
        assertTrue(notifications.notesCalls.all { it == setOf("1".repeat(64)) })
        assertEquals(2, profiles.prefetched.size)
        assertEquals(3, s.unread) // events 2, 3, 4 are newer than 150 — rows do not count
        c.markRead()
        advanceUntilIdle()
        assertEquals(1_000L, settings.readAtState.value)
        assertEquals(0, c.state.value.unread)
        notifications.updates.emit(items + item('6', NotificationKind.TIP, alice, 2_000, amount = 1))
        advanceUntilIdle()
        assertEquals(1, c.state.value.unread)
        assertEquals(2_000L, c.state.value.rows.first().row.createdAt)
    }

    @Test
    fun `rows are shown before the lookup finishes and get their notes afterwards`() = runTest {
        val notifications = FakeNotifications(listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+")))
        notifications.about = mapOf("1".repeat(64) to AboutNote.Found(note("mine")))
        notifications.notesGate = CompletableDeferred()
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        assertEquals(1, c.state.value.rows.size)
        assertNull(c.state.value.rows.single().about)
        notifications.notesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("mine", (c.state.value.rows.single().about as AboutNote.Found).note.content)
    }

    @Test
    fun `a like or repost of somebody else's note is no notification once the note is known, a like of mine stays`() = runTest {
        val me = "9".repeat(64)
        val strangers = Note("2".repeat(64), "8".repeat(64), "not mine", 100, 1, null, null, null, emptyList())
        val items = listOf(
            item('1', NotificationKind.REACTION, alice, 100),                      // about "1"*64, authored by me ("9"*64)
            item('2', NotificationKind.REPOST, alice, 200, about = "2".repeat(64)), // about a stranger's note
            item('3', NotificationKind.REPLY, bob, 300, text = "re", about = "2".repeat(64)), // a reply in a stranger's thread stays
        )
        val notifications = FakeNotifications(items).apply { about = mapOf("1".repeat(64) to AboutNote.Found(note("mine")), "2".repeat(64) to AboutNote.Found(strangers)) }
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager(), me = me) { 1_000 }
        c.start()
        advanceUntilIdle()
        assertEquals(listOf("3".repeat(64), "REACTION:${"1".repeat(64)}"), c.state.value.rows.map { it.row.key })
        assertEquals(2, c.state.value.unread) // the dropped repost does not count either
    }

    @Test
    fun `a newer list is shown at once while an older lookup is still pending`() = runTest {
        val first = listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))
        val notifications = FakeNotifications(first)
        notifications.about = mapOf("1".repeat(64) to AboutNote.Found(note("mine")))
        notifications.notesGate = CompletableDeferred()
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        notifications.updates.emit(first + item('4', NotificationKind.REPLY, bob, 300, text = "re"))
        advanceUntilIdle()
        // the relay has not answered: the new reply is on screen anyway (spec §4: the lookup must not hold up the list)
        assertEquals(listOf("4".repeat(64), "REACTION:${"1".repeat(64)}"), c.state.value.rows.map { it.row.key })
        notifications.notesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("mine", (c.state.value.rows[1].about as AboutNote.Found).note.content)
    }

    @Test
    fun `excerpts already known stay on screen while the next lookup runs`() = runTest {
        val first = listOf(item('3', NotificationKind.REACTION, alice, 200, text = "+"))
        val notifications = FakeNotifications(first)
        notifications.about = mapOf("1".repeat(64) to AboutNote.Found(note("mine")))
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        assertEquals("mine", (c.state.value.rows.single().about as AboutNote.Found).note.content)
        notifications.notesGate = CompletableDeferred()
        notifications.updates.emit(first + item('4', NotificationKind.REACTION, bob, 300, text = "+"))
        advanceUntilIdle()
        // the group grew; its excerpt did not flip back to "…"
        assertEquals("mine", (c.state.value.rows.single().about as AboutNote.Found).note.content)
        notifications.notesGate!!.complete(Unit)
    }

    @Test
    fun `a like whose note is unknown or not loadable is not shown, with a user set`() = runTest {
        val me = "9".repeat(64)
        val items = listOf(
            item('1', NotificationKind.REACTION, alice, 100),                       // about "1"*64 — mine once looked up
            item('2', NotificationKind.REACTION, alice, 200, about = "5".repeat(64)), // Missing: nobody can say whose note it is
            item('3', NotificationKind.TIP, bob, 300, amount = 5, about = "5".repeat(64)), // a validated receipt stays
        )
        val notifications = FakeNotifications(items).apply { about = mapOf("1".repeat(64) to AboutNote.Found(note("mine"))); notesGate = CompletableDeferred() }
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager(), me = me) { 1_000 }
        c.start()
        advanceUntilIdle()
        // before the lookup: nothing claims "your note" yet
        assertEquals(listOf("3".repeat(64)), c.state.value.rows.map { it.row.key })
        assertEquals(1, c.state.value.unread)
        notifications.notesGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("3".repeat(64), "REACTION:${"1".repeat(64)}"), c.state.value.rows.map { it.row.key })
        assertEquals(2, c.state.value.unread)
    }

    @Test
    fun `a withdrawn parent reaches the row as Withdrawn`() = runTest {
        val notifications = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100, text = "re")))
        notifications.about = mapOf("1".repeat(64) to AboutNote.Withdrawn)
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        assertEquals(AboutNote.Withdrawn, c.state.value.rows.single().about)
    }

    @Test
    fun `an empty local list stays in the loading state until the first relay refresh finished`() = runTest {
        val notifications = FakeNotifications(emptyList()).apply { refreshGate = CompletableDeferred() }
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.loading, "no premature 'no notifications yet'")
        notifications.refreshGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(c.state.value.loading)
        assertTrue(c.state.value.items.isEmpty())
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
        val notifications = FakeNotifications(items)
        val c = NotificationsController(notifications, profiles, FakeTipSettings(), eager(), muted = muted) { 1_000 }
        c.start(); advanceUntilIdle()
        assertEquals(listOf("3", "4"), c.state.value.items.map { it.id.take(1) })
        assertEquals(2, Unread.count(items, readAt = 0, muted = setOf(alice)))
        assertEquals(2, c.state.value.unread)
        val seen = ArrayList<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { Unread.live(notifications, FakeTipSettings().notificationsReadAt, muted).collect { seen += it } }
        advanceUntilIdle()
        assertEquals(2, seen.last())
        muted.value = emptySet(); advanceUntilIdle()
        assertEquals(4, c.state.value.items.size)
        assertEquals(4, seen.last())
        job.cancel()
    }

    @Test
    fun `the badge count follows the live flow without polling list()`() = runTest {
        val notifications = FakeNotifications(listOf(item('1', NotificationKind.REPLY, alice, 100)))
        val settings = FakeTipSettings()
        val seen = ArrayList<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { Unread.live(notifications, settings.notificationsReadAt).collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.last())
        notifications.updates.emit(listOf(item('1', NotificationKind.REPLY, alice, 100), item('2', NotificationKind.MENTION, bob, 200)))
        advanceUntilIdle()
        assertEquals(2, seen.last(), "a new live item raises the badge")
        settings.setNotificationsReadAt(150)
        advanceUntilIdle()
        assertEquals(1, seen.last(), "the read marker lowers it")
        job.cancel()
    }

    @Test
    fun `the badge leaves out likes and reposts of notes that are not the user's own`() = runTest {
        val mine = "1".repeat(64); val foreign = "2".repeat(64)
        val notifications = FakeNotifications(listOf(
            item('1', NotificationKind.REACTION, alice, 100, about = mine),
            item('2', NotificationKind.REACTION, bob, 200, about = foreign),
            item('3', NotificationKind.REPOST, bob, 300, about = foreign),
            item('4', NotificationKind.REPLY, bob, 400, about = foreign), // a reply to a foreign note mentions me: stays
        )).apply { ownIds = setOf(mine) }
        val seen = ArrayList<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { Unread.live(notifications, FakeTipSettings().notificationsReadAt).collect { seen += it } }
        advanceUntilIdle()
        assertEquals(2, seen.last())
        assertTrue(notifications.ownCalls.isNotEmpty() && notifications.ownCalls.all { it == setOf(mine, foreign) }, "only the liked and reposted ids are looked up: ${notifications.ownCalls}")
        job.cancel()
    }

    @Test
    fun `mentions inside notification texts get names`() = runTest {
        val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
        val carol = carolKeys.publicKey().toHex()
        val known = FakeProfiles(mapOf(carol to Profile(carol, "carol", "Carol", null, null, null)))
        val items = listOf(item('1', NotificationKind.REPLY, alice, 100, text = "hey nostr:${carolKeys.publicKey().toBech32()}"))
        val c = NotificationsController(FakeNotifications(items), known, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        assertEquals(mapOf(carol to "Carol"), c.names.value)
    }

    @Test
    fun `an anonymous tip has no name and asks for no profile, and each tip opens the right place`() = runTest {
        val anonymousProfile = NotificationItem("1".repeat(64), NotificationKind.TIP, null, 300, null, null, "", 5)
        val publicProfile = NotificationItem("2".repeat(64), NotificationKind.TIP, alice, 200, null, null, "", 5)
        val anonymousNote = NotificationItem("3".repeat(64), NotificationKind.TIP, null, 100, "9".repeat(64), "9".repeat(64), "", 5)
        val c = NotificationsController(FakeNotifications(listOf(anonymousProfile, publicProfile, anonymousNote)), profiles, FakeTipSettings(), eager()) { 1_000 }
        c.start()
        advanceUntilIdle()
        val rows = c.state.value.rows
        assertTrue(rows[0].names.isEmpty())
        assertEquals("Alice", rows[1].names[alice])
        assertTrue(rows[2].names.isEmpty())
        assertEquals(setOf(alice), profiles.prefetched.flatten().toSet(), "no profile lookup for a sender nobody knows")
        assertEquals(NotificationTarget.None, (rows[0].row as NotificationRow.Single).item.target)
        assertEquals(NotificationTarget.Profile(alice), (rows[1].row as NotificationRow.Single).item.target)
        assertEquals(NotificationTarget.Thread("9".repeat(64)), (rows[2].row as NotificationRow.Single).item.target)
    }
}
