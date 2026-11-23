package com.monostr.app.ui.notifications

import com.monostr.app.ui.note
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationFilterTest {
    private val r = "a".repeat(64)
    private val threads = setOf(r)
    private fun item(kind: NotificationKind, noteId: String? = null, aboutId: String? = null) =
        NotificationItem(kind.name + noteId + aboutId, kind, "f".repeat(64), 10, noteId, aboutId, "", null)

    @Test
    fun replyIntoMutedThread_dropped() {
        val i = item(NotificationKind.REPLY, noteId = "n".repeat(64), aboutId = r)
        assertEquals(emptyList<NotificationItem>(), NotificationFilter.visible(listOf(i), emptySet(), threads = threads))
    }

    @Test
    fun likeOnDeepReply_dropped() {
        val r2 = "b".repeat(64)
        val i = item(NotificationKind.REACTION, aboutId = r2)
        val about = mapOf(r2 to AboutNote.Found(note('b', replyTo = "c".repeat(64), root = r)))
        assertEquals(emptyList<NotificationItem>(), NotificationFilter.visible(listOf(i), emptySet(), threads = threads, about = about))
    }

    @Test
    fun replyWithMissingParent_droppedByOwnRoot() {
        val i = item(NotificationKind.REPLY, noteId = "n".repeat(64), aboutId = "b".repeat(64)).copy(rootId = r)
        assertEquals(emptyList<NotificationItem>(), NotificationFilter.visible(listOf(i), emptySet(), threads = threads, about = emptyMap()))
    }

    @Test
    fun mentionOutsideThread_kept() {
        val i = item(NotificationKind.MENTION, noteId = "n".repeat(64), aboutId = "e".repeat(64))
        assertEquals(listOf(i), NotificationFilter.visible(listOf(i), emptySet(), threads = threads))
    }

    @Test
    fun tipOnMutedThread_kept() {
        val i = item(NotificationKind.TIP, aboutId = r)
        assertEquals(listOf(i), NotificationFilter.visible(listOf(i), emptySet(), threads = threads))
    }

    @Test
    fun unreadCount_ignoresMutedThread() {
        val items = listOf(item(NotificationKind.REPLY, noteId = "n".repeat(64), aboutId = r), item(NotificationKind.MENTION, noteId = "m".repeat(64)))
        assertEquals(1, Unread.count(items, readAt = 0, threads = threads))
    }
}
