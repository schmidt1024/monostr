package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.ui.settings.MoneroSettingsController
import com.monostr.app.ui.settings.PersonalWatcherLine
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Watcher
import com.monostr.tips.WatcherStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoneroSettingsControllerTest {
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `presets are validated and stored, monero can be disabled, view key deleted, relays adopted`() = runTest {
        val setup = MoneroSetup("4addr", "https://w.example", "c".repeat(64), "mainnet", 1)
        val settings = FakeTipSettings(setup = setup)
        val secrets = FakeSecrets().apply { put(KeystoreSecretStore.SECRET_VIEW_KEY, "ab".repeat(32)) }
        val tips = FakeTips()
        val gateway = FakeWatcherGateway()
        var adoptResult = true
        val c = MoneroSettingsController(settings, secrets, tips, gateway, { adoptResult }, eager())
        c.start()
        advanceUntilIdle()
        assertEquals(setup, c.state.value.setup)
        assertTrue(c.state.value.hasViewKey)
        assertEquals(listOf("0.0001", "0.001", "0.01"), c.state.value.presets)
        c.savePresets(listOf("0.002", "abc", ""))
        advanceUntilIdle()
        assertEquals(MoneroSettingsController.errPresets(com.monostr.app.data.AmountUnit.XMR), c.state.value.error)
        assertEquals(listOf("0.0001", "0.001", "0.01"), c.state.value.presets)
        c.savePresets(listOf("0,01", "0.002", ""))
        advanceUntilIdle()
        assertNull(c.state.value.error)
        assertEquals(listOf(2_000_000_000L, 10_000_000_000L), settings.presetsState.value)
        assertEquals(listOf("0.002", "0.01"), c.state.value.presets)
        c.adoptRelayList()
        advanceUntilIdle()
        assertEquals(uiText(R.string.settings_msg_relays_adopted), c.state.value.message)
        adoptResult = false
        c.adoptRelayList()
        advanceUntilIdle()
        assertEquals(uiText(R.string.settings_msg_no_relay_list), c.state.value.message)
        c.deleteViewKey()
        advanceUntilIdle()
        assertFalse(c.state.value.hasViewKey)
        assertNull(secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
        c.disable()
        advanceUntilIdle()
        assertEquals(listOf("https://w.example"), gateway.unregistered)
        assertEquals(1, tips.disabledCalls)
        assertNull(settings.setupState.value)
        assertNull(c.state.value.setup)
        assertEquals(uiText(R.string.settings_msg_disabled), c.state.value.message)
    }

    @Test
    fun `disabling without a reachable relay keeps the setup and reports it`() = runTest {
        val setup = MoneroSetup("4addr", "https://w.example", "c".repeat(64), "mainnet", 1)
        val settings = FakeTipSettings(setup = setup)
        val tips = FakeTips(relaysOk = false)
        val c = MoneroSettingsController(settings, FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager())
        c.start()
        advanceUntilIdle()
        c.disable()
        advanceUntilIdle()
        assertEquals(1, tips.disabledCalls)
        assertEquals(setup, settings.setupState.value)
        assertEquals(setup, c.state.value.setup)
        assertEquals(MoneroSettingsController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertFalse(c.state.value.busy)
        assertNull(c.state.value.message)
    }

    @Test
    fun `without a setup here the address announced from another device is shown`() = runTest {
        val self = "5".repeat(64)
        val announced = MoneroKeys.generate().address(Network.MAINNET)
        val tips = FakeTips(infos = mapOf(self to PaymentInfo.Enabled(announced, Watcher.Http("https://w.example", "c".repeat(64)))))
        val c = MoneroSettingsController(FakeTipSettings(), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager(), selfPubkey = self)
        c.start()
        advanceUntilIdle()
        assertNull(c.state.value.setup)
        assertEquals(announced.encode(), c.state.value.announced)
    }

    @Test
    fun `a setup on this device shows itself, not the announced address`() = runTest {
        val self = "5".repeat(64)
        val own = MoneroKeys.generate().address(Network.MAINNET)
        val setup = MoneroSetup(own.encode(), "https://w.example", "c".repeat(64), "mainnet", 1)
        val tips = FakeTips(infos = mapOf(self to PaymentInfo.Enabled(own, Watcher.Http("https://w.example", "c".repeat(64)))))
        val c = MoneroSettingsController(FakeTipSettings(setup = setup), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager(), selfPubkey = self)
        c.start()
        advanceUntilIdle()
        assertEquals(setup, c.state.value.setup)
        assertNull(c.state.value.announced)
    }

    @Test
    fun `presets grow to six fields, then adding is disabled`() = runTest {
        val c = MoneroSettingsController(FakeTipSettings(), FakeSecrets(), FakeTips(), FakeWatcherGateway(), { true }, eager())
        c.start()
        advanceUntilIdle()
        assertEquals(3, c.state.value.presets.size)
        assertTrue(c.state.value.canAddPreset)
        repeat(5) { c.addPreset() }
        assertEquals(6, c.state.value.presets.size)
        assertFalse(c.state.value.canAddPreset)
        assertEquals(listOf("0.0001", "0.001", "0.01", "", "", ""), c.state.value.presets)
        c.editPreset(3, "0.1")
        assertEquals("0.1", c.state.value.presets[3])
        c.savePresets()
        advanceUntilIdle()
        assertNull(c.state.value.error)
        assertEquals(listOf("0.0001", "0.001", "0.01", "0.1"), c.state.value.presets)
    }

    @Test
    fun `removing presets leaves at least one field`() = runTest {
        val c = MoneroSettingsController(FakeTipSettings(), FakeSecrets(), FakeTips(), FakeWatcherGateway(), { true }, eager())
        c.start()
        advanceUntilIdle()
        c.removePreset(0)
        assertEquals(listOf("0.001", "0.01"), c.state.value.presets)
        c.removePreset(1)
        c.removePreset(0)
        assertEquals(listOf("0.001"), c.state.value.presets, "the last field stays")
        assertTrue(c.state.value.canAddPreset)
    }

    @Test
    fun `in mXMR the preset fields read and save in mXMR`() = runTest {
        val settings = FakeTipSettings()
        val c = MoneroSettingsController(settings, FakeSecrets(), FakeTips(), FakeWatcherGateway(), { true }, eager())
        c.start()
        advanceUntilIdle()
        c.setUnit(com.monostr.app.data.AmountUnit.MXMR)
        advanceUntilIdle()
        assertEquals(listOf("0.1", "1", "10"), c.state.value.presets)
        c.savePresets(listOf("2", "0,5"))
        advanceUntilIdle()
        assertEquals(listOf(500_000_000L, 2_000_000_000L), settings.presetsState.value)
    }

    private val watcherHex = "d".repeat(64)
    private val relays = listOf("wss://w.example", "wss://w2.example")
    private val personal = MoneroSetup("4addr", "", watcherHex, "mainnet", 1, relays)
    private val status = WatcherStatus(watcherHex, "5".repeat(64), MoneroKeys.generate().address(Network.MAINNET), Network.MAINNET, 3_512_345, "0.4.0", 1_000)

    @Test
    fun `personal setup loads the watcher status`() = runTest {
        val tips = FakeTips().apply { statuses[watcherHex] = status }
        val c = MoneroSettingsController(FakeTipSettings(setup = personal), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager(), now = { 1_100 })
        c.start()
        advanceUntilIdle()
        assertEquals(watcherHex to relays, tips.statusRequests.last())
        assertEquals(status, c.state.value.watcherStatus)
        assertTrue(c.state.value.statusChecked)
    }

    @Test
    fun `http setup loads no status`() = runTest {
        val setup = MoneroSetup("4addr", "https://w.example", "c".repeat(64), "mainnet", 1)
        val tips = FakeTips()
        val c = MoneroSettingsController(FakeTipSettings(setup = setup), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager())
        c.start()
        advanceUntilIdle()
        assertTrue(tips.statusRequests.isEmpty())
        assertNull(c.state.value.watcherStatus)
    }

    @Test
    fun `disable of a personal watcher sends no DELETE`() = runTest {
        val settings = FakeTipSettings(setup = personal)
        val tips = FakeTips()
        val gateway = FakeWatcherGateway()
        val c = MoneroSettingsController(settings, FakeSecrets(), tips, gateway, { true }, eager())
        c.start()
        advanceUntilIdle()
        c.disable()
        advanceUntilIdle()
        assertTrue(gateway.unregistered.isEmpty())
        assertEquals(1, tips.disabledCalls)
        assertNull(settings.setupState.value)
    }

    @Test
    fun `missing status is checked but null`() = runTest {
        val c = MoneroSettingsController(FakeTipSettings(setup = personal), FakeSecrets(), FakeTips(), FakeWatcherGateway(), { true }, eager())
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.statusChecked)
        assertNull(c.state.value.watcherStatus)
    }

    private val self = "5".repeat(64)
    private val ownAddress = MoneroKeys.generate().address(Network.MAINNET)
    private val ownSetup = MoneroSetup(ownAddress.encode(), "", watcherHex, "mainnet", 1, relays)
    private fun ownStatus(createdAt: Long = 1_000, recipient: String = self, address: com.monostr.monero.Address = ownAddress) =
        WatcherStatus(watcherHex, recipient, address, Network.MAINNET, 3_512_345, "0.4.0", createdAt)

    private fun TestScope.lineAt(now: Long, st: WatcherStatus?): PersonalWatcherLine {
        val tips = FakeTips().apply { if (st != null) statuses[watcherHex] = st }
        val c = MoneroSettingsController(FakeTipSettings(setup = ownSetup), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager(), selfPubkey = self, now = { now })
        c.start()
        advanceUntilIdle()
        return c.state.value.personalLine
    }

    @Test
    fun `line is unknown before the lookup finishes`() = runTest {
        val tips = FakeTips().apply { statusGate = kotlinx.coroutines.CompletableDeferred() }
        val c = MoneroSettingsController(FakeTipSettings(setup = ownSetup), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager(), selfPubkey = self, now = { 1_000 })
        c.start()
        advanceUntilIdle()
        assertEquals(PersonalWatcherLine.Unknown, c.state.value.personalLine)
    }

    @Test
    fun `line is online up to 900 seconds and offline after`() = runTest {
        assertEquals(PersonalWatcherLine.Online, lineAt(1_900, ownStatus()))
        assertEquals(PersonalWatcherLine.Offline(1_000), lineAt(1_901, ownStatus()))
    }

    @Test
    fun `line is not found without a status`() = runTest {
        assertEquals(PersonalWatcherLine.NotFound, lineAt(1_000, null))
    }

    @Test
    fun `line is mismatch for another account or another address`() = runTest {
        assertEquals(PersonalWatcherLine.Mismatch, lineAt(1_000, ownStatus(recipient = "6".repeat(64))))
        assertEquals(PersonalWatcherLine.Mismatch, lineAt(1_000, ownStatus(address = MoneroKeys.generate().address(Network.MAINNET))))
        assertEquals(PersonalWatcherLine.Mismatch, lineAt(5_000, ownStatus(recipient = "6".repeat(64))), "mismatch wins over offline")
    }

    private val httpSetup = MoneroSetup(MoneroKeys.generate().address(Network.MAINNET).encode(), "https://w.example", "c".repeat(64), "mainnet", 1)
    private val v05 = com.monostr.tips.watcher.WatcherInfo("c".repeat(64), listOf("wss://r"), "mainnet", 1, "t", "0.5")

    @Test
    fun `switch republishes and stores`() = runTest {
        val settings = FakeTipSettings(setup = httpSetup)
        val tips = FakeTips()
        val c = MoneroSettingsController(settings, FakeSecrets(), tips, FakeWatcherGateway(info = v05), { true }, eager())
        c.start()
        advanceUntilIdle()
        assertEquals(true, c.state.value.amountsSupported)
        c.setAmountsPublic(false)
        advanceUntilIdle()
        assertTrue(tips.publishedInfos.last().third)
        assertTrue(settings.setupState.value!!.amountsHidden)
        assertTrue(c.state.value.setup!!.amountsHidden)
        assertFalse(c.state.value.busy)
        assertNull(c.state.value.error)
        c.setAmountsPublic(true)
        advanceUntilIdle()
        assertFalse(tips.publishedInfos.last().third)
        assertFalse(settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `failed republish keeps the old state`() = runTest {
        val settings = FakeTipSettings(setup = httpSetup)
        val tips = FakeTips(relaysOk = false)
        val c = MoneroSettingsController(settings, FakeSecrets(), tips, FakeWatcherGateway(info = v05), { true }, eager())
        c.start()
        advanceUntilIdle()
        c.setAmountsPublic(false)
        advanceUntilIdle()
        assertEquals(MoneroSettingsController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertEquals(httpSetup, settings.setupState.value)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `switch disabled below 0_5`() = runTest {
        val settings = FakeTipSettings(setup = httpSetup)
        val tips = FakeTips()
        val old = v05.copy(protocol = null)
        val c = MoneroSettingsController(settings, FakeSecrets(), tips, FakeWatcherGateway(info = old), { true }, eager())
        c.start()
        advanceUntilIdle()
        assertEquals(false, c.state.value.amountsSupported)
        c.setAmountsPublic(false)
        advanceUntilIdle()
        assertTrue(tips.publishedInfos.isEmpty())
        assertEquals(httpSetup, settings.setupState.value)
    }

    private class MemHints : com.monostr.app.data.HintStore {
        val done = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
        override suspend fun firstLoginAt(pubkey: String): Long? = null
        override suspend fun markFirstLogin(pubkey: String, at: Long) {}
        override fun supportHintDone(pubkey: String) = kotlinx.coroutines.flow.MutableStateFlow(false)
        override suspend fun setSupportHintDone(pubkey: String) {}
        override fun dismissed(pubkey: String, hint: String) = kotlinx.coroutines.flow.flow { emit("$pubkey/$hint" in done.value) }
        override suspend fun dismiss(pubkey: String, hint: String) { done.value = done.value + "$pubkey/$hint" }
    }

    @Test
    fun `a successful switch in settings answers the one-time question`() = runTest {
        val settings = FakeTipSettings(setup = httpSetup)
        val hints = MemHints()
        val self = "a".repeat(64)
        val c = MoneroSettingsController(settings, FakeSecrets(), FakeTips(), FakeWatcherGateway(info = v05), { true }, eager(), selfPubkey = self, hints = hints)
        c.start()
        advanceUntilIdle()
        c.setAmountsPublic(false)
        advanceUntilIdle()
        assertTrue(hints.dismissed(self, com.monostr.app.ui.tips.AmountsPrompt.HINT).first())
    }

    @Test
    fun `a failed switch leaves the one-time question open`() = runTest {
        val hints = MemHints()
        val self = "a".repeat(64)
        val c = MoneroSettingsController(FakeTipSettings(setup = httpSetup), FakeSecrets(), FakeTips(relaysOk = false), FakeWatcherGateway(info = v05), { true }, eager(), selfPubkey = self, hints = hints)
        c.start()
        advanceUntilIdle()
        c.setAmountsPublic(false)
        advanceUntilIdle()
        assertFalse(hints.dismissed(self, com.monostr.app.ui.tips.AmountsPrompt.HINT).first())
    }

    @Test
    fun `hidden setup on an unsupported watcher can switch back to public`() = runTest {
        val hiddenSetup = httpSetup.copy(amountsHidden = true)
        val settings = FakeTipSettings(setup = hiddenSetup)
        val tips = FakeTips()
        val c = MoneroSettingsController(settings, FakeSecrets(), tips, FakeWatcherGateway(info = v05.copy(protocol = null)), { true }, eager())
        c.start()
        advanceUntilIdle()
        assertEquals(false, c.state.value.amountsSupported)
        c.setAmountsPublic(true)
        advanceUntilIdle()
        assertFalse(tips.publishedInfos.last().third)
        assertFalse(settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `public setup on an unsupported watcher cannot be hidden`() = runTest {
        val tips = FakeTips()
        val c = MoneroSettingsController(FakeTipSettings(setup = httpSetup), FakeSecrets(), tips, FakeWatcherGateway(info = v05.copy(protocol = null)), { true }, eager())
        c.start()
        advanceUntilIdle()
        c.setAmountsPublic(false)
        advanceUntilIdle()
        assertTrue(tips.publishedInfos.isEmpty())
    }

    @Test
    fun `support is checked when the screen opens`() = runTest {
        val gateway = FakeWatcherGateway(info = v05)
        val c = MoneroSettingsController(FakeTipSettings(setup = httpSetup), FakeSecrets(), FakeTips(), gateway, { true }, eager())
        assertNull(c.state.value.amountsSupported)
        c.start()
        advanceUntilIdle()
        assertEquals(1, gateway.infoCalls)
        assertEquals(true, c.state.value.amountsSupported)

        val tips = FakeTips().apply { statuses[watcherHex] = status.copy(protocol = "0.5") }
        val p = MoneroSettingsController(FakeTipSettings(setup = personal), FakeSecrets(), tips, FakeWatcherGateway(), { true }, eager())
        p.start()
        advanceUntilIdle()
        assertEquals(true, p.state.value.amountsSupported)

        val unreachable = MoneroSettingsController(FakeTipSettings(setup = httpSetup), FakeSecrets(), FakeTips(), FakeWatcherGateway(infoError = java.io.IOException("x")), { true }, eager())
        unreachable.start()
        advanceUntilIdle()
        assertNull(unreachable.state.value.amountsSupported)
    }
}
