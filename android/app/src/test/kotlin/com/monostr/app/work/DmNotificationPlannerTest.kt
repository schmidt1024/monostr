package com.monostr.app.work

import com.monostr.app.R
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.ui.common.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DmNotificationPlannerTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun msg(id: String, peer: String, text: String, at: Long, outgoing: Boolean = false, receivedAt: Long = at) =
        DmMessage(id, peer, outgoing, text, at, receivedAt, if (outgoing) DmStatus.SENT else DmStatus.RECEIVED, "w$id")

    @Test
    fun `messages of a muted peer produce no notification`() {
        val fresh = listOf(msg("1", alice, "from muted", 100), msg("2", bob, "from bob", 150))
        val plan = DmNotificationPlanner.plan(fresh, mapOf(alice to "Alice", bob to "Bob"), pending = 0, showName = true, showText = true, muted = setOf(alice))
        assertEquals(listOf(bob), plan.map { it.peer })
    }

    @Test
    fun `one notification per peer with the newest text`() {
        val fresh = listOf(msg("1", alice, "first", 100), msg("2", alice, "second", 200), msg("3", bob, "hi bob", 150))
        val plan = DmNotificationPlanner.plan(fresh, mapOf(alice to "Alice", bob to "Bob"), pending = 0, showName = true, showText = true)
        assertEquals(2, plan.size)
        val a = plan.single { it.peer == alice }
        assertEquals(UiText.Res(R.string.notif_dm_title, listOf("Alice")), a.title)
        assertEquals(UiText.Plain("second"), a.text)
        assertEquals(alice.hashCode(), a.id)
        val b = plan.single { it.peer == bob }
        assertEquals(UiText.Res(R.string.notif_dm_title, listOf("Bob")), b.title)
        assertEquals(UiText.Plain("hi bob"), b.text)
        assertEquals(bob.hashCode(), b.id)
    }

    @Test
    fun `name hidden and text hidden follow the switches`() {
        val fresh = listOf(msg("1", alice, "secret", 100))
        val hidden = DmNotificationPlanner.plan(fresh, mapOf(alice to "Alice"), pending = 0, showName = false, showText = false).single()
        assertEquals(UiText.Res(R.string.notif_dm_title_anonymous), hidden.title)
        assertEquals(UiText.Res(R.string.notif_dm_tap), hidden.text)
        val nameOnly = DmNotificationPlanner.plan(fresh, mapOf(alice to "Alice"), pending = 0, showName = true, showText = false).single()
        assertEquals(UiText.Res(R.string.notif_dm_title, listOf("Alice")), nameOnly.title)
        assertEquals(UiText.Res(R.string.notif_dm_tap), nameOnly.text)
        val textOnly = DmNotificationPlanner.plan(fresh, mapOf(alice to "Alice"), pending = 0, showName = false, showText = true).single()
        assertEquals(UiText.Res(R.string.notif_dm_title_anonymous), textOnly.title)
        assertEquals(UiText.Plain("secret"), textOnly.text)
    }

    @Test
    fun `pending wraps add one summary`() {
        val plan = DmNotificationPlanner.plan(listOf(msg("1", alice, "hey", 100)), mapOf(alice to "Alice"), pending = 3, showName = true, showText = true)
        assertEquals(2, plan.size)
        val summary = plan.single { it.id == "dm-pending".hashCode() }
        assertEquals(UiText.Res(R.string.notif_dm_title_anonymous), summary.title)
        assertEquals(UiText.Plural(R.plurals.dm_pending_summary, 3, listOf(3)), summary.text)
        val onlyPending = DmNotificationPlanner.plan(emptyList(), emptyMap(), pending = 1, showName = true, showText = true)
        assertEquals(listOf("dm-pending".hashCode()), onlyPending.map { it.id })
        assertTrue(DmNotificationPlanner.plan(emptyList(), emptyMap(), pending = 0, showName = true, showText = true).isEmpty())
    }

    @Test
    fun `outgoing and already-notified messages are ignored`() {
        val outgoing = msg("1", alice, "mine", 300, outgoing = true)
        assertTrue(DmNotificationPlanner.plan(listOf(outgoing), mapOf(alice to "Alice"), pending = 0, showName = true, showText = true).isEmpty())
        // alice's newest message is our own: the notification shows her newest incoming text
        val mixed = DmNotificationPlanner.plan(listOf(msg("2", alice, "theirs", 200), outgoing), mapOf(alice to "Alice"), pending = 0, showName = true, showText = true).single()
        assertEquals(UiText.Plain("theirs"), mixed.text)

        val old = msg("3", bob, "old", 100, receivedAt = 500)
        val new = msg("4", bob, "new", 110, receivedAt = 900)
        assertEquals(listOf(new), DmNotificationPlanner.unnotified(listOf(old, new, outgoing.copy(receivedAt = 1000)), lastNotified = 500))
    }

    @Test
    fun `an unknown name falls back to the short pubkey`() {
        val n = DmNotificationPlanner.plan(listOf(msg("1", alice, "yo", 100)), emptyMap(), pending = 0, showName = true, showText = true).single()
        assertEquals(UiText.Res(R.string.notif_dm_title, listOf("aaaaaaaa…aaaa")), n.title)
    }
}
