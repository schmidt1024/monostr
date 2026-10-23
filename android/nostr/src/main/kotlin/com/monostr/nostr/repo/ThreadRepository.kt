package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.model.Nip10
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import com.monostr.tips.event.Event as JsonEvent

/**
 * A thread: the root note and every reply that references it, oldest first. [gone]: the focused note has been withdrawn by its author (NIP-09).
 * [repliesFetched]: the relays were asked for the replies (`#e` fetch) before this view was read (spec perf2 A1, mark `thread relay replies shown`).
 */
data class ThreadView(val root: Note?, val focused: Note, val replies: List<Note>, val gone: Boolean = false, val ancestors: List<Slot> = emptyList(), val repliesFetched: Boolean = false)

interface ThreadRepository {
    /** Loads the note, resolves its root, fetches replies and keeps listening for new ones. Empty flow when the note cannot be found. */
    fun observe(noteId: String): Flow<ThreadView>

    /** One note from the local database, fetched once when missing; null when unknown. */
    suspend fun note(id: String): Note?
}

class NostrThreadRepository(
    private val engine: NostrEngine,
    private val conversations: ConversationRepository = NostrConversationRepository(engine),
    /** Spec 2026-10-23 §5.3: replies from the root author's read relays; without one the thread asks only the own relays. */
    private val locator: EventLocator? = null,
    /** Timeout of the thread's relay fetches (tests shorten it). */
    private val fetchTimeout: Duration = Duration.ofSeconds(8),
    /** Spec perf2 C4: how long a live change waits for more before the thread is read again. */
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Spec perf2 C3/C7: jobs R and D per root on the engine's background scope; leaving does not stop them, a reopen
     * joins or skips them. The keys `replies:<root>:<focused>`, `deletions:<root>` and `readrelays:<root>` (spec
     * 2026-10-23 §5.3) share one gate (accepted, ruling K10).
     */
    private val gate: RefreshGate<String> = RefreshGate(engine.backgroundScope, GATE_TTL_SECONDS),
) : ThreadRepository {

    /**
     * Spec perf2 C1: what the jobs found beside the database, written only under [lock]. [ancestors] is set in the first
     * view when the database walk was complete, else by the walk: spec 2026-10-23 §5.2 lifts perf2's «never partial»,
     * the chain comes in parts (database and own relays first, then each foreign hop), a gap stage 3 may still fill
     * shown as [Slot.Pending] until it is found or settled as unavailable.
     */
    private class ThreadState(val focused: Note, val rootId: String, val tagIds: List<String>) {
        val lock = Mutex()
        var ancestors: List<Slot> = emptyList()
        var repliesFetched = false
    }

    override fun observe(noteId: String): Flow<ThreadView> = channelFlow {
        val focused = loadNote(noteId) ?: return@channelFlow
        val rootId = (focused.rootId ?: focused.id).lowercase()
        // spec perf2 decision 2: replies that tag only the focused note are found too
        val tagIds = listOf(rootId, focused.id).filter { HEX_ID.matches(it) }.distinct()
        val s = ThreadState(focused, rootId, tagIds)
        // the chain through the database only: complete, or not shown before the walk is done (spec perf2 C2.4)
        val local = try {
            conversations.localAncestors(focused)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalWalk(null, emptySet())
        }
        local.ancestors?.let { s.ancestors = it } // no other coroutine runs yet
        val pacer = SignalPacer(windowMs)
        // spec 2026-10-23 §7: the locator forgot its marks (network back, refresh): the walk and the read relays once more
        val walkAgain = Channel<Unit>(Channel.CONFLATED)
        val readAgain = Channel<Unit>(Channel.CONFLATED)
        launch(start = CoroutineStart.UNDISPATCHED) {
            conversations.retries.collect { walkAgain.trySend(Unit); readAgain.trySend(Unit) }
        }
        val shown = AtomicReference<Set<String>>(emptySet())
        var last = current(s)
        // job R right after the first read (the first view is the database before any fetch of this open), on the
        // background scope keyed by root: leaving does not cancel it, a reopen of this root joins it (spec perf2 C3)
        val replies: Job? = gate.launchStamped("replies:$rootId:${focused.id}") {
            fetchReplies(tagIds).also { asked ->
                // job D right behind R, also on the background scope
                if (asked) gate.launchStamped("deletions:$rootId") { fetchDeletions(rootId, focused.id, tagIds) }
            }
        }
        shown.set(last.ids())
        send(last)
        launch { live(s, shown, pacer) }
        // the one emitter: every view is the database view now (Withdrawn and NIP-09 in one place), never a repeat
        launch {
            pacer.run {
                // a failed read must not end the loop (SignalPacer): the next signal reads again
                val v = try {
                    current(s)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@run
                }
                if (v != last) {
                    last = v
                    shown.set(v.ids())
                    send(v)
                }
            }
        }
        launch {
            replies?.join()
            s.lock.withLock { s.repliesFetched = true }
            pacer.signal(milestone = true)
            // the D that R started (shared), or a new one when R did not get that far; null when D ran less than a minute ago
            gate.launchStamped("deletions:$rootId") { fetchDeletions(rootId, focused.id, tagIds) }?.join()
            pacer.signal(milestone = true)
        }
        launch {
            walk(s, local, replies, pacer)
            for (retry in walkAgain) walk(s, local, replies, pacer)
        }
        // spec 2026-10-23 §5.3: after job R, gated per root like R and D (background scope, once a minute); a retry forces it
        locator?.let { l ->
            launch {
                replies?.join()
                var retry = false
                while (true) {
                    gate.launchStamped("readrelays:$rootId", force = retry) { fetchFromReadRelays(s, l) }?.let { it.join(); pacer.signal() }
                    readAgain.receive()
                    retry = true
                }
            }
        }
    }

    override suspend fun note(id: String): Note? = loadNote(id)

    private suspend fun loadNote(id: String): Note? {
        val local = engine.eventById(id)
        val ev = local ?: run {
            quietly { engine.fetch(Filter().id(EventId.parse(id)), fetchTimeout, exit = Exit.AllIds(setOf(id))) }
            engine.eventById(id)
        }
        return ev?.takeUnless { Withdrawn.isWithdrawn(engine, it) }?.let { NoteMapper.note(it) }
    }

    /** Job R (spec perf2 C3): replies and deletion requests that tag the root or the focused note; true when a connected relay was asked. */
    private suspend fun fetchReplies(tagIds: List<String>): Boolean {
        val asked = engine.connectedNormalRelayUrls().isNotEmpty()
        quietly { engine.fetch(taggedFilter(listOf(1, 5), tagIds), fetchTimeout) }
        return asked
    }

    /** Job D (spec perf2 C3): deletion requests for root, focused and every stored reply, 100 ids per request, the requests in parallel; true when a connected relay was asked. */
    private suspend fun fetchDeletions(rootId: String, focusedId: String, tagIds: List<String>): Boolean = coroutineScope {
        val asked = engine.connectedNormalRelayUrls().isNotEmpty()
        val replyIds = try {
            engine.queryDecoded(taggedFilter(listOf(1), tagIds)).map { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val ids = (listOf(rootId, focusedId) + replyIds).filter { HEX_ID.matches(it) }.distinct()
        for (chunk in ids.chunked(DELETION_CHUNK)) launch {
            quietly { engine.fetch(Filter().kind(Kind(5u)).events(chunk.map { EventId.parse(it) }), fetchTimeout) }
        }
        asked
    }

    /**
     * Job W (spec perf2 C3): round 1 (the root when it is not stored, the first missing hop) in one ids request beside
     * job R; after R the walk again, which finds the hops R stored (NIP-10: they tag the root) and fetches the rest
     * by id, one request per round. Spec 2026-10-23 §5.2/§4.4: that chain is shown at once; its pending gaps go to
     * stage 3, one round per hop, and the chain is walked and shown again after each, at most [FOREIGN_HOPS] rounds.
     * A hop still missing after all rounds is unavailable, the root on top.
     */
    private suspend fun walk(s: ThreadState, local: LocalWalk, replies: Job?, pacer: SignalPacer) {
        try {
            if (conversations.fetchMissing(local.missing + s.rootId)) pacer.signal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the walk below asks again
        }
        if (local.ancestors != null) return
        replies?.join()
        // spec 2026-10-23 §10: an id that missed on the own relays in this walk is not asked there again after a round
        val asked = HashSet<String>()
        var ancestors = ancestorsOrNull(s, asked) ?: emptyList()
        show(s, ancestors, pacer)
        var hops = 0
        while (hops < FOREIGN_HOPS) {
            val pending = ancestors.filterIsInstance<Slot.Pending>()
            if (pending.isEmpty()) return
            try {
                conversations.resolve(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the walk below shows what is stored; a gap it still finds pending counts as one hop
            }
            hops++
            ancestors = ancestorsOrNull(s, asked) ?: break
            show(s, ancestors, pacer)
        }
        // no further foreign hop (spec §4.4): what is still pending is unavailable
        val settled = ancestors.map { if (it is Slot.Pending) Slot.Unavailable(it.id) else it }
        if (settled != ancestors) show(s, settled, pacer)
    }

    private suspend fun ancestorsOrNull(s: ThreadState, asked: MutableSet<String>): List<Slot>? = try {
        conversations.ancestors(s.focused, asked)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun show(s: ThreadState, ancestors: List<Slot>, pacer: SignalPacer) {
        s.lock.withLock { s.ancestors = ancestors }
        pacer.signal(milestone = true)
    }

    /**
     * Spec 2026-10-23 §5.3: replies from up to three read relays of the root's author (NIP-65: repliers write there).
     * The author: the stored root's, else the root hint's of the focused note; true when a relay was asked.
     */
    private suspend fun fetchFromReadRelays(s: ThreadState, locator: EventLocator): Boolean = try {
        rootAuthor(s)?.let { locator.repliesFrom(it, s.tagIds) } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun rootAuthor(s: ThreadState): String? {
        if (s.rootId == s.focused.id) return s.focused.author
        val stored = if (HEX_ID.matches(s.rootId)) engine.queryDecoded(idsFilter(listOf(s.rootId))).firstOrNull { it.kind == 1 }?.pubkey else null
        return stored ?: s.focused.rootHint?.author
    }

    /**
     * Job L (spec perf2 C3): listening before the REQ goes out, so nothing between the fetch and the subscription is
     * lost; the REQ starts [LIVE_OVERLAP_SECONDS] back (D2: relays do not resend the thread job R fetches). The
     * collector only signals; the emitter reads.
     */
    private suspend fun live(s: ThreadState, shown: AtomicReference<Set<String>>, pacer: SignalPacer) {
        var subId: String? = null
        try {
            engine.incoming
                .onSubscription {
                    subId = try {
                        engine.subscribe(taggedFilter(listOf(1, 5), s.tagIds, since = now() - LIVE_OVERLAP_SECONDS))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // no relay: what other subscriptions bring still lands
                    }
                }
                .collect { inc -> if (ThreadLiveMatch.touches(inc.json, s.tagIds, shown.get())) pacer.signal() }
        } finally {
            withContext(NonCancellable) { subId?.let { id -> quietly { engine.unsubscribe(id) } } }
        }
    }

    /**
     * Spec perf2 C1: the thread as the database has it now — root and focused in one read, the replies in one,
     * one Withdrawn check for all — with the jobs' state. A withdrawn or deleted focused note is [ThreadView.gone].
     */
    private suspend fun current(s: ThreadState): ThreadView {
        val (ancestors, fetched) = s.lock.withLock { s.ancestors to s.repliesFetched }
        val heads = engine.queryBoth(idsFilter(listOf(s.rootId, s.focused.id).filter { HEX_ID.matches(it) }.distinct()))
        val tagged = engine.queryBoth(taggedFilter(listOf(1), s.tagIds))
        val kept = Withdrawn.filterDecoded(engine, heads + tagged).map { it.second }
        val byId = kept.associateBy { it.id }
        val taggedIds = tagged.mapTo(HashSet()) { it.second.id }
        val focusedNow = byId[s.focused.id]
        val rootNow = if (s.rootId == s.focused.id) focusedNow else byId[s.rootId]
        val replies = kept
            .filter { it.id in taggedIds && it.id != s.rootId && s.holds(it) }
            .distinctBy { it.id }
            .mapNotNull { NoteMapper.note(it) }
            .sortedBy { it.createdAt }
        return ThreadView(rootNow?.let { NoteMapper.note(it) }, s.focused, replies, gone = focusedNow == null, ancestors = ancestors, repliesFetched = fetched)
    }

    /** A reply of this thread: it tags the root (any `e` tag, as before), or its NIP-10 reference is the focused note (a mention is not). */
    private fun ThreadState.holds(ev: JsonEvent): Boolean =
        ev.tags.any { it.size >= 2 && it[0] == "e" && it[1] == rootId } ||
            Nip10.parse(ev.tags).let { it.replyToId == focused.id || it.rootId == focused.id }

    private fun ThreadView.ids(): Set<String> = buildSet {
        root?.let { add(it.id) }
        add(focused.id)
        replies.forEach { add(it.id) }
        ancestors.forEach { add(it.id) }
    }

    companion object {
        /** Spec perf2 C4. */
        const val WINDOW_MS = 150L
        /** Spec perf2 C3: a root's replies (and deletions) are fetched again at most once a minute in this session. */
        const val GATE_TTL_SECONDS = 60L
        /** Spec perf2 D2: the live REQ starts this far back; job R fetches what is older. */
        const val LIVE_OVERLAP_SECONDS = 60L
        /** Spec 2026-10-23 §4.4: stage 3 rounds of one thread walk (a long chain would take 50 × 5 s otherwise). */
        const val FOREIGN_HOPS = 3
        private const val DELETION_CHUNK = 100
    }
}

/** Spec perf2 C3 job L: whether a live event changes an open thread, read from the event the engine decoded (no FFI). */
internal object ThreadLiveMatch {
    fun touches(event: JsonEvent, tagIds: Collection<String>, shown: Set<String>): Boolean = when (event.kind) {
        1 -> event.tags.any { it.size >= 2 && it[0] == "e" && it[1] in tagIds }
        5 -> event.tags.any { it.size >= 2 && it[0] == "e" && it[1] in shown }
        else -> false
    }
}
