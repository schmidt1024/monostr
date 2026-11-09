package com.monostr.app.ui.settings

import com.monostr.app.R
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.label
import com.monostr.app.ui.common.relativeTime
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.RelayList
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.model.RelayModes
import com.monostr.nostr.model.RelayState
import com.monostr.tips.RelayHosts

/**
 * 0.24.3: one relay's status line in the settings: its [state], then when it was last connected ([lastSeenAgo], from
 * the account's relay-seen file) or that it never was ([neverSeen]). A connected relay shows its state only.
 */
data class RelayStatusLine(val state: UiText, val lastSeenAgo: UiText? = null, val neverSeen: Boolean = false)

/** The engine's view of [url]: the pool spells a host in lower case, so a hidden service matches without regard to case, any other relay exactly. */
fun relayInfoFor(live: List<RelayInfo>, url: String): RelayInfo? {
    val key = url.trimEnd('/')
    return live.firstOrNull { it.url.trimEnd('/').let { u -> u == key || (RelayHosts.isOnionHost(key) && u.equals(key, ignoreCase = true)) } }
}

/** [info]: the engine's view of the relay (null: not in the pool); [lastSeen]: epoch seconds it was last seen connected, if ever recorded. [onion]: the relay is a `.onion` address. */
fun relayStatusLine(info: RelayInfo?, lastSeen: Long?, now: Long, onion: Boolean = false): RelayStatusLine {
    // a hidden service the engine does not hold (the switch is off): said as it is, nothing is asked to find out
    if (info == null) return RelayStatusLine(uiText(if (onion) R.string.relay_needs_tor else R.string.relay_not_connected))
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
fun relayListDiff(local: List<String>, published: RelayList?, chosen: Map<String, RelayMode> = emptyMap()): RelayListDiff? {
    if (published == null) return RelayListDiff(0, 0, noneFound = true)
    fun norm(urls: List<String>) = urls.map { it.trim().trimEnd('/') }.toSet()
    // a hidden service is never published: it cannot be missing from the published list
    val mine = norm(local.filterNot { RelayHosts.isOnionHost(it) })
    val theirs = norm(published.read + published.write)
    val read = published.read.map { it.trim().trimEnd('/') }.toSet()
    val write = published.write.map { it.trim().trimEnd('/') }.toSet()
    // a relay in both lists whose chosen mode is not its published marker (no chosen mode: the marker stays) is not published yet either
    val changed = chosen.entries.count { (url, mode) ->
        val u = url.trim().trimEnd('/')
        u in mine && u in theirs && mode != RelayModes.modeOf(u in read, u in write)
    }
    val diff = RelayListDiff(unpublished = (mine - theirs).size + changed, missing = (theirs - mine).size)
    return diff.takeIf { it.unpublished > 0 || it.missing > 0 }
}

/** How a relay reads in a list: `wss://` is on nearly every relay and takes the room, an unencrypted `ws://` stays visible. */
fun relayShownUrl(url: String): String {
    val u = url.trim().trimEnd('/')
    return if (u.startsWith("wss://", ignoreCase = true)) u.substring(6) else u
}
