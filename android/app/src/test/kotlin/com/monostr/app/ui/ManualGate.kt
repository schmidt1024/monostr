package com.monostr.app.ui

import com.monostr.nostr.StartupGate
import kotlinx.coroutines.CompletableDeferred

/**
 * A [StartupGate] without timer (spec perf2 §6): the test opens it; [reasons] holds every [open] call.
 * The app's copy of the nostr test helper of the same name: test sources do not cross the module boundary (R14).
 */
class ManualGate : StartupGate {
    private val opened = CompletableDeferred<Unit>()
    val reasons = java.util.concurrent.CopyOnWriteArrayList<String>()
    override val isOpen: Boolean get() = opened.isCompleted
    override suspend fun await() = opened.await()
    override fun open(reason: String) {
        reasons += reason
        opened.complete(Unit)
    }
}
