package com.monostr.nostr.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.RelayUrl
import java.time.Duration

/** After the first relay connected, how long the others still get before a send goes out: relays that connect together all get it. */
internal const val FIRST_CONNECTED_GRACE_MS = 300L

private const val POLL_MS = 50L

/**
 * Waits for relays whose connections were started: until all of [urls] are connected, or one is and [graceMs] have passed since,
 * or [timeout]. One relay is enough to deliver; a host that takes no tor circuit never answers, and behind SOCKS rust-nostr's own
 * connect timeout does not hold (Pixel 2026-11-07), so this is the only bound. Measures wall-clock time only on a dispatcher that
 * does not skip delays: callers run it on their ffi dispatcher.
 */
internal suspend fun awaitConnected(
    urls: List<RelayUrl>,
    timeout: Duration,
    graceMs: Long = FIRST_CONNECTED_GRACE_MS,
    isConnected: suspend (RelayUrl) -> Boolean,
) {
    if (urls.isEmpty()) return
    withTimeoutOrNull(timeout.toMillis()) {
        var firstAt: Long? = null
        while (true) {
            val connected = urls.count { isConnected(it) }
            if (connected == urls.size) return@withTimeoutOrNull
            val now = System.nanoTime()
            if (connected > 0 && firstAt == null) firstAt = now
            if (firstAt != null && (now - firstAt) / 1_000_000 >= graceMs) return@withTimeoutOrNull
            delay(POLL_MS)
        }
    }
}
