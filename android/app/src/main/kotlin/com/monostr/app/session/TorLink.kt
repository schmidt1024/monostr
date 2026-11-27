package com.monostr.app.session

import com.monostr.app.net.TorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A session's relay engine runs only while Tor is connected: rust-nostr's own reconnects cannot ask, and their failures would rest
 * the relays for ten minutes. Latest wins, so a [hold] never waits for an [open] still talking to rust. Ends with the caller's scope.
 *
 * [freshRoute] (spec «Tor isolation per account»): awaited once Tor is connected, before the first [open] only; Tor dropping
 * during it asks for it again on the next connect.
 */
internal suspend fun followTor(state: Flow<TorState>, hold: () -> Unit, freshRoute: (suspend () -> Unit)? = null, open: suspend () -> Unit) {
    var fresh = freshRoute
    state.map { it == TorState.Connected }.distinctUntilChanged().collectLatest { connected ->
        if (connected) {
            fresh?.let { it(); fresh = null }
            open()
        } else {
            hold()
        }
    }
}

/**
 * Spec «Tor isolation per account»: tor may still hold circuits that carried the streams of the last account whose session went
 * through it. rust-nostr's proxy has no SOCKS auth, so the isolation is in time: a session of another account goes out only
 * after a NEWNYM. Process-wide, as tor's circuits are.
 */
internal class TorRouteHistory {
    /** The account tor's circuits belong to: the last one whose session opened via Tor. */
    private var owner: String? = null

    /**
     * A session of [pubkey] starts (null: an account not yet known); true when it goes via Tor and tor's circuits belong to another
     * account. Direct: nothing to isolate.
     */
    @Synchronized
    fun needsFreshRoute(pubkey: String?, viaTor: Boolean): Boolean = viaTor && owner != null && owner != pubkey

    /** [pubkey]'s session opens via Tor, after its fresh route if it needed one: from here on tor's circuits carry its streams. */
    @Synchronized
    fun opened(pubkey: String) { owner = pubkey }
}
