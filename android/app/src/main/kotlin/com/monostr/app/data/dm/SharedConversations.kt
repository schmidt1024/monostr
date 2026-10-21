package com.monostr.app.data.dm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Spec perf2 D3: the conversations query runs once per change, shared by the Messages list and every badge
 * collector, on a store-owned [scope] (cancelled by the store's close). A collector never sees a list older
 * than the writes made before it started (each result carries the signal state it was computed for), and a
 * failing query reaches the collectors as an exception, as an unshared flow would, instead of ending the
 * shared job on the store scope.
 */
class SharedConversations(scope: CoroutineScope, private val signals: DmSignals, query: suspend () -> List<Conversation>) {
    private class Computed(val stamp: DmSignals.Stamp, val result: Result<List<Conversation>>)

    private val state: StateFlow<Computed?> = signals.conversationStamp
        .map { stamp ->
            // the stamp is read before the query, so the result holds at least every change the stamp counts
            val result = try {
                Result.success(query())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            Computed(stamp, result)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    val conversations: Flow<List<Conversation>> = flow {
        val since = signals.currentStamp()
        emitAll(state.filterNotNull().filter { it.stamp.covers(since) }.map { it.result.getOrThrow() })
    }.distinctUntilChanged()
}
