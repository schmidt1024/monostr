package com.monostr.app.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True while a note publish, media upload, tip send or DM send of the active account runs: an account switch is
 * refused then, so nothing half-sent is cut off. Counts nested and parallel blocks.
 */
@Singleton
class AccountBusy @Inject constructor() {
    private var running = 0
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Runs [block] as busy; released in `finally`, so a cancel or an exception frees it too. */
    suspend fun <T> track(block: suspend () -> T): T {
        change(+1)
        try {
            return block()
        } finally {
            change(-1)
        }
    }

    private fun change(by: Int) = synchronized(this) {
        running += by
        _busy.value = running > 0
    }
}
