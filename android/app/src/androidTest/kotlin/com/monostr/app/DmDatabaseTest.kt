package com.monostr.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.dm.DmDatabase
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.DropReason
import com.monostr.app.data.dm.PendingSince
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * The DmStore contract (DmStoreContractTest, run there against InMemoryDmStore) replayed against the
 * real SQLite [DmDatabase] on the device, plus reopening and the close guard: once closed, a store
 * never recreates its deleted file. Each test uses a fresh random account; [cleanup] deletes its file.
 */
@RunWith(AndroidJUnit4::class)
class DmDatabaseTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pubkey = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val opened = mutableListOf<DmDatabase>()
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private fun store() = DmDatabase(ctx, pubkey).also { opened += it }

    private fun msg(id: Char, peer: String, at: Long, outgoing: Boolean = false, status: DmStatus = DmStatus.RECEIVED, text: String = "m$id") =
        DmMessage(id.toString().repeat(64), peer, outgoing, text, at, at, status, wrapId = "w$id".padEnd(64, '0'))

    // mirrors DmDatabase's private file name; only used to observe that the file exists
    private fun dbFile() = ctx.getDatabasePath("dms-${pubkey.take(16)}.db")

    @After fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        DmDatabase.delete(ctx, pubkey)
    }

    @Test fun conversationsAreSortedByLastMessageAndCountUnreadIncoming() = runBlocking {
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

    @Test fun upsertOntoAnExistingRowKeepsItsStatusAndOnlyFillsAMissingWrapId() = runBlocking {
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
        s.upsert(DmMessage(id, bob, true, "x", 200, 400, DmStatus.SENT, "t".repeat(64)))
        assertEquals("s".repeat(64), s.message(id)!!.wrapId)
    }

    @Test fun failStaleSendingFailsSendingRowsAndLeavesTheOthers() = runBlocking {
        val s = store()
        s.upsert(msg('1', bob, 100, outgoing = true, status = DmStatus.SENDING))
        s.upsert(msg('2', bob, 200, outgoing = true, status = DmStatus.SENT))
        s.upsert(msg('3', bob, 300))
        s.failStaleSending()
        assertEquals(listOf(DmStatus.FAILED, DmStatus.SENT, DmStatus.RECEIVED), s.messages(bob).first().map { it.status })
    }

    @Test fun replaceIdMovesAMessageAndHasOutgoingFollowsTheMessages() = runBlocking {
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

    @Test fun messagesAreChronologicalAndWrapsAndPendingWrapsAreTracked() = runBlocking {
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

    @Test fun pendingSinceCountsOnlyLaterWrapsAndReportsTheNewest() = runBlocking {
        val s = store()
        assertEquals(PendingSince(0, 0), s.pendingSince(0))
        s.addPendingWrap("p".repeat(64), 10); s.addPendingWrap("r".repeat(64), 30); s.addPendingWrap("q".repeat(64), 20)
        assertEquals(PendingSince(3, 30), s.pendingSince(0))
        assertEquals("strictly after the watermark", PendingSince(1, 30), s.pendingSince(20))
        assertEquals(PendingSince(0, 0), s.pendingSince(30))
        assertEquals("the total is unaffected", 3, s.pendingCount().first())
    }

    @Test fun incomingSinceIsStrictlyLaterExcludesOutgoingAndIsOrderedByReceipt() = runBlocking {
        val s = store()
        s.upsert(DmMessage("1".repeat(64), bob, false, "a", 100, 300, DmStatus.RECEIVED, "w1".padEnd(64, '0')))
        s.upsert(DmMessage("2".repeat(64), carol, false, "b", 900, 200, DmStatus.RECEIVED, "w2".padEnd(64, '0')))
        s.upsert(DmMessage("3".repeat(64), bob, true, "c", 400, 400, DmStatus.SENT, "w3".padEnd(64, '0')))
        s.upsert(DmMessage("4".repeat(64), bob, false, "d", 50, 100, DmStatus.RECEIVED, "w4".padEnd(64, '0')))
        assertEquals(listOf("4", "2", "1").map { it.repeat(64) }, s.incomingSince(0).map { it.rumorId })
        assertEquals("strictly after the watermark", listOf("1".repeat(64)), s.incomingSince(200).map { it.rumorId })
        assertTrue(s.incomingSince(300).isEmpty())
    }

    @Test fun aReParkedWrapKeepsItsFirstReceiptTime() = runBlocking {
        val s = store()
        s.addPendingWrap("p".repeat(64), 10); s.addPendingWrap("p".repeat(64), 50)
        assertEquals(PendingSince(1, 10), s.pendingSince(0))
        assertEquals("the second park did not move it past the watermark", PendingSince(0, 0), s.pendingSince(10))
    }

    @Test fun droppedWrapsAreRememberedUntilClear() = runBlocking {
        val s = store()
        assertFalse(s.isDropped("d".repeat(64)))
        s.markDropped("d".repeat(64), 5); s.markDropped("d".repeat(64), 6)
        assertTrue(s.isDropped("d".repeat(64))); assertFalse(s.isDropped("e".repeat(64)))
        assertEquals("dropping is not parking", 0, s.pendingCount().first())
        s.clear()
        assertFalse(s.isDropped("d".repeat(64)))
    }

    @Test fun lastTextStripsMediaUrlsAndTakesTheFirstLine() = runBlocking {
        val s = store()
        s.upsert(msg('1', bob, 100, text = "look https://x.example/a.jpg\nsecond line"))
        assertEquals("look", s.conversations().first()[0].lastText)
    }

    @Test fun markReadOnAPeerWithoutMessagesCreatesNoConversation() = runBlocking {
        val s = store()
        s.markRead(bob, 100)
        assertTrue(s.conversations().first().isEmpty())
        s.upsert(msg('1', bob, 50))
        val afterFirst = s.conversations().first()
        assertEquals(1, afterFirst.size); assertEquals(0, afterFirst[0].unread)
        s.upsert(msg('2', bob, 150))
        assertEquals(1, s.unreadTotal().first())
    }

    @Test fun replaceIdOntoAnExistingIdMergesInsteadOfDuplicating() = runBlocking {
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

    @Test fun dataSurvivesReopeningTheSameAccount() = runBlocking {
        val s = store()
        s.upsert(msg('1', bob, 100)); s.setLastSync(42)
        s.close()
        val reopened = store()
        assertEquals(DmDatabase.DATABASE_VERSION, reopened.readableDatabase.version)
        assertEquals(2, DmDatabase.DATABASE_VERSION)
        assertEquals(listOf(msg('1', bob, 100)), reopened.messages(bob).first())
        assertEquals(42L, reopened.lastSync())
        val indices = reopened.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'messages'", null).use { c -> // sql
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }
        assertTrue(indices.toString(), indices.containsAll(setOf("messages_peer", "messages_wrap", "messages_incoming")))
    }

    @Test fun anUnknownUpgradeThrowsInsteadOfWipingTheMessages() = runBlocking {
        val s = store()
        s.upsert(msg('1', bob, 100)); s.setLastSync(42)
        val db = s.writableDatabase
        val thrown = runCatching { s.onUpgrade(db, 2, 99) }.exceptionOrNull()
        assertTrue("onUpgrade(2, 99) must throw, got $thrown", thrown is IllegalStateException)
        assertEquals(listOf(msg('1', bob, 100)), s.messages(bob).first())
        assertEquals(42L, s.lastSync())
    }

    @Test fun writesAfterCloseNeverRecreateTheDeletedFile() = runBlocking {
        val s = store()
        s.upsert(msg('1', bob, 100))
        assertTrue(dbFile().exists())
        s.close()
        DmDatabase.delete(ctx, pubkey)
        assertFalse(dbFile().exists())
        // late writes and reads, as from a sync still running at logout
        s.upsert(msg('2', bob, 200)); s.setLastSync(9); s.addPendingWrap("p".repeat(64), 1); s.markRead(bob, 300)
        assertTrue(s.messages(bob).first().none { it.rumorId == msg('1', bob, 100).rumorId })
        assertFalse("a closed store recreated its file", dbFile().exists())
    }

    @Test fun parkingCountsAttemptsAndDroppedWrapsKeepTheirReason() = runBlocking {
        val s = store()
        val w = "p".repeat(64)
        s.addPendingWrap(w, 100); s.addPendingWrap(w, 200)
        assertEquals(2, s.pendingAttempts(w))
        assertEquals(PendingSince(1, 100), s.pendingSince(0))
        s.resetPendingAttempts()
        assertEquals(0, s.pendingAttempts(w))
        s.markDropped("d".repeat(64), 1, DropReason.SUPERSEDED)
        assertEquals(DropReason.SUPERSEDED, s.dropReason("d".repeat(64)))
        s.setPendingSummaryShown(true)
        assertTrue(s.pendingSummaryShown())
    }

    @Test fun closingTwiceIsHarmless() = runBlocking {
        val s = store()
        s.upsert(msg('1', bob, 100))
        s.close()
        s.close()
        assertTrue(s.messages(bob).first().isEmpty())
    }

    @Test fun aVersionOneDatabaseKeepsItsRowsThroughTheMigration() = runBlocking {
        // the Plan 10b schema, written by hand
        val file = dbFile().apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE messages (rumor_id TEXT PRIMARY KEY, peer TEXT NOT NULL, outgoing INTEGER NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, received_at INTEGER NOT NULL, status TEXT NOT NULL, wrap_id TEXT)")
            db.execSQL("CREATE TABLE conversations (peer TEXT PRIMARY KEY, last_at INTEGER NOT NULL, read_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE pending_wraps (wrap_id TEXT PRIMARY KEY, received_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE dropped_wraps (wrap_id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sync (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO pending_wraps VALUES ('${"p".repeat(64)}', 100)")
            db.execSQL("INSERT INTO dropped_wraps VALUES ('${"d".repeat(64)}', 50)")
            db.execSQL("INSERT INTO sync VALUES ('last_sync', 1234)")
            db.version = 1
        }
        val s = store()
        assertEquals(1, s.pendingAttempts("p".repeat(64)))
        assertEquals(DropReason.REJECTED, s.dropReason("d".repeat(64)))
        assertEquals(1234L, s.lastSync())
        s.addPendingWrap("p".repeat(64), 200)
        assertEquals(2, s.pendingAttempts("p".repeat(64)))
    }
}
