package com.monostr.app.ui.common

import kotlinx.coroutines.CancellationException

/** Runs a best-effort call: null on failure, cancellation still propagates (unlike `runCatching`). */
suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
