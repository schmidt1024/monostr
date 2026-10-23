package com.monostr.app.session

/** What a change of the default network asks of the relay connections (spec 2026-10-23 §7). */
enum class NetworkChange {
    /** A network is back after the last one was lost: reconnecting the lost relays is enough. */
    RETURNED,
    /** Another network took over without a loss in between (Wi-Fi to mobile): sockets may be dead but still "connected". */
    SWITCHED,
}

/**
 * Pure state machine behind the default-network callback (spec 2026-10-23 §7), one per activity: it remembers the last
 * default network across [onRegistered] (each foreground start). The first callback after registering counts only
 * when it reports another network than the last one seen (a switch while in the background; the very first one ever
 * never counts). After a loss the same network back is [NetworkChange.RETURNED], another one [NetworkChange.SWITCHED].
 * At most one change per [debounceMs]; a change within that window gives null but still updates the state. Networks
 * are `Network.networkHandle`s.
 */
class NetworkTransitions(private val nowMs: () -> Long, private val debounceMs: Long = 5_000) {
    /** The last default network seen; kept through a loss, so the network coming back can be compared. */
    private var last: Long? = null
    private var fresh = true
    private var lost = false
    private var lastChangeMs: Long? = null

    /** The callback was registered (again): its first [onAvailable] reports the network as it is now. */
    fun onRegistered() {
        fresh = true
        // a loss in the background is answered by the start's own reconnect; only the network now counts
        lost = false
    }

    fun onAvailable(network: Long): NetworkChange? {
        val before = last
        val change = when {
            before == null || network == before -> if (lost && !fresh) NetworkChange.RETURNED else null
            else -> NetworkChange.SWITCHED
        }
        fresh = false
        lost = false
        last = network
        if (change == null) return null
        val now = nowMs()
        val previous = lastChangeMs
        if (previous != null && now - previous < debounceMs) return null
        lastChangeMs = now
        return change
    }

    fun onLost(network: Long) {
        // a loss of a network that is no longer the default one says nothing about the current one
        if (network == last) lost = true
    }
}
