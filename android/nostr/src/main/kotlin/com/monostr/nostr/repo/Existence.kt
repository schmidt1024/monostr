package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import rust.nostr.sdk.Filter
import java.time.Duration

/**
 * Whether an event matching [filter] exists: stored, or found by one fetch from the session's relays that stops at the
 * first event (spec wave 2 B4, `AnyEvent`: existence, not freshness). A relay error counts as "not found"; then only
 * the database decides.
 */
suspend fun storedOrFetched(engine: NostrEngine, filter: Filter, timeout: Duration): Boolean {
    if (engine.query(filter).isNotEmpty()) return true
    quietly { engine.fetch(filter, timeout, Exit.AnyEvent) }
    return engine.query(filter).isNotEmpty()
}
