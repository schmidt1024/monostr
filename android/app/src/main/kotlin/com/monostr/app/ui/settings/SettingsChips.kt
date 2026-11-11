package com.monostr.app.ui.settings

import androidx.annotation.StringRes
import com.monostr.app.R
import com.monostr.app.data.MoneroSetup
import com.monostr.app.net.TorState
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState

enum class ChipTone { OK, NEUTRAL, WARN }

/**
 * [connected] of the [total] relays of the main list. UX P2-15: green only when all are connected, amber when fewer than
 * half are (0 included), neutral in between, so a short drop of one relay does not look like trouble.
 */
data class RelayChip(val connected: Int, val total: Int) {
    val tone: ChipTone get() = when {
        connected >= total -> ChipTone.OK
        connected * 2 < total -> ChipTone.WARN
        else -> ChipTone.NEUTRAL
    }
}

fun relayChip(mainList: List<String>, states: List<RelayInfo>): RelayChip {
    val connected = states.filter { it.state == RelayState.CONNECTED }.map { it.url.trimEnd('/') }.toSet()
    return RelayChip(mainList.count { it.trimEnd('/') in connected }, mainList.size)
}

enum class TorChip(@StringRes val text: Int, val tone: ChipTone) {
    OFF(R.string.settings_chip_tor_off, ChipTone.NEUTRAL),
    CONNECTING(R.string.settings_chip_tor_connecting, ChipTone.NEUTRAL),
    ON(R.string.settings_chip_tor_on, ChipTone.OK),
    FAILED(R.string.settings_chip_tor_failed, ChipTone.WARN),
}

fun torChip(active: Boolean, state: TorState?): TorChip = when {
    !active -> TorChip.OFF
    state == TorState.Connected -> TorChip.ON
    state == TorState.Failed -> TorChip.FAILED
    else -> TorChip.CONNECTING
}

/** The overview's Monero chip: none, «Ready» (a setup stored here) or «Active» (owner 4: an address announced or in payto, set up elsewhere). */
enum class MoneroChip { NONE, READY, ACTIVE }

/** From the stored [setup] and what the settings syncs already read ([announced], [payto]); no relay call of its own. */
fun moneroChip(setup: MoneroSetup?, announced: String?, payto: String?): MoneroChip = when {
    setup != null -> MoneroChip.READY
    announced != null || payto != null -> MoneroChip.ACTIVE
    else -> MoneroChip.NONE
}
