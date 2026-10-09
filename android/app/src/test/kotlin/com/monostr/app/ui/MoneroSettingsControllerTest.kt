package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.ui.settings.MoneroSettingsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        assertEquals(MoneroSettingsController.ERR_PRESETS, c.state.value.error)
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
}
