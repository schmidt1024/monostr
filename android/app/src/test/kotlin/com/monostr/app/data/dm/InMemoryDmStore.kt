package com.monostr.app.data.dm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    private val signals = DmSignals()
    /** Query evaluations so far; a test hook (spec perf2 §6). */
    val queryCount: Int get() = signals.queries.get()

    private fun <T> counted(block: () -> T): T { signals.queries.incrementAndGet(); return block() }

    /** Like DmDatabase's store scope; Unconfined keeps the JVM tests single-threaded and in step. */
    private val storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    private val previews = PreviewMemo()
    private val sharedConversations = SharedConversations(storeScope, signals) { counted { computeConversations() } }

    private fun computeConversations(): List<Conversation> =
        messages.groupBy { it.peer }.map { (peer, msgs) ->
            val last = msgs.maxBy { it.createdAt }
            val read = readAt[peer] ?: 0L
            val unread = msgs.count { !it.outgoing && it.createdAt > read }
            val hasOutgoing = msgs.any { it.outgoing }
            Conversation(peer, last.createdAt, read, previews.of(peer, last.createdAt, last.content), unread, hasOutgoing)
        }.sortedByDescending { it.lastAt }

    override fun conversations(): Flow<List<Conversation>> = sharedConversations.conversations

    override fun messages(peer: String, limit: Int): Flow<List<DmMessage>> =
        signals.messages.map { counted { messages.filter { it.peer == peer }.sortedBy { it.createdAt }.takeLast(limit) } }.distinctUntilChanged()

    override suspend fun message(rumorId: String): DmMessage? = messages.firstOrNull { it.rumorId == rumorId }

    override fun unreadTotal(): Flow<Int> =
        signals.conversationStamp.map { counted { messages.count { !it.outgoing && it.createdAt > (readAt[it.peer] ?: 0L) } } }.distinctUntilChanged()

    override suspend fun upsert(message: DmMessage): Boolean {
        val index = messages.indexOfFirst { it.rumorId == message.rumorId }
        if (index >= 0) {
            val existing = messages[index]
            if (existing.wrapId != null || message.wrapId == null) return false
            messages[index] = existing.copy(wrapId = message.wrapId)
        } else {
            messages.add(message)
        }
        signals.messagesChanged()
        return true
    }

    override suspend fun setStatus(rumorId: String, status: DmStatus) {
        val index = messages.indexOfFirst { it.rumorId == rumorId }
        if (index < 0 || messages[index].status == status) return
        messages[index] = messages[index].copy(status = status)
        signals.messagesChanged()
    }

    override suspend fun failStaleSending() {
        if (messages.none { it.status == DmStatus.SENDING }) return
        messages.replaceAll { if (it.status == DmStatus.SENDING) it.copy(status = DmStatus.FAILED) else it }
        signals.messagesChanged()
    }

    override suspend fun replaceId(oldRumorId: String, newRumorId: String, status: DmStatus, wrapId: String?) {
        val newIndex = messages.indexOfFirst { it.rumorId == newRumorId }
        if (newIndex >= 0) {
            // A self-copy echo already upserted the final id; merge into it instead of duplicating.
            val existing = messages[newIndex]
            val oldIndex = messages.indexOfFirst { it.rumorId == oldRumorId }
            val provisionalWrapId = if (oldIndex >= 0) messages[oldIndex].wrapId else null
            messages[newIndex] = existing.copy(status = status, wrapId = existing.wrapId ?: provisionalWrapId ?: wrapId)
            if (oldIndex >= 0) messages.removeAt(oldIndex)
        } else {
            val index = messages.indexOfFirst { it.rumorId == oldRumorId }
            if (index >= 0) messages[index] = messages[index].let { it.copy(rumorId = newRumorId, status = status, wrapId = it.wrapId ?: wrapId) }
        }
        signals.allChanged()
    }

    override suspend fun markRead(peer: String, at: Long) {
        val old = readAt[peer] ?: 0L
        val moved = maxOf(old, at)
        readAt[peer] = moved
        // spec perf2 D2: the same bounds as the unread count
        if (messages.any { it.peer == peer && !it.outgoing && it.createdAt > old && it.createdAt <= moved }) signals.readChanged()
    }

    override suspend fun hasWrap(wrapId: String): Boolean = messages.any { it.wrapId == wrapId }

    override suspend fun addPendingWrap(wrapId: String, receivedAt: Long) {
        val inserted = pendingWraps.putIfAbsent(wrapId, receivedAt) == null
        attempts[wrapId] = (attempts[wrapId] ?: 0) + 1
        if (inserted) signals.pendingChanged()
    }

    override suspend fun pendingAttempts(wrapId: String): Int = if (wrapId in pendingWraps) attempts[wrapId] ?: 0 else 0

    override suspend fun resetPendingAttempts() { attempts.keys.toList().forEach { attempts[it] = 0 } }

    override suspend fun pendingWraps(): List<String> = pendingWraps.entries.sortedBy { it.value }.map { it.key }

    override suspend fun removePendingWrap(wrapId: String) {
        attempts.remove(wrapId)
        if (pendingWraps.remove(wrapId) != null) signals.pendingChanged()
    }

    override fun pendingCount(): Flow<Int> = signals.pending.map { counted { pendingWraps.size } }.distinctUntilChanged()

    override suspend fun markDropped(wrapId: String, at: Long, reason: DropReason) { droppedWraps.putIfAbsent(wrapId, reason) }

    override suspend fun isDropped(wrapId: String): Boolean = wrapId in droppedWraps

    /** Always loaded here: the lists are the truth. */
    override fun isKnownWrap(wrapId: String): Boolean = messages.any { it.wrapId == wrapId } || wrapId in droppedWraps

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
    }

    override suspend fun lastNotified(): Long = lastNotifiedAt

    override suspend fun setLastNotified(at: Long) {
        lastNotifiedAt = at
    }

    override suspend fun clear() {
        messages.clear(); readAt.clear(); pendingWraps.clear(); droppedWraps.clear()
        attempts.clear(); summaryShown = false
        lastSyncAt = 0L; lastNotifiedAt = 0L
        signals.allChanged()
        previews.clear()
    }
}
