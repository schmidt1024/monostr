package com.monostr.app.session

import com.monostr.app.net.TorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A session's relay engine runs only while Tor is connected: rust-nostr's own reconnects cannot ask, and their failures would rest
 * the relays for ten minutes. Latest wins, so a [hold] never waits for an [open] still talking to rust. Ends with the caller's scope.
 */
internal suspend fun followTor(state: Flow<TorState>, hold: () -> Unit, open: suspend () -> Unit) {
    state.map { it == TorState.Connected }.distinctUntilChanged().collectLatest { connected -> if (connected) open() else hold() }
}
