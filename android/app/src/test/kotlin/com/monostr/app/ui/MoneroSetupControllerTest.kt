package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.ui.monero.MoneroSetupController
import com.monostr.app.ui.monero.SetupStep
import com.monostr.monero.Mnemonic
import com.monostr.monero.MoneroKeys
import com.monostr.tips.PaymentInfo
import com.monostr.monero.Network
import com.monostr.monero.toHex
import com.monostr.tips.watcher.WatcherException
import com.monostr.tips.watcher.WatcherInfo
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
import java.io.IOException
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MoneroSetupControllerTest {
    private val watcherPk = "c".repeat(64)
    private val url = "https://watcher.example"
    private val self = "5".repeat(64)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private class Rig(
        val gateway: FakeWatcherGateway = FakeWatcherGateway(),
        val tips: FakeTips = FakeTips(),
        val settings: FakeTipSettings = FakeTipSettings(),
        val secrets: FakeSecrets = FakeSecrets(),
        val keys: MoneroKeys = MoneroKeys.generate(),
        val relays: MutableList<List<String>> = ArrayList(),
    )

    private fun TestScope.controller(rig: Rig, intro: Boolean = false) = MoneroSetupController(
        intro = intro, gateway = rig.gateway, tips = rig.tips, settings = rig.settings, secrets = rig.secrets,
        relays = { rig.relays += it }, scope = eager(), selfPubkey = self, keyGen = { rig.keys }, random = Random(7), now = { 5_000 },
    ).also { it.start(); advanceUntilIdle() }

    private fun MoneroSetupController.loadDefaultWatcher() { setWatcherUrl(url); loadWatcher() }

    @Test
    fun `path A generates a wallet, verifies three words, registers and publishes`() = runTest {
        val rig = Rig()
        val expectedAddress = rig.keys.address(Network.MAINNET)
        val expectedViewKey = rig.keys.viewSecret.toHex()
        val c = controller(rig, intro = true)
        assertTrue(c.state.value.intro)
        assertEquals(SetupStep.Watcher("https://watcher.monostr.com"), c.state.value.step)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        val choose = c.state.value.step as SetupStep.Choose
        assertEquals(3_000_000L, choose.info.height)
        assertFalse(choose.canReuse)
        c.startNew()
        val seed = c.state.value.step as SetupStep.Seed
        assertEquals(25, seed.words.size)
        assertEquals(Mnemonic.encode(rig.keys.spendSecret), seed.words)
        assertEquals(3_000_000L, seed.restoreHeight)
        c.seedWritten()
        val verify = c.state.value.step as SetupStep.Verify
        assertEquals(3, verify.positions.distinct().size)
        assertTrue(verify.positions.all { it in 0..24 })
        c.verify(listOf("wrong", "words", "here"))
        advanceUntilIdle()
        assertTrue((c.state.value.step as SetupStep.Verify).wrong)
        assertTrue(rig.gateway.registered.isEmpty())
        c.verify(verify.positions.map { " " + seed.words[it].uppercase() + " " })
        advanceUntilIdle()
        assertEquals(SetupStep.Done(expectedAddress.encode(), 3_000_000L), c.state.value.step)
        assertEquals(listOf(Triple(url, expectedAddress, expectedViewKey)), rig.gateway.registered)
        assertEquals(listOf(Triple(expectedAddress, url, watcherPk)), rig.tips.publishedInfos)
        assertEquals(expectedViewKey, rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
        assertEquals(MoneroSetup(expectedAddress.encode(), url, watcherPk, "mainnet", 5_000), rig.settings.setupState.value)
        assertTrue(rig.settings.seenState.value)
        assertEquals(listOf(listOf("wss://watcher-relay.example")), rig.relays)
        assertTrue(rig.keys.spendSecret.all { it == 0.toByte() }, "spend key wiped after registration")
        c.finish()
        assertTrue(c.state.value.finished)
    }

    @Test
    fun `existing wallet input is validated locally before the watcher is called`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startExisting()
        val wallet = MoneroKeys.generate()
        val address = wallet.address(Network.MAINNET)
        val viewKey = wallet.viewSecret.toHex()
        c.setAddress(address.integrated(ByteArray(8))); c.setViewKey(viewKey)
        assertFalse(c.state.value.step.toString().contains(viewKey))
        c.checkExisting()
        assertEquals(MoneroSetupController.ERR_INTEGRATED, c.state.value.error)
        c.setAddress("4" + "x".repeat(94)); c.checkExisting()
        assertEquals(MoneroSetupController.ERR_ADDRESS, c.state.value.error)
        c.setAddress(wallet.address(Network.STAGENET).encode()); c.checkExisting()
        assertEquals(MoneroSetupController.ERR_NETWORK, c.state.value.error)
        c.setAddress(address.encode()); c.setViewKey("zz"); c.checkExisting()
        assertEquals(MoneroSetupController.ERR_VIEW_KEY_FORMAT, c.state.value.error)
        c.setViewKey(MoneroKeys.generate().viewSecret.toHex()); c.checkExisting()
        assertEquals(MoneroSetupController.ERR_VIEW_KEY_MISMATCH, c.state.value.error)
        assertTrue(c.state.value.step is SetupStep.Existing)
        assertTrue(rig.gateway.registered.isEmpty())
        c.setViewKey(viewKey.uppercase()); c.checkExisting()
        assertNull(c.state.value.error)
        assertEquals(SetupStep.Confirm(address.encode(), null), c.state.value.step)
        c.confirm()
        advanceUntilIdle()
        assertEquals(SetupStep.Done(address.encode(), null), c.state.value.step)
        assertEquals(listOf(Triple(url, address, viewKey)), rig.gateway.registered)
        assertEquals(viewKey, rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
    }

    @Test
    fun `watcher url and info are validated`() = runTest {
        val rig = Rig(FakeWatcherGateway(infoError = WatcherException.Network(IOException("down"))))
        val c = controller(rig)
        c.setWatcherUrl("http://watcher.example"); c.loadWatcher()
        advanceUntilIdle()
        assertEquals(MoneroSetupController.ERR_URL, c.state.value.error)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_watcher_unreachable), c.state.value.error)
        assertEquals(SetupStep.Watcher(url), c.state.value.step)
        rig.gateway.infoError = null
        rig.gateway.info = WatcherInfo(watcherPk, emptyList(), "testnet", 1, "x")
        c.loadWatcher()
        advanceUntilIdle()
        assertEquals(MoneroSetupController.ERR_WATCHER_NETWORK, c.state.value.error)
        rig.gateway.info = WatcherInfo("nothex", emptyList(), "mainnet", 1, "x")
        c.loadWatcher()
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_watcher_invalid), c.state.value.error)
        // a loopback http watcher is allowed for local testing
        rig.gateway.info = WatcherInfo(watcherPk, emptyList(), "stagenet", 1, "x")
        c.setWatcherUrl("http://127.0.0.1:8080"); c.loadWatcher()
        advanceUntilIdle()
        assertTrue(c.state.value.step is SetupStep.Choose, "step ${c.state.value.step}")
    }

    @Test
    fun `registration failure leaves no local state`() = runTest {
        val rig = Rig(FakeWatcherGateway(registerError = WatcherException.Http(500, "boom")))
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startNew()
        val words = (c.state.value.step as SetupStep.Seed).words
        c.seedWritten()
        val positions = (c.state.value.step as SetupStep.Verify).positions
        c.verify(positions.map { words[it] })
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_watcher_rejected), c.state.value.error)
        assertTrue(c.state.value.step is SetupStep.Confirm, "step ${c.state.value.step}")
        assertNull(rig.settings.setupState.value)
        assertNull(rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
        assertTrue(rig.tips.publishedInfos.isEmpty())
        assertFalse(rig.settings.seenState.value)
        // retry from the confirm step succeeds once the watcher answers, but a pubkey that differs from /v1/info is refused
        rig.gateway.registerError = null
        rig.gateway.info = rig.gateway.info!!.copy(pubkey = "d".repeat(64))
        c.confirm()
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_watcher_invalid), c.state.value.error)
        assertNull(rig.settings.setupState.value)
        assertEquals(listOf(url), rig.gateway.unregistered, "the account at a watcher that is not the announced one is removed again")
        rig.gateway.info = rig.gateway.info!!.copy(pubkey = watcherPk)
        c.confirm()
        advanceUntilIdle()
        assertEquals(SetupStep.Done(rig.keys.address(Network.MAINNET).encode(), 3_000_000L), c.state.value.step, "a retry keeps the restore height")
    }

    @Test
    fun `switching the watcher reuses the stored wallet and unregisters the old one`() = runTest {
        val wallet = MoneroKeys.generate()
        val address = wallet.address(Network.MAINNET)
        val rig = Rig(settings = FakeTipSettings(setup = MoneroSetup(address.encode(), "https://old.example", "e".repeat(64), "mainnet", 1)))
        rig.secrets.put(KeystoreSecretStore.SECRET_VIEW_KEY, wallet.viewSecret.toHex())
        val c = controller(rig)
        assertEquals(SetupStep.Watcher("https://old.example"), c.state.value.step)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        assertTrue((c.state.value.step as SetupStep.Choose).canReuse)
        c.reuseStored()
        assertEquals(SetupStep.Confirm(address.encode(), null), c.state.value.step)
        c.confirm()
        advanceUntilIdle()
        assertEquals(listOf("https://old.example"), rig.gateway.unregistered)
        assertEquals(url, rig.gateway.registered.single().first)
        assertEquals(url, rig.settings.setupState.value?.watcherUrl)
    }

    @Test
    fun `skip marks the intro as seen and finishes`() = runTest {
        val rig = Rig()
        val c = controller(rig, intro = true)
        c.skip()
        advanceUntilIdle()
        assertTrue(rig.settings.seenState.value)
        assertTrue(c.state.value.finished)
        assertNull(rig.settings.setupState.value)
    }

    @Test
    fun `payment info that reaches no relay blocks the setup`() = runTest {
        val rig = Rig(tips = FakeTips(relaysOk = false))
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startExisting()
        val wallet = MoneroKeys.generate()
        val address = wallet.address(Network.MAINNET)
        c.setAddress(address.encode()); c.setViewKey(wallet.viewSecret.toHex()); c.checkExisting()
        c.confirm()
        advanceUntilIdle()
        assertEquals(SetupStep.Confirm(address.encode(), null), c.state.value.step)
        assertEquals(MoneroSetupController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertNull(rig.settings.setupState.value)
        assertNull(rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
        assertFalse(rig.settings.seenState.value)
        assertEquals(1, rig.gateway.registered.size)
        assertEquals(1, rig.tips.publishedInfos.size)
        assertEquals(listOf(url), rig.gateway.unregistered, "a registration nobody can find must not stay behind")
    }

    @Test
    fun `after a new login a failed setup does not remove the account the relays still announce`() = runTest {
        // logout cleared the local setup; the watcher account and the kind 10037 on the relays stayed
        val wallet = MoneroKeys.generate()
        val address = wallet.address(Network.MAINNET)
        val announced = PaymentInfo.Enabled(address, url, watcherPk)
        val rig = Rig(tips = FakeTips(infos = mapOf(self to announced), relaysOk = false))
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startExisting()
        c.setAddress(address.encode()); c.setViewKey(wallet.viewSecret.toHex()); c.checkExisting()
        c.confirm()
        advanceUntilIdle()
        assertEquals(MoneroSetupController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertTrue(rig.gateway.unregistered.isEmpty(), "senders still find this watcher in the payment info: it must keep watching")
    }

    @Test
    fun `a failed switch of the watcher keeps the old watcher and removes the new registration`() = runTest {
        val wallet = MoneroKeys.generate()
        val address = wallet.address(Network.MAINNET)
        val rig = Rig(tips = FakeTips(relaysOk = false), settings = FakeTipSettings(setup = MoneroSetup(address.encode(), "https://old.example", "e".repeat(64), "mainnet", 1)))
        rig.secrets.put(KeystoreSecretStore.SECRET_VIEW_KEY, wallet.viewSecret.toHex())
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.reuseStored()
        c.confirm()
        advanceUntilIdle()
        assertEquals(MoneroSetupController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertEquals(listOf(url), rig.gateway.unregistered, "the announced (old) watcher stays, the new one is taken back")
        assertEquals("https://old.example", rig.settings.setupState.value?.watcherUrl)
    }

    @Test
    fun `a failed change of the wallet without the stored view key removes the registration`() = runTest {
        val old = MoneroKeys.generate()
        val rig = Rig(tips = FakeTips(relaysOk = false), settings = FakeTipSettings(setup = MoneroSetup(old.address(Network.MAINNET).encode(), url, watcherPk, "mainnet", 1)))
        val c = controller(rig) // no view key in the secret store: the old wallet cannot be registered again
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startExisting()
        val wallet = MoneroKeys.generate()
        c.setAddress(wallet.address(Network.MAINNET).encode()); c.setViewKey(wallet.viewSecret.toHex()); c.checkExisting()
        c.confirm()
        advanceUntilIdle()
        assertEquals(1, rig.gateway.registered.size)
        assertEquals(listOf(url), rig.gateway.unregistered, "the watcher does not keep a wallet nobody announced")
    }

    @Test
    fun `a failed change of the wallet puts the previous registration back`() = runTest {
        val old = MoneroKeys.generate()
        val oldAddress = old.address(Network.MAINNET)
        val rig = Rig(tips = FakeTips(relaysOk = false), settings = FakeTipSettings(setup = MoneroSetup(oldAddress.encode(), url, watcherPk, "mainnet", 1)))
        rig.secrets.put(KeystoreSecretStore.SECRET_VIEW_KEY, old.viewSecret.toHex())
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startExisting()
        val wallet = MoneroKeys.generate()
        val address = wallet.address(Network.MAINNET)
        c.setAddress(address.encode()); c.setViewKey(wallet.viewSecret.toHex()); c.checkExisting()
        c.confirm()
        advanceUntilIdle()
        assertEquals(MoneroSetupController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertEquals(listOf(address, oldAddress), rig.gateway.registered.map { it.second }, "the watcher watches the announced wallet again")
        assertEquals(old.viewSecret.toHex(), rig.gateway.registered.last().third)
        assertTrue(rig.gateway.unregistered.isEmpty(), "the working setup is not thrown away")
        assertEquals(oldAddress.encode(), rig.settings.setupState.value?.address)
        assertEquals(old.viewSecret.toHex(), rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
    }
}
