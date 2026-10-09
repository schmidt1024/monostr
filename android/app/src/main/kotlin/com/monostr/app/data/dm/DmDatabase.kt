package com.monostr.app.data.dm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [DmStore], one database per account (spec §4). Not written to from the main
 * thread: every read and write hops to [Dispatchers.IO], and query [Flow]s recompute whenever
 * [version] changes.
 */
class DmDatabase(context: Context, pubkey: String) :
    SQLiteOpenHelper(context, fileName(pubkey), null, DATABASE_VERSION), DmStore {

    private val version = MutableStateFlow(0L)
    private fun bump() { version.update { it + 1 } }

    /**
     * Set by [close] (logout closes, then deletes the file). SQLiteOpenHelper would reopen, and so
     * recreate, the deleted file on the next access; after close every access goes to an empty
     * throwaway in-memory database instead, so late reads see nothing and late writes vanish.
     */
    @Volatile private var closed = false
    private val detached: SQLiteDatabase by lazy { SQLiteDatabase.create(null).also { onCreate(it) } }

    @Synchronized
    override fun close() {
        if (closed) return // spec 7.3: a failed start and a later logout may both close
        closed = true
        super.close()
    }

    override fun getWritableDatabase(): SQLiteDatabase = if (closed) detached else super.getWritableDatabase()

    override fun getReadableDatabase(): SQLiteDatabase = if (closed) detached else super.getReadableDatabase()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (rumor_id TEXT PRIMARY KEY, peer TEXT NOT NULL, outgoing INTEGER NOT NULL, " +
                "content TEXT NOT NULL, created_at INTEGER NOT NULL, received_at INTEGER NOT NULL, status TEXT NOT NULL, wrap_id TEXT)", // sql
        )
        db.execSQL("CREATE INDEX messages_peer ON messages(peer, created_at)") // sql
        // hasWrap runs for every incoming wrap; incomingSince is the worker's notification query
        db.execSQL("CREATE INDEX messages_wrap ON messages(wrap_id)") // sql
        db.execSQL("CREATE INDEX messages_incoming ON messages(outgoing, received_at)") // sql
        db.execSQL("CREATE TABLE conversations (peer TEXT PRIMARY KEY, last_at INTEGER NOT NULL, read_at INTEGER NOT NULL DEFAULT 0)") // sql
        db.execSQL("CREATE TABLE pending_wraps (wrap_id TEXT PRIMARY KEY, received_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 1)") // sql
        db.execSQL("CREATE TABLE dropped_wraps (wrap_id TEXT PRIMARY KEY, at INTEGER NOT NULL, reason TEXT NOT NULL DEFAULT 'rejected')") // sql
        db.execSQL("CREATE TABLE sync (key TEXT PRIMARY KEY, value INTEGER NOT NULL)") // sql
    }

    /**
     * One explicit step per schema version. Version 2 (Plan 10d) has a step 1 to 2; any other
     * version is a programming error and throws instead of dropping the tables: the
     * messages exist only on this device (spec §4), a wiped table cannot be fetched back past the
     * relays' retention.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var from = oldVersion
        while (from < newVersion) {
            when (from) {
                1 -> migrate1To2(db)
                else -> throw IllegalStateException("unknown DM schema version $from (upgrading $oldVersion to $newVersion)")
            }
            from++
        }
    }

    /** Plan 10d: parked wraps count their silent attempts, dropped wraps keep why (spec 7.4/7.6). Rows stay. */
    private fun migrate1To2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_wraps ADD COLUMN attempts INTEGER NOT NULL DEFAULT 1") // sql
        db.execSQL("ALTER TABLE dropped_wraps ADD COLUMN reason TEXT NOT NULL DEFAULT 'rejected'") // sql
    }

    override fun conversations(): Flow<List<Conversation>> =
        version.map { withContext(Dispatchers.IO) { queryConversations() } }.flowOn(Dispatchers.IO)

    override fun messages(peer: String): Flow<List<DmMessage>> =
        version.map { withContext(Dispatchers.IO) { queryMessages(peer) } }.flowOn(Dispatchers.IO)

    override fun unreadTotal(): Flow<Int> =
        version.map { withContext(Dispatchers.IO) { queryUnreadTotal() } }.flowOn(Dispatchers.IO)

    override fun pendingCount(): Flow<Int> =
        version.map { withContext(Dispatchers.IO) { queryPendingCount() } }.flowOn(Dispatchers.IO)

    private fun queryConversations(): List<Conversation> {
        val db = readableDatabase
        val result = mutableListOf<Conversation>()
        // The peer set and last_at come from messages (the source of truth); conversations only
        // contributes read_at, and only when a row exists there (a peer never messaged has none).
        val sql = "SELECT m.peer, MAX(m.created_at) AS last_at, COALESCE(c.read_at, 0) AS read_at " +
            "FROM messages m LEFT JOIN conversations c ON c.peer = m.peer GROUP BY m.peer ORDER BY last_at DESC" // sql
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                val peer = cursor.getString(0)
                val lastAt = cursor.getLong(1)
                val readAt = cursor.getLong(2)
                result.add(Conversation(peer, lastAt, readAt, lastTextFor(db, peer), countUnread(db, peer, readAt), hasOutgoingFor(db, peer)))
            }
        }
        return result
    }

    private fun lastTextFor(db: SQLiteDatabase, peer: String): String {
        db.rawQuery("SELECT content FROM messages WHERE peer = ? ORDER BY created_at DESC LIMIT 1", arrayOf(peer)).use { c -> // sql
            return if (c.moveToFirst()) DmText.preview(c.getString(0)) else ""
        }
    }

    private fun countUnread(db: SQLiteDatabase, peer: String, readAt: Long): Int {
        db.rawQuery("SELECT COUNT(*) FROM messages WHERE peer = ? AND outgoing = 0 AND created_at > ?", arrayOf(peer, readAt.toString())).use { c -> // sql
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    private fun hasOutgoingFor(db: SQLiteDatabase, peer: String): Boolean {
        db.rawQuery("SELECT EXISTS(SELECT 1 FROM messages WHERE peer = ? AND outgoing = 1)", arrayOf(peer)).use { c -> // sql
            c.moveToFirst()
            return c.getInt(0) != 0
        }
    }

    private fun queryMessages(peer: String): List<DmMessage> {
        val result = mutableListOf<DmMessage>()
        readableDatabase.rawQuery(
            "SELECT rumor_id, peer, outgoing, content, created_at, received_at, status, wrap_id FROM messages WHERE peer = ? ORDER BY created_at ASC", // sql
            arrayOf(peer),
        ).use { c ->
            while (c.moveToNext()) result.add(messageAt(c))
        }
        return result
    }

    override suspend fun incomingSince(receivedAfter: Long): List<DmMessage> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DmMessage>()
        readableDatabase.rawQuery(
            "SELECT rumor_id, peer, outgoing, content, created_at, received_at, status, wrap_id FROM messages WHERE outgoing = 0 AND received_at > ? ORDER BY received_at ASC", // sql
            arrayOf(receivedAfter.toString()),
        ).use { c ->
            while (c.moveToNext()) result.add(messageAt(c))
        }
        result
    }

    override suspend fun message(rumorId: String): DmMessage? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT rumor_id, peer, outgoing, content, created_at, received_at, status, wrap_id FROM messages WHERE rumor_id = ?", // sql
            arrayOf(rumorId),
        ).use { c -> if (c.moveToFirst()) messageAt(c) else null }
    }

    private fun messageAt(c: Cursor) = DmMessage(
        rumorId = c.getString(0),
        peer = c.getString(1),
        outgoing = c.getInt(2) != 0,
        content = c.getString(3),
        createdAt = c.getLong(4),
        receivedAt = c.getLong(5),
        status = DmStatus.valueOf(c.getString(6)),
        wrapId = if (c.isNull(7)) null else c.getString(7),
    )

    private fun queryUnreadTotal(): Int {
        val sql = "SELECT COUNT(*) FROM messages m WHERE m.outgoing = 0 AND " +
            "m.created_at > COALESCE((SELECT read_at FROM conversations WHERE peer = m.peer), 0)" // sql
        readableDatabase.rawQuery(sql, null).use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    private fun queryPendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_wraps", null).use { c -> // sql
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    override suspend fun upsert(message: DmMessage) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                var peer = message.peer
                var outgoing = message.outgoing
                var content = message.content
                var createdAt = message.createdAt
                var receivedAt = message.receivedAt
                var status = message.status
                var wrapId = message.wrapId
                db.rawQuery(
                    "SELECT peer, outgoing, content, created_at, received_at, status, wrap_id FROM messages WHERE rumor_id = ?", // sql
                    arrayOf(message.rumorId),
                ).use { c ->
                    if (c.moveToFirst()) {
                        peer = c.getString(0)
                        outgoing = c.getInt(1) != 0
                        content = c.getString(2)
                        createdAt = c.getLong(3)
                        receivedAt = c.getLong(4)
                        // the row's status is decided by whoever created it (deliver for an own send); an echo only adds its wrap
                        status = DmStatus.valueOf(c.getString(5))
                        val existingWrapId = if (c.isNull(6)) null else c.getString(6)
                        wrapId = existingWrapId ?: message.wrapId
                    }
                }
                val values = ContentValues().apply {
                    put("rumor_id", message.rumorId)
                    put("peer", peer)
                    put("outgoing", if (outgoing) 1 else 0)
                    put("content", content)
                    put("created_at", createdAt)
                    put("received_at", receivedAt)
                    put("status", status.name)
                    put("wrap_id", wrapId)
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        bump()
    }

    override suspend fun setStatus(rumorId: String, status: DmStatus) {
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL("UPDATE messages SET status = ? WHERE rumor_id = ?", arrayOf(status.name, rumorId)) // sql
        }
        bump()
    }

    override suspend fun failStaleSending() {
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL("UPDATE messages SET status = ? WHERE status = ?", arrayOf(DmStatus.FAILED.name, DmStatus.SENDING.name)) // sql
        }
        bump()
    }

    /**
     * Normally just moves the provisional row to its final id. But a self-copy echo can already
     * have upserted a row under [newRumorId] before this runs (spec §3.1); when that happens the
     * two rows are merged (the echo's wrap id, [status] from the sender) and the provisional row
     * is dropped instead of leaving a duplicate.
     */
    override suspend fun replaceId(oldRumorId: String, newRumorId: String, status: DmStatus) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                var existingStatus: DmStatus? = null
                var existingWrapId: String? = null
                db.rawQuery("SELECT status, wrap_id FROM messages WHERE rumor_id = ?", arrayOf(newRumorId)).use { c -> // sql
                    if (c.moveToFirst()) {
                        existingStatus = DmStatus.valueOf(c.getString(0))
                        existingWrapId = if (c.isNull(1)) null else c.getString(1)
                    }
                }
                val existing = existingStatus
                if (existing != null) {
                    var provisionalWrapId: String? = null
                    db.rawQuery("SELECT wrap_id FROM messages WHERE rumor_id = ?", arrayOf(oldRumorId)).use { c -> // sql
                        if (c.moveToFirst() && !c.isNull(0)) provisionalWrapId = c.getString(0)
                    }
                    // the sender's verdict wins over the echo's SENT: a self-copy says nothing about the peer wrap
                    val finalWrapId = existingWrapId ?: provisionalWrapId
                    db.execSQL("UPDATE messages SET status = ?, wrap_id = ? WHERE rumor_id = ?", arrayOf(status.name, finalWrapId, newRumorId)) // sql
                    db.execSQL("DELETE FROM messages WHERE rumor_id = ?", arrayOf(oldRumorId)) // sql
                } else {
                    db.execSQL("UPDATE messages SET rumor_id = ?, status = ? WHERE rumor_id = ?", arrayOf(newRumorId, status.name, oldRumorId)) // sql
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        bump()
    }

    override suspend fun markRead(peer: String, at: Long) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                var exists = false
                var current = 0L
                db.rawQuery("SELECT read_at FROM conversations WHERE peer = ?", arrayOf(peer)).use { c -> // sql
                    exists = c.moveToFirst()
                    if (exists) current = c.getLong(0)
                }
                if (exists) {
                    db.execSQL("UPDATE conversations SET read_at = ? WHERE peer = ?", arrayOf<Any>(maxOf(current, at), peer)) // sql
                } else {
                    // last_at has no reader (conversations() derives ordering from messages); kept
                    // only because the column is NOT NULL.
                    db.execSQL("INSERT INTO conversations(peer, last_at, read_at) VALUES (?, ?, ?)", arrayOf<Any>(peer, at, at)) // sql
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        bump()
    }

    override suspend fun hasWrap(wrapId: String): Boolean = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT EXISTS(SELECT 1 FROM messages WHERE wrap_id = ?)", arrayOf(wrapId)).use { c -> // sql
            c.moveToFirst()
            c.getInt(0) != 0
        }
    }

    override suspend fun addPendingWrap(wrapId: String, receivedAt: Long) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply { put("wrap_id", wrapId); put("received_at", receivedAt); put("attempts", 1) }
                if (db.insertWithOnConflict("pending_wraps", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    db.execSQL("UPDATE pending_wraps SET attempts = attempts + 1 WHERE wrap_id = ?", arrayOf(wrapId)) // sql
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        bump()
    }

    override suspend fun pendingAttempts(wrapId: String): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT attempts FROM pending_wraps WHERE wrap_id = ?", arrayOf(wrapId)).use { c -> // sql
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    override suspend fun resetPendingAttempts() {
        withContext(Dispatchers.IO) { writableDatabase.execSQL("UPDATE pending_wraps SET attempts = 0") } // sql
    }

    override suspend fun pendingWraps(): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT wrap_id FROM pending_wraps ORDER BY received_at ASC", null).use { c -> // sql
            while (c.moveToNext()) result.add(c.getString(0))
        }
        result
    }

    override suspend fun pendingSince(at: Long): PendingSince = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(MAX(received_at), 0) FROM pending_wraps WHERE received_at > ?", arrayOf(at.toString())).use { c -> // sql
            c.moveToFirst()
            PendingSince(c.getInt(0), c.getLong(1))
        }
    }

    override suspend fun removePendingWrap(wrapId: String) {
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL("DELETE FROM pending_wraps WHERE wrap_id = ?", arrayOf(wrapId)) // sql
        }
        bump()
    }

    override suspend fun markDropped(wrapId: String, at: Long, reason: DropReason) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put("wrap_id", wrapId); put("at", at); put("reason", reason.code) }
            writableDatabase.insertWithOnConflict("dropped_wraps", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    override suspend fun dropReason(wrapId: String): DropReason? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT reason FROM dropped_wraps WHERE wrap_id = ?", arrayOf(wrapId)).use { c -> // sql
            if (c.moveToFirst()) DropReason.of(c.getString(0)) else null
        }
    }

    override suspend fun pendingSummaryShown(): Boolean = withContext(Dispatchers.IO) { readSyncValue(KEY_PENDING_SUMMARY) != 0L }

    override suspend fun setPendingSummaryShown(shown: Boolean) {
        withContext(Dispatchers.IO) { writeSyncValue(KEY_PENDING_SUMMARY, if (shown) 1L else 0L) }
    }

    override suspend fun isDropped(wrapId: String): Boolean = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT EXISTS(SELECT 1 FROM dropped_wraps WHERE wrap_id = ?)", arrayOf(wrapId)).use { c -> // sql
            c.moveToFirst()
            c.getInt(0) != 0
        }
    }

    override suspend fun lastSync(): Long = withContext(Dispatchers.IO) { readSyncValue(KEY_LAST_SYNC) }

    override suspend fun setLastSync(at: Long) {
        withContext(Dispatchers.IO) { writeSyncValue(KEY_LAST_SYNC, at) }
        bump()
    }

    override suspend fun lastNotified(): Long = withContext(Dispatchers.IO) { readSyncValue(KEY_LAST_NOTIFIED) }

    override suspend fun setLastNotified(at: Long) {
        withContext(Dispatchers.IO) { writeSyncValue(KEY_LAST_NOTIFIED, at) }
        bump()
    }

    private fun readSyncValue(key: String): Long {
        readableDatabase.rawQuery("SELECT value FROM sync WHERE key = ?", arrayOf(key)).use { c -> // sql
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    private fun writeSyncValue(key: String, value: Long) {
        val values = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("sync", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.execSQL("DELETE FROM messages") // sql
            db.execSQL("DELETE FROM conversations") // sql
            db.execSQL("DELETE FROM pending_wraps") // sql
            db.execSQL("DELETE FROM dropped_wraps") // sql
            db.execSQL("DELETE FROM sync") // sql
        }
        bump()
    }

    companion object {
        const val DATABASE_VERSION = 2
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_NOTIFIED = "last_notified"
        private const val KEY_PENDING_SUMMARY = "pending_summary"

        private fun fileName(pubkey: String) = "dms-${pubkey.take(16)}.db"

        fun delete(context: Context, pubkey: String) = context.deleteDatabase(fileName(pubkey))
    }
}
