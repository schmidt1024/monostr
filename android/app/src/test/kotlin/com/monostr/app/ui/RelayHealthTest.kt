package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.settings.relayInfoFor
import com.monostr.app.ui.settings.relayShownUrl
import com.monostr.app.ui.settings.RelayListDiff
import com.monostr.app.ui.settings.RelayStatusLine
import com.monostr.app.ui.settings.relayListDiff
import com.monostr.app.ui.settings.relayStatusLine
import com.monostr.nostr.RelayList
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.model.RelayState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 0.24.3: what the settings say about each relay and about the published relay list. */
class RelayHealthTest {
    private val now = 1_000_000L

    @Test
    fun `a connected relay shows only its state`() {
        assertEquals(RelayStatusLine(uiText(R.string.relay_connected)), relayStatusLine(RelayInfo("wss://a", RelayState.CONNECTED, everConnected = true), lastSeen = now - 30, now = now))
    }

    @Test
    fun `a relay away for a while shows when it was last connected`() {
        val line = relayStatusLine(RelayInfo("wss://a", RelayState.DISCONNECTED, everConnected = false), lastSeen = now - 2 * 86_400, now = now)
        assertEquals(RelayStatusLine(uiText(R.string.relay_disconnected), lastSeenAgo = uiText(R.string.time_days, 2L)), line)
    }

    @Test
    fun `a resting relay that was never seen says so`() {
        val line = relayStatusLine(RelayInfo("wss://dead", RelayState.DISCONNECTED, resting = true), lastSeen = null, now = now)
        assertEquals(RelayStatusLine(uiText(R.string.relay_resting), neverSeen = true), line)
    }

    @Test
    fun `a relay that connected in this session before the first record is not called never seen`() {
        val line = relayStatusLine(RelayInfo("wss://a", RelayState.DISCONNECTED, everConnected = true), lastSeen = null, now = now)
        assertEquals(RelayStatusLine(uiText(R.string.relay_disconnected)), line)
    }

    @Test
    fun `a relay the engine does not report is not connected`() {
        assertEquals(RelayStatusLine(uiText(R.string.relay_not_connected)), relayStatusLine(null, lastSeen = null, now = now))
    }

    @Test
    fun `the same relays in both lists need no hint, trailing slashes and order aside`() {
        assertNull(relayListDiff(listOf("wss://a", "wss://b/"), RelayList(read = listOf("wss://b"), write = listOf("wss://a/"))))
    }

    @Test
    fun `relays added in the app and relays only the published list has are counted`() {
        val diff = relayListDiff(listOf("wss://a", "wss://new1", "wss://new2"), RelayList(read = listOf("wss://a", "wss://old"), write = listOf("wss://a")))
        assertEquals(RelayListDiff(unpublished = 2, missing = 1), diff)
    }

    @Test
    fun `no published list found`() {
        assertEquals(RelayListDiff(unpublished = 0, missing = 0, noneFound = true), relayListDiff(listOf("wss://a"), null))
    }

    private val published = RelayList(read = listOf("wss://a", "wss://b"), write = listOf("wss://a"))

    @Test
    fun `a chosen mode that differs from the marker counts as unpublished`() {
        // b is marked read only in the published list, the user made it read and write
        assertEquals(RelayListDiff(unpublished = 1, missing = 0), relayListDiff(listOf("wss://a", "wss://b"), published, mapOf("wss://b" to RelayMode.BOTH)))
    }

    @Test
    fun `a marker with no chosen mode does not count`() {
        assertNull(relayListDiff(listOf("wss://a", "wss://b"), published, emptyMap()))
    }

    @Test
    fun `a chosen mode equal to the marker does not count`() {
        assertNull(relayListDiff(listOf("wss://a", "wss://b/"), published, mapOf("wss://b" to RelayMode.READ, "wss://a/" to RelayMode.BOTH)))
    }

    @Test
    fun `a relay address reads without wss, an unencrypted one keeps its scheme`() {
        assertEquals("relay.example.com", relayShownUrl("wss://relay.example.com"))
        assertEquals("relay.example.com/inbox", relayShownUrl("wss://relay.example.com/inbox/"))
        assertEquals("relay.example.com", relayShownUrl("WSS://relay.example.com"))
        assertEquals("ws://10.0.0.2:7777", relayShownUrl("ws://10.0.0.2:7777"))
    }

    @Test
    fun `an onion relay the engine does not hold reads needs Tor, others not connected`() {
        assertEquals(RelayStatusLine(uiText(R.string.relay_needs_tor)), relayStatusLine(null, lastSeen = null, now = now, onion = true))
        assertEquals(RelayStatusLine(uiText(R.string.relay_not_connected)), relayStatusLine(null, lastSeen = null, now = now))
        // with Tor on the engine holds it: the state is the engine's
        assertEquals(RelayStatusLine(uiText(R.string.relay_connected)), relayStatusLine(RelayInfo("ws://a.onion", RelayState.CONNECTED, everConnected = true), lastSeen = null, now = now, onion = true))
    }

    @Test
    fun `an onion relay is not counted as unpublished, it is never published`() {
        val published = RelayList(read = listOf("wss://a.example"), write = listOf("wss://a.example"))
        assertEquals(null, relayListDiff(listOf("wss://a.example", "ws://abc.onion"), published))
        assertEquals(null, relayListDiff(listOf("wss://a.example", "ws://abc%2Eonion", "wss://x_y.onion"), published))
        assertEquals(RelayListDiff(1, 0), relayListDiff(listOf("wss://a.example", "wss://b.example", "ws://abc.onion"), published))
    }

    @Test
    fun `the pool spells an onion host in lower case, the row still finds it`() {
        val live = listOf(RelayInfo("ws://abc.onion", RelayState.CONNECTED), RelayInfo("wss://Path.example/Case", RelayState.CONNECTED))
        assertEquals(live[0], relayInfoFor(live, "ws://ABC.ONION"))
        assertEquals(live[0], relayInfoFor(live, "ws://abc.onion/"))
        assertEquals(null, relayInfoFor(live, "wss://path.example/case"), "other relays keep an exact match")
        assertEquals(live[1], relayInfoFor(live, "wss://Path.example/Case"))
    }
}
