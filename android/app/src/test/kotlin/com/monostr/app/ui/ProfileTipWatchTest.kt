package com.monostr.app.ui

import com.monostr.app.data.PendingTip
import com.monostr.app.ui.tips.ProfileTipWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileTipWatchTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val mine = "1".repeat(64)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private class Rig(val tips: FakeTips = FakeTips(), val pending: FakePendingTips = FakePendingTips()) {
        var arrivals = 0
        /** The watch's scope; tests cancel it at the end. */
        lateinit var scope: CoroutineScope
    }

    private fun TestScope.watch(rig: Rig, collect: Boolean = true, visible: Boolean = true): ProfileTipWatch {
        val scope = eager()
        rig.scope = scope
        val w = ProfileTipWatch(alice, rig.tips, rig.pending, scope) { 5_000 }
        if (!visible) w.setVisible(false)
        if (collect) scope.launch { w.arrived.collect { rig.arrivals++ } }
        w.start()
        return w
    }

    private fun anonymous(id: String, at: Long, relays: List<String> = listOf("wss://w.example")) =
        PendingTip(id, null, 5, at, recipient = alice, anonEvent = "{}", anonRelays = relays)

    @Test
    fun `without a pending profile tip for this person nothing is asked and nothing is pending`() = runTest {
        val rig = Rig()
        rig.pending.add(PendingTip("2".repeat(64), note('1', alice).id, 5, 4_000, recipient = alice)) // a note tip to her
        rig.pending.add(PendingTip("3".repeat(64), null, 5, 4_000, recipient = bob))                  // a profile tip to someone else
        rig.pending.add(PendingTip("4".repeat(64), null, 5, 4_000 - 86_400, recipient = alice))       // expired
        val w = watch(rig)
        advanceUntilIdle()
        assertFalse(w.pendingTip.value)
        assertTrue(rig.tips.profileObserved.isEmpty(), "no receipt request without a pending profile tip")
    }

    @Test
    fun `a pending profile tip shows, is watched from shortly before it was sent, and its receipt settles it once`() = runTest {
        val rig = Rig()
        val w = watch(rig)
        advanceUntilIdle()
        rig.pending.add(PendingTip(mine, null, 5, 4_000, recipient = alice))
        advanceUntilIdle()
        assertTrue(w.pendingTip.value)
        assertEquals(listOf(alice to 4_000L - ProfileTipWatch.SINCE_MARGIN), rig.tips.profileObserved)
        rig.tips.profileReceipts.value = setOf(mine)
        advanceUntilIdle()
        assertTrue(rig.pending.state.value.isEmpty())
        assertFalse(w.pendingTip.value)
        assertEquals(1, rig.arrivals)
    }

    @Test
    fun `a receipt for another intent settles nothing`() = runTest {
        val rig = Rig()
        val noteTip = PendingTip("2".repeat(64), note('1', alice).id, 5, 4_000, recipient = alice)
        rig.pending.add(PendingTip(mine, null, 5, 4_000, recipient = alice))
        rig.pending.add(noteTip)
        val w = watch(rig)
        advanceUntilIdle()
        rig.tips.profileReceipts.value = setOf("9".repeat(64), noteTip.intentId) // someone else's tip, and an id that belongs to a note tip
        advanceUntilIdle()
        assertTrue(w.pendingTip.value)
        assertEquals(2, rig.pending.state.value.size)
        assertEquals(0, rig.arrivals)
    }

    @Test
    fun `of two pending profile tips only the confirmed one goes`() = runTest {
        val rig = Rig()
        val second = "5".repeat(64)
        rig.pending.add(PendingTip(mine, null, 5, 4_000, recipient = alice))
        rig.pending.add(PendingTip(second, null, 5, 4_500, recipient = alice))
        val w = watch(rig)
        advanceUntilIdle()
        rig.tips.profileReceipts.value = setOf(mine)
        advanceUntilIdle()
        assertEquals(listOf(second), rig.pending.state.value.map { it.intentId })
        assertTrue(w.pendingTip.value, "the other one is still waiting")
        assertEquals(1, rig.arrivals)
    }

    @Test
    fun `an anonymous pending profile tip is watched over its own connection only`() = runTest {
        val rig = Rig()
        rig.pending.add(anonymous(mine, 4_000))
        val w = watch(rig)
        advanceUntilIdle()
        assertTrue(w.pendingTip.value)
        assertEquals(listOf(Triple(alice, 4_000L - ProfileTipWatch.SINCE_MARGIN, listOf("wss://w.example"))), rig.tips.anonymousProfileObserved)
        assertTrue(rig.tips.profileObserved.isEmpty(), "never asked over the user's connection")
        rig.tips.anonymousProfileReceipts.value = setOf(mine)
        advanceUntilIdle()
        assertTrue(rig.pending.state.value.isEmpty())
        assertEquals(1, rig.arrivals)
        assertFalse(w.pendingTip.value)
        assertEquals(1, rig.tips.anonymousProfileObserved.size, "one connection for the whole wait")
        assertTrue(rig.tips.profileObserved.isEmpty())
        rig.scope.cancel()
    }

    @Test
    fun `a public and an anonymous pending tip each take their own path`() = runTest {
        val rig = Rig()
        val public = "5".repeat(64)
        rig.pending.add(PendingTip(public, null, 5, 4_500, recipient = alice))
        rig.pending.add(anonymous(mine, 4_000, listOf("wss://w2.example", "wss://w.example")))
        rig.pending.add(anonymous("6".repeat(64), 4_200, listOf("wss://w.example")))
        watch(rig)
        advanceUntilIdle()
        assertEquals(listOf(alice to 4_500L - ProfileTipWatch.SINCE_MARGIN), rig.tips.profileObserved)
        assertEquals(listOf(Triple(alice, 4_000L - ProfileTipWatch.SINCE_MARGIN, listOf("wss://w.example", "wss://w2.example"))), rig.tips.anonymousProfileObserved)
        rig.scope.cancel()
    }

    @Test
    fun `nothing is asked while the profile is not visible`() = runTest {
        val rig = Rig()
        val w = watch(rig, visible = false)
        rig.pending.add(PendingTip("5".repeat(64), null, 5, 4_500, recipient = alice))
        rig.pending.add(anonymous(mine, 4_000))
        advanceUntilIdle()
        assertTrue(w.pendingTip.value, "the marker follows the store regardless")
        assertTrue(rig.tips.profileObserved.isEmpty())
        assertTrue(rig.tips.anonymousProfileObserved.isEmpty())
        w.setVisible(true)
        advanceUntilIdle()
        assertEquals(1, rig.tips.profileObserved.size)
        assertEquals(1, rig.tips.anonymousProfileObserved.size)
        rig.scope.cancel()
    }

    @Test
    fun `an arrival settled while nobody collects reaches the next collector`() = runTest {
        val rig = Rig()
        rig.pending.add(PendingTip(mine, null, 5, 4_000, recipient = alice))
        val w = watch(rig, collect = false)
        advanceUntilIdle()
        rig.tips.profileReceipts.value = setOf(mine)
        advanceUntilIdle()
        assertTrue(rig.pending.state.value.isEmpty())
        rig.scope.launch { w.arrived.collect { rig.arrivals++ } }
        advanceUntilIdle()
        assertEquals(1, rig.arrivals)
        rig.scope.cancel()
    }

    @Test
    fun `an anonymous arrival is signalled even when the store write suspends after the entry is gone`() = runTest {
        val rig = Rig()
        // the removal ends the watch (its key becomes null) while remove is still suspended
        rig.pending.afterRemove = { delay(1) }
        rig.tips.anonymousProfileReceipts.value = setOf(mine)
        rig.pending.add(anonymous(mine, 4_000))
        val w = watch(rig)
        advanceUntilIdle()
        assertTrue(rig.pending.state.value.isEmpty())
        assertFalse(w.pendingTip.value)
        assertEquals(1, rig.arrivals)
        rig.scope.cancel()
    }

    @Test
    fun `a failing anonymous watch is taken up again`() = runTest {
        val rig = Rig()
        rig.tips.anonymousProfileError = IllegalStateException("relay gone")
        rig.tips.anonymousProfileReceipts.value = setOf(mine)
        rig.pending.add(anonymous(mine, 4_000))
        val w = watch(rig)
        runCurrent()
        assertEquals(1, rig.tips.anonymousProfileObserved.size)
        assertTrue(w.pendingTip.value, "nothing settled yet")
        advanceTimeBy(ProfileTipWatch.RETRY_MS + 1)
        runCurrent()
        assertEquals(2, rig.tips.anonymousProfileObserved.size)
        assertTrue(rig.pending.state.value.isEmpty())
        assertEquals(1, rig.arrivals)
        rig.scope.cancel()
    }
}
