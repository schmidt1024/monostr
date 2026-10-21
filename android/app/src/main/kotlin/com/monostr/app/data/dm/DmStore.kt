package com.monostr.app.data.dm

import com.monostr.nostr.model.NoteMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

enum class DmStatus { SENDING, SENT, FAILED, RECEIVED }

/** Spec 7.6: why a wrap will never yield a message; the verdict is cached per wrap id. */
enum class DropReason(val code: String) {
    /** Not a message for us: invalid rumor, forged id, not our key. */
    REJECTED("rejected"),
    /** The self copy of an own message that a retry replaced. */
    SUPERSEDED("superseded");

    companion object {
        fun of(code: String?): DropReason? = entries.firstOrNull { it.code == code }
    }
}

data class DmMessage(
    val rumorId: String,
    val peer: String,
    val outgoing: Boolean,
    val content: String,
    val createdAt: Long,
    val receivedAt: Long,
    val status: DmStatus,
    val wrapId: String?,
)

/** [count] parked wraps received after a watermark; [newestReceivedAt] is 0 when [count] is 0. */
data class PendingSince(val count: Int, val newestReceivedAt: Long)

data class Conversation(
    val peer: String,
    val lastAt: Long,
    val readAt: Long,
    val lastText: String,
    val unread: Int,
    val hasOutgoing: Boolean,
)

/** The badge count without the conversations of [muted] peers (spec 9.3); their messages stay stored. One shared query feeds every badge (spec perf2 D3). */
fun DmStore.unreadTotalExcept(muted: Flow<Set<String>>): Flow<Int> =
    combine(conversations(), muted) { conversations, m -> conversations.filter { it.peer !in m }.sumOf { it.unread } }
        .catch { emit(0) } // a query failing while the database closes (logout) shows no badge instead of crashing the screen
        .distinctUntilChanged()

/** Local storage for NIP-17 direct messages, one instance per logged-in account (spec §4). */
interface DmStore {
    /** Sorted by [Conversation.lastAt] descending. */
    fun conversations(): Flow<List<Conversation>>

    /** A single peer's newest [limit] messages, sorted by [DmMessage.createdAt] ascending (spec perf2 D5: the chat pages by 100). */
    fun messages(peer: String, limit: Int = Int.MAX_VALUE): Flow<List<DmMessage>>

    fun unreadTotal(): Flow<Int>

    /** One message by its rumor id (or provisional "pending-<uuid>" id); null when unknown. */
    suspend fun message(rumorId: String): DmMessage?

    /**
     * Inserts by [DmMessage.rumorId]. An existing row keeps everything, its status included (an
     * own message's status is the sender's delivery verdict; a self-copy echo says nothing about the
     * peer wrap); only a missing `wrapId` is filled in. Returns true when it inserted the row or
     * filled its wrap id, false when nothing changed (spec perf2 D1: then nothing is signalled).
     */
    suspend fun upsert(message: DmMessage): Boolean

    suspend fun setStatus(rumorId: String, status: DmStatus)

    /** Turns every SENDING row into FAILED: a send cannot still be running when a session starts. */
    suspend fun failStaleSending()

    /**
     * Moves a provisional row (id "pending-<uuid>") to its final rumor id once the rumor exists,
     * with [status]. A row a self-copy already created under the final id is merged: it keeps its
     * wrap id and takes [status]. [wrapId] (the own self-copy's wrap, spec perf2 D4) fills a missing
     * wrap id only.
     */
    suspend fun replaceId(oldRumorId: String, newRumorId: String, status: DmStatus, wrapId: String? = null)

    suspend fun markRead(peer: String, at: Long)

    suspend fun hasWrap(wrapId: String): Boolean

    /** Parks a wrap the silent signer could not open; a wrap already parked keeps its first [receivedAt] and counts one more attempt. */
    suspend fun addPendingWrap(wrapId: String, receivedAt: Long)

    /** Silent attempts on a parked wrap since it was parked or since [resetPendingAttempts]; 0 when it is not parked. */
    suspend fun pendingAttempts(wrapId: String): Int

    /** Spec 7.4: a healed AUTH lets every parked wrap be tried silently again. */
    suspend fun resetPendingAttempts()

    suspend fun pendingWraps(): List<String>

    suspend fun removePendingWrap(wrapId: String)

    fun pendingCount(): Flow<Int>

    /** Remembers a wrap that will never yield a message (spec §3.2, 7.6 of 10d): it is not decrypted again. */
    suspend fun markDropped(wrapId: String, at: Long, reason: DropReason = DropReason.REJECTED)

    suspend fun dropReason(wrapId: String): DropReason?

    /** Spec 7.4: the "waiting for Amber" summary was posted and is not posted again until reset. */
    suspend fun pendingSummaryShown(): Boolean

    suspend fun setPendingSummaryShown(shown: Boolean)

    suspend fun isDropped(wrapId: String): Boolean

    /**
     * Spec perf2 D4: true when [wrapId] is known from memory to be stored or dropped (final: it is never
     * decrypted again). False is not final: unknown, or the set is still loading; [hasWrap]/[isDropped]
     * decide then. Never blocks; the first call may start loading the set in the background.
     */
    fun isKnownWrap(wrapId: String): Boolean

    /** Parked wraps received after [at] (the worker's watermark): only these warrant a new summary. */
    suspend fun pendingSince(at: Long): PendingSince

    /** 0 when the store has never synced. */
    suspend fun lastSync(): Long

    suspend fun setLastSync(at: Long)

    /** Incoming messages received after [receivedAfter], by [DmMessage.receivedAt] ascending (the worker's notification source). */
    suspend fun incomingSince(receivedAfter: Long): List<DmMessage>

    /** The background worker's watermark (a message's `receivedAt`); 0 when never notified. */
    suspend fun lastNotified(): Long

    suspend fun setLastNotified(at: Long)

    suspend fun clear()
}

/** Shared between [DmStore] implementations: the conversation-list preview text for a message. */
object DmText {
    fun preview(content: String): String =
        NoteMedia.parse(content, emptyList(), withQuote = false).displayContent.lineSequence().firstOrNull()?.trim().orEmpty()
}
