package com.monostr.app.ui

import com.monostr.app.net.TorState
import com.monostr.app.ui.settings.ChipTone
import com.monostr.app.ui.settings.RelayChip
import com.monostr.app.ui.settings.TorChip
import com.monostr.app.ui.settings.MoneroChip
import com.monostr.app.ui.settings.moneroChip
import com.monostr.app.ui.settings.relayChip
import com.monostr.app.ui.settings.torChip
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsChipsTest {
    @Test fun unknownRelaysCountAsNotConnected() {
        val c = relayChip(listOf("wss://a", "wss://b.onion", "wss://c"), listOf(RelayInfo("wss://a/", RelayState.CONNECTING)))
        assertEquals(RelayChip(0, 3), c); assertEquals(ChipTone.WARN, c.tone)
    }

    @Test fun connectedMainRelaysAreCountedOthersIgnored() {
        val c = relayChip(listOf("wss://a", "wss://b"), listOf(RelayInfo("wss://a/", RelayState.CONNECTED), RelayInfo("wss://x", RelayState.CONNECTED)))
        assertEquals(RelayChip(1, 2), c); assertEquals(ChipTone.NEUTRAL, c.tone)
    }

    @Test fun relayToneGreenOnlyWhenAllAmberBelowHalf() {
        assertEquals(ChipTone.OK, RelayChip(4, 4).tone)
        assertEquals(ChipTone.NEUTRAL, RelayChip(3, 4).tone)
        assertEquals(ChipTone.NEUTRAL, RelayChip(2, 4).tone)
        assertEquals(ChipTone.WARN, RelayChip(1, 4).tone)
        assertEquals(ChipTone.WARN, RelayChip(0, 1).tone)
    }

    @Test fun torStates() {
        assertEquals(TorChip.OFF, torChip(false, TorState.Connected))
        assertEquals(TorChip.ON, torChip(true, TorState.Connected))
        assertEquals(TorChip.FAILED, torChip(true, TorState.Failed))
        assertEquals(TorChip.CONNECTING, torChip(true, null))
        assertEquals(TorChip.CONNECTING, torChip(true, TorState.Connecting(40)))
    }

    @Test fun moneroChipStates() {
        assertEquals(MoneroChip.NONE, moneroChip(null, null, null))
        assertEquals(MoneroChip.ACTIVE, moneroChip(null, "4abc", null))
        assertEquals(MoneroChip.ACTIVE, moneroChip(null, null, "4abc"))
    }
}
