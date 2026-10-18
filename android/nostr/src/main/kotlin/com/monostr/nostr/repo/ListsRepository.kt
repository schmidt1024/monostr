package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** NIP-51 follow sets (kind 30000) of the user and of others (spec 2026-10-18 §3, §4.1). */
interface ListsRepository {
    /** The user's own lists, newest first; the first collection loads them (database, then one relay fetch). */
    fun own(): Flow<List<FollowSet>>
    /** [author]'s lists: for the user the own ones, else the public part of each (spec §2.2), newest first. */
    suspend fun of(author: String): List<FollowSet>
    /** One list, or null when it is deleted or found nowhere. [fetch] false reads the database only. */
    suspend fun byAddress(address: ListAddress, fetch: Boolean = true): FollowSet?
    /** Publishes the whole new [set] (create, edit, private switch); the own lists change at once, a failure rolls back. */
    suspend fun save(set: FollowSet): SaveResult
    /** Sets title, description and private on the list's current state (members as they are now), under the list's lock. */
    suspend fun edit(address: ListAddress, title: String, description: String?, private: Boolean): SaveResult
    /** Adds or removes one member on top of the last accepted state; calls per list run one after another (spec §3.4). */
    suspend fun setMember(address: ListAddress, pubkey: String, present: Boolean): SaveResult
    /** Kind 5 for the list's address, then an empty replacement (spec §3.2). */
    suspend fun delete(set: FollowSet): SaveResult
    /** Decrypts a locked own list again, asking the signer; true when it is readable now. */
    suspend fun unlock(address: ListAddress): Boolean
    /** A fresh `d` for a new list: 12 characters a-z0-9 (spec §3.1). */
    fun newId(): String
}

class NostrListsRepository(
    private val engine: NostrEngine,
    private val signer: Signer,
    private val authorRelays: AuthorRelaysRepository,
    /** The app's default relays: lists are looked up there too (spec §4.2). */
    private val defaultRelays: List<String> = emptyList(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    private val fetchTimeout: Duration = Duration.ofSeconds(6),
    private val random: java.util.Random = SecureRandom(),
) : ListsRepository {
    private val privateTags = PrivateTags(signer)

    /** Null until the first load. */
    private val _own = MutableStateFlow<List<FollowSet>?>(null)
    private val loadMutex = Mutex()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Newest `created_at` of any own list event seen or written: every write is strictly newer (replaceable, NIP-01). */
    @Volatile private var lastCreatedAt = 0L

    private fun kind() = Kind(FollowSetCodec.KIND.toUShort())
    private fun ownFilter() = Filter().kind(kind()).author(PublicKey.parse(signer.pubkey))
    private fun lockFor(d: String): Mutex = locks.computeIfAbsent(d) { Mutex() }
    /** Newer than any own list event seen and than [after], the version being replaced (it may come from a clock ahead). */
    private fun nextCreatedAt(after: Long = 0): Long = maxOf(now(), lastCreatedAt + 1, after + 1).also { lastCreatedAt = it }

    override fun own(): Flow<List<FollowSet>> = _own.onStart { ensureOwn() }.filterNotNull()

    private suspend fun ensureOwn() = loadMutex.withLock {
        if (_own.value != null) return@withLock
        fetchWide(engine, ownFilter(), defaultRelays, fetchTimeout)
        _own.value = readOwn()
    }

    private suspend fun readOwn(): List<FollowSet> {
        val events = newestPerD(engine.query(ownFilter()))
        events.forEach { lastCreatedAt = maxOf(lastCreatedAt, it.createdAt().asSecs().toLong()) }
        return events.mapNotNull { FollowSetCodec.decode(it, privateTags, interactive = false) }.sortedByDescending { it.createdAt }
    }

    private fun putOwn(set: FollowSet) {
        lastCreatedAt = maxOf(lastCreatedAt, set.createdAt)
        _own.update { cur -> (listOf(set) + cur.orEmpty().filterNot { it.address == set.address }).sortedByDescending { it.createdAt } }
    }

    /** Puts [previous] back for [address] (or removes the entry when there was none). */
    private fun restoreOwn(address: ListAddress, previous: FollowSet?) =
        _own.update { cur -> (cur.orEmpty().filterNot { it.address == address } + listOfNotNull(previous)).sortedByDescending { it.createdAt } }

    override suspend fun of(author: String): List<FollowSet> {
        if (author == signer.pubkey) {
            ensureOwn()
            return _own.value.orEmpty()
        }
        val filter = Filter().kind(kind()).author(PublicKey.parse(author))
        quietly { fetchWide(engine, filter, authorRelays.writeRelays(author) + defaultRelays, fetchTimeout) }
        return newestPerD(engine.query(filter)).mapNotNull { FollowSetCodec.decode(it, null, interactive = false) }.sortedByDescending { it.createdAt }
    }

    override suspend fun byAddress(address: ListAddress, fetch: Boolean): FollowSet? {
        if (address.pubkey == signer.pubkey) {
            ensureOwn()
            _own.value?.firstOrNull { it.address == address }?.let { return it }
            if (!fetch) return null
            // not among the lists read at start (offline then, or made on another device since): one fetch for it.
            // Under the list's lock: a delete in flight finishes first, so its old version is not put back.
            return lockFor(address.d).withLock {
                _own.value?.firstOrNull { it.address == address }?.let { return@withLock it }
                val filter = ownFilter().identifier(address.d)
                fetchWide(engine, filter, defaultRelays, fetchTimeout)
                val set = newest(engine.query(filter))?.let { FollowSetCodec.decode(it, privateTags, interactive = false) } ?: return@withLock null
                putOwn(set)
                set
            }
        }
        val filter = Filter().kind(kind()).author(PublicKey.parse(address.pubkey)).identifier(address.d)
        if (fetch) quietly { fetchWide(engine, filter, authorRelays.writeRelays(address.pubkey) + defaultRelays, fetchTimeout) }
        return newest(engine.query(filter))?.let { FollowSetCodec.decode(it, null, interactive = false) }
    }

    override suspend fun save(set: FollowSet): SaveResult = lockFor(set.address.d).withLock { write(set) }

    override suspend fun edit(address: ListAddress, title: String, description: String?, private: Boolean): SaveResult = lockFor(address.d).withLock {
        ensureOwn()
        val current = _own.value?.firstOrNull { it.address == address } ?: return@withLock SaveResult.Failed
        write(current.copy(title = title, description = description, private = private))
    }

    override suspend fun setMember(address: ListAddress, pubkey: String, present: Boolean): SaveResult = lockFor(address.d).withLock {
        ensureOwn()
        val current = _own.value?.firstOrNull { it.address == address } ?: return@withLock SaveResult.Failed
        if (current.locked) return@withLock SaveResult.Locked
        if ((pubkey in current.members) == present) return@withLock SaveResult.Ok
        write(current.copy(members = if (present) current.members + pubkey else current.members - pubkey))
    }

    /** The write behind [save] and [setMember]; the caller holds the list's lock. */
    private suspend fun write(set: FollowSet): SaveResult {
        ensureOwn()
        val previous = _own.value?.firstOrNull { it.address == set.address }
        if (previous?.locked == true) return SaveResult.Locked
        val ts = nextCreatedAt(after = previous?.createdAt ?: 0)
        val next = set.copy(author = signer.pubkey, createdAt = ts, locked = false)
        putOwn(next) // the UI updates at once (spec §3.4)
        val ok = try {
            val (tags, content) = FollowSetCodec.encode(next, privateTags)
            send(EventBuilder(kind(), content).tags(tags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(ts.toULong()))).sentToAny
        } catch (e: CancellationException) {
            restoreOwn(set.address, previous)
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            restoreOwn(set.address, previous)
            return SaveResult.Failed
        }
        return SaveResult.Ok
    }

    override suspend fun delete(set: FollowSet): SaveResult = lockFor(set.address.d).withLock {
        ensureOwn()
        val previous = _own.value?.firstOrNull { it.address == set.address }
        _own.update { cur -> cur.orEmpty().filterNot { it.address == set.address } }
        val ok = try {
            // the kind 5 deletes versions up to its own created_at (NIP-09): the empty replacement must be newer
            val deletionAt = nextCreatedAt(after = maxOf(previous?.createdAt ?: 0, set.createdAt))
            quietly {
                send(
                    EventBuilder(Kind(5u), "")
                        .tags(listOf(Tag.parse(listOf("a", set.address.toString())), Tag.parse(listOf("k", FollowSetCodec.KIND.toString()))))
                        .customCreatedAt(Timestamp.fromSecs(deletionAt.toULong())),
                )
            }
            val replacementAt = nextCreatedAt()
            send(EventBuilder(kind(), "").tags(listOf(Tag.parse(listOf("d", set.address.d)))).customCreatedAt(Timestamp.fromSecs(replacementAt.toULong()))).sentToAny
        } catch (e: CancellationException) {
            restoreOwn(set.address, previous)
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            restoreOwn(set.address, previous)
            return@withLock SaveResult.Failed
        }
        SaveResult.Ok
    }

    override suspend fun unlock(address: ListAddress): Boolean = lockFor(address.d).withLock {
        ensureOwn()
        val event = newest(engine.query(ownFilter().identifier(address.d))) ?: return@withLock false
        val set = FollowSetCodec.decode(event, privateTags, interactive = true) ?: return@withLock false
        putOwn(set)
        !set.locked
    }

    override fun newId(): String = String(CharArray(ID_LENGTH) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

    private companion object {
        const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val ID_LENGTH = 12
    }
}

/** The newest event per `d`: relays and the database may still hold older versions of an addressable event. */
internal fun newestPerD(events: List<Event>): List<Event> =
    events.groupBy { e -> e.tags().toVec().map { it.asVec() }.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "" }
        .values.map { group -> group.maxBy { it.createdAt().asSecs() } }

internal fun newest(events: List<Event>): Event? = events.maxByOrNull { it.createdAt().asSecs() }

/**
 * Fetches [filter] from the session's relays and from [extra] (another account's write relays, the default
 * relays), the latter attached only for the call (spec §4.2). Never throws except on cancellation.
 */
internal suspend fun fetchWide(engine: NostrEngine, filter: Filter, extra: List<String>, timeout: Duration) {
    quietly { engine.fetch(filter, timeout) }
    val others = extra.distinct() - engine.relayUrls().toSet()
    if (others.isEmpty()) return
    quietly {
        val held = engine.attachTemporary(others)
        try {
            engine.fetchFrom(others, filter, timeout, attach = false)
        } finally {
            engine.detachTemporary(held)
        }
    }
}
