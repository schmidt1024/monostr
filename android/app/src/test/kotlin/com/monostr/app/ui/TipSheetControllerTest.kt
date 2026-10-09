package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.data.PendingTip
import com.monostr.app.data.PendingTipStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.monostr.app.ui.tips.TipPhase
import com.monostr.app.ui.tips.TipSheetController
import com.monostr.app.ui.tips.TipTarget
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipType
import com.monostr.tips.watcher.WatcherException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TipSheetControllerTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val watcherPk = "c".repeat(64)
    private val aliceAddress = MoneroKeys.generate().address(Network.MAINNET)
    private val enabled = PaymentInfo.Enabled(aliceAddress, "https://watcher.example", watcherPk)
    private val n = note('1', alice)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private class Rig(
        val tips: FakeTips = FakeTips(),
        val gateway: FakeWatcherGateway = FakeWatcherGateway(),
        val pending: FakePendingTips = FakePendingTips(),
        val settings: FakeTipSettings = FakeTipSettings().apply { anonymousState.value = false },
    )

    private fun TestScope.controller(rig: Rig) =
        TipSheetController(rig.tips, rig.gateway, rig.settings, rig.pending, eager()) { 5_000 }

    @Test
    fun `open loads presets and the recipient's payment info`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertEquals(listOf(100_000_000L, 1_000_000_000L, 10_000_000_000L), c.state.value.presets)
        assertEquals(1_000_000_000L, c.state.value.selected)
        assertEquals(1_000_000_000L, c.state.value.amount)
        // the middle preset is preselected, not the smallest
        c.open(note('2', bob))
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
        c.dismiss()
        assertFalse(c.state.value.visible)
    }

    @Test
    fun `free amount is validated before anything is published`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        for (bad in listOf("abc", "-1", "0", "0.0000000000001", "1e3")) {
            c.setCustom(bad)
            assertNull(c.state.value.amount, "amount for '$bad'")
            c.send()
            advanceUntilIdle()
            assertEquals(TipSheetController.ERROR_AMOUNT, c.state.value.error, "error for '$bad'")
            assertEquals(TipPhase.Ready, c.state.value.phase)
        }
        assertTrue(rig.tips.sentIntents.isEmpty())
        c.setCustom("0,005")
        assertNull(c.state.value.selected, "typing a free amount clears the preset")
        assertEquals(5_000_000_000L, c.state.value.amount)
        c.send()
        advanceUntilIdle()
        val sent = rig.tips.sentIntents.single()
        assertEquals(5_000_000_000L, sent.intent.amount)
        assertEquals(n.id, sent.intent.noteId)
        assertEquals(alice, sent.intent.recipientPubkey)
        assertEquals(TipType.TIP, sent.intent.type)
        assertEquals("", sent.intent.comment)
        assertEquals(listOf(listOf("wss://watcher-relay.example")), rig.tips.sentWatcherRelays)
        assertEquals(n.id, rig.pending.state.value.single().noteId)
        assertEquals(alice, rig.pending.state.value.single().recipient)
        assertEquals(5_000_000_000L, rig.pending.state.value.single().amount)
        val pay = c.state.value.phase as TipPhase.Pay
        assertTrue(pay.uri.startsWith("monero:${aliceAddress.integrated(sent.intent.paymentId.bytes)}?tx_amount=0.005"), pay.uri)
        assertEquals(aliceAddress.integrated(sent.intent.paymentId.bytes), pay.address)
        assertEquals(5_000_000_000L, pay.amount)
        assertNull(pay.walletMissing)
        assertNull(c.state.value.notice)
    }

    @Test
    fun `missing wallet shows the fallback and can retry`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.select(10_000_000_000L)
        c.send()
        advanceUntilIdle()
        assertEquals(TipType.TIP, rig.tips.sentIntents.single().intent.type)
        c.walletResult(false)
        assertEquals(true, (c.state.value.phase as TipPhase.Pay).walletMissing)
        c.walletResult(true)
        assertEquals(false, (c.state.value.phase as TipPhase.Pay).walletMissing)
    }

    @Test
    fun `unreachable watcher does not stop the tip and offline relays are reported`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), relaysOk = false), FakeWatcherGateway(infoError = WatcherException.Network(IOException("down"))))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertTrue(c.state.value.phase is TipPhase.Pay, "phase ${c.state.value.phase}")
        assertEquals(listOf(emptyList<String>()), rig.tips.sentWatcherRelays)
        assertEquals(TipSheetController.NOTICE_OFFLINE, c.state.value.notice)
    }

    @Test
    fun `a failing publish keeps the sheet open with a message and no side effects`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), failSend = true))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertEquals(uiText(R.string.error_send_failed), c.state.value.error)
        assertTrue(rig.pending.state.value.isEmpty())
    }

    @Test
    fun `send is ignored while loading, sending or without monero`() = runTest {
        val rig = Rig(FakeTips(infos = emptyMap()))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
        c.send()
        advanceUntilIdle()
        assertTrue(rig.tips.sentIntents.isEmpty())
    }

    @Test
    fun `publish finishing after a reopen does not touch the new sheet`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)).apply { sendGate = gate })
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(TipPhase.Sending, c.state.value.phase)
        c.dismiss()
        val other = note('2', alice)
        c.open(other)
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(other.id, c.state.value.target?.key)
        assertEquals(TipPhase.Ready, c.state.value.phase, "the new sheet stays untouched")
        assertNull(c.state.value.notice)
        assertNull(c.state.value.error)
        assertEquals(n.id, rig.pending.state.value.single().noteId, "the sent tip is still recorded")
    }

    @Test
    fun `a tip publishes only the intent, no reaction`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(TipType.TIP, rig.tips.sentIntents.single().intent.type)
        assertEquals(1, rig.tips.sentIntents.size)
        assertEquals(1, rig.pending.state.value.size)
    }

    @Test
    fun `open asks the relays first and ignores a stale cached payment info`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)).apply { liveInfos = emptyMap() })
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
    }

    @Test
    fun `open falls back to the cache when the relays do not answer in time`() = runTest {
        val tips = FakeTips(infos = mapOf(alice to enabled)).apply { liveInfos = emptyMap(); liveDelayMs = 10_000 }
        val rig = Rig(tips)
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertEquals(listOf(alice), tips.cachedCalls, "the fallback is a local read")
        assertTrue(tips.staleCalls.isEmpty(), "the relays that just timed out are not asked again")
    }

    @Test
    fun `the anonymous switch starts on, is remembered and is read on open`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)), settings = FakeTipSettings())
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertTrue(c.state.value.anonymous)
        c.setAnonymous(false)
        advanceUntilIdle()
        assertFalse(c.state.value.anonymous)
        assertFalse(rig.settings.anonymousState.value)
        c.dismiss()
        c.open(n)
        advanceUntilIdle()
        assertFalse(c.state.value.anonymous)
    }

    @Test
    fun `an anonymous profile tip goes out over the anonymous path and keeps the signed event for a resend`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)), settings = FakeTipSettings())
        val c = controller(rig)
        c.open(TipTarget.Profile(alice))
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        c.send()
        advanceUntilIdle()
        assertTrue(rig.tips.sentIntents.isEmpty(), "nothing goes over the user's own connection")
        val sent = rig.tips.anonymousIntents.single()
        assertNull(sent.intent.noteId)
        assertTrue(sent.intent.anonymous)
        assertEquals("", sent.intent.comment)
        assertEquals(alice, sent.intent.recipientPubkey)
        assertEquals(listOf(listOf("wss://watcher-relay.example")), rig.tips.anonymousRelays)
        val tip = rig.pending.state.value.single()
        assertNull(tip.noteId)
        assertEquals(alice, tip.recipient)
        assertNotNull(tip.anonEvent)
        assertEquals(listOf("wss://watcher-relay.example"), tip.anonRelays)
        assertTrue(tip.anonDelivered, "a relay accepted it: no resend")
        assertTrue(c.state.value.phase is TipPhase.Pay)
        assertNull(c.state.value.notice)
    }

    @Test
    fun `an anonymous tip without known watcher relays fails loudly and never falls back to a public tip`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)), FakeWatcherGateway(infoError = WatcherException.Network(IOException("down"))), settings = FakeTipSettings())
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertEquals(TipSheetController.ERROR_ANON_UNAVAILABLE, c.state.value.error)
        assertTrue(rig.tips.sentIntents.isEmpty())
        assertTrue(rig.tips.anonymousIntents.isEmpty())
        assertTrue(rig.pending.state.value.isEmpty())
        // only the user's own choice makes it a public tip
        c.setAnonymous(false)
        assertNull(c.state.value.error)
        c.send()
        advanceUntilIdle()
        assertEquals(1, rig.tips.sentIntents.size)
        assertFalse(rig.tips.sentIntents.single().intent.anonymous)
    }

    @Test
    fun `an anonymous tip no watcher relay accepted stays pending with the offline notice`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), relaysOk = false), settings = FakeTipSettings())
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertTrue(c.state.value.phase is TipPhase.Pay)
        assertEquals(TipSheetController.NOTICE_OFFLINE, c.state.value.notice)
        assertEquals(n.id, rig.pending.state.value.single().noteId)
        assertNotNull(rig.pending.state.value.single().anonEvent)
        assertFalse(rig.pending.state.value.single().anonDelivered, "no relay accepted it: the next start sends it again")
    }

    @Test
    fun `an anonymous send that throws publishes nothing and records nothing`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), failSend = true), settings = FakeTipSettings())
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertTrue(c.state.value.anonymous)
        c.send()
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertEquals(uiText(R.string.error_send_failed), c.state.value.error)
        assertTrue(rig.tips.sentIntents.isEmpty(), "never falls back to a public tip")
        assertTrue(rig.tips.anonymousIntents.isEmpty())
        assertTrue(rig.pending.state.value.isEmpty())
    }

    @Test
    fun `a public profile tip has no note and records the recipient`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(TipTarget.Profile(alice))
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        val sent = rig.tips.sentIntents.single()
        assertNull(sent.intent.noteId)
        assertFalse(sent.intent.anonymous)
        val tip = rig.pending.state.value.single()
        assertNull(tip.noteId)
        assertEquals(alice, tip.recipient)
        assertNull(tip.anonEvent)
        assertTrue(tip.anonRelays.isEmpty())
    }

    @Test
    fun `the open sheet shows the arrival once its pending tip is settled`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        val mine = rig.pending.state.value.single().intentId
        assertFalse((c.state.value.phase as TipPhase.Pay).arrived)
        val other = "9".repeat(64)
        rig.pending.add(PendingTip(other, "2".repeat(64), 5, 5_000))
        rig.pending.remove(listOf(other))
        advanceUntilIdle()
        assertFalse((c.state.value.phase as TipPhase.Pay).arrived, "another tip settling means nothing for this sheet")
        rig.pending.remove(listOf(mine))
        advanceUntilIdle()
        assertTrue((c.state.value.phase as TipPhase.Pay).arrived)
    }

    @Test
    fun `a store that reads empty before it has the tip does not count as an arrival`() = runTest {
        // the DataStore-backed store answers a failed read with an empty list
        val inner = FakePendingTips()
        val flaky = object : PendingTipStore by inner {
            override val pending: Flow<List<PendingTip>> = flow { emit(emptyList()); emitAll(inner.state) }
        }
        val tips = FakeTips(infos = mapOf(alice to enabled))
        val c = TipSheetController(tips, FakeWatcherGateway(), FakeTipSettings().apply { anonymousState.value = false }, flaky, eager()) { 5_000 }
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertFalse((c.state.value.phase as TipPhase.Pay).arrived, "an empty read is not a receipt")
        inner.remove(listOf(inner.state.value.single().intentId))
        advanceUntilIdle()
        assertTrue((c.state.value.phase as TipPhase.Pay).arrived)
    }

    @Test
    fun `a settled tip does not touch a sheet that was closed or reopened`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        val mine = rig.pending.state.value.single().intentId
        c.dismiss()
        c.open(n)
        advanceUntilIdle()
        rig.pending.remove(listOf(mine))
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        c.dismiss()
        assertFalse(c.state.value.visible)
    }

    @Test
    fun `a profile without monero shows the no-monero state`() = runTest {
        val c = controller(Rig(FakeTips(infos = emptyMap())))
        c.open(TipTarget.Profile(bob))
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
    }
}
