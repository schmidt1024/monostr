package com.monostr.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Spec perf2 R9: collects on a real-time dispatcher until a value matches [predicate] and returns every value
 * seen, the matching one last. Thread tests wait for a state, never for a number of emissions.
 */
suspend fun <T> Flow<T>.untilFirst(timeoutMs: Long = 10_000, predicate: (T) -> Boolean): List<T> = withContext(Dispatchers.Default) {
    val seen = ArrayList<T>()
    withTimeout(timeoutMs) { this@untilFirst.first { seen += it; predicate(it) } }
    seen
}
