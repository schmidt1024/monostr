package com.monostr.app.ui

import com.monostr.app.ui.notifications.NotificationGroups
import com.monostr.app.ui.notifications.NotificationRow
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationGroupsTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val noteX = "1".repeat(64)
    private val noteY = "2".repeat(64)

    private fun item(id: Char, kind: NotificationKind, from: String?, at: Long, about: String? = noteX) =
        NotificationItem(id.toString().repeat(64), kind, from, at, if (kind == NotificationKind.REPLY) id.toString().repeat(64) else about, about, "", if (kind == NotificationKind.TIP) 1L else null)

    @Test
    fun `likes of one note become one group, newest actor first, one entry per actor`() {
        val rows = NotificationGroups.build(listOf(
            item('1', NotificationKind.REACTION, alice, 100),
            item('2', NotificationKind.REACTION, bob, 300),
            item('3', NotificationKind.REACTION, alice, 200), // alice again
        ))
        val group = rows.single() as NotificationRow.Group
        assertEquals(listOf(bob, alice), group.actors)
        assertEquals(2, group.count)
        assertEquals(300L, group.createdAt)
        assertEquals("REACTION:$noteX", group.key)
    }

    @Test
    fun `kind and note split groups, everything else stays single, all sorted by time`() {
        val rows = NotificationGroups.build(listOf(
            item('1', NotificationKind.REACTION, alice, 100),
            item('2', NotificationKind.REPOST, alice, 150),
            item('3', NotificationKind.REACTION, bob, 200, about = noteY),
            item('4', NotificationKind.REPLY, bob, 250),
            item('5', NotificationKind.TIP, alice, 300),
            item('6', NotificationKind.TIP, bob, 350),
            item('7', NotificationKind.MENTION, carol, 50, about = null),
            item('8', NotificationKind.QUOTE, carol, 60),
        ))
        assertEquals(listOf("6".repeat(64), "5".repeat(64), "4".repeat(64), "REACTION:$noteY", "REPOST:$noteX", "REACTION:$noteX", "8".repeat(64), "7".repeat(64)), rows.map { it.key })
        assertEquals(listOf(350L, 300L, 250L, 200L, 150L, 100L, 60L, 50L), rows.map { it.createdAt })
    }

    @Test
    fun `a reaction without a note or without a sender is a single row`() {
        val rows = NotificationGroups.build(listOf(
            item('1', NotificationKind.REACTION, alice, 100, about = null),
            item('2', NotificationKind.REPOST, null, 200),
        ))
        assertEquals(listOf("2".repeat(64), "1".repeat(64)), rows.map { it.key })
        assertEquals(2, rows.filterIsInstance<NotificationRow.Single>().size)
    }

    @Test
    fun `a hundred likers are one row that counts them all`() {
        val likes = (0 until 100).map { i -> NotificationItem(i.toString().padStart(64, '0'), NotificationKind.REACTION, i.toString().padStart(64, 'e'), 1000L + i, noteX, noteX, "+", null) }
        val group = NotificationGroups.build(likes).single() as NotificationRow.Group
        assertEquals(100, group.count)
        assertEquals("99".padStart(64, 'e'), group.actors.first())
    }
}
