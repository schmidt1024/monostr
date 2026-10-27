package com.monostr.app.ui.discover

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Spec 2026-10-27 §4: the home feed shows the discover block to accounts that follow fewer than [THRESHOLD] people. */
object DiscoverVisibility {
    const val THRESHOLD = 10
    /** The [com.monostr.app.data.HintStore] key of the dismissed block. */
    const val HINT = "discover"

    /** Null while the own contact list is unknown (nothing is decided then); else whether the block shows. */
    fun decide(followed: Set<String>?, dismissed: Boolean): Boolean? =
        if (followed == null) null else !dismissed && followed.size < THRESHOLD

    /**
     * The own follows for the decision: [read] (a fresh look at the contact list, local first) is asked up to
     * [attempts] times, [pauseMs] apart, while it does not know the list (new device, the feed's fetch still
     * running); only then a list nobody delivered counts as none (a brand-new account).
     */
    suspend fun awaitFollowed(attempts: Int = 4, pauseMs: Long = 5_000, read: suspend () -> Set<String>?): Set<String> {
        repeat(attempts) { i ->
            read()?.let { return it }
            if (i < attempts - 1) delay(pauseMs)
        }
        return emptySet()
    }
}

/**
 * Spec 2026-10-27 §4: decided once per session (app process): the first known answer stays, so the block never
 * vanishes under the finger when follows pass the threshold; only [dismiss] takes it away.
 */
class DiscoverDecision {
    private val _shown = MutableStateFlow<Boolean?>(null)
    val shown: StateFlow<Boolean?> = _shown.asStateFlow()

    fun offer(value: Boolean?) {
        if (value == null) return
        _shown.update { it ?: value }
    }

    fun dismiss() {
        _shown.value = false
    }
}
