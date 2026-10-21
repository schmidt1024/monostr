package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Nip10
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.tips.Kinds
import com.monostr.tips.PaymentInfo
import com.monostr.tips.ReceiptValidator
import com.monostr.tips.TipReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import java.time.Duration
import com.monostr.tips.event.Event as JsonEvent

enum class NotificationKind { REPLY, MENTION, QUOTE, REACTION, REPOST, TIP }

/**
 * One notification (spec 5.7, spec 11-C §2). [noteId] is what a tap opens: the reply, quote or mention itself,
 * else [aboutId]; null for a tip to the profile. [aboutId] is the note the event is about: the parent of a reply,
 * the `q` target of a quote, the `e` target of a reaction or repost, the tipped note; null for a mention and a
 * profile tip. [from] is null for an anonymous tip. [amount] is set for tips only (piconero).
 */
data class NotificationItem(
    val id: String,
    val kind: NotificationKind,
    val from: String?,
    val createdAt: Long,
    val noteId: String?,
    val aboutId: String?,
    val text: String,
    val amount: Long?,
)

/** The note a notification is about (spec 11-C §2.4). */
sealed interface AboutNote {
    data class Found(val note: Note) : AboutNote
    /** Withdrawn by its author (NIP-09). */
    data object Withdrawn : AboutNote
    /** Neither stored nor delivered by the relays. */
    data object Missing : AboutNote
}

interface NotificationsRepository {
    /** Local items, newest first. */
    suspend fun list(limit: Int = 100): List<NotificationItem>
    /** Spec perf 3.6: the stored notes [ids] name, found or withdrawn, from the database only (never a relay, never throws); ids not stored are left out. */
    suspend fun localNotes(ids: Set<String>): Map<String, AboutNote>
    /** The notes [ids] name: local first, the rest in one relay request; an id no relay had is `Missing` without a new request for 10 minutes; never throws; every id is a key. */
    suspend fun notes(ids: Set<String>): Map<String, AboutNote>
    /** Those of [ids] that are the user's own stored notes or reposts (local only, never throws): what a like or repost may call "your note". */
    suspend fun own(ids: Set<String>): Set<String>
    /** Pulls items from relays into the database; [since] limits the fetch (unix seconds). */
    suspend fun refresh(since: Long? = null, limit: Int = 100)
    /**
     * Spec perf 3.6: the one live subscription of the session. Subscribes from now on when collected, emits once per
     * incoming event that names the user, unsubscribes when the collector ends.
     */
    fun incoming(): Flow<Unit>
}

/** kind 1 / 6 / 7 / 9739 with `#p` = the user (spec 5.7, 11-C); receipts are validated against the user's own payment info. */
class NostrNotificationsRepository(
    private val engine: NostrEngine,
    private val tips: TipsRepository,
    /** Spec perf 3.6: a parent no relay had is not asked for again within this window. */
    private val missingTtlSeconds: Long = 600,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : NotificationsRepository {
    private val me = requireNotNull(engine.pubkey) { "notifications need a logged-in user" }

    private fun filter(limit: Int, since: Long?): Filter {
        var f = Filter().kinds(KINDS.map { Kind(it.toUShort()) }).pubkey(PublicKey.parse(me)).limit(limit.toULong())
        if (since != null) f = f.since(Timestamp.fromSecs(since.toULong()))
        return f
    }

    override suspend fun list(limit: Int): List<NotificationItem> = withContext(Dispatchers.IO) {
        val ownInfo = tips.cachedPaymentInfo(me) // never waits for relays (the refresh asks them)
        // spec perf 3.6: each event crosses the FFI once, as JSON, and is read in Kotlin through Withdrawn and map
        // (was 10-20 rust calls per event); NIP-09 for what the database could not refuse (spec 11-C §2.3)
        val decoded = engine.query(filter(limit, null)).map { it to NoteMapper.decoded(it) }
        Withdrawn.filterDecoded(engine, decoded).mapNotNull { map(it.second, ownInfo) }.sortedByDescending { it.createdAt }
    }

    /** The last [MEMO_CAPACITY] found or withdrawn notes; a missing one is asked for again after [missingTtlSeconds]. */
    private val memo = object : LinkedHashMap<String, AboutNote>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AboutNote>?): Boolean = size > MEMO_CAPACITY
    }
    private val memoLock = Any()

    /** When each id was last found nowhere (epoch s), the last [MEMO_CAPACITY]; guarded by [memoLock]. */
    private val missingAt = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MEMO_CAPACITY
    }

    override suspend fun own(ids: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        val valid = ids.filter { HEX64.matches(it) }
        if (valid.isEmpty()) return@withContext emptySet()
        try {
            engine.query(idsFilter(valid).author(PublicKey.parse(me)).kinds(listOf(Kind(1u), Kind(6u))))
                .map { it.id().toHex() }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }

    override suspend fun localNotes(ids: Set<String>): Map<String, AboutNote> = withContext(Dispatchers.IO) {
        val result = HashMap<String, AboutNote>()
        val wanted = ids.filter { HEX64.matches(it) }.filter { id -> synchronized(memoLock) { memo[id] }?.also { result[id] = it } == null }
        if (wanted.isNotEmpty()) readStored(wanted, result)
        synchronized(memoLock) { result.forEach { (id, note) -> if (note != AboutNote.Missing) memo[id] = note } }
        result
    }

    override suspend fun notes(ids: Set<String>): Map<String, AboutNote> = withContext(Dispatchers.IO) {
        val result = HashMap<String, AboutNote>()
        val t = now()
        // ids come from event tags: a malformed one is Missing, never a parse exception
        val wanted = ids.filter { HEX64.matches(it) }.filter { id -> synchronized(memoLock) { memo[id] }?.also { result[id] = it } == null }
        if (wanted.isNotEmpty()) {
            readStored(wanted, result)
            // a remembered miss skips the relays only: the database may have got the note since (another screen, a subscription)
            val missing = wanted.filter { it !in result }
            val ask = synchronized(memoLock) { missing.filter { id -> missingAt[id]?.let { t - it >= missingTtlSeconds } ?: true } }
            if (ask.isNotEmpty()) {
                quietly { engine.fetch(idsFilter(ask), exit = Exit.AllIds(ask.toSet())) }
                readStored(ask, result)
            }
            synchronized(memoLock) {
                for (id in wanted) {
                    when (val note = result[id]) {
                        null, AboutNote.Missing -> if (id in ask) missingAt[id] = t
                        else -> { memo[id] = note; missingAt.remove(id) }
                    }
                }
            }
        }
        for (id in ids) result.putIfAbsent(id, AboutNote.Missing)
        result
    }

    /**
     * Stored notes (kind 1, or a kind 6 — a like of my repost names the repost) of [ids] into [into]: found, or
     * withdrawn when the author's kind 5 names them. A failing read leaves the ids out (they end up Missing): the
     * list must not die of one bad lookup.
     */
    private suspend fun readStored(ids: List<String>, into: MutableMap<String, AboutNote>) {
        try {
            val stored = engine.query(idsFilter(ids).kinds(listOf(Kind(1u), Kind(6u)))).map { it to NoteMapper.decoded(it) }
            if (stored.isEmpty()) return
            val kept = Withdrawn.filterDecoded(engine, stored).mapTo(HashSet()) { it.second.id }
            val byId = stored.associate { it.second.id to it.first }
            for ((_, ev) in stored) {
                into[ev.id] = if (ev.id in kept) NoteMapper.note(ev, resolveRepost = { rid -> byId[rid] })?.let { AboutNote.Found(it) } ?: AboutNote.Missing else AboutNote.Withdrawn
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // leave the ids missing
        }
    }

    override suspend fun refresh(since: Long?, limit: Int) = coroutineScope {
        // list() reads the cached payment info and never waits, so the refresh brings it in (spec perf 3.6), beside the
        // notifications fetch: a user without a stored info makes it wait up to its fetch timeout
        val info = async { quietly { tips.paymentInfo(me) } }
        quietly { engine.fetch(filter(limit, since)) }
        // A deletion request (un-like, un-repost, withdrawn reply) carries no `#p`: asked for by the ids held, the newest
        // [limit] of them whatever [since] (an un-like may come long after the like)
        val ids = engine.query(filter(limit, null)).map { NoteMapper.decoded(it) }.filter { it.kind != Kinds.TIP_RECEIPT }.map { it.id }
        for (chunk in ids.chunked(100)) quietly { engine.fetch(deletionsFilter(chunk), DELETIONS_TIMEOUT) }
        info.await()
    }

    /** Kind 5 naming [ids] (`#e`), built from JSON. */
    private fun deletionsFilter(ids: List<String>): Filter = Filter.fromJson(
        buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(5))))
            put("#e", JsonArray(ids.map { JsonPrimitive(it) }))
        }.toString(),
    )

    override fun incoming(): Flow<Unit> = flow {
        var subId: String? = null
        try {
            engine.incoming
                // listening before the REQ goes out: an event the relay sends at once is not missed
                .onSubscription { subId = subscribeLive() }
                .filter { LiveMatch.matches(it.json, me) }
                .collect { emit(Unit) }
        } finally {
            subId?.let { id -> withContext(NonCancellable) { quietly { engine.unsubscribe(id) } } }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun subscribeLive(): String? = try {
        engine.subscribe(filter(LIVE_LIMIT, now()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Maps one event read in Kotlin; the user's own events and receipts failing spec 3.4 yield null. */
    internal fun map(event: JsonEvent, ownInfo: PaymentInfo): NotificationItem? {
        val author = event.pubkey
        if (author == me) return null
        val id = event.id
        val at = event.createdAt
        val tags = event.tags
        return when (event.kind) {
            1 -> {
                val refs = Nip10.parse(tags)
                val quoted = tags.firstOrNull { it.size >= 2 && it[0] == "q" && HEX64.matches(it[1]) }?.get(1)
                when {
                    refs.replyToId != null -> NotificationItem(id, NotificationKind.REPLY, author, at, id, refs.replyToId, event.content, null)
                    quoted != null -> NotificationItem(id, NotificationKind.QUOTE, author, at, id, quoted, event.content, null)
                    else -> NotificationItem(id, NotificationKind.MENTION, author, at, id, null, event.content, null)
                }
            }
            6 -> {
                // NIP-18: the original is the last `e` tag (a relay-hint `e` may precede it)
                val target = tags.lastOrNull { it.size >= 2 && it[0] == "e" && HEX64.matches(it[1]) }?.get(1) ?: return null
                NotificationItem(id, NotificationKind.REPOST, author, at, target, target, "", null)
            }
            7 -> {
                // NIP-25: "-" is a dislike, not something to tell the user about
                if (event.content.trim() == "-") return null
                // tags are relay input: only a well-formed id is a target
                val target = tags.lastOrNull { it.size >= 2 && it[0] == "e" && HEX64.matches(it[1]) }?.get(1) ?: return null
                NotificationItem(id, NotificationKind.REACTION, author, at, target, target, event.content, null)
            }
            Kinds.TIP_RECEIPT -> {
                val receipt = TipReceipt.parse(event) ?: return null
                if (!ReceiptValidator.isValid(receipt, me, ownInfo)) return null
                NotificationItem(id, NotificationKind.TIP, receipt.senderPubkey, at, receipt.noteId, receipt.noteId, "", receipt.amount)
            }
            else -> null
        }
    }

    private companion object {
        val KINDS = listOf(1, 6, 7, Kinds.TIP_RECEIPT)
        const val MEMO_CAPACITY = 200
        const val LIVE_LIMIT = 100
        /** Deletion requests are rare: a short wait keeps the refresh from doubling on a slow relay. */
        val DELETIONS_TIMEOUT: Duration = Duration.ofSeconds(3)
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Spec perf 3.6 / wave 2 B2: whether a live event is a notification candidate, read from the event the engine
 * decoded once: the kind and a `p` tag naming the user, no FFI call. Never throws.
 */
internal object LiveMatch {
    private val KINDS = setOf(1, 6, 7, Kinds.TIP_RECEIPT)

    fun matches(event: JsonEvent, me: String): Boolean =
        event.kind in KINDS && event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == me }
}
