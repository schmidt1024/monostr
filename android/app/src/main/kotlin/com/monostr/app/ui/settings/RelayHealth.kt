package com.monostr.app.ui.settings

import com.monostr.app.R
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.label
import com.monostr.app.ui.common.relativeTime
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.RelayList
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState

/**
 * 0.24.3: one relay's status line in the settings: its [state], then when it was last connected ([lastSeenAgo], from
 * the account's relay-seen file) or that it never was ([neverSeen]). A connected relay shows its state only.
 */
data class RelayStatusLine(val state: UiText, val lastSeenAgo: UiText? = null, val neverSeen: Boolean = false)

/** [info]: the engine's view of the relay (null: not in the pool); [lastSeen]: epoch seconds it was last seen connected, if ever recorded. */
fun relayStatusLine(info: RelayInfo?, lastSeen: Long?, now: Long): RelayStatusLine {
    if (info == null) return RelayStatusLine(uiText(R.string.relay_not_connected))
    val state = if (info.resting) uiText(R.string.relay_resting) else info.state.label()
    if (info.state != RelayState.DISCONNECTED) return RelayStatusLine(state)
    return when {
        lastSeen != null -> RelayStatusLine(state, lastSeenAgo = relativeTime(lastSeen, now))
        // it connected in this session before anything was recorded: not "never"
        info.everConnected -> RelayStatusLine(state)
        else -> RelayStatusLine(state, neverSeen = true)
    }
}

/**
 * 0.24.3: how the app's relays differ from the user's published NIP-65 list: [unpublished] relays are only in the app
 * (other apps and the tip watcher do not look for the user there), [missing] ones only in the published list.
 * [noneFound]: no published list was found (none yet, or the relays did not answer).
 */
data class RelayListDiff(val unpublished: Int, val missing: Int, val noneFound: Boolean = false)

/** Null when both lists name the same relays (trailing slashes and order aside). */
fun relayListDiff(local: List<String>, published: RelayList?): RelayListDiff? {
    if (published == null) return RelayListDiff(0, 0, noneFound = true)
    fun norm(urls: List<String>) = urls.map { it.trim().trimEnd('/') }.toSet()
    val mine = norm(local)
    val theirs = norm(published.read + published.write)
    val diff = RelayListDiff(unpublished = (mine - theirs).size, missing = (theirs - mine).size)
    return diff.takeIf { it.unpublished > 0 || it.missing > 0 }
}
