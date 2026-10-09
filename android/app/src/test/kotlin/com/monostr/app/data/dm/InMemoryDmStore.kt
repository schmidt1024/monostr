package com.monostr.app.data.dm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** JVM fake mirroring [DmDatabase]'s semantics with plain lists; used by [DmStoreContractTest] and by app-layer unit tests. */
class InMemoryDmStore : DmStore {
    private val messages = mutableListOf<DmMessage>()
    private val readAt = mutableMapOf<String, Long>()
    private val pendingWraps = LinkedHashMap<String, Long>()
    private val droppedWraps = HashMap<String, DropReason>()
    private val attempts = HashMap<String, Int>()
    private var summaryShown = false
    private var lastSyncAt = 0L
    private var lastNotifiedAt = 0L
    private val version = MutableStateFlow(0L)

    private fun bump() { version.update { it + 1 } }

    private fun computeConversations(): List<Conversation> =
        messages.groupBy { it.peer }.map { (peer, msgs) ->
            val last = msgs.maxBy { it.createdAt }
            val read = readAt[peer] ?: 0L
            val unread = msgs.count { !it.outgoing && it.createdAt > read }
            val hasOutgoing = msgs.any { it.outgoing }
            Conversation(peer, last.createdAt, read, DmText.preview(last.content), unread, hasOutgoing)
        }.sortedByDescending { it.lastAt }

    override fun conversations(): Flow<List<Conversation>> = version.map { computeConversations() }

    override fun messages(peer: String): Flow<List<DmMessage>> =
        version.map { messages.filter { it.peer == peer }.sortedBy { it.createdAt } }

    override suspend fun message(rumorId: String): DmMessage? = messages.firstOrNull { it.rumorId == rumorId }

    override fun unreadTotal(): Flow<Int> =
        version.map { messages.count { !it.outgoing && it.createdAt > (readAt[it.peer] ?: 0L) } }

    override suspend fun upsert(message: DmMessage) {
        val index = messages.indexOfFirst { it.rumorId == message.rumorId }
        if (index >= 0) {
            val existing = messages[index]
            messages[index] = existing.copy(wrapId = existing.wrapId ?: message.wrapId)
        } else {
            messages.add(message)
        }
        bump()
    }

    override suspend fun setStatus(rumorId: String, status: DmStatus) {
        val index = messages.indexOfFirst { it.rumorId == rumorId }
        if (index >= 0) messages[index] = messages[index].copy(status = status)
        bump()
    }

    override suspend fun failStaleSending() {
        messages.replaceAll { if (it.status == DmStatus.SENDING) it.copy(status = DmStatus.FAILED) else it }
        bump()
    }

    override suspend fun replaceId(oldRumorId: String, newRumorId: String, status: DmStatus) {
        val newIndex = messages.indexOfFirst { it.rumorId == newRumorId }
        if (newIndex >= 0) {
            // A self-copy echo already upserted the final id; merge into it instead of duplicating.
            val existing = messages[newIndex]
            val oldIndex = messages.indexOfFirst { it.rumorId == oldRumorId }
            val provisionalWrapId = if (oldIndex >= 0) messages[oldIndex].wrapId else null
            messages[newIndex] = existing.copy(status = status, wrapId = existing.wrapId ?: provisionalWrapId)
            if (oldIndex >= 0) messages.removeAt(oldIndex)
        } else {
            val index = messages.indexOfFirst { it.rumorId == oldRumorId }
            if (index >= 0) messages[index] = messages[index].copy(rumorId = newRumorId, status = status)
        }
        bump()
    }

    override suspend fun markRead(peer: String, at: Long) {
        readAt[peer] = maxOf(readAt[peer] ?: 0L, at)
        bump()
    }

    override suspend fun hasWrap(wrapId: String): Boolean = messages.any { it.wrapId == wrapId }

    override suspend fun addPendingWrap(wrapId: String, receivedAt: Long) {
        pendingWraps.putIfAbsent(wrapId, receivedAt)
        attempts[wrapId] = (attempts[wrapId] ?: 0) + 1
        bump()
    }

    override suspend fun pendingAttempts(wrapId: String): Int = if (wrapId in pendingWraps) attempts[wrapId] ?: 0 else 0

    override suspend fun resetPendingAttempts() { attempts.keys.toList().forEach { attempts[it] = 0 } }

    override suspend fun pendingWraps(): List<String> = pendingWraps.entries.sortedBy { it.value }.map { it.key }

    override suspend fun removePendingWrap(wrapId: String) {
        pendingWraps.remove(wrapId)
        attempts.remove(wrapId)
        bump()
    }

    override fun pendingCount(): Flow<Int> = version.map { pendingWraps.size }

    override suspend fun markDropped(wrapId: String, at: Long, reason: DropReason) { droppedWraps.putIfAbsent(wrapId, reason) }

    override suspend fun isDropped(wrapId: String): Boolean = wrapId in droppedWraps

    override suspend fun dropReason(wrapId: String): DropReason? = droppedWraps[wrapId]

    override suspend fun pendingSummaryShown(): Boolean = summaryShown

    override suspend fun setPendingSummaryShown(shown: Boolean) { summaryShown = shown }

    override suspend fun incomingSince(receivedAfter: Long): List<DmMessage> =
        messages.filter { !it.outgoing && it.receivedAt > receivedAfter }.sortedBy { it.receivedAt }

    override suspend fun pendingSince(at: Long): PendingSince =
        pendingWraps.values.filter { it > at }.let { PendingSince(it.size, it.maxOrNull() ?: 0L) }

    override suspend fun lastSync(): Long = lastSyncAt

    override suspend fun setLastSync(at: Long) {
        lastSyncAt = at
        bump()
    }

    override suspend fun lastNotified(): Long = lastNotifiedAt

    override suspend fun setLastNotified(at: Long) {
        lastNotifiedAt = at
        bump()
    }

    override suspend fun clear() {
        messages.clear(); readAt.clear(); pendingWraps.clear(); droppedWraps.clear()
        attempts.clear(); summaryShown = false
        lastSyncAt = 0L; lastNotifiedAt = 0L
        bump()
    }
}
