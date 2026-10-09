package com.monostr.app.session

import kotlinx.coroutines.CancellationException

/** Spec 7.3 of Plan 10d: tearing down a half-started session. */
internal object SessionCleanup {
    /** Runs every step, in order, even when an earlier one fails; failures are dropped, cancellation is not. */
    suspend fun closeAll(vararg steps: suspend () -> Unit) {
        for (step in steps) {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the next step must still run: an open DM database outlives the failed login otherwise
            }
        }
    }
}
