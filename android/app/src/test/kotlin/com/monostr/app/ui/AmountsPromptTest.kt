package com.monostr.app.ui

import com.monostr.app.data.HintStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.ui.tips.AmountsPrompt
import com.monostr.app.ui.tips.AmountsVisibility
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Watcher
import com.monostr.tips.watcher.WatcherInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmountsPromptTest {
    private val me = "a".repeat(64)
    private val watcherPk = "c".repeat(64)
    private val address = MoneroKeys.generate().address(Network.MAINNET)
    private val setup = MoneroSetup(address.encode(), "https://w.example", watcherPk, "mainnet", 1)
    private val watcher = Watcher.Http("https://w.example", watcherPk)

    private class MemHints : HintStore {
        val done = MutableStateFlow(emptySet<String>())
        override suspend fun firstLoginAt(pubkey: String): Long? = null
        override suspend fun markFirstLogin(pubkey: String, at: Long) {}
        override fun supportHintDone(pubkey: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setSupportHintDone(pubkey: String) {}
        override fun dismissed(pubkey: String, hint: String): Flow<Boolean> = done.map { "$pubkey/$hint" in it }
        override suspend fun dismiss(pubkey: String, hint: String) { done.value = done.value + "$pubkey/$hint" }
    }

    private fun info(protocol: String?) = WatcherInfo(watcherPk, listOf("wss://r.example"), "mainnet", 3_000_000, "test", protocol)

    private class Env(
        val tips: FakeTips,
        val settings: FakeTipSettings,
        val hints: MemHints,
        val prompt: AmountsPrompt,
    )

    private fun env(
        stored: MoneroSetup? = setup,
        announced: PaymentInfo = PaymentInfo.Enabled(address, watcher, false),
        protocol: String? = "0.5",
        dismissed: Boolean = false,
        relaysOk: Boolean = true,
    ): Env {
        val tips = FakeTips(infos = mapOf(me to announced), relaysOk = relaysOk)
        val settings = FakeTipSettings(setup = stored)
        val hints = MemHints().also { if (dismissed) it.done.value = setOf("$me/amounts_hidden_prompt") }
        val visibility = AmountsVisibility(tips, FakeWatcherGateway(info = info(protocol)), settings)
        return Env(tips, settings, hints, AmountsPrompt(settings, tips, hints, visibility, me))
    }

    private val Env.dismissed get() = "$me/amounts_hidden_prompt" in hints.done.value

    @Test
    fun `asks an existing public recipient whose watcher supports it`() = runTest {
        assertTrue(env().prompt.shouldAsk())
    }

    @Test
    fun `no question below 0_5`() = runTest {
        val old = env(protocol = null)
        assertFalse(old.prompt.shouldAsk())
        assertFalse(old.dismissed)
    }

    @Test
    fun `no question when support is unknown`() = runTest {
        val e = env()
        val gateway = FakeWatcherGateway(infoError = com.monostr.tips.watcher.WatcherException.Network(java.io.IOException("down")))
        val prompt = AmountsPrompt(e.settings, e.tips, e.hints, AmountsVisibility(e.tips, gateway, e.settings), me)
        assertFalse(prompt.shouldAsk())
        assertFalse(e.dismissed)
    }

    @Test
    fun `no question without a setup`() = runTest {
        assertFalse(env(stored = null).prompt.shouldAsk())
    }

    @Test
    fun `no question when already hidden`() = runTest {
        assertFalse(env(stored = setup.copy(amountsHidden = true)).prompt.shouldAsk())
    }

    @Test
    fun `no question when dismissed`() = runTest {
        assertFalse(env(dismissed = true).prompt.shouldAsk())
    }

    @Test
    fun `no question when the announced info already hides`() = runTest {
        assertFalse(env(announced = PaymentInfo.Enabled(address, watcher, true)).prompt.shouldAsk())
    }

    @Test
    fun `no question when nothing is announced`() = runTest {
        assertFalse(env(announced = PaymentInfo.Disabled).prompt.shouldAsk())
    }

    @Test
    fun `yes republishes hidden and is dismissed`() = runTest {
        val e = env()
        assertTrue(e.prompt.answer(true))
        assertEquals(listOf(Triple(address, watcher, true)), e.tips.publishedInfos)
        assertTrue(e.settings.setupState.value!!.amountsHidden)
        assertTrue(e.dismissed)
    }

    @Test
    fun `no is dismissed and publishes nothing`() = runTest {
        val e = env()
        assertTrue(e.prompt.answer(false))
        assertTrue(e.tips.publishedInfos.isEmpty())
        assertTrue(e.dismissed)
    }

    @Test
    fun `failed yes asks again`() = runTest {
        val e = env(relaysOk = false)
        assertFalse(e.prompt.answer(true))
        assertFalse(e.dismissed)
        assertFalse(e.settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `deferring without an answer stores nothing and asks again`() = runTest {
        val e = env()
        assertTrue(e.prompt.shouldAsk())
        // dialog dismissed by back/outside: the view model only hides it, no answer call
        assertFalse(e.dismissed)
        assertTrue(e.tips.publishedInfos.isEmpty())
        assertTrue(e.prompt.shouldAsk())
    }
}
