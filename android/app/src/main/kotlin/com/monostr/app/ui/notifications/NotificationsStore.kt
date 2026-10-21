package com.monostr.app.ui.notifications

import com.monostr.nostr.StartupGate
import com.monostr.app.R
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.ui.common.MentionNames
import com.monostr.app.ui.common.MentionResolver
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationsRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec perf 3.6: the notifications of this session, one instance in [com.monostr.app.session.Ready] shared by the
 * feed's badge, the notifications screen and the tip check. One live subscription, the last result in memory (a
 * reopen renders it at once), refreshes at most every [REFRESH_EVERY_SECONDS] unless pulled, with `since` = the newest
 * shown − [SINCE_MARGIN_SECONDS].
 *
 * Everything the rows are made of lives in one [Inputs] state: every change (a database reload, a parent note, a
 * landed profile, the mute list) renders the rows again, and no input cancels the work of another. Rows come from
 * the database at once; "is this my note" is the local own-notes query; parent notes the database lacks and actor
 * profiles arrive later from a lookup that runs on [scope] and is not restarted by new events.
 */
class NotificationsStore(
    private val notifications: NotificationsRepository,
    private val profiles: ProfileRepository,
    private val settings: TipSettingsStore,
    /** `engine.backgroundScope` in the app: leaving a screen stops nothing; logout closes it. */
    private val scope: CoroutineScope,
    private val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** The user's pubkey: a like or repost of anybody else's note is no notification; null (tests) keeps everything. */
    private val me: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshEverySeconds: Long = REFRESH_EVERY_SECONDS,
    /** Spec perf2 A3: the relay refresh waits until the home feed's first page is in; the rows and the live REQ do not. */
    private val startupGate: StartupGate = StartupGate.OPEN,
) {
    /** Mention names of the rows' texts and excerpts; scanned on [scope], never on the main thread. */
    private val mentions = MentionResolver(profiles, scope)
    val names: StateFlow<Map<String, String>> get() = mentions.names

    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    /** The badge: visible events newer than the read marker (spec 11-C §3: events, not rows). */
    val unread: StateFlow<Int> = _state.map { it.unread }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, 0)

    private data class Inputs(
        /** The stored list, newest first; null before the first database read. */
        val items: List<NotificationItem>? = null,
        /** Parent notes known so far; an entry is never replaced by a worse one (Found and Withdrawn stay). */
        val about: Map<String, AboutNote> = emptyMap(),
        /** Ids of liked or reposted notes that are the user's own stored notes. */
        val own: Set<String> = emptySet(),
        val profiles: Map<String, Profile> = emptyMap(),
        /** Parent ids the database lacks, waiting for the relay lookup. */
        val pending: Set<String> = emptySet(),
        /** The session's first refresh has finished. */
        val refreshed: Boolean = false,
    )

    private val inputs = MutableStateFlow(Inputs())
    private val started = AtomicBoolean(false)
    /** A pull is waiting for the relays; a second pull meanwhile is ignored. */
    private val pulling = AtomicBoolean(false)
    /** Reload requests; several while one runs make one more (live events come in bursts). */
    private val reloads = Channel<Unit>(Channel.CONFLATED)
    /** Database reads one at a time: the last read wins, a slow one never overwrites a newer one. */
    private val reloadLock = Mutex()
    private val firstLoad = CompletableDeferred<Unit>()
    /** Pubkeys the rows asked profiles for; landed profiles of others are not kept here. */
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val gate = RefreshGate<String>(scope, refreshEverySeconds, now)

    /** Starts the pipeline once per session (the badge or the screen, whichever comes first); true for the call that started it. */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        // subscribed before the first getAll (plan A contract): a profile that lands meanwhile is not missed
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.updates.collect { byKey ->
                val ours = byKey.filterKeys { it in requested }
                if (ours.isNotEmpty()) inputs.update { it.copy(profiles = it.profiles + ours) }
            }
        }
        scope.launch { settings.notificationsReadAt.collect { at -> _state.update { it.copy(readAt = at) } } }
        // the only writer of the rows: inputs change atomically, each snapshot renders the whole list
        scope.launch { combine(inputs, muted) { i, m -> i to m }.collect { (i, m) -> publish(i, m) } }
        scope.launch { lookUpParents() }
        scope.launch { _state.map(::texts).distinctUntilChanged().collect { mentions.track(MentionNames.collectTexts(it)) } }
        scope.launch { for (request in reloads) reload() }
        scope.launch {
            try {
                // incoming() is cold (one REQ per collector): collected here once for the whole session
                notifications.incoming().collect { reloads.trySend(Unit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no live updates this session; opens and refreshes still reload
            }
        }
        reloads.trySend(Unit)
        refresh()
        return true
    }

    /** A screen open: the rows in memory stay on screen, the database is read again behind them and a due refresh starts. */
    fun open() {
        if (!start()) {
            reloads.trySend(Unit)
            refresh()
        }
        if (PerfTrace.enabled) {
            scope.launch {
                state.first { !it.loading }
                PerfTrace.mark("notifications first rows")
                state.first { it.complete }
                PerfTrace.mark("notifications complete")
            }
        }
    }

    /** A background refresh unless one finished in the last [REFRESH_EVERY_SECONDS]; never waited for. */
    fun refresh() {
        gate.launch(REFRESH_KEY) { runRefresh() }
    }

    /** Pull-to-refresh: always asks the relays (a refresh that runs is shared); a second pull while one runs is ignored. */
    fun pull() {
        start()
        // a user pull never waits for the startup gate
        startupGate.open("notifications pull")
        if (!pulling.compareAndSet(false, true)) return
        _state.update { it.copy(refreshing = true) }
        scope.launch {
            try {
                gate.await(REFRESH_KEY, force = true) { runRefresh() }
            } finally {
                _state.update { it.copy(refreshing = false) }
                pulling.set(false)
            }
        }
    }

    /** The tip check stored receipts: a running store reads the database again; a store nobody started stays off. */
    fun reloadLocal() {
        if (started.get()) reloads.trySend(Unit)
    }

    fun markRead() {
        scope.launch { runCatching { settings.setNotificationsReadAt(now()) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun runRefresh() {
        firstLoad.await()
        startupGate.await()
        _state.update { it.copy(syncing = true) }
        try {
            // also fetches the own payment info (R4b): tip rows can only appear in the reload below
            notifications.refresh(since = since())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best effort: what is stored stays on screen
        } finally {
            _state.update { it.copy(syncing = false) }
        }
        reload()
        inputs.update { it.copy(refreshed = true) }
    }

    /**
     * The newest shown notification − [SINCE_MARGIN_SECONDS] (late relays, clock skew); a `created_at` further in the
     * future than the margin is left out, so a forged date cannot silence later refreshes; null (everything) while no
     * other notification is stored.
     */
    private fun since(): Long? {
        val limit = now() + SINCE_MARGIN_SECONDS
        val newest = inputs.value.items?.filter { it.createdAt <= limit }?.maxOfOrNull { it.createdAt } ?: return null
        return (minOf(newest, now()) - SINCE_MARGIN_SECONDS).coerceAtLeast(0)
    }

    /** One database read of the list, the stored parents, the own ids and the stored profiles, merged as one update. */
    private suspend fun reload() {
        reloadLock.withLock {
            val items = try {
                notifications.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = uiText(R.string.notifications_error_load)) }
                firstLoad.complete(Unit)
                return@withLock
            }
            val current = inputs.value
            val ids = items.mapNotNullTo(HashSet()) { it.aboutId }
            // a remembered miss is read from the database again (a thread may have stored it meanwhile) but never
            // goes back to the relay lookup: R7, only an id never seen is unknown, so `complete` does not flip
            val toRead = ids.filterTo(HashSet()) { current.about[it].let { a -> a == null || a == AboutNote.Missing } }
            val local = if (toRead.isEmpty()) emptyMap() else notifications.localNotes(toRead)
            val liked = items.filter { it.kind in NotificationGroups.GROUPED }.mapNotNullTo(HashSet()) { it.aboutId } - current.own
            val own = if (liked.isEmpty()) emptySet() else notifications.own(liked)
            // unknown or still empty profiles: getAll answers from the database at once and queues the rest
            val pubkeys = items.mapNotNull { it.from }.distinct().filter { pk -> current.profiles[pk].let { it == null || it == Profile.empty(pk) } }
            requested += pubkeys
            val stored = if (pubkeys.isEmpty()) emptyMap() else try {
                profiles.getAll(pubkeys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            val found = local.filterValues { it != AboutNote.Missing }
            inputs.update { i ->
                fun unknown(id: String) = i.about[id].let { it == null || it == AboutNote.Missing }
                i.copy(
                    items = items,
                    about = i.about + found.filterKeys(::unknown),
                    own = i.own + own,
                    // a profile that landed while getAll ran is newer than what getAll read
                    profiles = i.profiles + stored.filterKeys { pk -> i.profiles[pk].let { it == null || it == Profile.empty(pk) } },
                    // checked against the newest state: a lookup that answered during this read is not asked again
                    pending = i.pending + ids.filter { it !in found && i.about[it] == null },
                )
            }
            firstLoad.complete(Unit)
        }
    }

    /**
     * The relay lookup of parent notes the database lacks: one at a time, for everything pending when it starts; a new
     * event never cancels it, ids added meanwhile go into the next round. Misses are remembered by the repository.
     */
    private suspend fun lookUpParents() {
        inputs.map { it.pending }.distinctUntilChanged().collect { ids ->
            if (ids.isEmpty()) return@collect
            val got = try {
                notifications.notes(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ids.associateWith { AboutNote.Missing }
            }
            inputs.update { i ->
                i.copy(
                    about = i.about + got.filterKeys { k -> i.about[k].let { it == null || it == AboutNote.Missing } },
                    pending = i.pending - ids,
                )
            }
        }
    }

    private fun publish(i: Inputs, mutedNow: Set<String>) {
        val items = i.items ?: return
        val visible = NotificationFilter.visible(items, mutedNow)
        // a like or repost says "your note": only for a note known to be the user's own (a forged tag could otherwise
        // put any excerpt under that sentence — security review); own notes are stored, so this is local
        fun verified(id: String?) = me == null || (id != null && id in i.own) || (i.about[id] as? AboutNote.Found)?.note?.author == me
        val rows = NotificationGroups.build(visible).filterNot { it is NotificationRow.Group && !verified(it.aboutId) }
        val counted = visible.filterNot { it.kind in NotificationGroups.GROUPED && !verified(it.aboutId) }
        val ui = rows.map { row ->
            val people = row.pubkeys.associateWith { pk -> i.profiles[pk] ?: Profile.empty(pk) }
            NotificationRowUi(row, people.mapValues { it.value.shownName }, people.mapValues { it.value.picture }, row.aboutId?.let { i.about[it] })
        }
        _state.update { it.copy(rows = ui, items = counted, loading = false, complete = i.refreshed && i.pending.isEmpty()) }
    }

    /** What mention names are needed for: the events' texts and the parent excerpts. */
    private fun texts(s: NotificationsUiState): List<String> =
        s.rows.flatMap { r -> listOfNotNull((r.row as? NotificationRow.Single)?.item?.text, (r.about as? AboutNote.Found)?.note?.displayContent) }

    companion object {
        /** Spec perf 3.6: a background refresh at most this often; a pull always asks. */
        const val REFRESH_EVERY_SECONDS = 120L
        /** How far before the newest shown notification a refresh asks (late relays, clock skew). */
        const val SINCE_MARGIN_SECONDS = 600L
        private const val REFRESH_KEY = "notifications"
    }
}
