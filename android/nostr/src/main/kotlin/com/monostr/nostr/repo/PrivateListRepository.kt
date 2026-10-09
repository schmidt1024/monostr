package com.monostr.nostr.repo

import com.monostr.nostr.Nip44UnsupportedException
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** Ids of a private NIP-51 list (private + public entries of its tag), whether it may be written, whether load ran. */
data class ListState(val ids: List<String> = emptyList(), val writable: Boolean = false, val loaded: Boolean = false)

sealed class ListOutcome {
    data object Ok : ListOutcome()
    data object PublishFailed : ListOutcome()
    data object Unsupported : ListOutcome()
    data object Rejected : ListOutcome()
    /** The list could not be loaded yet (no local copy and the relay fetch failed or there are no relays): retry later. */
    data object NotLoaded : ListOutcome()
}

/**
 * One replaceable NIP-51 list of the user ([kind]) whose entries of [tag] this app edits: public
 * ones in the event's tags, private ones NIP-44-encrypted to the user in `content`. Everything else
 * in the list (other tags, other apps' private entries) is preserved verbatim. Bookmarks (kind 10003,
 * `e`) and the mute list (kind 10000, `p`) are the two instances; see their repositories for the
 * load/toggle semantics, which live here unchanged from the bookmarks' original implementation.
 */
class NostrPrivateList(
    private val engine: NostrEngine,
    private val signer: Signer,
    private val kind: Int,
    private val tag: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    private val fetchTimeout: Duration = Duration.ofSeconds(8),
    private val refreshTimeout: Duration = Duration.ofSeconds(3),
    private val watch: CoroutineScope? = null,
    /**
     * Called with every new [state] (from each emit and from [clear]): a hook for a wrapper that
     * derives its own view (the mute repository keeps a set of pubkeys). It runs inside the
     * list's mutex, so it must be cheap and must not throw.
     */
    private val onState: (ListState) -> Unit = {},
) {
    private val _state = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val json = Json

    /** The parts of the list we preserve verbatim; only privateIds is ours to edit. */
    private var publicTags: List<List<String>> = emptyList()
    private var privateOther: List<List<String>> = emptyList()
    private var privateIds: List<String> = emptyList()
    private val publicIds: List<String> get() = publicTags.filter { it.size >= 2 && it[0] == tag }.map { it[1] }

    /**
     * `created_at` of the last known list (loaded or published). The list's kind is replaceable
     * (NIP-01): relays and the local database keep only the highest `created_at`, tie-broken in a
     * way we do not control, so every publish must use a strictly higher timestamp than what came
     * before it - never just [now] as-is, which can tie or even trail an existing event.
     */
    private var lastCreatedAt: Long = 0

    /**
     * `created_at` of the list our in-memory state actually reflects: set only by [adopt] (a list
     * we read) and after a successful publish. Unlike [lastCreatedAt] a failed write never bumps
     * it, so [refreshLocked] still adopts a remote list that is newer than what we read even if
     * it is older than our failed attempt.
     */
    private var snapshotCreatedAt: Long = 0

    private fun filter() = Filter().kind(Kind(kind.toUShort())).author(PublicKey.parse(signer.pubkey)).limit(1u)

    /** Set on [Decrypt.NO_SILENT_ANSWER]; skips the silent signer on further implicit loads until an interactive call. */
    private var silentTried = false

    /** Whether [loadLocked] has already refreshed an existing local copy from the relays this session. */
    private var refreshed = false

    /** Loads once (semantics as documented on [BookmarksRepository.ensureLoaded]), then watches for newer lists. */
    suspend fun ensureLoaded(interactive: Boolean) {
        mutex.withLock { loadLocked(interactive) }
        startWatching()
    }

    private enum class Load { ALREADY, LOADED, NOT_LOADED, REJECTED }

    /** How [adopt] fared with a list's private part. */
    private enum class Decrypt { OK, NO_SILENT_ANSWER, REJECTED, UNREADABLE }

    private suspend fun loadLocked(interactive: Boolean): Load {
        if (interactive) silentTried = false
        if (_state.value.loaded) return Load.ALREADY
        if (!interactive && silentTried) return Load.NOT_LOADED
        var event = engine.query(filter()).firstOrNull()
        if (event == null) {
            // No local copy: a fetch that throws, or one that quietly reaches no relay at all
            // (a dead/unconfigured relay list returns an empty result without throwing - see
            // NostrEngine.fetch), must not be mistaken for "the user has no list": that would
            // make the very next toggle overwrite whatever is actually on the relays. Only a
            // fetch that some normal relay actually answered, even with nothing, proves there
            // really is no list yet - a connected search relay never received the fetch.
            try {
                engine.fetch(filter(), fetchTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ListState(ids = emptyList(), writable = false, loaded = false))
                return Load.NOT_LOADED
            }
            event = engine.query(filter()).firstOrNull()
            if (event == null && engine.connectedNormalRelayUrls().isEmpty()) {
                emit(ListState(ids = emptyList(), writable = false, loaded = false))
                return Load.NOT_LOADED
            }
        } else {
            // We already have a local copy: a failed refresh just means we keep using it. Only
            // the first load per session hits the relays here - the subscription keeps it fresh after.
            if (!refreshed) {
                refreshed = true
                quietly { engine.fetch(filter(), fetchTimeout) }
                event = engine.query(filter()).firstOrNull() ?: event
            }
        }
        if (event == null) {
            publish(writable = true, loaded = true)
            return Load.LOADED
        }
        return when (adopt(event, interactive)) {
            Decrypt.OK -> { publish(writable = true, loaded = true); Load.LOADED }
            Decrypt.UNREADABLE -> { publish(writable = false, loaded = true); Load.LOADED }
            Decrypt.NO_SILENT_ANSWER -> { silentTried = true; publish(writable = false, loaded = false); Load.NOT_LOADED }
            Decrypt.REJECTED -> { publish(writable = false, loaded = false); Load.REJECTED }
        }
    }

    private var watching = false
    private val watchMutex = Mutex()

    /**
     * Subscribes to our own list once per session so a newer list from another device is adopted without polling.
     * [watching] is set only after the subscription succeeded, so a failed subscribe is retried on the next [ensureLoaded].
     */
    private suspend fun startWatching(): Unit = watchMutex.withLock {
        if (watching) return@withLock
        val scope = watch ?: return@withLock
        runCatching { engine.subscribe(filter()) }.getOrNull() ?: return@withLock
        watching = true
        scope.launch {
            engine.events.collect { ev ->
                if (ev.kind().asU16().toInt() == kind && ev.author().toHex() == signer.pubkey) onListEvent(ev)
            }
        }
    }

    /**
     * A list event of ours from a relay: adopted when newer than what we show (spec 7.3). A list
     * we cannot read silently is ignored until the next interactive load: [adopt] overwrites the
     * in-memory list before the decrypt outcome is known, so anything short of a readable result
     * must roll that back - otherwise the next [toggle] would publish the wiped list, and a
     * silently-unreadable newer list would look like an unchanged, still-loaded one.
     */
    internal suspend fun onListEvent(event: Event) = mutex.withLock {
        if (event.createdAt().asSecs().toLong() <= snapshotCreatedAt) return@withLock
        val prevPublicTags = publicTags
        val prevPrivateOther = privateOther
        val prevPrivateIds = privateIds
        val prevSnapshotCreatedAt = snapshotCreatedAt
        val prevLastCreatedAt = lastCreatedAt
        when (adopt(event, interactive = false)) {
            Decrypt.OK -> publish(writable = true, loaded = true)
            Decrypt.UNREADABLE -> publish(writable = false, loaded = true)
            else -> {
                // silent decrypt impossible: keep the current list, the next interactive load picks it up
                publicTags = prevPublicTags; privateOther = prevPrivateOther; privateIds = prevPrivateIds
                snapshotCreatedAt = prevSnapshotCreatedAt; lastCreatedAt = prevLastCreatedAt
            }
        }
    }

    /**
     * Takes [event] as the current list: its public tags always, its private part when it
     * decrypts. Anything short of [Decrypt.OK] clears the private part, so a stale private list
     * is never mixed with newer public tags.
     */
    private suspend fun adopt(event: Event, interactive: Boolean): Decrypt {
        val createdAt = event.createdAt().asSecs().toLong()
        snapshotCreatedAt = createdAt
        lastCreatedAt = maxOf(lastCreatedAt, createdAt)
        publicTags = event.tags().toVec().map { it.asVec() }
        privateIds = emptyList(); privateOther = emptyList()
        val content = event.content()
        if (content.isBlank()) return Decrypt.OK
        val tags = try {
            withContext(Dispatchers.Default) {
                val text = (if (interactive) signer.nip44Decrypt(signer.pubkey, content) else signer.nip44DecryptSilent(signer.pubkey, content))
                    ?: return@withContext null
                parseTags(text)
            } ?: return Decrypt.NO_SILENT_ANSWER
        } catch (e: CancellationException) {
            throw e
        } catch (e: SigningRejectedException) {
            return Decrypt.REJECTED
        } catch (e: Exception) {
            return Decrypt.UNREADABLE
        }
        privateIds = tags.filter { it.size >= 2 && it[0] == tag }.map { it[1] }
        privateOther = tags.filter { it.size < 2 || it[0] != tag }
        return Decrypt.OK
    }

    /**
     * Before a write: picks up a newer list another device published since our load (best-effort
     * 3 s fetch, then the newest local copy). Null to go on, or the outcome that stops the write.
     */
    /** Result of [refreshLocked]: whether a newer remote list replaced ours, and a failure outcome if it could not be read. */
    private class Refresh(val adopted: Boolean, val outcome: ListOutcome?)

    /**
     * Pulls the newest copy of the list and adopts it when it is newer than what we read. Does not emit
     * state: the caller decides what the user sees (a toggle in flight has already flipped the UI).
     */
    private suspend fun refreshLocked(): Refresh {
        quietly { engine.fetch(filter(), refreshTimeout) }
        val newest = engine.query(filter()).firstOrNull() ?: return Refresh(false, null)
        if (newest.createdAt().asSecs().toLong() <= snapshotCreatedAt) return Refresh(false, null)
        return when (adopt(newest, interactive = true)) {
            Decrypt.OK -> Refresh(true, null)
            Decrypt.UNREADABLE -> Refresh(true, ListOutcome.Unsupported)
            Decrypt.NO_SILENT_ANSWER, Decrypt.REJECTED -> Refresh(true, ListOutcome.Rejected)
        }
    }

    /** Applies the direction the user chose; idempotent, so it can be re-applied after a refresh. */
    private fun applyToggle(id: String, remove: Boolean) {
        if (remove) {
            privateIds = privateIds.filterNot { it == id }
            publicTags = publicTags.filterNot { it.size >= 2 && it[0] == tag && it[1] == id }
        } else if (id !in privateIds && id !in publicIds) {
            privateIds = listOf(id) + privateIds
        }
    }

    /**
     * Flips [id]: takes it out of both parts when the list the user saw holds it, else adds it
     * privately. Semantics as documented on [BookmarksRepository.toggle].
     */
    suspend fun toggle(id: String): ListOutcome = mutex.withLock {
        val load = loadLocked(interactive = true)
        if (load == Load.REJECTED) return@withLock ListOutcome.Rejected
        if (!_state.value.loaded) return@withLock ListOutcome.NotLoaded
        if (!_state.value.writable) return@withLock ListOutcome.Unsupported
        // The direction comes from the list the user saw (read after the load, inside the lock).
        setLocked(id, present = id !in privateIds && id !in publicIds, load)
    }

    /** Puts [id] into the list ([present]) or takes it out of both parts; idempotent. Semantics as documented on [BookmarksRepository.toggle]. */
    suspend fun set(id: String, present: Boolean): ListOutcome = mutex.withLock {
        val load = loadLocked(interactive = true)
        if (load == Load.REJECTED) return@withLock ListOutcome.Rejected
        if (!_state.value.loaded) return@withLock ListOutcome.NotLoaded
        if (!_state.value.writable) return@withLock ListOutcome.Unsupported
        setLocked(id, present, load)
    }

    /** The write behind [toggle] and [set]; the caller holds [mutex] and has loaded a writable list. */
    private suspend fun setLocked(id: String, present: Boolean, load: Load): ListOutcome {
        // The UI flips at once, the relays are reconciled afterwards (a refresh may bring a newer
        // list, the same direction is re-applied).
        val remove = !present
        var revertPublic = publicTags; var revertPrivate = privateIds
        applyToggle(id, remove)
        // Nothing changed (muting a muted account, unmuting one that is not): no signature, no publish.
        if (publicTags == revertPublic && privateIds == revertPrivate) return ListOutcome.Ok
        publish(writable = true, loaded = true)
        try {
            // A load that just ran has fetched already; only an older snapshot needs the refresh.
            if (load == Load.ALREADY) {
                val refresh = refreshLocked()
                if (refresh.outcome != null) {
                    publicTags = revertPublic; privateIds = revertPrivate
                    publish(writable = refresh.outcome != ListOutcome.Unsupported, loaded = refresh.outcome != ListOutcome.Rejected)
                    return refresh.outcome
                }
                if (refresh.adopted) {
                    revertPublic = publicTags; revertPrivate = privateIds
                    applyToggle(id, remove)
                    publish(writable = true, loaded = true)
                }
            }
            val ts = maxOf(now(), lastCreatedAt + 1)
            lastCreatedAt = ts
            val outcome = try {
                val content = withContext(Dispatchers.Default) {
                    signer.nip44Encrypt(signer.pubkey, encodeTags(privateOther + privateIds.map { listOf(tag, it) }))
                }
                val builder = EventBuilder(Kind(kind.toUShort()), content).tags(publicTags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(ts.toULong()))
                if (send(builder).sentToAny) ListOutcome.Ok else ListOutcome.PublishFailed
            } catch (e: CancellationException) {
                throw e
            } catch (e: SigningRejectedException) {
                ListOutcome.Rejected
            } catch (e: Nip44UnsupportedException) {
                ListOutcome.Unsupported
            } catch (e: Exception) {
                ListOutcome.PublishFailed
            }
            if (outcome == ListOutcome.Ok) snapshotCreatedAt = ts
            if (outcome != ListOutcome.Ok) {
                publicTags = revertPublic; privateIds = revertPrivate
                publish(writable = outcome != ListOutcome.Unsupported, loaded = true)
            }
            return outcome
        } catch (e: CancellationException) {
            // the caller left (e.g. back from a thread during the refresh or before the relay OK): a change
            // that reached no relay must not stay in memory
            publicTags = revertPublic; privateIds = revertPrivate
            publish(writable = true, loaded = true)
            throw e
        }
    }

    fun clear() {
        publicTags = emptyList(); privateOther = emptyList(); privateIds = emptyList(); lastCreatedAt = 0; snapshotCreatedAt = 0
        emit(ListState())
    }

    private fun publish(writable: Boolean, loaded: Boolean) {
        emit(ListState(ids = (privateIds + publicIds).distinct(), writable = writable, loaded = loaded))
    }

    private fun emit(state: ListState) {
        _state.value = state
        onState(state)
    }

    private fun parseTags(text: String): List<List<String>> =
        json.parseToJsonElement(text).jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }

    private fun encodeTags(tags: List<List<String>>): String =
        JsonArray(tags.map { t -> JsonArray(t.map { JsonPrimitive(it) }) }).toString()
}
