package com.monostr.app.data.dm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedConversationsTest {
    private fun conv(peer: String, unread: Int) = Conversation(peer, 100, 0, "", unread, false)
    private fun TestScope.scope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `every collector shares one query per change`() = runTest {
        val signals = DmSignals()
        var queries = 0
        var unread = 1
        val shared = SharedConversations(scope(), signals) { queries++; listOf(conv("bob", unread)) }
        val a = record(shared.conversations); val b = record(shared.conversations); val c = record(shared.conversations)
        assertEquals(1, queries)
        unread = 2; signals.messagesChanged()
        assertEquals(2, queries)
        assertEquals(listOf(1, 2), a.map { it[0].unread }); assertEquals(a, b); assertEquals(a, c)
    }

    @Test
    fun `a reader that starts after a write never gets the list from before it`() = runTest {
        val signals = DmSignals()
        var unread = 1
        // the shared job runs only when the scheduler gets to it, as a slow IO read would
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val shared = SharedConversations(owner, signals) { listOf(conv("bob", unread)) }
        val hot = record(shared.conversations)
        runCurrent()
        assertEquals(1, hot.single()[0].unread)
        unread = 5; signals.readChanged() // the recompute is queued, not run yet
        assertEquals(5, shared.conversations.first()[0].unread, "the cached list from before the write is never handed out")
    }

    @Test
    fun `a failing query reaches the collector and the shared job lives on`() = runTest {
        val signals = DmSignals()
        var fail = true
        val owner = scope()
        val shared = SharedConversations(owner, signals) { if (fail) error("db gone") else listOf(conv("bob", 0)) }
        var caught: Throwable? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runCatching { shared.conversations.collect { } }.onFailure { caught = it } }
        assertEquals("db gone", caught?.message)
        assertTrue(owner.isActive)
        fail = false; signals.messagesChanged()
        assertEquals(listOf(conv("bob", 0)), shared.conversations.first())
    }
}
