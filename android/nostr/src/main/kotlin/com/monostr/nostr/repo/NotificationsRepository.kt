package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.RustConvert
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import java.time.Duration

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
    /** The notes [ids] name: local first, the rest in one relay request; never throws; every id is a key. */
    suspend fun notes(ids: Set<String>): Map<String, AboutNote>
    /** Those of [ids] that are the user's own stored notes or reposts (local only, never throws): what a like or repost may call "your note". */
    suspend fun own(ids: Set<String>): Set<String>
    /** Pulls items from relays into the database; [since] limits the fetch (unix seconds). */
    suspend fun refresh(since: Long? = null, limit: Int = 100)
    /** Local list, then refreshed once from relays, then re-emitted on every matching live event. */
    fun live(limit: Int = 100): Flow<List<NotificationItem>>
}

/** kind 1 / 6 / 7 / 9739 with `#p` = the user (spec 5.7, 11-C); receipts are validated against the user's own payment info. */
class NostrNotificationsRepository(
    private val engine: NostrEngine,
    private val tips: TipsRepository,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : NotificationsRepository {
    private val me = requireNotNull(engine.pubkey) { "notifications need a logged-in user" }

    private fun filter(limit: Int, since: Long?): Filter {
        var f = Filter().kinds(KINDS.map { Kind(it.toUShort()) }).pubkey(PublicKey.parse(me)).limit(limit.toULong())
        if (since != null) f = f.since(Timestamp.fromSecs(since.toULong()))
        return f
    }

    override suspend fun list(limit: Int): List<NotificationItem> = withContext(Dispatchers.IO) {
        val ownInfo = tips.paymentInfo(me)
        // NIP-09 for replies, quotes, mentions and reposts the database could not refuse (spec 11-C §2.3)
        Withdrawn.filter(engine, engine.query(filter(limit, null))).mapNotNull { map(it, ownInfo) }.sortedByDescending { it.createdAt }
    }

    /** The last [MEMO_CAPACITY] found or withdrawn notes; a missing one is asked for again next time. */
    private val memo = object : LinkedHashMap<String, AboutNote>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AboutNote>?): Boolean = size > MEMO_CAPACITY
    }
    private val memoLock = Any()

    override suspend fun own(ids: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        val valid = ids.filter { HEX64.matches(it) }
        if (valid.isEmpty()) return@withContext emptySet()
        try {
            engine.query(Filter().ids(valid.map { EventId.parse(it) }).author(PublicKey.parse(me)).kinds(listOf(Kind(1u), Kind(6u))))
                .map { it.id().toHex() }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }

    override suspend fun notes(ids: Set<String>): Map<String, AboutNote> = withContext(Dispatchers.IO) {
        val result = HashMap<String, AboutNote>()
        // ids come from event tags: a malformed one is Missing, never a parse exception
        val wanted = ids.filter { HEX64.matches(it) }.filter { id -> synchronized(memoLock) { memo[id] }?.also { result[id] = it } == null }
        if (wanted.isEmpty()) {
            for (id in ids) result.putIfAbsent(id, AboutNote.Missing)
            return@withContext result
        }
        readStored(wanted, result)
        val missing = wanted.filter { it !in result }
        if (missing.isNotEmpty()) {
            quietly { engine.fetch(Filter().ids(missing.map { EventId.parse(it) })) }
            readStored(missing, result)
        }
        for (id in ids) result.putIfAbsent(id, AboutNote.Missing)
        synchronized(memoLock) { result.forEach { (id, note) -> if (note != AboutNote.Missing) memo[id] = note } }
        result
    }

    /**
     * Stored notes (kind 1, or a kind 6 — a like of my repost names the repost) of [ids] into [into]: found, or
     * withdrawn when the author's kind 5 names them. A failing read leaves the ids out (they end up Missing): the
     * list must not die of one bad lookup.
     */
    private suspend fun readStored(ids: List<String>, into: MutableMap<String, AboutNote>) {
        try {
            val stored = engine.query(Filter().ids(ids.map { EventId.parse(it) }).kinds(listOf(Kind(1u), Kind(6u))))
            if (stored.isEmpty()) return
            val kept = Withdrawn.filter(engine, stored).map { it.id().toHex() }.toSet()
            for (ev in stored) {
                val id = ev.id().toHex()
                into[id] = if (id in kept) NoteMapper.note(ev, resolveRepost = { rid -> stored.firstOrNull { it.id().toHex() == rid } })?.let { AboutNote.Found(it) } ?: AboutNote.Missing else AboutNote.Withdrawn
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // leave the ids missing
        }
    }

    override suspend fun refresh(since: Long?, limit: Int) {
        quietly { engine.fetch(filter(limit, since)) }
        // a deletion request (un-like, un-repost, withdrawn reply) carries no `#p`: asked for by the ids held
        val ids = engine.query(filter(limit, since)).filter { it.kind().asU16().toInt() != Kinds.TIP_RECEIPT }.map { it.id() }
        for (chunk in ids.chunked(100)) quietly { engine.fetch(Filter().kind(Kind(5u)).events(chunk), DELETIONS_TIMEOUT) }
    }

    override fun live(limit: Int): Flow<List<NotificationItem>> = flow {
        val local = list(limit)
        if (local.isNotEmpty()) emit(local) // an empty local list would only flash "no notifications" before the fetch
        refresh(null, limit)
        emit(list(limit))
        val subId = try {
            engine.subscribe(filter(limit, now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            engine.events
                .filter { ev -> ev.kind().asU16().toInt() in KINDS && ev.tags().publicKeys().any { it.toHex() == me } }
                .conflate()
                .collect { emit(list(limit)) }
        } finally {
            if (subId != null) withContext(NonCancellable) { quietly { engine.unsubscribe(subId) } }
        }
    }.flowOn(Dispatchers.IO)

    /** Maps one event; the user's own events and receipts failing spec 3.4 yield null. */
    internal fun map(event: Event, ownInfo: PaymentInfo): NotificationItem? {
        val author = event.author().toHex()
        if (author == me) return null
        val id = event.id().toHex()
        val at = event.createdAt().asSecs().toLong()
        val tags = event.tags().toVec().map { it.asVec() }
        return when (event.kind().asU16().toInt()) {
            1 -> {
                val refs = Nip10.parse(tags)
                val quoted = tags.firstOrNull { it.size >= 2 && it[0] == "q" && HEX64.matches(it[1]) }?.get(1)
                when {
                    refs.replyToId != null -> NotificationItem(id, NotificationKind.REPLY, author, at, id, refs.replyToId, event.content(), null)
                    quoted != null -> NotificationItem(id, NotificationKind.QUOTE, author, at, id, quoted, event.content(), null)
                    else -> NotificationItem(id, NotificationKind.MENTION, author, at, id, null, event.content(), null)
                }
            }
            6 -> {
                // NIP-18: the original is the last `e` tag (a relay-hint `e` may precede it)
                val target = tags.lastOrNull { it.size >= 2 && it[0] == "e" && HEX64.matches(it[1]) }?.get(1) ?: return null
                NotificationItem(id, NotificationKind.REPOST, author, at, target, target, "", null)
            }
            7 -> {
                // NIP-25: "-" is a dislike, not something to tell the user about
                if (event.content().trim() == "-") return null
                // tags are relay input: only a well-formed id is a target (EventId.parse would throw on the rest)
                val target = tags.lastOrNull { it.size >= 2 && it[0] == "e" && HEX64.matches(it[1]) }?.get(1) ?: return null
                NotificationItem(id, NotificationKind.REACTION, author, at, target, target, event.content(), null)
            }
            Kinds.TIP_RECEIPT -> {
                val receipt = TipReceipt.parse(RustConvert.toTips(event)) ?: return null
                if (!ReceiptValidator.isValid(receipt, me, ownInfo)) return null
                NotificationItem(id, NotificationKind.TIP, receipt.senderPubkey, at, receipt.noteId, receipt.noteId, "", receipt.amount)
            }
            else -> null
        }
    }

    private companion object {
        val KINDS = listOf(1, 6, 7, Kinds.TIP_RECEIPT)
        const val MEMO_CAPACITY = 200
        /** Deletion requests are rare: a short wait keeps the refresh from doubling on a slow relay. */
        val DELETIONS_TIMEOUT: Duration = Duration.ofSeconds(3)
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}
