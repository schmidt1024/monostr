package com.monostr.app.work

import com.monostr.app.ui.FakeNotifications
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TipCheckerTest {
    private fun item(id: Char, kind: NotificationKind, at: Long) =
        NotificationItem(id.toString().repeat(64), kind, "a".repeat(64), at, "1".repeat(64), "1".repeat(64), "", if (kind == NotificationKind.TIP) 1L else null)

    @Test
    fun `first run only sets the watermark, later runs report new tips only`() = runTest {
        val notifications = FakeNotifications(listOf(item('1', NotificationKind.TIP, 100), item('2', NotificationKind.TIP, 300), item('3', NotificationKind.REPLY, 400)))
        val first = TipChecker.check(notifications, since = 0, now = 500, notified = emptySet())
        assertTrue(first.fresh.isEmpty())
        assertEquals(500L, first.watermark)
        assertTrue(notifications.refreshCalls.isEmpty(), "no relay round trip on the first run")
        val second = TipChecker.check(notifications, since = 200, now = 600, notified = first.notified)
        assertEquals(listOf(300L), second.fresh.map { it.createdAt })
        assertEquals(300L, second.watermark)
        assertEquals(listOf<Long?>(199L), notifications.refreshCalls, "fetch one second earlier than the watermark")
        val third = TipChecker.check(notifications, since = 300, now = 700, notified = second.notified)
        assertTrue(third.fresh.isEmpty())
        assertEquals(300L, third.watermark)
    }

    @Test
    fun `a receipt in the same second as the watermark is reported once, not lost, not repeated`() = runTest {
        val notifications = FakeNotifications(listOf(item('1', NotificationKind.TIP, 300)))
        val first = TipChecker.check(notifications, since = 300, now = 400, notified = emptySet())
        assertEquals(listOf("1".repeat(64)), first.fresh.map { it.id })
        assertTrue(first.notified.contains("1".repeat(64)))
        val second = TipChecker.check(notifications, since = first.watermark, now = 500, notified = first.notified)
        assertTrue(second.fresh.isEmpty())
    }

    @Test
    fun `the notified set is capped at 200 ids, oldest first`() = runTest {
        val old = (0 until 200).map { "x$it" }.toSet()
        val notifications = FakeNotifications(listOf(item('1', NotificationKind.TIP, 300)))
        val out = TipChecker.check(notifications, since = 300, now = 400, notified = old)
        assertEquals(200, out.notified.size)
        assertTrue(out.notified.contains("1".repeat(64)))
        assertTrue(!out.notified.contains("x0"))
    }
}
