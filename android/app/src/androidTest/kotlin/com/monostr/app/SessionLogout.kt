package com.monostr.app

import com.monostr.app.session.NostrSession
import com.monostr.app.session.SessionState
import kotlinx.coroutines.flow.first

/**
 * Device tests end with a clean device as logout did before account switching: every account leaves it, the inactive ones
 * first, so removing the active one last starts no other account.
 */
suspend fun NostrSession.logout() {
    val running = (state.value as? SessionState.Active)?.ready?.pubkey
    accounts.first().map { it.pubkey }.filter { it != running }.forEach { removeAccount(it) }
    running?.let { removeAccount(it) }
}
