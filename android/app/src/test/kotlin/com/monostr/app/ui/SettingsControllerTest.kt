package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.settings.DmInboxHints
import com.monostr.app.ui.settings.DmRelayStoreAdapter
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
    fun `publishing the relay list sends the app's relays and says what happened`() = runTest {
        val relays = listOf("wss://relay.monostr.com", "wss://nos.lol")
        val publish = FakePublish()
        assertEquals(uiText(R.string.settings_msg_relay_list_published), publishRelayList({ relays }, { publish.publishRelayList(it) }))
        assertEquals(listOf("relaylist:2"), publish.calls)
        val refused = FakePublish(relaysOk = false)
        assertEquals(uiText(R.string.settings_msg_relay_list_not_published), publishRelayList({ relays }, { refused.publishRelayList(it) }))
        val failing = FakePublish(fail = true)
        assertEquals(uiText(R.string.error_send_failed), publishRelayList({ relays }, { failing.publishRelayList(it) }))
    }
}
