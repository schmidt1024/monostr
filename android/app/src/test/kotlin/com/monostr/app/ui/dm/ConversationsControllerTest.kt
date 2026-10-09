package com.monostr.app.ui.dm

import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.InMemoryDmStore
import com.monostr.app.dm.DmSyncState
import com.monostr.app.ui.FakeFeed
import com.monostr.app.ui.FakeProfiles
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import com.monostr.app.data.dm.unreadTotalExcept
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsControllerTest {
    private val bob = "a".repeat(64) // FakeFeed follows exactly this key
    private val carol = "c".repeat(64)
    private val dave = "d".repeat(64)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun msg(id: String, peer: String, at: Long, outgoing: Boolean = false, text: String = "hi $id") =
        DmMessage(id, peer, outgoing, text, at, at, if (outgoing) DmStatus.SENT else DmStatus.RECEIVED, "w$id")

    private fun TestScope.controller(
        store: InMemoryDmStore,
        profiles: FakeProfiles = FakeProfiles(),
        syncState: MutableStateFlow<DmSyncState> = MutableStateFlow(DmSyncState()),
        onRefresh: suspend () -> Unit = {},
        onUnlock: suspend () -> Int = { 0 },
        muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    ) = ConversationsController(store, profiles, FakeFeed(), syncState, store.pendingCount(), onRefresh, onUnlock, eager(), muted)

    @Test
    fun `followed peers are conversations, strangers without own reply are requests`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("1", bob, 100))
        store.upsert(msg("2", carol, 200))
        store.upsert(msg("3", dave, 300))
        store.upsert(msg("4", dave, 310, outgoing = true))
        val c = controller(store)
        c.start()
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(listOf(dave, bob), s.items.map { it.conversation.peer })
        assertTrue(s.items.none { it.request })
        assertEquals(listOf(carol), s.requests.map { it.conversation.peer })
        assertTrue(s.requests.single().request)
        // a reply of our own moves carol out of the requests
        store.upsert(msg("5", carol, 400, outgoing = true))
        advanceUntilIdle()
        assertEquals(listOf(carol, dave, bob), c.state.value.items.map { it.conversation.peer })
        assertTrue(c.state.value.requests.isEmpty())
    }

    @Test
    fun `a conversation with a muted account is neither listed nor counted, the messages stay stored`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("1", bob, 100))
        store.upsert(msg("2", carol, 200))
        val muted = MutableStateFlow(setOf(bob, carol))
        val c = controller(store, muted = muted)
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.items.isEmpty())
        assertTrue(c.state.value.requests.isEmpty())
        assertEquals(0, store.unreadTotalExcept(muted).first())
        assertEquals(1, store.messages(bob).first().size) // stored, only hidden
        muted.value = setOf(carol); advanceUntilIdle()
        assertEquals(listOf(bob), c.state.value.items.map { it.conversation.peer })
        assertTrue(c.state.value.requests.isEmpty())
        assertEquals(1, store.unreadTotalExcept(muted).first())
        muted.value = emptySet(); advanceUntilIdle()
        assertEquals(listOf(bob), c.state.value.items.map { it.conversation.peer })
        assertEquals(listOf(carol), c.state.value.requests.map { it.conversation.peer })
        assertEquals(2, store.unreadTotalExcept(muted).first())
    }

    @Test
    fun `auth hint and pending count come from the sync state`() = runTest {
        val store = InMemoryDmStore()
        val syncState = MutableStateFlow(DmSyncState())
        var refreshed = 0
        var unlocked = 0
        val gate = CompletableDeferred<Unit>()
        val c = controller(store, syncState = syncState, onRefresh = { refreshed++; gate.await() }, onUnlock = { unlocked++; 2 })
        c.start()
        advanceUntilIdle()
        assertFalse(c.state.value.loading)
        assertTrue(c.state.value.items.isEmpty())
        assertEquals(emptySet<String>(), c.state.value.authFailed)
        assertEquals(0, c.state.value.pending)
        syncState.value = DmSyncState(authFailed = setOf("wss://auth.example"))
        store.addPendingWrap("w1", 10)
        store.addPendingWrap("w2", 11)
        advanceUntilIdle()
        assertEquals(setOf("wss://auth.example"), c.state.value.authFailed)
        syncState.value = DmSyncState(authRejected = mapOf("wss://broken.example" to "error: x"))
        assertEquals(mapOf("wss://broken.example" to "error: x"), c.state.value.authRejected)
        assertEquals(emptySet<String>(), c.state.value.authFailed)
        assertEquals(2, c.state.value.pending)
        assertEquals(0, unlocked) // never automatic: Amber prompts only on a tap
        c.refresh()
        c.refresh() // ignored while the first one runs
        c.unlockPending()
        advanceUntilIdle()
        assertTrue(c.state.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(c.state.value.refreshing)
        assertEquals(1, refreshed)
        assertEquals(1, unlocked)
    }

    @Test
    fun `sorted by last message desc, names from profiles`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(msg("1", bob, 100, text = "old"))
        store.upsert(msg("2", dave, 500, outgoing = true, text = "newest"))
        store.upsert(msg("3", bob, 300, text = "newer"))
        val profiles = FakeProfiles(mapOf(bob to Profile(bob, "bob", "Bob", null, null, null)))
        val c = controller(store, profiles = profiles)
        c.start()
        advanceUntilIdle()
        val items = c.state.value.items
        assertEquals(listOf(dave, bob), items.map { it.conversation.peer })
        assertEquals(listOf("newest", "newer"), items.map { it.conversation.lastText })
        assertEquals("Bob", items[1].profile.shownName)
        assertEquals(Profile.shortPubkey(dave), items[0].profile.shownName)
        assertTrue(profiles.prefetched.any { it.containsAll(listOf(bob, dave)) })
    }
}
