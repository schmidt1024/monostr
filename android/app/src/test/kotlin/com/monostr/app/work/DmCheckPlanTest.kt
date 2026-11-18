package com.monostr.app.work

import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.PendingSince
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DmCheckPlanTest {
    private val alice = "a".repeat(64)

    private fun msg(id: String, receivedAt: Long, outgoing: Boolean = false, createdAt: Long = receivedAt) =
        DmMessage(id, alice, outgoing, "t$id", createdAt, receivedAt, if (outgoing) DmStatus.SENT else DmStatus.RECEIVED, "w$id")

    @Test
    fun `the first run only sets the watermark to now`() {
        val s = DmCheckPlan.select(lastNotified = 0, incoming = listOf(msg("1", 100), msg("2", 200)), pending = PendingSince(4, 300), totalPending = 4, now = 1000)
        assertTrue(s.fresh.isEmpty(), "a month of history must not burst out as notifications")
        assertEquals(0, s.pending)
        assertEquals(1000L, s.watermark)
    }

    @Test
    fun `a later run notifies the new incoming messages and the summary for new parked wraps`() {
        val s = DmCheckPlan.select(lastNotified = 500, incoming = listOf(msg("1", 400), msg("2", 600), msg("3", 700, outgoing = true)), pending = PendingSince(1, 550), totalPending = 3, now = 1000)
        assertEquals(listOf("2"), s.fresh.map { it.rumorId })
        assertEquals(3, s.pending, "the summary shows the total")
        assertEquals(600L, s.watermark, "the newest selected receivedAt, not the clock")
    }

    @Test
    fun `the watermark follows a newer parked wrap`() {
        val s = DmCheckPlan.select(lastNotified = 500, incoming = listOf(msg("1", 600)), pending = PendingSince(2, 800), totalPending = 2, now = 1000)
        assertEquals(800L, s.watermark)
    }

    @Test
    fun `nothing new keeps the watermark and posts no summary for wraps parked before it`() {
        val s = DmCheckPlan.select(lastNotified = 500, incoming = emptyList(), pending = PendingSince(0, 0), totalPending = 2, now = 1000)
        assertTrue(s.fresh.isEmpty())
        assertEquals(0, s.pending)
        assertEquals(500L, s.watermark)
    }

    @Test
    fun `the summary is posted once until the flag is reset`() {
        val first = DmCheckPlan.select(lastNotified = 500, incoming = emptyList(), pending = PendingSince(2, 800), totalPending = 2, now = 1000, summaryShown = false)
        assertEquals(2, first.pending)
        val again = DmCheckPlan.select(lastNotified = 800, incoming = emptyList(), pending = PendingSince(1, 900), totalPending = 3, now = 1000, summaryShown = true)
        assertEquals(0, again.pending)
        assertEquals(900L, again.watermark) // the watermark still moves past the new parked wrap
    }

    @Test
    fun `old history stored late is not notified but the watermark passes it`() {
        val lastNotified = 100 * DAY
        // a backfill (new inbox relay, a relay back from the dead) stores weeks-old messages now
        val old = msg("old", receivedAt = lastNotified + 60, createdAt = lastNotified - 20 * DAY)
        val new = msg("new", receivedAt = lastNotified + 30, createdAt = lastNotified + 20)
        val s = DmCheckPlan.select(lastNotified, listOf(new, old), PendingSince(0, 0), totalPending = 0, now = lastNotified + 100)
        assertEquals(listOf("new"), s.fresh.map { it.rumorId })
        assertEquals(lastNotified + 60, s.watermark, "the skipped old message is not looked at again")
    }

    @Test
    fun `a message delivered late within the grace is still notified`() {
        val lastNotified = 100 * DAY
        val late = msg("late", receivedAt = lastNotified + 60, createdAt = lastNotified - 3600)
        val s = DmCheckPlan.select(lastNotified, listOf(late), PendingSince(0, 0), totalPending = 0, now = lastNotified + 100)
        assertEquals(listOf("late"), s.fresh.map { it.rumorId })
    }

    private companion object {
        const val DAY = 86_400L
    }
}
