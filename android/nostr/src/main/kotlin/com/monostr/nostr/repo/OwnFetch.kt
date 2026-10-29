package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Filter
import java.time.Duration

/** [found]: the relays returned at least one event; [completed]: an empty result proves there is none anywhere (see [fetchOwn]). */
internal class OwnFetch(val found: Boolean, val completed: Boolean)

/** The notification loop that records CLOSED gets this long to land a frame that came in just before the fetch returned. */
private const val CLOSED_SETTLE_MS = 100L

/**
 * One relay fetch of an own replaceable event (stored by the engine). Completed, i.e. an empty result
 * means "none anywhere": the fetch returned before its timeout without an error, EVERY configured
 * normal relay was connected before and after it (rust-nostr ends the fetch on the EOSE of the
 * relays it reached, so one connected relay proves nothing about the others), and none of them
 * sent a CLOSED meanwhile (a refusal is no answer).
 */
internal suspend fun NostrEngine.fetchOwn(filter: Filter, timeout: Duration): OwnFetch {
    val configured = relayUrls().toSet()
    val connectedBefore = connectedNormalRelayUrls()
    val mark = closedMark()
    val started = System.nanoTime()
    val events = try {
        fetch(filter, timeout)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return OwnFetch(found = false, completed = false)
    }
    val inTime = System.nanoTime() - started < timeout.toNanos()
    val everyRelay = configured.isNotEmpty() && connectedBefore.containsAll(configured)
    // only where it decides anything: an empty, otherwise complete fetch
    if (events.isEmpty() && inTime && everyRelay && !closedSince(mark, configured)) {
        withContext(Dispatchers.Default) { delay(CLOSED_SETTLE_MS) }
    }
    val completed = inTime && everyRelay && connectedNormalRelayUrls().containsAll(configured) && !closedSince(mark, configured)
    return OwnFetch(events.isNotEmpty(), completed)
}
