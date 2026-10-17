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
import com.monostr.tips.Watcher
import com.monostr.monero.Network
import com.monostr.monero.toHex
import com.monostr.tips.watcher.WatcherException
import com.monostr.tips.watcher.WatcherInfo
import com.monostr.tips.WatcherStatus
import com.monostr.app.data.Presets
import com.monostr.app.ui.common.UiText
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip19Profile
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        relays = { rig.relays += it }, scope = eager(), selfPubkey = self, keyGen = { rig.keys }, random = Random(7), now = { NOW },
        formatTime = { "t$it" },
    ).also { it.start(); advanceUntilIdle() }

    private val watcherHex = Keys.generate().publicKey().toHex()
    private val personalWallet = MoneroKeys.generate()
    private val personalAddress = personalWallet.address(Network.MAINNET)

    private fun nprofile(vararg relays: String): String =
        Nip19Profile(PublicKey.parse(watcherHex), relays.map { RelayUrl.parse(it) }).toBech32()

    private fun status(recipient: String = self, network: Network? = Network.MAINNET, createdAt: Long = NOW) =
        WatcherStatus(watcherHex, recipient, personalAddress, network, 3_512_345, "0.4.0", createdAt)

    private fun TestScope.loadPersonal(c: MoneroSetupController, input: String, relay: String = "") {
        c.startPersonal(); c.setPersonalInput(input); c.setPersonalRelay(relay); c.loadPersonal(); advanceUntilIdle()
    }

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
        assertEquals(listOf(Triple(expectedAddress, Watcher.Http(url, watcherPk), false)), rig.tips.publishedInfos)
        assertEquals(expectedViewKey, rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY))
        assertEquals(MoneroSetup(expectedAddress.encode(), url, watcherPk, "mainnet", 5_000), rig.settings.setupState.value)
        assertTrue(rig.settings.seenState.value)
        assertEquals(listOf(listOf("wss://watcher-relay.example")), rig.relays)
        assertTrue(rig.keys.spendSecret.all { it == 0.toByte() }, "spend key wiped after registration")
        c.finish()
        assertTrue(c.state.value.finished)
    }

    @Test
    fun `the intro skips itself when the account already announces a tip address`() = runTest {
        val announced = MoneroKeys.generate().address(Network.MAINNET)
        val rig = Rig(tips = FakeTips().apply { liveInfos = mapOf(self to PaymentInfo.Enabled(announced, Watcher.Http(url, watcherPk))) })
        val c = controller(rig, intro = true)
        assertTrue(c.state.value.finished)
        assertTrue(rig.settings.seenState.value)
        assertTrue(rig.gateway.registered.isEmpty())
        assertTrue(rig.tips.publishedInfos.isEmpty())
    }

    @Test
    fun `setup from settings warns that it replaces an address announced elsewhere`() = runTest {
        val announced = MoneroKeys.generate().address(Network.MAINNET)
        val rig = Rig(tips = FakeTips().apply { liveInfos = mapOf(self to PaymentInfo.Enabled(announced, Watcher.Http(url, watcherPk))) })
        val c = controller(rig)
        assertFalse(c.state.value.finished)
        assertEquals(announced.encode(), c.state.value.announced)
    }

    @Test
    fun `a setup on this device is not treated as announced elsewhere`() = runTest {
        val own = MoneroKeys.generate().address(Network.MAINNET)
        val rig = Rig(tips = FakeTips().apply { liveInfos = mapOf(self to PaymentInfo.Enabled(own, Watcher.Http(url, watcherPk))) })
        rig.settings.setupState.value = MoneroSetup(own.encode(), url, watcherPk, "mainnet", 1)
        val c = controller(rig)
        assertNull(c.state.value.announced)
    }

    @Test
    fun `without announced payment info the intro shows as before`() = runTest {
        val c = controller(Rig(tips = FakeTips().apply { liveInfos = emptyMap() }), intro = true)
        assertFalse(c.state.value.finished)
        assertNull(c.state.value.announced)
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
        assertEquals(SetupStep.Watcher(url, retry = true), c.state.value.step)
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
        val announced = PaymentInfo.Enabled(address, Watcher.Http(url, watcherPk))
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

    @Test
    fun `personal watcher from nprofile is announced in pubkey form`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status()
        val c = controller(rig)
        val input = nprofile("wss://w.example")
        loadPersonal(c, input)
        assertNull(c.state.value.error)
        assertEquals(SetupStep.PersonalConfirm(status(), listOf("wss://w.example")), c.state.value.step)
        assertEquals(listOf(watcherHex to listOf("wss://w.example")), rig.tips.statusRequests)
        c.confirmPersonal()
        advanceUntilIdle()
        assertEquals(Triple(personalAddress, Watcher.Personal(watcherHex, listOf("wss://w.example")), false), rig.tips.publishedInfos.last())
        assertEquals(MoneroSetup(personalAddress.encode(), "", watcherHex, "mainnet", NOW, listOf("wss://w.example")), rig.settings.setupState.value)
        assertTrue(rig.settings.setupState.value!!.isPersonal)
        assertTrue(rig.settings.seenState.value)
        assertEquals(listOf(listOf("wss://w.example")), rig.relays)
        assertEquals(SetupStep.Done(personalAddress.encode(), null), c.state.value.step)
        assertTrue(rig.secrets.values.isEmpty(), "no view key on the personal path")
        assertTrue(rig.gateway.registered.isEmpty())
        assertTrue(rig.gateway.unregistered.isEmpty())
        assertEquals(listOf(Presets.DEFAULT_WATCHER), rig.gateway.infoUrls, "only the prefetch of the prefilled watcher")
    }

    @Test
    fun `npub without relay is rejected`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        val npub = com.monostr.nostr.Npub.encode(watcherHex)
        loadPersonal(c, npub)
        assertEquals(MoneroSetupController.ERR_PERSONAL_RELAY, c.state.value.error)
        assertEquals(SetupStep.Personal(npub, ""), c.state.value.step)
        assertTrue(rig.tips.statusRequests.isEmpty())
    }

    @Test
    fun `unreadable input is rejected`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        loadPersonal(c, "hello", "wss://w.example")
        assertEquals(MoneroSetupController.ERR_PERSONAL_INPUT, c.state.value.error)
        assertEquals(SetupStep.Personal("hello", "wss://w.example"), c.state.value.step)
        assertTrue(rig.tips.statusRequests.isEmpty())
        c.setPersonalInput("hell")
        assertNull(c.state.value.error, "typing clears the error")
    }

    @Test
    fun `relays are filtered to wss and capped at four`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status()
        val c = controller(rig)
        loadPersonal(c, nprofile("ws://x.example", "wss://1.example", "wss://2.example", "wss://3.example"), "wss://4.example/")
        val expected = listOf("wss://1.example", "wss://2.example", "wss://3.example", "wss://4.example")
        assertEquals(listOf(watcherHex to expected), rig.tips.statusRequests)
        assertEquals(SetupStep.PersonalConfirm(status(), expected), c.state.value.step)
        c.startPersonal()
        loadPersonal(c, nprofile("wss://1.example", "wss://2.example", "wss://3.example", "wss://4.example", "wss://5.example"))
        assertEquals(watcherHex to expected, rig.tips.statusRequests.last())
    }

    @Test
    fun `missing status`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        val input = nprofile("wss://w.example")
        loadPersonal(c, input, "wss://r.example")
        assertEquals(MoneroSetupController.ERR_PERSONAL_NOT_FOUND, c.state.value.error)
        assertEquals(SetupStep.Personal(input, "wss://r.example"), c.state.value.step, "input kept")
        assertEquals(1, rig.tips.statusRequests.size)
    }

    @Test
    fun `status of another account is refused`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status(recipient = "c".repeat(64))
        val c = controller(rig)
        loadPersonal(c, nprofile("wss://w.example"))
        assertEquals(MoneroSetupController.ERR_PERSONAL_OTHER_ACCOUNT, c.state.value.error)
        assertTrue(c.state.value.step is SetupStep.Personal)
        c.confirmPersonal()
        advanceUntilIdle()
        assertTrue(rig.tips.publishedInfos.isEmpty())
        assertNull(rig.settings.setupState.value)
    }

    @Test
    fun `status with unknown or mismatching network is refused`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        for (net in listOf(null, Network.STAGENET)) {
            rig.tips.statuses[watcherHex] = status(network = net)
            loadPersonal(c, nprofile("wss://w.example"))
            assertEquals(MoneroSetupController.ERR_PERSONAL_NETWORK, c.state.value.error, "network $net")
            assertTrue(c.state.value.step is SetupStep.Personal)
        }
        assertTrue(rig.tips.publishedInfos.isEmpty())
    }

    @Test
    fun `offline watcher shows since when`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status(createdAt = NOW - 901)
        val c = controller(rig)
        loadPersonal(c, nprofile("wss://w.example"))
        assertEquals(UiText.Res(R.string.setup_personal_err_offline, listOf("t${NOW - 901}")), c.state.value.error)
        assertTrue(c.state.value.step is SetupStep.Personal)
        // exactly 15 minutes is still online
        rig.tips.statuses[watcherHex] = status(createdAt = NOW - 900)
        c.loadPersonal()
        advanceUntilIdle()
        assertTrue(c.state.value.step is SetupStep.PersonalConfirm, "step ${c.state.value.step}")
    }

    @Test
    fun `switch from http watcher unregisters only after publishing`() = runTest {
        val wallet = MoneroKeys.generate()
        val oldUrl = "https://old.example"
        val rig = Rig(settings = FakeTipSettings(setup = MoneroSetup(wallet.address(Network.MAINNET).encode(), oldUrl, "e".repeat(64), "mainnet", 1)))
        rig.secrets.put(KeystoreSecretStore.SECRET_VIEW_KEY, wallet.viewSecret.toHex())
        val order = ArrayList<String>()
        val gateway = object : com.monostr.app.data.WatcherGateway by rig.gateway {
            override suspend fun unregister(url: String) { order += "unregister"; rig.gateway.unregister(url) }
        }
        val tips = object : com.monostr.nostr.repo.TipsRepository by rig.tips {
            override suspend fun publishPaymentInfo(address: com.monostr.monero.Address, watcher: Watcher, amountsHidden: Boolean) =
                rig.tips.publishPaymentInfo(address, watcher, amountsHidden).also { order += "publish" }
        }
        rig.tips.statuses[watcherHex] = status()
        val c = MoneroSetupController(
            intro = false, gateway = gateway, tips = tips, settings = rig.settings, secrets = rig.secrets,
            relays = { rig.relays += it }, scope = eager(), selfPubkey = self, now = { NOW }, formatTime = { "t$it" },
        ).also { it.start(); advanceUntilIdle() }
        loadPersonal(c, nprofile("wss://w.example"))
        c.confirmPersonal()
        advanceUntilIdle()
        assertEquals(listOf("publish", "unregister"), order)
        assertEquals(listOf(oldUrl), rig.gateway.unregistered)
        assertEquals(wallet.viewSecret.toHex(), rig.secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY), "view key untouched")
        assertTrue(rig.settings.setupState.value!!.isPersonal)
    }

    @Test
    fun `failed publish keeps the old setup`() = runTest {
        val wallet = MoneroKeys.generate()
        val old = MoneroSetup(wallet.address(Network.MAINNET).encode(), "https://old.example", "e".repeat(64), "mainnet", 1)
        val rig = Rig(tips = FakeTips(relaysOk = false), settings = FakeTipSettings(setup = old))
        rig.tips.statuses[watcherHex] = status()
        val c = controller(rig)
        loadPersonal(c, nprofile("wss://w.example"))
        c.confirmPersonal()
        advanceUntilIdle()
        assertEquals(SetupStep.PersonalConfirm(status(), listOf("wss://w.example")), c.state.value.step)
        assertEquals(MoneroSetupController.ERR_NOT_PUBLISHED, c.state.value.error)
        assertEquals(old, rig.settings.setupState.value)
        assertTrue(rig.gateway.unregistered.isEmpty())
        assertTrue(rig.relays.isEmpty())
    }

    @Test
    fun `switch from personal to http starts with the default url and cannot reuse`() = runTest {
        val rig = Rig(settings = FakeTipSettings(setup = MoneroSetup(personalAddress.encode(), "", watcherHex, "mainnet", 1, listOf("wss://w.example"))))
        rig.secrets.put(KeystoreSecretStore.SECRET_VIEW_KEY, MoneroKeys.generate().viewSecret.toHex())
        val c = controller(rig)
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        c.loadWatcher()
        advanceUntilIdle()
        assertFalse((c.state.value.step as SetupStep.Choose).canReuse)
    }

    @Test
    fun `status arriving after back to watcher is dropped`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.tips.statusGate = gate
        val c = controller(rig)
        c.startPersonal(); c.setPersonalInput(nprofile("wss://w.example")); c.loadPersonal()
        runCurrent() // not idle: that would run into the 15 s timeout (spec §12)
        assertEquals(true, (c.state.value.step as SetupStep.Personal).loading)
        c.backToWatcher()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        c.confirmPersonal()
        advanceUntilIdle()
        assertTrue(rig.tips.publishedInfos.isEmpty())
        assertNull(rig.settings.setupState.value)
    }

    @Test
    fun `switch from personal to http registers fully and never unregisters the empty url`() = runTest {
        val old = MoneroSetup(personalAddress.encode(), "", watcherHex, "mainnet", 1, listOf("wss://w.example"))
        val rig = Rig(settings = FakeTipSettings(setup = old))
        val c = controller(rig)
        c.loadDefaultWatcher()
        advanceUntilIdle()
        c.startNew(); c.seedWritten()
        val verify = c.state.value.step as SetupStep.Verify
        val words = Mnemonic.encode(rig.keys.spendSecret)
        c.verify(verify.positions.map { words[it] })
        advanceUntilIdle()
        assertTrue(c.state.value.step is SetupStep.Done, "step ${c.state.value.step}")
        assertEquals(1, rig.gateway.registered.size)
        assertTrue(rig.gateway.unregistered.isEmpty(), "no unregister: ${rig.gateway.unregistered}")
        val now = rig.settings.setupState.value!!
        assertEquals(url, now.watcherUrl)
        assertFalse(now.isPersonal)
        assertEquals(listOf(Triple(rig.keys.address(Network.MAINNET), Watcher.Http(url, watcherPk), false)), rig.tips.publishedInfos)
    }

    @Test
    fun `switch from personal to personal stores and adopts the new relays without gateway calls`() = runTest {
        val old = MoneroSetup(personalAddress.encode(), "", watcherHex, "mainnet", 1, listOf("wss://old.example"))
        val rig = Rig(settings = FakeTipSettings(setup = old))
        rig.tips.statuses[watcherHex] = status()
        val c = controller(rig)
        loadPersonal(c, nprofile("wss://new.example"))
        c.confirmPersonal()
        advanceUntilIdle()
        assertEquals(SetupStep.Done(personalAddress.encode(), null), c.state.value.step)
        assertEquals(listOf("wss://new.example"), rig.settings.setupState.value!!.watcherRelays)
        assertEquals(listOf(listOf("wss://new.example")), rig.relays)
        assertTrue(rig.gateway.unregistered.isEmpty())
        assertTrue(rig.gateway.registered.isEmpty())
        assertEquals(listOf(Presets.DEFAULT_WATCHER), rig.gateway.infoUrls, "only the prefetch of the prefilled watcher")
    }

    @Test
    fun `new http setup hides amounts when the watcher supports it`() = runTest {
        val rig = Rig(FakeWatcherGateway(info = WatcherInfo(watcherPk, listOf("wss://watcher-relay.example"), "mainnet", 3_000_000, "test", protocol = "0.5")))
        val c = controller(rig)
        c.loadDefaultWatcher(); advanceUntilIdle()
        c.startExisting()
        val wallet = MoneroKeys.generate()
        c.setAddress(wallet.address(Network.MAINNET).encode()); c.setViewKey(wallet.viewSecret.toHex()); c.checkExisting()
        assertEquals(true, (c.state.value.step as SetupStep.Confirm).amountsHidden)
        c.confirm(); advanceUntilIdle()
        assertTrue(rig.tips.publishedInfos.last().third)
        assertTrue(rig.settings.setupState.value!!.amountsHidden)
    }

    private fun TestScope.pathA(rig: Rig): SetupStep.Done {
        val c = controller(rig)
        c.loadDefaultWatcher(); advanceUntilIdle()
        c.startNew()
        val seed = c.state.value.step as SetupStep.Seed
        c.seedWritten()
        val verify = c.state.value.step as SetupStep.Verify
        c.verify(verify.positions.map { seed.words[it] }); advanceUntilIdle()
        return c.state.value.step as SetupStep.Done
    }

    @Test
    fun `path A with a 0_5 watcher tells on done that amounts are hidden`() = runTest {
        val rig = Rig(FakeWatcherGateway(info = WatcherInfo(watcherPk, listOf("wss://watcher-relay.example"), "mainnet", 3_000_000, "test", protocol = "0.5")))
        assertTrue(pathA(rig).amountsHidden)
        assertTrue(rig.tips.publishedInfos.last().third)
        assertTrue(rig.settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `path A with an old watcher does not`() = runTest {
        val rig = Rig()
        assertFalse(pathA(rig).amountsHidden)
        assertFalse(rig.tips.publishedInfos.last().third)
    }

    @Test
    fun `old watcher gets no hidden tag`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        c.loadDefaultWatcher(); advanceUntilIdle()
        c.startExisting()
        val wallet = MoneroKeys.generate()
        c.setAddress(wallet.address(Network.MAINNET).encode()); c.setViewKey(wallet.viewSecret.toHex()); c.checkExisting()
        assertEquals(false, (c.state.value.step as SetupStep.Confirm).amountsHidden)
        c.confirm(); advanceUntilIdle()
        assertFalse(rig.tips.publishedInfos.last().third)
        assertFalse(rig.settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `new personal setup hides amounts when the watcher supports it`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status().copy(protocol = "0.5")
        val c = controller(rig)
        loadPersonal(c, nprofile("wss://w.example"))
        c.confirmPersonal(); advanceUntilIdle()
        assertTrue((c.state.value.step as SetupStep.Done).amountsHidden)
        assertTrue(rig.tips.publishedInfos.last().third)
        assertTrue(rig.settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `old personal watcher gets no hidden tag`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status()
        val c = controller(rig)
        loadPersonal(c, nprofile("wss://w.example"))
        c.confirmPersonal(); advanceUntilIdle()
        assertFalse(rig.tips.publishedInfos.last().third)
        assertFalse(rig.settings.setupState.value!!.amountsHidden)
    }

    @Test
    fun `back to watcher`() = runTest {
        val c = controller(Rig())
        c.startPersonal()
        assertEquals(SetupStep.Personal("", ""), c.state.value.step)
        c.backToWatcher()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
    }

    // spec §12: prefetch, progress and timeout of the watcher check

    @Test
    fun `a finished prefetch is reused and the next step appears at once`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        assertEquals(listOf(Presets.DEFAULT_WATCHER), rig.gateway.infoUrls, "the prefilled watcher is prefetched")
        c.loadWatcher()
        assertTrue(c.state.value.step is SetupStep.Choose, "step ${c.state.value.step}")
        assertEquals(1, rig.gateway.infoCalls)
    }

    @Test
    fun `a running prefetch is joined, not started again`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val rig = Rig(FakeWatcherGateway().apply { infoGate = gate })
        val c = controller(rig)
        c.loadWatcher()
        val step = c.state.value.step as SetupStep.Watcher
        assertTrue(step.loading && step.checking, "step $step")
        gate.complete(Unit)
        runCurrent()
        assertTrue(c.state.value.step is SetupStep.Choose, "step ${c.state.value.step}")
        assertEquals(1, rig.gateway.infoCalls)
    }

    @Test
    fun `editing the url cancels the prefetch`() = runTest {
        val rig = Rig(FakeWatcherGateway().apply { infoGate = kotlinx.coroutines.CompletableDeferred() })
        val c = controller(rig)
        c.setWatcherUrl(" ${Presets.DEFAULT_WATCHER} ")
        assertEquals(0, rig.gateway.infoCancelled, "the same url keeps its prefetch")
        c.setWatcherUrl(url)
        assertEquals(1, rig.gateway.infoCancelled)
        rig.gateway.infoGate = null
        c.loadWatcher()
        runCurrent()
        assertEquals(listOf(Presets.DEFAULT_WATCHER, url), rig.gateway.infoUrls)
        assertTrue(c.state.value.step is SetupStep.Choose, "step ${c.state.value.step}")
    }

    @Test
    fun `a failed prefetch stays silent until the user checks`() = runTest {
        val rig = Rig(FakeWatcherGateway(infoError = WatcherException.Network(IOException("down"))))
        val c = controller(rig)
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        assertNull(c.state.value.error)
        c.loadWatcher()
        runCurrent()
        assertEquals(2, rig.gateway.infoCalls, "the check runs anew")
        assertEquals(uiText(R.string.error_watcher_unreachable), c.state.value.error)
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER, retry = true), c.state.value.step)
    }

    @Test
    fun `a rejected watcher is no reason to try again`() = runTest {
        val rig = Rig(FakeWatcherGateway(infoError = WatcherException.Protocol("bad")))
        val c = controller(rig)
        c.loadWatcher()
        runCurrent()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        assertEquals(uiText(R.string.error_watcher_invalid), c.state.value.error)
    }

    @Test
    fun `a slow check says so after 3 s and times out after 15 s`() = runTest {
        val rig = Rig(FakeWatcherGateway().apply { infoGate = kotlinx.coroutines.CompletableDeferred() })
        val c = controller(rig)
        c.setWatcherUrl(url); c.loadWatcher()
        advanceTimeBy(2_999); runCurrent()
        assertEquals(SetupStep.Watcher(url, loading = true, checking = true), c.state.value.step)
        advanceTimeBy(2); runCurrent()
        assertEquals(SetupStep.Watcher(url, loading = true, checking = true, slow = true), c.state.value.step)
        advanceTimeBy(11_998); runCurrent()
        assertNull(c.state.value.error)
        advanceTimeBy(2); runCurrent()
        assertEquals(MoneroSetupController.ERR_TIMEOUT, c.state.value.error)
        assertEquals(SetupStep.Watcher(url, retry = true), c.state.value.step)
        assertEquals(2, rig.gateway.infoCancelled, "prefetch (url edited) and the timed-out check")
    }

    @Test
    fun `cancel stops the check without an error`() = runTest {
        val rig = Rig(FakeWatcherGateway().apply { infoGate = kotlinx.coroutines.CompletableDeferred() })
        val c = controller(rig)
        c.loadWatcher()
        advanceTimeBy(3_500); runCurrent()
        assertTrue((c.state.value.step as SetupStep.Watcher).slow)
        c.cancelCheck()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        assertNull(c.state.value.error)
        assertEquals(1, rig.gateway.infoCancelled)
    }

    @Test
    fun `the personal check is slow after 3 s and times out after 15 s`() = runTest {
        val rig = Rig()
        rig.tips.statusGate = kotlinx.coroutines.CompletableDeferred()
        val c = controller(rig)
        val input = nprofile("wss://w.example")
        c.startPersonal(); c.setPersonalInput(input); c.loadPersonal()
        advanceTimeBy(2_999); runCurrent()
        assertEquals(SetupStep.Personal(input, "", loading = true), c.state.value.step)
        advanceTimeBy(2); runCurrent()
        assertEquals(SetupStep.Personal(input, "", loading = true, slow = true), c.state.value.step)
        advanceTimeBy(12_000); runCurrent()
        assertEquals(MoneroSetupController.ERR_TIMEOUT, c.state.value.error)
        assertEquals(SetupStep.Personal(input, "", retry = true), c.state.value.step)
    }

    @Test
    fun `the personal check can be cancelled`() = runTest {
        val rig = Rig()
        rig.tips.statuses[watcherHex] = status()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.tips.statusGate = gate
        val c = controller(rig)
        val input = nprofile("wss://w.example")
        c.startPersonal(); c.setPersonalInput(input); c.loadPersonal()
        advanceTimeBy(4_000); runCurrent()
        c.cancelCheck()
        assertEquals(SetupStep.Personal(input, ""), c.state.value.step)
        gate.complete(Unit)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(SetupStep.Personal(input, ""), c.state.value.step)
        assertNull(c.state.value.error)
    }

    /** Started, but time is not advanced: for the start phase in virtual time. */
    private fun TestScope.started(rig: Rig, intro: Boolean = false) = MoneroSetupController(
        intro = intro, gateway = rig.gateway, tips = rig.tips, settings = rig.settings, secrets = rig.secrets,
        relays = { rig.relays += it }, scope = eager(), selfPubkey = self, keyGen = { rig.keys }, random = Random(7), now = { NOW },
        formatTime = { "t$it" },
    ).also { it.start(); runCurrent() }

    @Test
    fun `a slow start phase shows the hint after 3 s`() = runTest {
        val rig = Rig(tips = FakeTips().apply { liveInfos = emptyMap(); liveDelayMs = 4_000 })
        val c = started(rig, intro = true)
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER, loading = true), c.state.value.step)
        advanceTimeBy(3_001); runCurrent()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER, loading = true, slow = true), c.state.value.step)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        assertNull(c.state.value.announced)
    }

    @Test
    fun `the announced address lookup gives up after 5 s and counts as nothing announced`() = runTest {
        val announced = MoneroKeys.generate().address(Network.MAINNET)
        val rig = Rig(tips = FakeTips().apply { liveInfos = mapOf(self to PaymentInfo.Enabled(announced, Watcher.Http(url, watcherPk))); liveDelayMs = 8_000 })
        val c = started(rig, intro = true)
        advanceTimeBy(4_999); runCurrent()
        assertTrue((c.state.value.step as SetupStep.Watcher).loading)
        advanceTimeBy(2); runCurrent()
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        assertNull(c.state.value.announced)
        assertFalse(c.state.value.finished, "the intro stays: nothing counts as announced")
        assertEquals(listOf(Presets.DEFAULT_WATCHER), rig.gateway.infoUrls, "the watcher step starts its prefetch as usual")
    }

    @Test
    fun `a fast start phase is unchanged`() = runTest {
        val announced = MoneroKeys.generate().address(Network.MAINNET)
        val rig = Rig(tips = FakeTips().apply { liveInfos = mapOf(self to PaymentInfo.Enabled(announced, Watcher.Http(url, watcherPk))) })
        val c = started(rig)
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER), c.state.value.step)
        assertEquals(announced.encode(), c.state.value.announced)
    }

    @Test
    fun `back to watcher reuses the prefetch or starts one for the edited url`() = runTest {
        val rig = Rig()
        val c = controller(rig)
        c.startPersonal(); c.backToWatcher()
        assertEquals(1, rig.gateway.infoCalls, "the finished prefetch is kept")
        c.setWatcherUrl(url)
        c.startPersonal(); c.backToWatcher()
        assertEquals(listOf(Presets.DEFAULT_WATCHER, url), rig.gateway.infoUrls)
        c.loadWatcher()
        assertTrue(c.state.value.step is SetupStep.Choose, "step ${c.state.value.step}")
        assertEquals(2, rig.gateway.infoCalls)
    }

    @Test
    fun `later during the start phase stops it, no watcher step and no prefetch follow`() = runTest {
        val rig = Rig(tips = FakeTips().apply { liveInfos = emptyMap(); liveDelayMs = 4_000 })
        val c = started(rig, intro = true)
        advanceTimeBy(1_000); runCurrent()
        c.skip(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(c.state.value.finished)
        assertEquals(SetupStep.Watcher(Presets.DEFAULT_WATCHER, loading = true), c.state.value.step, "the start phase did not go on")
        assertEquals(emptyList<String>(), rig.gateway.infoUrls, "no prefetch after leaving")
    }

    @Test
    fun `later drops the prefetch`() = runTest {
        val rig = Rig(FakeWatcherGateway().apply { infoGate = kotlinx.coroutines.CompletableDeferred() })
        val c = controller(rig, intro = true)
        c.skip(); runCurrent()
        assertTrue(c.state.value.finished)
        assertEquals(1, rig.gateway.infoCancelled)
    }

    @Test
    fun `later during a check stops it`() = runTest {
        val rig = Rig(FakeWatcherGateway().apply { infoGate = kotlinx.coroutines.CompletableDeferred() })
        val c = controller(rig, intro = true)
        c.setWatcherUrl(url); c.loadWatcher()
        c.skip()
        advanceTimeBy(20_000); runCurrent()
        assertTrue(c.state.value.finished)
        assertNull(c.state.value.error, "no timeout after leaving")
        assertEquals(2, rig.gateway.infoCancelled, "prefetch (url edited) and the check")
    }

    private companion object {
        const val NOW = 5_000L
    }
}
