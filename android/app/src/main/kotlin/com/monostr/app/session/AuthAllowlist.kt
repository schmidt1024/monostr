package com.monostr.app.session

import com.monostr.nostr.AuthPolicy
import com.monostr.nostr.RelayKeys
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spec 2026-11-19: the session's part of the NIP-42 AUTH allowlist. The engine knows its own list in use; the own DM
 * inbox and the search relays the user set (never a default one) are read from settings at every challenge, in
 * parallel (`first()`, never a seeded stateIn), so an edit counts at the next challenge. A source that does not answer
 * within [AUTH_LOOKUP_TIMEOUT_MS] counts as empty; one that throws makes [AuthPolicy.allows] refuse.
 */
internal object AuthAllowlist {
    const val AUTH_LOOKUP_TIMEOUT_MS = 2_000L

    fun wire(policy: AuthPolicy, dmRelays: Flow<List<String>>, searchRelays: Flow<List<String>>, borrowed: (String) -> Boolean) {
        policy.borrowed = borrowed
        // both sources at once: one budget of AUTH_LOOKUP_TIMEOUT_MS per challenge, a hanging one counts as empty alone
        policy.ownExtra = {
            coroutineScope {
                val dm = async { keys(dmRelays) }
                val search = async { keys(searchRelays) }
                dm.await() + search.await()
            }
        }
    }

    private suspend fun keys(f: Flow<List<String>>): Set<String> =
        withTimeoutOrNull(AUTH_LOOKUP_TIMEOUT_MS) { f.first() }.orEmpty().mapNotNullTo(HashSet()) { RelayKeys.of(it) }
}
