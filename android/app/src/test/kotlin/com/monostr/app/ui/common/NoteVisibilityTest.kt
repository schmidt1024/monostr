package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.note
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoteVisibilityTest {
    private val root = note('a')
    private val reply = note('b', replyTo = root.id, root = root.id)
    private val other = note('c')
    private val repostOfReply = note('d').copy(repostOf = reply)
    private val v = NoteVisibility(NoteDeletions(), MutableStateFlow(emptySet()), mutedThreads = MutableStateFlow(setOf(root.id)))
    private fun ui(n: Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.author))
    private fun ids(l: List<NoteUi>) = l.map { it.note.id.first() }
    private val all = listOf(ui(root), ui(reply), ui(other))

    @Test
    fun mutedThread_hidesRootAndReplies() = assertEquals(listOf('c'), ids(v.visible(all)))

    @Test
    fun mutedThread_hidesRepostOfReply() = assertEquals(listOf('c'), ids(v.visible(listOf(ui(other), ui(repostOfReply)))))

    @Test
    fun withoutThreadMute_showsAll() = assertEquals(listOf('a', 'b', 'c'), ids(v.withoutThreadMute().visible(all)))

    @Test
    fun keep_survivesThreadMute() = assertEquals(listOf('b', 'c'), ids(v.visible(all, keep = reply.id)))

    @Test
    fun visibleAnyPow_ignoresThreadMute() = assertEquals(listOf('a', 'b', 'c'), ids(v.visibleAnyPow(all)))

    @Test
    fun mutedOnly_ignoresThreadMute() = assertEquals(emptyList<NoteUi>(), v.mutedOnly(all))

    @Test
    fun ownNote_staysVisibleInMutedThread() {
        val own = NoteVisibility(NoteDeletions(), MutableStateFlow(emptySet()), mutedThreads = MutableStateFlow(setOf(root.id)), ownPubkey = "f".repeat(64))
        val mine = note('e', author = "f".repeat(64), replyTo = root.id, root = root.id)
        assertEquals(listOf('e', 'c'), ids(own.visible(listOf(ui(root), ui(reply), ui(mine), ui(other)))))
    }

    @Test
    fun changes_firesOnThreadMute() = kotlinx.coroutines.test.runTest {
        val threads = MutableStateFlow<Set<String>>(emptySet())
        val vis = NoteVisibility(NoteDeletions(), MutableStateFlow(emptySet()), mutedThreads = threads)
        val fired = async(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vis.changes.first() }
        threads.value = setOf(root.id)
        fired.await()
    }
}
