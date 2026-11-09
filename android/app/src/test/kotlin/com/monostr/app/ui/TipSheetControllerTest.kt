package com.monostr.app.ui
import com.monostr.tips.watcher.WatcherInfo
import com.monostr.tips.RelayHosts
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.data.PendingTip
import com.monostr.app.data.PendingTipStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.monostr.app.ui.tips.TipPhase
import com.monostr.app.ui.tips.TipSheetController
import com.monostr.app.ui.tips.TipTarget
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Watcher
import com.monostr.tips.TipType
import com.monostr.tips.watcher.WatcherException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
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
    private val enabled = PaymentInfo.Enabled(aliceAddress, Watcher.Http("https://watcher.example", watcherPk))
    private val n = note('1', alice)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private class Rig(
        val tips: FakeTips = FakeTips(),
        val gateway: FakeWatcherGateway = FakeWatcherGateway(),
        val pending: FakePendingTips = FakePendingTips(),
        val settings: FakeTipSettings = FakeTipSettings().apply { anonymousState.value = false },
        val authorRelays: FakeAuthorRelays = FakeAuthorRelays(),
        var authorRelaysOn: Boolean = true,
    )

    private fun TestScope.controller(rig: Rig) =
        TipSheetController(rig.tips, rig.gateway, rig.settings, rig.pending, eager(), rig.authorRelays, { rig.authorRelaysOn }) { 5_000 }

    @Test
    fun `a watcher on a lan address is never asked and its relays are not used`() = runTest {
        val lan = PaymentInfo.Enabled(aliceAddress, Watcher.Http("https://192.168.1.10:8080", watcherPk))
        val rig = Rig(FakeTips(infos = mapOf(alice to lan)))
        val c = TipSheetController(rig.tips, rig.gateway, rig.settings, rig.pending, eager(), rig.authorRelays, { true }, now = { 5_000 }, watcherAllowed = RelayHosts::isPublic)
        c.open(n)
        c.setCustom("0,005")
        c.send()
        advanceUntilIdle()
        assertEquals(0, rig.gateway.infoCalls)
        assertEquals(listOf(emptyList<String>()), rig.tips.sentWatcherRelays)
    }

    @Test
    fun `lan relays a public watcher names are left out`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        rig.gateway.info = WatcherInfo(watcherPk, listOf("wss://10.0.0.3", "wss://watcher-relay.example", "ws://plain.example"), "mainnet", 3_000_000, "test")
        val c = controller(rig)
        c.open(n)
        c.setCustom("0,005")
        c.send()
        advanceUntilIdle()
        assertEquals(listOf(listOf("wss://watcher-relay.example")), rig.tips.sentWatcherRelays)
    }

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

    private val watcherRelay = "wss://watcher-relay.example"

    @Test
    fun `a public tip the watcher's relays accepted at once is not sent to them again`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        val tip = rig.pending.state.value.single()
        assertEquals(listOf(watcherRelay), tip.watcherRelays)
        assertTrue(tip.watcherDelivered)
        assertTrue(rig.tips.watcherResends.isEmpty())
    }

    @Test
    fun `a public tip only the own relays accepted goes to the watcher's relays again until one takes it`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), watcherAccepts = false))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        runCurrent()
        assertTrue(c.state.value.phase is TipPhase.Pay, "the wallet opens at once")
        assertFalse(rig.pending.state.value.single().watcherDelivered)
        advanceTimeBy(TipSheetController.REDELIVER_DELAYS_MS[0] + 1)
        assertEquals(1, rig.tips.watcherResends.size)
        assertEquals(listOf(watcherRelay), rig.tips.watcherResends.single().second)
        rig.tips.watcherAccepts = true
        advanceUntilIdle()
        assertEquals(2, rig.tips.watcherResends.size, "no further send once a relay took it")
        assertTrue(rig.pending.state.value.single().watcherDelivered)
    }

    @Test
    fun `the repeats end after the last delay and the tip stays undelivered for the next start`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), watcherAccepts = false))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(TipSheetController.REDELIVER_DELAYS_MS.size, rig.tips.watcherResends.size)
        assertFalse(rig.pending.state.value.single().watcherDelivered)
    }

    @Test
    fun `a watcher that did not answer at the send is asked again and its relays are kept`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), watcherAccepts = false))
        rig.gateway.infoError = IllegalStateException("no answer")
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        runCurrent()
        assertTrue(rig.pending.state.value.single().watcherRelays.isEmpty())
        rig.gateway.infoError = null
        advanceTimeBy(TipSheetController.REDELIVER_DELAYS_MS[0] + 1)
        assertEquals(listOf(watcherRelay), rig.pending.state.value.single().watcherRelays)
        assertEquals(listOf(watcherRelay), rig.tips.watcherResends.single().second)
    }

    @Test
    fun `an anonymous tip no relay took is sent again soon, not only at the next start`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), relaysOk = false), settings = FakeTipSettings())
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        runCurrent()
        rig.tips.relaysOk = true
        advanceUntilIdle()
        assertEquals(1, rig.tips.anonymousResends.size)
        assertTrue(rig.pending.state.value.single().anonDelivered)
    }

    @Test
    fun `a tip settled meanwhile is not sent again`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), watcherAccepts = false))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        runCurrent()
        rig.pending.settle(listOf(rig.pending.state.value.single().intentId))
        advanceUntilIdle()
        assertTrue(rig.tips.watcherResends.isEmpty())
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
        rig.pending.settle(listOf(other))
        advanceUntilIdle()
        assertFalse((c.state.value.phase as TipPhase.Pay).arrived, "another tip settling means nothing for this sheet")
        rig.pending.settle(listOf(mine))
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
        val c = TipSheetController(tips, FakeWatcherGateway(), FakeTipSettings().apply { anonymousState.value = false }, flaky, eager(), FakeAuthorRelays(), { true }) { 5_000 }
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertFalse((c.state.value.phase as TipPhase.Pay).arrived, "an empty read is not a receipt")
        inner.settle(listOf(inner.state.value.single().intentId))
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
        rig.pending.settle(listOf(mine))
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

    @Test
    fun `public tip to personal watcher uses tag relays`() = runTest {
        val personal = PaymentInfo.Enabled(aliceAddress, Watcher.Personal(watcherPk, listOf("wss://w.example")))
        val rig = Rig(FakeTips(infos = mapOf(alice to personal)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(listOf("wss://w.example"), rig.tips.sentWatcherRelays.last())
        assertEquals(0, rig.gateway.infoCalls)
    }

    @Test
    fun `anonymous tip to personal watcher uses tag relays`() = runTest {
        val personal = PaymentInfo.Enabled(aliceAddress, Watcher.Personal(watcherPk, listOf("wss://w.example")))
        val rig = Rig(FakeTips(infos = mapOf(alice to personal)), settings = FakeTipSettings().apply { anonymousState.value = true })
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertEquals(listOf("wss://w.example"), rig.tips.anonymousRelays.last())
        assertEquals(0, rig.gateway.infoCalls)
    }

    private val sub = "8" + "A".repeat(94)

    @Test
    fun `payto only recipient gets payto`() = runTest {
        val rig = Rig(FakeTips().apply { paytos = mapOf(alice to sub) })
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.PayTo(sub, "monero:$sub"), c.state.value.phase)
    }

    private val dittoRelays = listOf("wss://relay.ditto.example", "wss://nostr.mom.example")

    @Test
    fun `paytoOnlyOnAuthorRelaysGetsPayTo`() = runTest {
        // an Amethyst/Ditto user keeps the kind 10133 on its own write relays only
        val rig = Rig(FakeTips().apply { outboxPaytos = mapOf(alice to sub) }, authorRelays = FakeAuthorRelays(mapOf(alice to dittoRelays)))
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.PayTo(sub, "monero:$sub"), c.state.value.phase)
        assertEquals(listOf(alice to dittoRelays), rig.tips.paytoFetches)
    }

    @Test
    fun `authorRelaysOffAsksNoForeignRelay`() = runTest {
        val rig = Rig(FakeTips().apply { outboxPaytos = mapOf(alice to sub) }, authorRelays = FakeAuthorRelays(mapOf(alice to dittoRelays)), authorRelaysOn = false)
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
        assertTrue(rig.tips.paytoFetches.isEmpty())
    }

    @Test
    fun `readyDoesNotWaitForOutboxFetch`() = runTest {
        // the forced read is held until the outbox fetch is under way, then answers with a valid info
        val live = CompletableDeferred<Unit>()
        val tips = FakeTips().apply { liveInfos = mapOf(alice to enabled); liveGate = live; outboxGate = CompletableDeferred() }
        val rig = Rig(tips, authorRelays = FakeAuthorRelays(mapOf(alice to dittoRelays)))
        val c = controller(rig)
        c.open(n)
        runCurrent()
        assertEquals(TipPhase.Loading, c.state.value.phase)
        live.complete(Unit)
        runCurrent()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertEquals(0L, currentTime, "no virtual time spent on the outbox fetch")
        assertEquals(listOf(alice to dittoRelays), rig.tips.paytoFetches, "the outbox fetch started")
        assertEquals(1, rig.tips.outboxCancelled, "and was cancelled once the info was valid")
    }

    @Test
    fun `dismissCancelsOutboxFetchAtOnce`() = runTest {
        val tips = FakeTips().apply { liveInfos = emptyMap(); outboxGate = CompletableDeferred() }
        val c = controller(Rig(tips, authorRelays = FakeAuthorRelays(mapOf(alice to dittoRelays))))
        c.open(n)
        runCurrent()
        assertEquals(TipPhase.Loading, c.state.value.phase, "waiting for the outbox fetch")
        c.dismiss()
        runCurrent()
        assertEquals(1, tips.outboxCancelled, "the recipient's relays are released without waiting for the budget")
        assertEquals(0L, currentTime)
        c.open(n)
        runCurrent()
        c.open(n) // a reopen cancels the previous load too
        runCurrent()
        assertEquals(2, tips.outboxCancelled)
    }

    @Test
    fun `outboxFetchRespectsSharedBudget`() = runTest {
        // the forced read answers "no info" after 2 s; the outbox fetch never ends: one 3 s budget from opening, not 2 + 3
        val tips = FakeTips().apply { liveInfos = emptyMap(); liveDelayMs = 2_000; outboxGate = CompletableDeferred() }
        val c = controller(Rig(tips, authorRelays = FakeAuthorRelays(mapOf(alice to dittoRelays))))
        c.open(n)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(TipPhase.Loading, c.state.value.phase)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
    }

    @Test
    fun `payment info wins over payto`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)).apply { paytos = mapOf(alice to sub) })
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
    }

    @Test
    fun `no payto stays no monero`() = runTest {
        val c = controller(Rig(FakeTips().apply { paytos = mapOf(bob to sub) }))
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
    }

    @Test
    fun `payto wallet missing shows the fallback`() = runTest {
        val c = controller(Rig(FakeTips().apply { paytos = mapOf(alice to sub) }))
        c.open(n)
        advanceUntilIdle()
        c.payToWalletResult(false)
        assertEquals(true, (c.state.value.phase as TipPhase.PayTo).walletMissing)
        c.payToWalletResult(true)
        assertEquals(false, (c.state.value.phase as TipPhase.PayTo).walletMissing)
    }

    @Test
    fun `payto sends no intent and records nothing`() = runTest {
        val rig = Rig(FakeTips().apply { paytos = mapOf(alice to sub) })
        val c = controller(rig)
        c.open(n)
        advanceUntilIdle()
        c.payToWalletResult(true)
        assertTrue(rig.tips.sentIntents.isEmpty())
        assertTrue(rig.pending.pending.first().isEmpty())
    }

    private fun TestScope.gated(rig: Rig, send: SendRig) =
        TipSheetController(rig.tips, rig.gateway, rig.settings, rig.pending, eager(), rig.authorRelays, { rig.authorRelaysOn }, sendGate = send.send, now = { 5_000 })

    @Test
    fun `tip send refused while canSend is false, with reason, no intent`() = runTest {
        for (anonymous in listOf(false, true)) {
            val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
            rig.settings.anonymousState.value = anonymous
            val send = SendRig(connected = false)
            val c = gated(rig, send)
            c.open(n)
            advanceUntilIdle()
            c.send()
            advanceUntilIdle()
            assertEquals(TipSheetController.ERROR_TOR, c.state.value.error)
            assertEquals(TipPhase.Ready, c.state.value.phase, "the sheet stays as it was")
            assertTrue(rig.tips.sentIntents.isEmpty() && rig.tips.anonymousIntents.isEmpty(), "no intent")
            assertTrue(rig.pending.state.value.isEmpty(), "no pending tip")
            // not a queue: Tor coming back sends nothing
            send.connected.value = true
            advanceUntilIdle()
            assertTrue(rig.tips.sentIntents.isEmpty() && rig.tips.anonymousIntents.isEmpty())
            assertTrue(rig.tips.watcherResends.isEmpty() && rig.tips.anonymousResends.isEmpty())
            // the user's own tap then works
            c.send()
            advanceUntilIdle()
            assertEquals(1, rig.pending.state.value.size)
        }
    }

    @Test
    fun `Tor going away while the watcher is asked refuses the tip`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val send = SendRig()
        val c = gated(rig, send)
        c.open(n)
        advanceUntilIdle()
        val slow = CompletableDeferred<Unit>()
        rig.gateway.infoGate = slow
        c.send()
        send.connected.value = false
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(TipSheetController.ERROR_TOR, c.state.value.error)
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertTrue(rig.tips.sentIntents.isEmpty())
    }

    @Test
    fun `an intent nobody took because Tor just went leaves no pending tip and no repeat`() = runTest {
        for (anonymous in listOf(false, true)) {
            val rig = Rig(FakeTips(infos = mapOf(alice to enabled), relaysOk = false, watcherAccepts = false))
            rig.settings.anonymousState.value = anonymous
            val send = SendRig()
            val c = gated(rig, send)
            c.open(n)
            advanceUntilIdle()
            val inFlight = CompletableDeferred<Unit>()
            rig.tips.sendGate = inFlight
            c.send()
            send.connected.value = false
            inFlight.complete(Unit)
            advanceUntilIdle()
            assertEquals(TipSheetController.ERROR_TOR, c.state.value.error)
            assertEquals(TipPhase.Ready, c.state.value.phase)
            assertTrue(rig.pending.state.value.isEmpty())
            send.connected.value = true
            advanceUntilIdle()
            assertTrue(rig.tips.watcherResends.isEmpty() && rig.tips.anonymousResends.isEmpty())
        }
    }

    @Test
    fun `a tip sent under Tor is marked, travels Tor only, and one sent direct is neither`() = runTest {
        for ((mode, viaTor) in listOf(com.monostr.nostr.net.NetworkMode.Tor(1, 2) to true, com.monostr.nostr.net.NetworkMode.Direct to false)) {
            val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
            rig.settings.anonymousState.value = true
            val c = gated(rig, SendRig(mode))
            c.open(n)
            advanceUntilIdle()
            c.send()
            advanceUntilIdle()
            assertEquals(listOf(viaTor), rig.tips.anonymousTorOnly)
            assertEquals(viaTor, rig.pending.state.value.single().underTor)
        }
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = gated(rig, SendRig())
        c.open(n)
        advanceUntilIdle()
        c.send()
        advanceUntilIdle()
        assertTrue(rig.pending.state.value.single().underTor, "a public one too")
    }

    @Test
    fun `a send begun in Direct and finished after turning Tor on counts as under Tor`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        rig.settings.anonymousState.value = true
        val send = SendRig(com.monostr.nostr.net.NetworkMode.Direct)
        val c = gated(rig, send)
        c.open(n)
        advanceUntilIdle()
        val inFlight = CompletableDeferred<Unit>()
        rig.tips.sendGate = inFlight
        c.send()
        send.gate.set(com.monostr.nostr.net.NetworkMode.Tor(1, 2))
        inFlight.complete(Unit)
        advanceUntilIdle()
        assertTrue(rig.pending.state.value.single().underTor)
    }

    @Test
    fun `Tor leaving during the repeats ends them, also when it is back before the next one`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), watcherAccepts = false))
        val send = SendRig()
        val c = gated(rig, send)
        c.open(n)
        runCurrent()
        c.send()
        runCurrent()
        assertEquals(0, rig.tips.watcherResends.size)
        advanceTimeBy(TipSheetController.REDELIVER_DELAYS_MS[0] + 1)
        assertEquals(1, rig.tips.watcherResends.size, "the schedule runs while Tor is connected")
        send.connected.value = false
        runCurrent()
        send.connected.value = true
        advanceUntilIdle()
        assertEquals(1, rig.tips.watcherResends.size, "no send later on its own")
        assertFalse(rig.pending.state.value.single().watcherDelivered)
    }

    @Test
    fun `an underTor tip is not repeated once the route is direct`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), relaysOk = false))
        rig.settings.anonymousState.value = true
        val send = SendRig()
        val c = gated(rig, send)
        c.open(n)
        runCurrent()
        c.send()
        runCurrent()
        assertTrue(rig.pending.state.value.single().underTor)
        send.gate.set(com.monostr.nostr.net.NetworkMode.Direct)
        advanceUntilIdle()
        assertTrue(rig.tips.anonymousResends.isEmpty(), "that would link the anonymous intent to the IP")
    }

    @Test
    fun `the wallet note comes once, with a phase that leads to the wallet, and not for a sheet that cannot pay`() = runTest {
        var taken = 0
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled)))
        val c = TipSheetController(rig.tips, rig.gateway, rig.settings, rig.pending, eager(), rig.authorRelays, { true }, takeWalletNote = { ++taken == 1 }, now = { 5_000 })
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.Ready, c.state.value.phase)
        assertTrue(c.state.value.walletNote)
        c.dismiss()
        c.open(n)
        advanceUntilIdle()
        assertFalse(c.state.value.walletNote)

        var asked = false
        val blocked = TipSheetController(FakeTips(), rig.gateway, rig.settings, rig.pending, eager(), rig.authorRelays, { true }, sendGate = SendRig(connected = false).send, takeWalletNote = { asked = true; true }, now = { 5_000 })
        blocked.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.Blocked, blocked.state.value.phase)
        assertFalse(blocked.state.value.walletNote)
        assertFalse(asked, "not used up by a sheet that never reaches the wallet")
    }

    @Test
    fun `with Tor not connected and no payment info known the sheet gives the reason, not that the recipient has no Monero`() = runTest {
        val rig = Rig(FakeTips())
        val c = gated(rig, SendRig(connected = false))
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.Blocked, c.state.value.phase)
        assertEquals(TipSheetController.ERROR_TOR, c.state.value.error)
    }

    @Test
    fun `with Tor connected a recipient without payment info still reads as no Monero`() = runTest {
        val c = gated(Rig(FakeTips()), SendRig())
        c.open(n)
        advanceUntilIdle()
        assertEquals(TipPhase.NoMonero, c.state.value.phase)
        assertNull(c.state.value.error)
    }

    @Test
    fun `a late public underTor tip asks the watcher nothing once the route is direct`() = runTest {
        val rig = Rig(FakeTips(infos = mapOf(alice to enabled), watcherAccepts = false))
        rig.gateway.infoError = IllegalStateException("no answer")
        val send = SendRig()
        val c = gated(rig, send)
        c.open(n)
        runCurrent()
        c.send()
        runCurrent()
        assertTrue(rig.pending.state.value.single().underTor)
        assertTrue(rig.pending.state.value.single().watcherRelays.isEmpty())
        val asked = rig.gateway.infoCalls
        send.gate.set(com.monostr.nostr.net.NetworkMode.Direct)
        rig.gateway.infoError = null
        advanceUntilIdle()
        assertEquals(asked, rig.gateway.infoCalls, "a direct HTTP call to the watcher")
        assertTrue(rig.tips.watcherResends.isEmpty())
    }
}
