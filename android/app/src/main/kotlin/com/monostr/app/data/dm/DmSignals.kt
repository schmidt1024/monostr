package com.monostr.app.data.dm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec perf2 D1: one change signal per table instead of one global version, shared by [DmDatabase] and the JVM
 * twin. A store bumps a signal only when a statement changed rows a query reads; one helper per table, so a
 * forgotten bump is one place to look (spec §8 R7).
 */
class DmSignals {
    private val messagesV = MutableStateFlow(0L)
    private val readV = MutableStateFlow(0L)
    private val pendingV = MutableStateFlow(0L)

    /** The messages table. */
    val messages: StateFlow<Long> = messagesV.asStateFlow()
    /** conversations.read_at (the read markers). */
    val read: StateFlow<Long> = readV.asStateFlow()
    /** pending_wraps. */
    val pending: StateFlow<Long> = pendingV.asStateFlow()

    /** Every query evaluation of the store; a test hook (spec perf2 §6). */
    val queries = AtomicInteger(0)

    fun messagesChanged() = messagesV.update { it + 1 }
    fun readChanged() = readV.update { it + 1 }
    fun pendingChanged() = pendingV.update { it + 1 }
    fun allChanged() { messagesChanged(); readChanged(); pendingChanged() }

    /** What the conversation list and the unread counts read: messages and read markers. */
    val conversationStamp: Flow<Stamp> = combine(messagesV, readV) { m, r -> Stamp(m, r) }

    fun currentStamp(): Stamp = Stamp(messagesV.value, readV.value)

    data class Stamp(val messages: Long, val read: Long) {
        /** This state includes every change [other] did (signals only grow). */
        fun covers(other: Stamp): Boolean = messages >= other.messages && read >= other.read
    }
}
