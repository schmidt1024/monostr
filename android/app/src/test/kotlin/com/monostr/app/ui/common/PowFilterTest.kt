package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.note
import com.monostr.app.ui.notifications.NotificationFilter
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PowFilterTest {
    private val friend = "f".repeat(64)
    private val stranger = "b".repeat(64)
    private val pow = MutableStateFlow(PowFilter(8, setOf(friend)))
    private val v = NoteVisibility(NoteDeletions(), MutableStateFlow(emptySet()), pow)
    private fun ui(n: Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.author))
    private fun ids(l: List<NoteUi>) = l.map { it.note.id.first() }
    private val weak = ui(note('w', author = stranger))
    private val strong = ui(note('s', author = stranger).copy(powBits = 8))
    private val fromFriend = ui(note('f', author = friend))

    @Test
    fun `below the minimum a stranger is hidden, a follow and enough work stay`() {
        assertEquals(listOf('s', 'f'), ids(v.visible(listOf(weak, strong, fromFriend))))
    }

    @Test
    fun `off hides nothing`() {
        pow.value = PowFilter.OFF
        assertEquals(listOf('w', 's', 'f'), ids(v.visible(listOf(weak, strong, fromFriend))))
    }

    @Test
    fun `the focused note, its ancestors and bookmarks stay`() {
        assertEquals(listOf('w'), ids(v.visible(listOf(weak), keep = weak.note.id)))
        assertEquals(listOf('w'), ids(v.visible(listOf(weak), powKeep = setOf(weak.note.id))))
        assertEquals(listOf('w'), ids(v.visibleAnyPow(listOf(weak))))
    }

    @Test
    fun `only a lower minimum counts as relaxed`() = runTest {
        val fired = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { v.relaxed.first(); fired.complete(Unit) }
        pow.value = PowFilter(16, setOf(friend)); testScheduler.runCurrent()
        assertEquals(false, fired.isCompleted)
        pow.value = PowFilter(8, setOf(friend)); testScheduler.runCurrent()
        assertEquals(true, fired.isCompleted)
        job.cancel()
    }

    private fun item(kind: NotificationKind, from: String?, bits: Int = 0) = NotificationItem(kind.name, kind, from, 1, null, null, "", null, bits)

    @Test
    fun `notifications hide weak replies, mentions and quotes of strangers, never tips, likes or reposts`() {
        val items = listOf(
            item(NotificationKind.REPLY, stranger), item(NotificationKind.MENTION, stranger), item(NotificationKind.QUOTE, stranger),
            item(NotificationKind.TIP, stranger), item(NotificationKind.REACTION, stranger), item(NotificationKind.REPOST, stranger),
        )
        assertEquals(listOf(NotificationKind.TIP, NotificationKind.REACTION, NotificationKind.REPOST), NotificationFilter.visible(items, emptySet(), pow.value).map { it.kind })
        val ok = listOf(item(NotificationKind.REPLY, friend), item(NotificationKind.REPLY, stranger, bits = 8))
        assertEquals(ok, NotificationFilter.visible(ok, emptySet(), pow.value))
    }
}
