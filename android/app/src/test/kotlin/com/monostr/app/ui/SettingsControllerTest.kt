package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.settings.DmInboxHints
import com.monostr.app.ui.settings.DmRelayStoreAdapter
import com.monostr.app.ui.settings.RelayListRefused
import com.monostr.app.ui.settings.SettingsController
import com.monostr.app.ui.settings.applyDmRelays
import com.monostr.app.ui.settings.isDefaultRelayList
import com.monostr.app.ui.settings.publishRelayList
import com.monostr.nostr.PublishResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `minimum one keeps the last relay, minimum zero lets the list empty`() = runTest {
        val one = FakeRelayStore(listOf("wss://a.example"))
        val strict = SettingsController(one, { one.set(it) }, eager())
        strict.start(); advanceUntilIdle()
        strict.remove("wss://a.example"); advanceUntilIdle()
        assertEquals(listOf("wss://a.example"), strict.state.value.relays)
        assertEquals(uiText(R.string.settings_relay_keep_one), strict.state.value.error)

        val search = FakeRelayStore(listOf("wss://s.example"))
        val loose = SettingsController(search, { search.set(it) }, eager(), minimum = 0)
        loose.start(); advanceUntilIdle()
        loose.remove("wss://s.example"); advanceUntilIdle()
        assertEquals(emptyList<String>(), loose.state.value.relays)
        assertNull(loose.state.value.error)
    }

    @Test
    fun `dm relay list keeps at least one relay and applies through the store`() = runTest {
        val dmSettings = FakeDmSettings(listOf("wss://relay.monostr.com"))
        val applied = ArrayList<List<String>>()
        val controller = SettingsController(DmRelayStoreAdapter(dmSettings), { list -> dmSettings.setRelays(list); applied += list }, eager(), minimum = 1)
        controller.start(); advanceUntilIdle()

        controller.remove("wss://relay.monostr.com"); advanceUntilIdle()
        assertEquals(listOf("wss://relay.monostr.com"), controller.state.value.relays)
        assertEquals(uiText(R.string.settings_relay_keep_one), controller.state.value.error)
        assertEquals(emptyList<List<String>>(), applied)

        controller.add("wss://second.example"); advanceUntilIdle()
        assertEquals(listOf("wss://relay.monostr.com", "wss://second.example"), controller.state.value.relays)
        assertEquals(listOf("wss://relay.monostr.com", "wss://second.example"), dmSettings.relayState.value)
        assertEquals(listOf(listOf("wss://relay.monostr.com", "wss://second.example")), applied)
    }

    @Test
    fun `replaceAll puts another list in place of the whole relay list`() = runTest {
        // v0.8.5: back to the default relays after a relay list full of dead relays was adopted
        val store = FakeRelayStore((1..31).map { "wss://dead$it.example" })
        val applied = ArrayList<List<String>>()
        var fail = false
        val c = SettingsController(store, { if (fail) error("engine gone"); applied += it; store.set(it) }, eager())
        c.start(); advanceUntilIdle()
        val defaults = listOf("wss://relay.monostr.com", "wss://nos.lol")
        c.replaceAll(defaults); advanceUntilIdle()
        assertEquals(listOf(defaults), applied)
        assertEquals(defaults, c.state.value.relays)
        assertNull(c.state.value.error)
        fail = true
        c.replaceAll(listOf("wss://other.example")); advanceUntilIdle()
        assertEquals(defaults, c.state.value.relays, "a list that could not be applied leaves the old one")
        assertEquals(uiText(R.string.settings_relay_save_failed), c.state.value.error)
        fail = false
        c.replaceAll(emptyList()); advanceUntilIdle()
        assertEquals(defaults, c.state.value.relays, "never below the minimum")
        assertEquals(uiText(R.string.settings_relay_keep_one), c.state.value.error)
    }

    @Test
    fun `the reset is offered only for a list that differs from the defaults`() {
        val defaults = listOf("wss://relay.monostr.com", "wss://nos.lol")
        assertTrue(isDefaultRelayList(listOf("wss://nos.lol", "wss://relay.monostr.com"), defaults), "order does not matter")
        assertTrue(isDefaultRelayList(emptyList(), defaults), "before the store answered there is nothing to reset")
        assertFalse(isDefaultRelayList(defaults + "wss://dead.example", defaults))
        assertFalse(isDefaultRelayList(listOf("wss://nos.lol"), defaults))
        assertFalse(isDefaultRelayList(defaults, defaults, mapOf("wss://nos.lol" to RelayMode.READ)), "stored modes can be cleared")
        assertTrue(isDefaultRelayList(defaults, defaults, emptyMap()))
    }

    @Test
    fun `replace swaps one relay in place and keeps the rest`() = runTest {
        val store = FakeRelayStore(listOf("wss://a.example", "wss://b.example", "wss://c.example", "wss://d.example"))
        val applied = ArrayList<List<String>>()
        val c = SettingsController(store, { applied += it; store.set(it) }, eager())
        c.start(); advanceUntilIdle()
        c.replace("wss://b.example", "wss://relay.monostr.com"); advanceUntilIdle()
        assertEquals(listOf("wss://a.example", "wss://relay.monostr.com", "wss://c.example", "wss://d.example"), applied.single())
        c.replace("wss://a.example", "not a url"); advanceUntilIdle()
        assertEquals(1, applied.size)
        assertEquals(uiText(R.string.settings_relay_invalid), c.state.value.error)
    }

    @Test
    fun `a DM relay list no relay accepted is not stored and the inbox card stays`() = runTest {
        val dmSettings = FakeDmSettings(listOf("wss://inbox.example.com"))
        var accept = false
        var changed = 0
        val publish: suspend (List<String>) -> PublishResult = {
            if (accept) PublishResult("0".repeat(64), 10050, listOf("wss://r"), emptyMap())
            else PublishResult("0".repeat(64), 10050, emptyList(), mapOf("wss://r" to "blocked"))
        }
        val c = SettingsController(DmRelayStoreAdapter(dmSettings), { applyDmRelays(it, publish, dmSettings) { changed++ } }, eager(), minimum = 1)
        c.start(); advanceUntilIdle()
        c.add(DmInboxHints.MONOSTR); advanceUntilIdle()
        assertEquals(listOf("wss://inbox.example.com"), dmSettings.relayState.value) // nothing stored
        assertEquals(listOf("wss://inbox.example.com"), c.state.value.relays)
        assertTrue(DmInboxHints.missing(c.state.value.relays)) // the card stays
        assertEquals(uiText(R.string.settings_relay_save_failed), c.state.value.error)
        assertEquals(0, changed) // the DM sync did not restart

        accept = true
        c.add(DmInboxHints.MONOSTR); advanceUntilIdle()
        assertEquals(listOf("wss://inbox.example.com", DmInboxHints.MONOSTR), dmSettings.relayState.value)
        assertFalse(DmInboxHints.missing(c.state.value.relays))
        assertEquals(1, changed)
    }

    @Test
    fun `a list the session refuses stays as it was and says so`() = runTest {
        val store = FakeRelayStore(listOf("wss://a.example", "wss://b.example"))
        val c = SettingsController(store, { throw RelayListRefused() }, eager())
        c.start(); advanceUntilIdle()
        c.remove("wss://a.example"); advanceUntilIdle()
        assertEquals(listOf("wss://a.example", "wss://b.example"), c.state.value.relays)
        assertEquals(uiText(R.string.settings_relay_keep_read_write), c.state.value.error)
    }

    @Test
    fun `replacing the whole list can go through a step of its own, with the same error handling`() = runTest {
        val store = FakeRelayStore(listOf("wss://a.example"))
        val plain = ArrayList<List<String>>()
        val own = ArrayList<List<String>>()
        val c = SettingsController(store, { plain += it }, eager())
        c.start(); advanceUntilIdle()
        c.replaceAll(listOf("wss://b.example/")) { own += it }; advanceUntilIdle()
        assertEquals(listOf(listOf("wss://b.example")), own)
        assertEquals(emptyList<List<String>>(), plain)

        c.replaceAll(listOf("wss://b.example")) { error("storage failed") }; advanceUntilIdle()
        assertEquals(uiText(R.string.settings_relay_save_failed), c.state.value.error)
        assertEquals(listOf("wss://a.example"), c.state.value.relays)
        c.replaceAll(listOf("wss://b.example")) { throw RelayListRefused() }; advanceUntilIdle()
        assertEquals(uiText(R.string.settings_relay_keep_read_write), c.state.value.error)
    }

    @Test
    fun `publishing the relay list sends the app's relays and says what happened`() = runTest {
        val relays = listOf(RelayEntry("wss://relay.monostr.com", RelayMode.WRITE), RelayEntry("wss://nos.lol", RelayMode.BOTH))
        val publish = FakePublish()
        assertEquals(uiText(R.string.settings_msg_relay_list_published), publishRelayList({ relays }, { publish.publishRelayList(it) }))
        assertEquals(listOf("relaylist:2"), publish.calls)
        val refused = FakePublish(relaysOk = false)
        assertEquals(uiText(R.string.settings_msg_relay_list_not_published), publishRelayList({ relays }, { refused.publishRelayList(it) }))
        val failing = FakePublish(fail = true)
        assertEquals(uiText(R.string.error_send_failed), publishRelayList({ relays }, { failing.publishRelayList(it) }))
    }

    @Test
    fun `search and dm lists refuse an onion address at entry, the main list takes it`() = runTest {
        for (spelling in listOf("ws://abc.onion", "wss://ABC.ONION/", "ws://sub.abc.onion:7777")) {
            val search = FakeRelayStore(listOf("wss://s.example"))
            val searchList = SettingsController(search, { search.set(it) }, eager(), minimum = 0, onionAllowed = false)
            searchList.start(); advanceUntilIdle()
            assertFalse(searchList.add(spelling), spelling)
            assertEquals(uiText(R.string.settings_relay_onion_main_only), searchList.state.value.error)
            assertEquals(listOf("wss://s.example"), searchList.state.value.relays)

            val dm = FakeRelayStore(listOf("wss://d.example"))
            val dmList = SettingsController(dm, { dm.set(it) }, eager(), onionAllowed = false)
            dmList.start(); advanceUntilIdle()
            dmList.replace("wss://d.example", spelling); advanceUntilIdle()
            assertEquals(uiText(R.string.settings_relay_onion_main_only), dmList.state.value.error)
            assertEquals(listOf("wss://d.example"), dmList.state.value.relays)
        }
        val main = FakeRelayStore(listOf("wss://m.example"))
        val mainList = SettingsController(main, { main.set(it) }, eager())
        mainList.start(); advanceUntilIdle()
        assertTrue(mainList.add("ws://abc.onion")); advanceUntilIdle()
        assertEquals(listOf("wss://m.example", "ws://abc.onion"), mainList.state.value.relays)
    }

    @Test
    fun `an onion already stored in the search list is listed and does not crash`() = runTest {
        val search = FakeRelayStore(listOf("wss://s.example", "ws://abc.onion"))
        val list = SettingsController(search, { search.set(it) }, eager(), minimum = 0, onionAllowed = false)
        list.start(); advanceUntilIdle()
        assertEquals(listOf("wss://s.example", "ws://abc.onion"), list.state.value.relays)
        list.remove("ws://abc.onion"); advanceUntilIdle()
        assertEquals(listOf("wss://s.example"), list.state.value.relays)
    }
}
