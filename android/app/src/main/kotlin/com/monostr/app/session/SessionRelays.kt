package com.monostr.app.session

import com.monostr.app.data.RelayModeStore
import com.monostr.app.data.RelayStore
import com.monostr.app.data.isValidRelayUrl
import com.monostr.nostr.RelayList
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.model.RelayModes
import com.monostr.tips.RelayHosts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What adopting the published relay list did; [MARKERS_IGNORED]: the relays were taken, but the list had no relay to read from or none to write to. */
enum class AdoptResult { NONE, ADOPTED, MARKERS_IGNORED }

/** The running session as far as [SessionRelays] needs it. */
interface RunningRelays {
    suspend fun relayUrls(): List<String>
    suspend fun removeRelays(urls: List<String>)
    suspend fun addRelays(urls: List<String>)
    suspend fun applyRelayModes(modes: Map<String, RelayMode>)
    suspend fun connect()
    suspend fun restartDms()
    suspend fun ownRelayList(fresh: Boolean): RelayList?
}

/**
 * The main relay list and its read and write modes, stored and applied to the running session (spec 2026-11-05).
 * Every change goes through [RelayModes.valid], with URLs normalised as the stores keep them: at least one relay reads
 * and one writes. [lock] is the session's own, so a change never meets a login or logout. [running] is null without a session.
 */
class SessionRelays(
    private val relays: RelayStore,
    private val modes: RelayModeStore,
    private val lock: Mutex,
    private val running: () -> RunningRelays?,
) {
    /** A new relay list: a removed relay loses its mode, a new one has none. False, and nothing changes, when it would leave no reader or writer. */
    suspend fun apply(list: List<String>): Boolean = lock.withLock {
        val wanted = clean(list)
        applyLocked(wanted, modes.modes.first().filterKeys { it in wanted })
    }

    /** False, and nothing changes, for a relay outside the list or a mode that would leave no reader or writer. No connection is dropped and DMs do not restart. */
    suspend fun setRelayMode(url: String, mode: RelayMode): Boolean = lock.withLock {
        val relay = norm(url)
        val list = relays.relays.first()
        val next = modes.modes.first() + (relay to mode)
        if (relay !in list || !RelayModes.valid(list, next)) return@withLock false
        modes.setModes(next)
        running()?.applyRelayModes(next)
        true
    }

    /**
     * The watcher's relays must be able to deliver tip receipts: a new one joins as read and write, one the user only
     * writes to gets read switched on and keeps writing. The watcher names them, not the user, so only public wss ones count.
     */
    suspend fun ensure(extra: List<String>) = lock.withLock {
        val named = extra.map(::norm).filter { isValidRelayUrl(it) && RelayHosts.isPublicWss(it) }
        val current = relays.relays.first()
        val stored = modes.modes.first()
        val wanted = (current + named).distinct()
        val next = stored.mapValues { (url, mode) -> if (mode == RelayMode.WRITE && url in named) RelayMode.BOTH else mode }
        if (wanted != current || next != stored) applyLocked(wanted, next)
    }

    /** Replaces the list and the modes with the user's NIP-65 list, as the relays have it now (backlog 36). */
    suspend fun adopt(): AdoptResult {
        val run = running() ?: return AdoptResult.NONE
        val list = run.ownRelayList(fresh = true) ?: return AdoptResult.NONE
        val adoption = RelayModes.adopt(RelayList(list.read.filter { isValidRelayUrl(it) }, list.write.filter { isValidRelayUrl(it) }))
        if (adoption.relays.isEmpty()) return AdoptResult.NONE
        // ignored markers are stored as both, as the message says: otherwise a publish would keep the broken ones ([RelayModes.forPublish])
        val chosen = if (adoption.markersIgnored) adoption.relays.associateWith { RelayMode.BOTH } else adoption.modes
        if (!lock.withLock { applyLocked(adoption.relays, chosen) }) return AdoptResult.NONE
        return if (adoption.markersIgnored) AdoptResult.MARKERS_IGNORED else AdoptResult.ADOPTED
    }

    /**
     * What to publish: a chosen mode wins, else the marker of the stored published list, else both. Kept markers beside a
     * chosen mode can leave no reader or writer: such a list is never published, a relay with a kept marker gives way first.
     */
    suspend fun entriesForPublish(): List<RelayEntry> {
        val chosen = modes.modes.first()
        val entries = RelayModes.forPublish(relays.relays.first(), chosen, running()?.ownRelayList(fresh = false))
        val kept = entries.map { it.url }.sortedBy { it in chosen }
        val repaired = RelayModes.repair(kept, entries.associate { it.url to it.mode })
        return entries.map { it.copy(mode = repaired[it.url] ?: it.mode) }
    }

    /**
     * [list] with every relay reading and writing, as one step: a relay that leaves is gone before any relay starts
     * reading again, so a write-only relay on its way out never gets a live subscription. Failures reach the caller.
     */
    suspend fun resetTo(list: List<String>): Boolean = lock.withLock {
        applyLocked(clean(list), emptyMap())
    }

    /**
     * The stored modes for an engine that is about to connect. [applyLocked] writes list and modes in one edit, so they
     * agree; a store that does not anyway is repaired as restrictively as possible ([RelayModes.repair]) and written back,
     * so store, settings row and engine see the same. Takes no lock: the session calls it while holding it.
     */
    suspend fun startupModes(): Map<String, RelayMode> {
        val stored = modes.modes.first()
        val list = relays.relays.first()
        if (RelayModes.valid(list, stored) && stored.keys.all { it in list }) return stored
        val repaired = RelayModes.repair(list, stored)
        modes.setListAndModes(list, repaired)
        return repaired
    }

    /**
     * List and modes are stored in one edit, so a crash cannot leave one without the other. In the engine a removed relay
     * goes first (it needs no change of its subscriptions), a new one comes last, so it gets what its mode allows. Only a
     * changed list reconnects and restarts DMs: an inbox relay that left the normal set left the pool with it (the DM run
     * held it as a normal relay, not a temporary one), one that joined was promoted, so the inbox list is taken again.
     */
    private suspend fun applyLocked(list: List<String>, chosen: Map<String, RelayMode>): Boolean {
        if (!RelayModes.valid(list, chosen)) return false
        modes.setListAndModes(list, chosen)
        val run = running() ?: return true
        val current = run.relayUrls()
        val gone = current - list.toSet()
        val added = list - current.toSet()
        run.removeRelays(gone)
        run.applyRelayModes(chosen)
        run.addRelays(added)
        // a relay the engine would not take (a hidden service in Direct) joined nothing: no reconnect, no DM restart for it
        val joined = run.relayUrls().toSet() != current.toSet() - gone.toSet()
        if (gone.isNotEmpty() || joined) {
            run.connect()
            run.restartDms()
        }
        return true
    }

    private fun clean(list: List<String>) = list.map(::norm).filter { isValidRelayUrl(it) }.distinct()

    private fun norm(url: String) = url.trim().trimEnd('/')
}
