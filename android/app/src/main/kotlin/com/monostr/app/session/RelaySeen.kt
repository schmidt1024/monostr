package com.monostr.app.session

import com.monostr.nostr.SyncMarks
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState
import kotlinx.coroutines.delay

/** 0.24.3: the account's file of when each relay was last seen connected (`url → epoch seconds`); logout deletes it. */
const val RELAY_SEEN_FILE = "relay-seen.json"

/** A relay's time is written again only after this long: the settings show minutes at best, the file stays quiet. */
private const val SEEN_STEP_SECONDS = 600L

/** Records the connected relays of [states] in [marks] (URL without trailing slash), each at most once per [SEEN_STEP_SECONDS]. */
suspend fun recordRelaysSeen(states: List<RelayInfo>, marks: SyncMarks, now: Long) {
    val known = marks.all()
    for (info in states) {
        if (info.state != RelayState.CONNECTED) continue
        val url = info.url.trimEnd('/')
        if (now - (known[url] ?: 0L) >= SEEN_STEP_SECONDS) marks.put(url, now)
    }
}

/** Looks at the relays once a minute for as long as the session runs (engine scope), see [recordRelaysSeen]. */
suspend fun trackRelaysSeen(states: suspend () -> List<RelayInfo>, marks: SyncMarks, now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    while (true) {
        runCatching { recordRelaysSeen(states(), marks, now()) }
        delay(60_000)
    }
}
