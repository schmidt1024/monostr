package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.data.MoneroSetup
import com.monostr.app.net.SendRefused
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.lists.AddToListController
import com.monostr.app.ui.lists.AddToListRow
import com.monostr.app.ui.lists.ListController
import com.monostr.app.ui.lists.ListEditController
import com.monostr.app.ui.lists.PinActions
import com.monostr.app.ui.lists.ProfileListsController
import com.monostr.app.ui.profile.ProfileEditController
import com.monostr.app.ui.settings.MoneroSettingsController
import com.monostr.app.session.AdoptResult
import com.monostr.app.ui.settings.MutedController
import com.monostr.app.ui.settings.SettingsController
import com.monostr.app.ui.settings.applyDmRelays
import com.monostr.app.ui.settings.DmRelayStoreAdapter
import com.monostr.app.ui.settings.publishRelayList
import com.monostr.app.ui.tips.AmountsVisibility
import com.monostr.app.ui.common.userMessage
import com.monostr.app.ui.common.uploadMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Spec 2026-11-06 5.3: what writes refuses while Tor is not connected, with the reason, before the repository. The repositories are
 * where a signer is asked (a private list or mute write encrypts before it publishes), so a repository that was not called means no signer call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendBlockedControllersTest {
    private val self = "e".repeat(64)
    private val bob = "b".repeat(64)
    private val reason = uiText(R.string.send_blocked_tor)
    private val blocked = SendRig(connected = false).send
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `add to list refuses before the repository`() = runTest {
        val set = followSet("a", emptyList(), author = self)
        val lists = FakeLists(listOf(set))
        val c = AddToListController(bob, lists, eager(), sendGate = blocked)
        c.start(); advanceUntilIdle()
        c.toggle(AddToListRow(set, false)); advanceUntilIdle()
        assertTrue(lists.calls.isEmpty(), "calls: ${lists.calls}")
        assertEquals(reason, c.state.value.message?.text)
        assertFalse(c.state.value.rows!!.single().checked, "the tick does not stay")
    }

    @Test
    fun `the list screen refuses removing a member, following, following all and pinning`() = runTest {
        val set = followSet("a", listOf(bob), author = self)
        val lists = FakeLists(listOf(set))
        val pins = FakeFeedTabs()
        val follows = FakeFollows()
        val c = ListController(set.address, self, lists, pins, FakeProfiles(), eager(), follows = follows, sendGate = blocked, newFeed = { _, _ -> error("no feed needed") })
        for (act in listOf<() -> Unit>({ c.removeMember(bob) }, { c.follow(bob) }, { c.togglePin() })) {
            c.clearMessage()
            act(); advanceUntilIdle()
            assertEquals(reason, c.state.value.message?.text)
        }
        assertTrue(lists.calls.isEmpty(), "calls: ${lists.calls}")
        assertTrue(pins.calls.isEmpty(), "calls: ${pins.calls}")
        assertTrue(follows.calls.isEmpty(), "calls: ${follows.calls}")
    }

    @Test
    fun `follow all refuses too`() = runTest {
        val set = followSet("a", listOf(bob), author = "c".repeat(64))
        val lists = FakeLists().apply { foreign = mapOf(set.address to set) }
        val follows = FakeFollows()
        val c = ListController(set.address, self, lists, FakeFeedTabs(), FakeProfiles(), eager(), follows = follows, sendGate = blocked, newFeed = { _, _ -> error("no feed needed") })
        c.followAll(); advanceUntilIdle()
        assertTrue(follows.calls.isEmpty(), "calls: ${follows.calls}")
        assertEquals(reason, c.state.value.message?.text)
    }

    @Test
    fun `saving and deleting a list refuse before the repository, the form stays`() = runTest {
        val set = followSet("a", listOf(bob), author = self)
        val lists = FakeLists(listOf(set))
        val pins = FakeFeedTabs(listOf(set.address))
        val c = ListEditController(set.address, self, null, lists, pins, eager(), sendGate = blocked)
        c.start(); advanceUntilIdle()
        c.setTitle("New title")
        c.save(); advanceUntilIdle()
        assertEquals(reason, c.state.value.message?.text)
        assertFalse(c.state.value.saving); assertFalse(c.state.value.done)
        assertEquals("New title", c.state.value.title)
        c.clearMessage()
        c.delete(); advanceUntilIdle()
        assertEquals(reason, c.state.value.message?.text)
        assertFalse(c.state.value.saving); assertFalse(c.state.value.done)
        assertTrue(lists.calls.none { it.startsWith("edit") || it.startsWith("save") || it.startsWith("delete") }, "calls: ${lists.calls}")
        assertTrue(pins.calls.isEmpty(), "calls: ${pins.calls}")
    }

    @Test
    fun `profile lists refuse pinning and moving, the order stays`() = runTest {
        val a = followSet("a", emptyList(), author = self); val b = followSet("b", emptyList(), author = self)
        val pins = FakeFeedTabs()
        val c = ProfileListsController(self, self, FakeLists(listOf(a, b)), pins, eager(), sendGate = blocked)
        c.open(); advanceUntilIdle()
        val before = c.state.value.lists
        c.move(0, 1); advanceUntilIdle()
        assertEquals(reason, c.state.value.message?.text)
        assertEquals(before, c.state.value.lists)
        c.clearMessage()
        c.togglePin(a); advanceUntilIdle()
        assertEquals(reason, c.state.value.message?.text)
        assertTrue(pins.calls.isEmpty(), "calls: ${pins.calls}")
    }

    @Test
    fun `pin actions refuse with the reason and no retry`() = runTest {
        val pins = FakeFeedTabs()
        val messages = ArrayList<com.monostr.app.ui.common.Message>()
        PinActions(pins, eager(), sendGate = blocked) { messages += it }.toggle(followSet("a", emptyList()).address, false)
        advanceUntilIdle()
        assertTrue(pins.calls.isEmpty())
        assertEquals(listOf(reason.asMessage()), messages)
    }

    @Test
    fun `bookmark toggles refuse before the repository, the remove buttons through the same path`() = runTest {
        val repo = FakeBookmarks(initial = listOf("1".repeat(64)))
        val actions = BookmarkActions(repo, eager(), sendGate = blocked)
        val results = ArrayList<UiText?>()
        actions.toggle(note('1', bob)) { results += it }
        actions.toggleArticle("30023:$bob:x") { results += it }
        advanceUntilIdle()
        assertTrue(repo.toggled.isEmpty(), "toggled: ${repo.toggled}")
        assertEquals(listOf<UiText?>(reason, reason), results)
        assertEquals(listOf("1".repeat(64)), repo.state.value.ids)
    }

    @Test
    fun `mute and unmute refuse before the repository`() = runTest {
        val mute = FakeMute(initial = setOf(bob))
        val actions = MuteActions(mute, eager(), FakeProfiles(), sendGate = blocked)
        val results = ArrayList<UiText?>()
        var undo: Boolean? = null
        actions.mute("c".repeat(64)) { text, ok -> results += text; undo = ok }
        actions.unmute(bob) { results += it }
        advanceUntilIdle()
        assertEquals(setOf(bob), mute.muted.value, "neither write ran")
        assertEquals(listOf<UiText?>(reason, reason), results)
        assertEquals(false, undo, "no undo offered")
    }

    @Test
    fun `the muted screen shows the reason`() = runTest {
        val mute = FakeMute(initial = setOf(bob))
        val c = MutedController(mute, FakeProfiles(), eager(), sendGate = blocked)
        c.start(); advanceUntilIdle()
        c.unmute(bob); advanceUntilIdle()
        assertEquals(setOf(bob), mute.muted.value)
        assertEquals(reason, c.state.value.message)
    }

    @Test
    fun `saving the profile refuses before asking anything and keeps the form`() = runTest {
        val publish = FakePublish()
        val c = ProfileEditController(self, FakeProfiles(), publish, eager(), sendGate = blocked)
        c.start(); advanceUntilIdle()
        c.update { it.copy(name = "alice") }
        c.save(); advanceUntilIdle()
        assertTrue(publish.calls.isEmpty(), "calls: ${publish.calls}")
        assertEquals("alice", c.state.value.form.name)
        assertFalse(c.state.value.saving)
        assertEquals(reason, c.state.value.message)
    }

    @Test
    fun `monero settings refuse switching off, removing the address and changing the amounts, each with the reason`() = runTest {
        val setup = MoneroSetup("4addr", "https://w.example", "c".repeat(64), "mainnet", 1, amountsHidden = true)
        val settings = FakeTipSettings(setup = setup)
        val tips = FakeTips()
        val gateway = FakeWatcherGateway()
        for (act in listOf<(MoneroSettingsController) -> Unit>({ it.disable() }, { it.removePayto() }, { it.setAmountsPublic(true) })) {
            val c = MoneroSettingsController(settings, FakeSecrets(), tips, gateway, { AdoptResult.ADOPTED }, eager(), sendGate = blocked)
            c.start(); advanceUntilIdle()
            act(c); advanceUntilIdle()
            assertEquals(reason, c.state.value.error)
            assertFalse(c.state.value.busy)
        }
        assertEquals(0, tips.disabledCalls)
        assertTrue(gateway.unregistered.isEmpty(), "the account stays at the watcher")
        assertTrue(tips.publishedInfos.isEmpty())
        assertEquals(setup, settings.setupState.value)
    }

    @Test
    fun `hiding the amounts from the question refuses before publishing`() = runTest {
        // an address that parses: otherwise setHidden fails there, whatever the gate says
        val address = com.monostr.monero.MoneroKeys.generate().address(com.monostr.monero.Network.MAINNET).encode()
        val setup = MoneroSetup(address, "https://w.example", "c".repeat(64), "mainnet", 1)
        val tips = FakeTips()
        val settings = FakeTipSettings(setup = setup)
        assertTrue(AmountsVisibility(tips, FakeWatcherGateway(), settings).setHidden(true), "with the gate open this very setup goes out")
        tips.publishedInfos.clear()
        settings.setupState.value = setup
        assertFalse(AmountsVisibility(tips, FakeWatcherGateway(), settings, sendGate = blocked).setHidden(true))
        assertTrue(tips.publishedInfos.isEmpty())
        assertEquals(setup, settings.setupState.value)
    }

    @Test
    fun `publishing the relay list refuses before reading or publishing`() = runTest {
        val publish = FakePublish()
        val text = publishRelayList({ error("not read") }, { publish.publishRelayList(it) }, blocked)
        assertEquals(reason, text)
        assertTrue(publish.calls.isEmpty())
    }

    @Test
    fun `an inbox list edit refuses before publishing, with the reason, and the list stays`() = runTest {
        val dmSettings = FakeDmSettings(listOf("wss://a.example"))
        val publish = FakePublish()
        val c = SettingsController(DmRelayStoreAdapter(dmSettings), { applyDmRelays(it, { l -> publish.dmRelayList(l) }, dmSettings, sendGate = blocked) {} }, eager())
        c.start(); advanceUntilIdle()
        c.add("wss://b.example"); advanceUntilIdle()
        assertTrue(publish.calls.isEmpty())
        assertEquals(listOf("wss://a.example"), dmSettings.relayState.value)
        assertEquals(listOf("wss://a.example"), c.state.value.relays)
        assertEquals(reason, c.state.value.error)
    }

    @Test
    fun `the refusal has its own text wherever an error is mapped`() {
        assertEquals(reason, SendRefused().userMessage())
    }

    @Test
    fun `the Retry of a failed add to list refuses too, no write and no signer`() = runTest {
        val set = followSet("a", emptyList(), author = self)
        val lists = FakeLists(listOf(set)).apply { result = com.monostr.nostr.model.SaveResult.Failed }
        val send = SendRig()
        val c = AddToListController(bob, lists, eager(), sendGate = send.send)
        c.start(); advanceUntilIdle()
        c.toggle(AddToListRow(set, false)); advanceUntilIdle()
        val retry = c.state.value.message!!.retry!!
        val calls = lists.calls.size
        send.connected.value = false
        retry(); advanceUntilIdle()
        assertEquals(calls, lists.calls.size, "calls: ${lists.calls}")
        assertEquals(reason, c.state.value.message?.text)
        assertFalse(c.state.value.rows!!.single().checked)
    }

    @Test
    fun `a follow all confirmed after Tor went refuses`() = runTest {
        val members = (10..30).map { "%064x".format(it) }
        val set = followSet("big", members, author = "c".repeat(64))
        val follows = FakeFollows()
        val send = SendRig()
        val c = com.monostr.app.ui.discover.DiscoverController(FakeDiscover(listOf(set)), follows, FakeProfiles(), FakeMute(), self, eager(), sendGate = send.send)
        c.start(); advanceUntilIdle()
        c.followAll(set.address); advanceUntilIdle()
        assertTrue(c.state.value.followAllConfirm != null, "the question is open")
        send.connected.value = false
        c.confirmFollowAll(); advanceUntilIdle()
        assertTrue(follows.calls.isEmpty(), "calls: ${follows.calls}")
        assertEquals(reason, c.state.value.message)
        c.clearMessage()
        c.follow(bob); advanceUntilIdle()
        assertTrue(follows.calls.isEmpty(), "calls: ${follows.calls}")
        assertEquals(reason, c.state.value.message)
    }

    @Test
    fun `an unfollow confirmed after Tor went refuses on the follows screen`() = runTest {
        val follows = FakeFollows()
        val c = com.monostr.app.ui.follows.FollowsController(self, FakeFollowListsForGate(), FakeProfiles(), eager(), follows = follows, sendGate = blocked)
        c.unfollow(bob); c.follow(bob); advanceUntilIdle()
        assertTrue(follows.calls.isEmpty(), "calls: ${follows.calls}")
        assertTrue(c.busy.value.isEmpty())
        assertEquals(reason, c.message.value)
    }

    private class FakeFollowListsForGate : com.monostr.nostr.repo.FollowListsRepository {
        override suspend fun following(pubkey: String): List<String> = emptyList()
        override suspend fun followers(pubkey: String, until: Long?, seen: Set<String>) = error("not asked")
    }

    @Test
    fun `a profile picture is not prepared, signed or uploaded while canSend is false`() = runTest {
        val up = FakeMediaUploader()
        val c = ProfileEditController(self, FakeProfiles(), FakePublish(), eager(), up, sendGate = blocked)
        c.start(); advanceUntilIdle()
        c.update { it.copy(name = "alice") }
        c.upload(com.monostr.app.ui.profile.PictureField.PICTURE, "file:///p"); advanceUntilIdle()
        assertTrue(up.uploads.isEmpty())
        assertEquals(com.monostr.app.ui.profile.PictureField.PICTURE to reason, c.state.value.uploadError)
        assertEquals(null, c.state.value.uploading)
        assertEquals("alice", c.state.value.form.name)
    }

    @Test
    fun `a refused upload says why`() {
        assertEquals(reason, SendRefused().uploadMessage())
    }
}
