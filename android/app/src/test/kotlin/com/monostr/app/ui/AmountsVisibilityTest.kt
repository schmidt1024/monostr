package com.monostr.app.ui

import com.monostr.app.data.MoneroSetup
import com.monostr.app.ui.tips.AmountsVisibility
import com.monostr.app.ui.tips.watcher
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.Watcher
import com.monostr.tips.WatcherStatus
import com.monostr.tips.watcher.WatcherException
import com.monostr.tips.watcher.WatcherInfo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AmountsVisibilityTest {
    private val watcherPk = "c".repeat(64)
    private val address = MoneroKeys.generate().address(Network.MAINNET)
    private val http = MoneroSetup(address.encode(), "https://w.example", watcherPk, "mainnet", 1)
    private val personal = MoneroSetup(address.encode(), "", watcherPk, "mainnet", 1, listOf("wss://w.example"))

    private fun info(protocol: String?) = WatcherInfo(watcherPk, listOf("wss://r.example"), "mainnet", 3_000_000, "test", protocol)
    private fun status(protocol: String?) = WatcherStatus(watcherPk, "5".repeat(64), address, Network.MAINNET, 3_000_000, "0.5.0", 1, protocol)

    private fun visibility(
        tips: FakeTips = FakeTips(),
        gateway: FakeWatcherGateway = FakeWatcherGateway(),
        settings: FakeTipSettings = FakeTipSettings(),
    ) = AmountsVisibility(tips, gateway, settings)

    @Test
    fun `http watcher with protocol 0_5 supports hiding`() = runTest {
        assertEquals(true, visibility(gateway = FakeWatcherGateway(info = info("0.5"))).supported(http))
    }

    @Test
    fun `http watcher without protocol does not`() = runTest {
        assertEquals(false, visibility(gateway = FakeWatcherGateway(info = info(null))).supported(http))
    }

    @Test
    fun `unreachable http watcher is unknown`() = runTest {
        assertNull(visibility(gateway = FakeWatcherGateway(infoError = WatcherException.Network(IOException("down")))).supported(http))
    }

    @Test
    fun `personal watcher with protocol 0_5 supports hiding`() = runTest {
        val tips = FakeTips().apply { statuses[watcherPk] = status("0.5") }
        assertEquals(true, visibility(tips = tips).supported(personal))
        assertEquals(listOf(watcherPk to listOf("wss://w.example")), tips.statusRequests)
    }

    @Test
    fun `personal watcher without status is unknown`() = runTest {
        assertNull(visibility().supported(personal))
    }

    @Test
    fun `setHidden republishes the stored setup and stores the flag`() = runTest {
        val tips = FakeTips()
        val settings = FakeTipSettings(setup = http)
        assertTrue(visibility(tips = tips, settings = settings).setHidden(true))
        assertEquals(listOf(Triple(address, Watcher.Http("https://w.example", watcherPk), true)), tips.publishedInfos)
        assertEquals(http.copy(amountsHidden = true), settings.setupState.value)
    }

    @Test
    fun `setHidden keeps fields stored after a caller read the setup`() = runTest {
        val tips = FakeTips()
        val settings = FakeTipSettings(setup = personal)
        val newer = personal.copy(watcherRelays = listOf("wss://new.example"), registeredAt = 9)
        settings.setupState.value = newer
        assertTrue(visibility(tips = tips, settings = settings).setHidden(true))
        assertEquals(Watcher.Personal(watcherPk, listOf("wss://new.example")), tips.publishedInfos.single().second)
        assertEquals(newer.copy(amountsHidden = true), settings.setupState.value)
    }

    @Test
    fun `setHidden without a setup does nothing`() = runTest {
        val tips = FakeTips()
        assertFalse(visibility(tips = tips).setHidden(true))
        assertTrue(tips.publishedInfos.isEmpty())
    }

    @Test
    fun `failed republish keeps the old state`() = runTest {
        val tips = FakeTips(relaysOk = false)
        val settings = FakeTipSettings(setup = personal)
        assertFalse(visibility(tips = tips, settings = settings).setHidden(true))
        assertEquals(Triple(address, Watcher.Personal(watcherPk, listOf("wss://w.example")), true), tips.publishedInfos.single())
        assertEquals(personal, settings.setupState.value)
    }

    @Test
    fun `throwing republish returns false and stores nothing`() = runTest {
        val tips = FakeTips().apply { publishError = IllegalStateException("signer gone") }
        val settings = FakeTipSettings(setup = http)
        assertFalse(visibility(tips = tips, settings = settings).setHidden(true))
        assertEquals(http, settings.setupState.value)
    }

    @Test
    fun `unparseable stored address returns false`() = runTest {
        val tips = FakeTips()
        val settings = FakeTipSettings(setup = http.copy(address = "garbage"))
        assertFalse(visibility(tips = tips, settings = settings).setHidden(true))
        assertTrue(tips.publishedInfos.isEmpty())
        assertFalse(settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `watcher of a setup`() {
        assertEquals(Watcher.Http("https://w.example", watcherPk), http.watcher())
        assertEquals(Watcher.Personal(watcherPk, listOf("wss://w.example")), personal.watcher())
    }
}
