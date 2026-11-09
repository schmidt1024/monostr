package com.monostr.app.session

import kotlinx.coroutines.sync.Mutex

/** The session's [mutex] across the steps of a Tor switch. A [finish] after a [take] that never came changes nothing, as the lock may be someone else's by then. */
internal class SwitchLock(private val mutex: Mutex) {
    suspend fun take() = mutex.lock(this)

    suspend fun finish(step: suspend () -> Unit) {
        if (!mutex.holdsLock(this)) return
        try {
            step()
        } finally {
            mutex.unlock(this)
        }
    }
}
