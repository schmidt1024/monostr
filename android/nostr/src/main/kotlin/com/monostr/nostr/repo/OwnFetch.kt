package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Filter
import java.time.Duration

/**
 * A write-only relay did not answer a read-before-write (spec 2026-11-05 goal 5): it may hold a newer version, so what was
 * read is no base for a write. Not a network failure of the write: the caller refuses it as it refuses any failed read.
 */
class OwnDataNotAnswered : Exception("a write-only relay did not answer")

/**
 * [found]: the relays returned at least one event; [completed]: an empty result proves there is none anywhere (see
 * [fetchOwn]); [writeOnlyAnswered]: the write-only relays answered, or there are none. A found event is a base for a write only with it.
 */
internal class OwnFetch(val found: Boolean, val completed: Boolean, val writeOnlyAnswered: Boolean)

/** The notification loop that records CLOSED gets this long to land a frame that came in just before the fetch returned. */
private const val CLOSED_SETTLE_MS = 100L

/**
 * One relay fetch of an own replaceable event (stored by the engine), write-only relays included and awaited: the
 * newest version may live only there and must be found before a write. Completed, i.e. an empty result
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
    val asked = askOwnEvents(filter, timeout)
    if (asked.failure != null) return OwnFetch(found = false, completed = false, writeOnlyAnswered = asked.writeOnlyAnswered)
    val events = asked.events
    val inTime = System.nanoTime() - started < timeout.toNanos()
    val everyRelay = configured.isNotEmpty() && connectedBefore.containsAll(configured)
    // only where it decides anything: an empty, otherwise complete fetch
    if (events.isEmpty() && inTime && everyRelay && !closedSince(mark, configured)) {
        withContext(Dispatchers.Default) { delay(CLOSED_SETTLE_MS) }
    }
    val completed = inTime && everyRelay && asked.writeOnlyAnswered && connectedNormalRelayUrls().containsAll(configured) && !closedSince(mark, configured)
    return OwnFetch(events.isNotEmpty(), completed, asked.writeOnlyAnswered)
}
