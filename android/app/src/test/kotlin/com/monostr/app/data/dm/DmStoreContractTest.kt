package com.monostr.app.data.dm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Behaviour every DmStore must have; the SQLite implementation runs the same cases in an androidTest (Task 11). */
abstract class DmStoreContractTest {
    abstract fun store(): DmStore
    private val bob = "b".repeat(64); private val carol = "c".repeat(64)
    private fun msg(id: Char, peer: String, at: Long, outgoing: Boolean = false, status: DmStatus = DmStatus.RECEIVED, text: String = "m$id") =
        DmMessage(id.toString().repeat(64), peer, outgoing, text, at, at, status, wrapId = "w$id".padEnd(64, '0'))

    @Test
    fun `conversations are sorted by last message and count unread incoming messages`() = runTest {
        val s = store()
        s.upsert(msg('1', bob, 100)); s.upsert(msg('2', bob, 200)); s.upsert(msg('3', carol, 150, outgoing = true, status = DmStatus.SENT))
        val c = s.conversations().first()
        assertEquals(listOf(bob, carol), c.map { it.peer })
        assertEquals(2, c[0].unread); assertEquals(0, c[1].unread); assertEquals("m2", c[0].lastText)
        assertEquals(2, s.unreadTotal().first())
        s.markRead(bob, 150)
        assertEquals(1, s.conversations().first()[0].unread)
        s.markRead(bob, 200)
        assertEquals(0, s.unreadTotal().first())
    }

    @Test
    fun `upsert onto an existing row keeps its status and only fills a missing wrap id`() = runTest {
        val s = store()
        s.upsert(msg('1', bob, 100, outgoing = true, status = DmStatus.SENT))
        s.upsert(msg('1', bob, 100, outgoing = true, status = DmStatus.SENDING))
        assertEquals(DmStatus.SENT, s.messages(bob).first().single().status)
        s.setStatus(msg('1', bob, 100).rumorId, DmStatus.FAILED)
        assertEquals(DmStatus.FAILED, s.messages(bob).first().single().status)

        // a self-copy of a message whose peer wrap failed: the row stays FAILED (retryable), gains the wrap id
        val id = "2".repeat(64)
        s.upsert(DmMessage(id, bob, true, "x", 200, 200, DmStatus.FAILED, null))
        s.upsert(DmMessage(id, bob, true, "x", 200, 300, DmStatus.SENT, "s".repeat(64)))
        val row = s.message(id)!!
        assertEquals(DmStatus.FAILED, row.status)
        assertEquals("s".repeat(64), row.wrapId)
        assertEquals(200L, row.receivedAt)
        // a wrap id once set is kept
        s.upsert(DmMessage(id, bob, true, "x", 200, 400, DmStatus.SENT, "t".repeat(64)))
        assertEquals("s".repeat(64), s.message(id)!!.wrapId)
    }

    @Test
    fun `failStaleSending fails sending rows and leaves the others`() = runTest {
        val s = store()
        s.upsert(msg('1', bob, 100, outgoing = true, status = DmStatus.SENDING))
        s.upsert(msg('2', bob, 200, outgoing = true, status = DmStatus.SENT))
        s.upsert(msg('3', bob, 300))
        s.failStaleSending()
        assertEquals(listOf(DmStatus.FAILED, DmStatus.SENT, DmStatus.RECEIVED), s.messages(bob).first().map { it.status })
    }

    @Test
    fun `replaceId moves a message to its final id and hasOutgoing follows the messages`() = runTest {
        val s = store()
        s.upsert(msg('1', bob, 100)); assertFalse(s.conversations().first()[0].hasOutgoing)
        s.upsert(DmMessage("pending-1", bob, true, "draft", 200, 200, DmStatus.SENDING, null))
        assertTrue(s.conversations().first()[0].hasOutgoing)
        s.replaceId("pending-1", "9".repeat(64), DmStatus.SENT)
        val m = s.messages(bob).first().last()
        assertEquals("9".repeat(64), m.rumorId); assertEquals(DmStatus.SENT, m.status)
        assertEquals(m, s.message("9".repeat(64))); assertNull(s.message("pending-1"))
        assertEquals(2, s.messages(bob).first().size)
        assertEquals(0L, s.lastNotified()); s.setLastNotified(7); assertEquals(7L, s.lastNotified())
    }

    @Test
    fun `messages of a peer are chronological and wraps and pending wraps are tracked`() = runTest {
        val s = store()
        s.upsert(msg('2', bob, 200)); s.upsert(msg('1', bob, 100))
        assertEquals(listOf(100L, 200L), s.messages(bob).first().map { it.createdAt })
        assertTrue(s.hasWrap(msg('1', bob, 100).wrapId!!)); assertFalse(s.hasWrap("x".repeat(64)))
        s.addPendingWrap("p".repeat(64), 1); s.addPendingWrap("p".repeat(64), 2)
        assertEquals(listOf("p".repeat(64)), s.pendingWraps()); assertEquals(1, s.pendingCount().first())
        s.removePendingWrap("p".repeat(64)); assertEquals(0, s.pendingCount().first())
        // added out of order: pendingWraps() is sorted by receivedAt ascending, not insertion order
        s.addPendingWrap("q".repeat(64), 20); s.addPendingWrap("r".repeat(64), 10)
        assertEquals(listOf("r".repeat(64), "q".repeat(64)), s.pendingWraps())
        assertEquals(0L, s.lastSync()); s.setLastSync(42); assertEquals(42L, s.lastSync())
        s.clear(); assertTrue(s.conversations().first().isEmpty())
    }

    @Test
    fun `pending wraps since a watermark count only the later ones and report the newest`() = runTest {
        val s = store()
        assertEquals(PendingSince(0, 0), s.pendingSince(0))
        s.addPendingWrap("p".repeat(64), 10); s.addPendingWrap("r".repeat(64), 30); s.addPendingWrap("q".repeat(64), 20)
        assertEquals(PendingSince(3, 30), s.pendingSince(0))
        assertEquals(PendingSince(1, 30), s.pendingSince(20), "strictly after the watermark")
        assertEquals(PendingSince(0, 0), s.pendingSince(30))
        assertEquals(3, s.pendingCount().first(), "the total is unaffected")
    }

    @Test
    fun `incoming since a watermark is strictly later, excludes outgoing and is ordered by receipt`() = runTest {
        val s = store()
        s.upsert(DmMessage("1".repeat(64), bob, false, "a", 100, 300, DmStatus.RECEIVED, "w1".padEnd(64, '0')))
        s.upsert(DmMessage("2".repeat(64), carol, false, "b", 900, 200, DmStatus.RECEIVED, "w2".padEnd(64, '0')))
        s.upsert(DmMessage("3".repeat(64), bob, true, "c", 400, 400, DmStatus.SENT, "w3".padEnd(64, '0')))
        s.upsert(DmMessage("4".repeat(64), bob, false, "d", 50, 100, DmStatus.RECEIVED, "w4".padEnd(64, '0')))
        assertEquals(listOf("4", "2", "1").map { it.repeat(64) }, s.incomingSince(0).map { it.rumorId })
        assertEquals(listOf("1".repeat(64)), s.incomingSince(200).map { it.rumorId }, "strictly after the watermark")
        assertTrue(s.incomingSince(300).isEmpty())
    }

    @Test
    fun `a re-parked wrap keeps its first receipt time`() = runTest {
        val s = store()
        s.addPendingWrap("p".repeat(64), 10); s.addPendingWrap("p".repeat(64), 50)
        assertEquals(PendingSince(1, 10), s.pendingSince(0))
        assertEquals(PendingSince(0, 0), s.pendingSince(10), "the second park did not move it past the watermark")
    }

    @Test
    fun `dropped wraps are remembered until clear`() = runTest {
        val s = store()
        assertFalse(s.isDropped("d".repeat(64)))
        s.markDropped("d".repeat(64), 5); s.markDropped("d".repeat(64), 6)
        assertTrue(s.isDropped("d".repeat(64))); assertFalse(s.isDropped("e".repeat(64)))
        assertEquals(0, s.pendingCount().first(), "dropping is not parking")
        s.clear()
        assertFalse(s.isDropped("d".repeat(64)))
    }

    @Test
    fun `last text strips media urls and takes the first line`() = runTest {
        val s = store()
        s.upsert(msg('1', bob, 100, text = "look https://x.example/a.jpg\nsecond line"))
        assertEquals("look", s.conversations().first()[0].lastText)
    }

    @Test
    fun `markRead on a peer without messages creates no conversation`() = runTest {
        val s = store()
        s.markRead(bob, 100)
        assertTrue(s.conversations().first().isEmpty())
        s.upsert(msg('1', bob, 50))
        val afterFirst = s.conversations().first()
        assertEquals(1, afterFirst.size); assertEquals(0, afterFirst[0].unread)
        s.upsert(msg('2', bob, 150))
        assertEquals(1, s.unreadTotal().first())
    }

    @Test
    fun `replaceId onto an existing id merges instead of duplicating`() = runTest {
        val s = store()
        // a self-copy echo can upsert the final row (outgoing, SENT) before the sender's own replaceId runs
        s.upsert(DmMessage("pending-1", bob, true, "draft", 200, 200, DmStatus.SENDING, null))
        s.upsert(DmMessage("9".repeat(64), bob, true, "draft", 200, 200, DmStatus.SENT, null))
        s.replaceId("pending-1", "9".repeat(64), DmStatus.SENT)
        val all = s.messages(bob).first()
        assertEquals(1, all.size)
        assertEquals("9".repeat(64), all[0].rumorId); assertEquals(DmStatus.SENT, all[0].status)

        // the sender's verdict wins over the echo: the peer wrap failed, so the merged row is FAILED
        s.upsert(DmMessage("pending-2", bob, true, "second", 300, 300, DmStatus.SENDING, null))
        s.upsert(DmMessage("8".repeat(64), bob, true, "second", 300, 300, DmStatus.SENT, "e".repeat(64)))
        s.replaceId("pending-2", "8".repeat(64), DmStatus.FAILED)
        val merged = s.message("8".repeat(64))!!
        assertEquals(DmStatus.FAILED, merged.status); assertEquals("e".repeat(64), merged.wrapId)
        assertNull(s.message("pending-2"))
    }

    @Test
    fun `parking the same wrap again counts attempts until they are reset`() = runTest {
        val s = store()
        val w = "p".repeat(64)
        assertEquals(0, s.pendingAttempts(w))
        s.addPendingWrap(w, 100)
        assertEquals(1, s.pendingAttempts(w))
        s.addPendingWrap(w, 200); s.addPendingWrap(w, 300)
        assertEquals(3, s.pendingAttempts(w))
        assertEquals(PendingSince(1, 100), s.pendingSince(0))
        s.resetPendingAttempts()
        assertEquals(0, s.pendingAttempts(w))
        assertEquals(listOf(w), s.pendingWraps())
        s.removePendingWrap(w)
        assertEquals(0, s.pendingAttempts(w))
    }

    @Test
    fun `a dropped wrap keeps its reason and the summary flag round-trips until clear`() = runTest {
        val s = store()
        val a = "a".repeat(64); val b = "b".repeat(64)
        s.markDropped(a, 1)
        s.markDropped(b, 2, DropReason.SUPERSEDED)
        assertEquals(DropReason.REJECTED, s.dropReason(a))
        assertEquals(DropReason.SUPERSEDED, s.dropReason(b))
        assertNull(s.dropReason("c".repeat(64)))
        assertFalse(s.pendingSummaryShown())
        s.setPendingSummaryShown(true)
        assertTrue(s.pendingSummaryShown())
        s.clear()
        assertFalse(s.pendingSummaryShown())
        assertNull(s.dropReason(a))
    }
}

class InMemoryDmStoreTest : DmStoreContractTest() { override fun store() = InMemoryDmStore() }
