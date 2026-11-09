package com.monostr.app.net

import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.PendingTips
import kotlinx.coroutines.flow.first
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import dagger.Lazy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a switch asks of the running session; the implementation is `NostrSession`. */
interface SwitchedSession {
    /** New relay connections stop at once, and nothing else of the session runs until [rebuild], which always follows. */
    suspend fun stop()
    suspend fun close()
    suspend fun rebuild()
}

/** Tor is usable: only then the ports are ours. Builds nothing of Tor before someone collects, which happens in Tor mode only. */
fun torConnected(tor: Lazy<TorController>): Flow<Boolean> =
    flow { emitAll(tor.get().state) }.map { it == TorState.Connected }.distinctUntilChanged()

/**
 * The deliberate change between Direct and Tor (spec 2026-11-06 5.2, 5.4). The marker comes first: when it cannot be written, nothing
 * changed and the caller gets the exception; when the process dies later, the next start reads what the user asked for. One switch
 * runs at a time, requests in their order, so the last one decides; a started one finishes even when its caller is gone.
 *
 * Also the one owner of Tor for the visible app, with [onForeground] and [onBackground].
 */
class TorSwitch(
    private val store: TorSwitchStore,
    private val gate: NetworkGate,
    private val dropConnections: () -> Unit,
    private val session: SwitchedSession,
    private val pendingTips: PendingTipStore,
    /** Touch Tor only when called, and they are called with the switch on only: with it off nothing of Tor may load. */
    private val acquire: (String) -> Unit,
    private val release: (String) -> Unit,
    /** For the deliberate off: no grace. */
    private val releaseNow: (String) -> Unit,
    /** The background checks follow the switch (spec 5.7). */
    private val schedule: (torOn: Boolean) -> Unit,
    /** This process ever gave a relay a direct route: its task may dial again and cannot be cancelled, so [turnOn] ends the process (F1). */
    private val directRelayRouteUsed: () -> Boolean,
    /** Does not return in the app. The marker is written by then, so the next start is on Tor from its first packet. */
    private val endProcess: () -> Unit,
) {
    private val turn = Mutex()
    /** The latest request: one overtaken while waiting for its turn is dropped, so a toggle that ends on Tor builds no direct session on the way. */
    @Volatile private var wanted: Boolean? = null
    private val lifecycle = Any()
    private var foreground = false
    /** Whether [OWNER] holds Tor, whatever the switch says by now: a turnOff that failed half way must not keep it. */
    private var owning = false

    /**
     * Nothing direct from the flip on: the engine stopped dialing before it, the running HTTP calls and the old session go right after.
     * After a direct relay route the old session goes with the process; a process that had none switches in place. Asked last, so that
     * a route that came up until here counts, and whatever failed by then: the end is the one thing that surely stops a relay task.
     */
    suspend fun turnOn() {
        wanted = true
        turn.withLock {
            if (wanted != true || (store.enabled.value && gate.mode.value is NetworkMode.Tor)) return@withLock
            // may throw, it opens sockets: before anything has changed
            val tor = gate.torMode()
            withContext(NonCancellable) {
                store.set(true)
                session.stop()
                var ended = false
                try {
                    gate.set(tor)
                    follow(torOn = true)
                    try {
                        dropConnections()
                    } finally {
                        if (directRelayRouteUsed()) {
                            ended = true
                            endProcess()
                        }
                    }
                    if (!ended) {
                        session.close()
                        synchronized(lifecycle) { if (foreground) own() }
                    }
                } finally {
                    if (!ended) session.rebuild()
                }
            }
        }
    }

    /**
     * For the question before [turnOff]: how many tips it would really lose. It drops more: one the watcher already has still gets its
     * receipt. An expired one is gone anyway; a superseded undelivered one still counts, it may go out for a tip paid after all.
     */
    suspend fun pendingUnderTor(now: Long = System.currentTimeMillis() / 1000): Int =
        PendingTips.active(pendingTips.pending.first(), now).count { it.underTor && !it.isDelivered }

    /**
     * Everything that ran over Tor ends before the first direct connection is allowed, the tips sent under Tor last of all, so that
     * none is stored when the gate opens. What still arrives later, or stays after a crash, is held back by `SendGate.mayResend`.
     * Returns how many tips left; 0 for a request that was overtaken.
     */
    suspend fun turnOff(): Int {
        wanted = false
        return turn.withLock {
            if (wanted != false || (!store.enabled.value && gate.mode.value == NetworkMode.Direct)) return@withLock 0
            withContext(NonCancellable) {
                store.set(false)
                session.stop()
                try {
                    session.close()
                    dropConnections()
                    synchronized(lifecycle) {
                        owning = false
                        releaseNow(OWNER)
                    }
                    val dropped = pendingTips.dropUnderTor()
                    // a step that threw above leaves the gate on Tor: nothing direct unless everything of Tor ended
                    gate.set(NetworkMode.Direct)
                    follow(torOn = false)
                    dropped
                } finally {
                    session.rebuild()
                }
            }
        }
    }

    /**
     * Someone holds our ports, so no attempt on them can work: session, HTTP and Tor move to a new pair, in the order of a switch.
     * The gate stays on Tor throughout. Nothing happens unless Tor is still what the user wants.
     */
    suspend fun newPorts() {
        turn.withLock {
            if (wanted == false || gate.mode.value !is NetworkMode.Tor) return@withLock
            withContext(NonCancellable) {
                session.stop()
                try {
                    // may throw, it opens sockets: Tor and the gate are then as they were
                    gate.newTorMode()
                    synchronized(lifecycle) {
                        owning = false
                        releaseNow(OWNER)
                    }
                    dropConnections()
                    session.close()
                    synchronized(lifecycle) { if (foreground) own() }
                } finally {
                    session.rebuild()
                }
            }
        }
    }

    /** The switch counts for more than the schedule, which the next start of the app sets right. */
    private fun follow(torOn: Boolean) {
        runCatching { schedule(torOn) }
    }

    fun onForeground() = synchronized(lifecycle) {
        foreground = true
        if (store.enabled.value) own()
    }

    fun onBackground() = synchronized(lifecycle) {
        foreground = false
        if (owning) {
            owning = false
            release(OWNER)
        }
    }

    private fun own() {
        owning = true
        acquire(OWNER)
    }

    private companion object {
        const val OWNER = "foreground"
    }
}
