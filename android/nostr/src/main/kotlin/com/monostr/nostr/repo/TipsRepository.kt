package com.monostr.nostr.repo

import com.monostr.monero.Address
import com.monostr.nostr.DetachedSender
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.RustConvert
import com.monostr.tips.Kinds
import com.monostr.tips.PaymentInfo
import com.monostr.tips.PreparedTip
import com.monostr.tips.ReceiptValidator
import com.monostr.tips.TipIntent
import com.monostr.tips.TipReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp

/** Valid receipts of one note (spec 3.4), newest first, with their sum in piconero. */
data class TipSummary(val total: Long, val count: Int, val receipts: List<TipReceipt>)

/** One valid receipt with the comment of its intent (spec 5.6). */
data class Tipper(val receipt: TipReceipt, val comment: String)

/** A tip intent signed with a one-time key: the publish outcome and the signed event (JSON) for a later resend. */
data class AnonymousIntent(val result: PublishResult, val eventJson: String)

interface TipsRepository {
    /** The recipient's payment info (kind 10037): local first, relays when missing or older than [maxAgeSeconds]. Malformed ⇒ Disabled. */
    suspend fun paymentInfo(pubkey: String, maxAgeSeconds: Long = 3600): PaymentInfo
    /** The local copy only, no relay round trip: the fallback when the relays are too slow (spec 7.3). */
    suspend fun cachedPaymentInfo(pubkey: String): PaymentInfo
    /**
     * Signs and publishes the user's own payment info (spec 3.1). It is kept locally only when a relay
     * accepted it: a copy nobody else can see would still make this device's tip sheet pay to it.
     */
    suspend fun publishPaymentInfo(address: Address, watcherUrl: String, watcherPubkey: String): PublishResult
    /** Publishes an address-less payment info: "no Monero" (spec 6.5). */
    suspend fun disablePaymentInfo(): PublishResult
    /** Signs, stores and publishes a tip intent to the user's relays and to [watcherRelays] (spec 3.2). */
    suspend fun sendIntent(prepared: PreparedTip, watcherRelays: List<String>): PublishResult
    /**
     * Signs [prepared] with a fresh one-time key and sends it over its own connection to
     * [watcherRelays] only (protocol 0.2, `anon`). The event is not stored in the local database
     * and never touches the user's own relay connection; the caller keeps [AnonymousIntent.eventJson]
     * for [resendAnonymousIntent]. Throws [IllegalArgumentException] without relays.
     */
    suspend fun sendAnonymousIntent(prepared: PreparedTip, watcherRelays: List<String>): AnonymousIntent
    /** Sends an anonymous intent again, the same way: its own connection, [relays] only. */
    suspend fun resendAnonymousIntent(eventJson: String, relays: List<String>): PublishResult
    /**
     * Valid receipts per note for [notes] = note id → author pubkey. Emits the local state at
     * once, again after one relay fetch, then on every receipt that arrives live. Notes without
     * valid receipts are absent from the map.
     */
    fun observeReceipts(notes: Map<String, String>): Flow<Map<String, TipSummary>>

    /**
     * Intent ids of the valid profile receipts (no `e`; with or without a sender) for [recipient]
     * since [since] (unix seconds): local state at once, again after one relay fetch, then on every
     * receipt that arrives live. The caller matches the ids against its own pending intents, which
     * works for anonymous tips too. Empty without valid payment info.
     */
    fun observeProfileReceipts(recipient: String, since: Long): Flow<Set<String>>

    /**
     * Like [observeProfileReceipts], for the sender's own anonymous profile tips: a live
     * subscription over a connection of its own to [relays] only, so the receipt shows the moment
     * the watcher publishes it. Asking over the user's connection would tell the relay who is
     * waiting for that receipt. Uses the locally stored payment info only (no relay round trip over
     * the user's connection either); without one the flow ends without asking anybody. Nothing it
     * sees is stored.
     */
    fun observeAnonymousProfileReceipts(recipient: String, since: Long, relays: List<String>): Flow<Set<String>>
    /** Valid receipts of one note with the intent comments, newest first. */
    suspend fun tippers(noteId: String, author: String): List<Tipper>
}

class NostrTipsRepository(
    private val engine: NostrEngine,
    private val detached: DetachedSender = DetachedSender(engine.ffiDispatcher),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : TipsRepository {
    private val infoFetchedAt = HashMap<String, Long>()
    private val mutex = Mutex()

    private fun infoFilter(pubkey: String) = Filter().kind(Kind(Kinds.PAYMENT_INFO.toUShort())).author(PublicKey.parse(pubkey)).limit(1u)

    private fun receiptFilter(noteIds: Collection<String>) =
        Filter().kind(Kind(Kinds.TIP_RECEIPT.toUShort())).events(noteIds.take(MAX_NOTES).map { EventId.parse(it) })

    override suspend fun paymentInfo(pubkey: String, maxAgeSeconds: Long): PaymentInfo {
        val filter = infoFilter(pubkey)
        val last = mutex.withLock { infoFetchedAt[pubkey] ?: 0L }
        var event = engine.query(filter).firstOrNull()
        // a missing info is cached like a present one: no relay round trip per call for users without Monero
        if ((event == null && last == 0L) || last + maxAgeSeconds < now()) {
            quietly { engine.fetch(filter) }
            mutex.withLock { infoFetchedAt[pubkey] = now() }
            event = engine.query(filter).firstOrNull()
        }
        return parsePaymentInfo(event)
    }

    override suspend fun cachedPaymentInfo(pubkey: String): PaymentInfo = parsePaymentInfo(engine.query(infoFilter(pubkey)).firstOrNull())

    /** [event] as payment info; missing or malformed ⇒ Disabled. `toTips` is a rust-nostr call: on the FFI dispatcher. */
    private suspend fun parsePaymentInfo(event: Event?): PaymentInfo =
        if (event == null) PaymentInfo.Disabled else withContext(engine.ffiDispatcher) { PaymentInfo.parse(RustConvert.toTips(event)) }

    /** When the payment info of [pubkey] was last fetched from relays; null if never (for tests). */
    internal suspend fun lastFetchAt(pubkey: String): Long? = mutex.withLock { infoFetchedAt[pubkey] }

    override suspend fun publishPaymentInfo(address: Address, watcherUrl: String, watcherPubkey: String): PublishResult =
        engine.signAndPublish(RustConvert.toBuilder(PaymentInfo.build(address, watcherUrl, watcherPubkey, now())))

    override suspend fun disablePaymentInfo(): PublishResult = engine.signAndPublish(RustConvert.toBuilder(PaymentInfo.buildDisabled(now())))

    override suspend fun sendIntent(prepared: PreparedTip, watcherRelays: List<String>): PublishResult {
        require(!prepared.intent.anonymous) { "an anonymous intent must not go out under the user's key" }
        val own = engine.signAndSend(prepared.unsignedEvent)
        if (watcherRelays.isEmpty()) return own
        val more = engine.sendTo(watcherRelays, own.eventId)
        return PublishResult(own.eventId, own.kind, (own.successRelays + more.successRelays).distinct(), own.failedRelays + more.failedRelays)
    }

    override suspend fun sendAnonymousIntent(prepared: PreparedTip, watcherRelays: List<String>): AnonymousIntent {
        require(watcherRelays.isNotEmpty()) { "an anonymous intent needs the watcher's relays" }
        require(prepared.intent.anonymous) { "the intent is not marked anonymous" }
        // a fresh key per tip, never stored: nothing links two anonymous tips to each other or to the user
        val (event, json) = withContext(engine.ffiDispatcher) {
            val signed = RustConvert.toBuilder(prepared.unsignedEvent).signWithKeys(Keys.generate())
            signed to signed.asJson()
        }
        return AnonymousIntent(detached.send(event, watcherRelays), json)
    }

    override suspend fun resendAnonymousIntent(eventJson: String, relays: List<String>): PublishResult {
        val event = withContext(engine.ffiDispatcher) { Event.fromJson(eventJson) }
        return detached.send(event, relays)
    }

    override fun observeReceipts(notes: Map<String, String>): Flow<Map<String, TipSummary>> = flow {
        if (notes.isEmpty()) {
            emit(emptyMap())
            return@flow
        }
        val filter = receiptFilter(notes.keys)
        emit(summaries(notes))
        quietly { engine.fetch(filter) }
        emit(summaries(notes))
        // best-effort: without relays the local receipts stay and no live updates arrive
        val subId = try {
            engine.subscribe(filter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            engine.events
                .filter { ev -> ev.kind().asU16().toInt() == Kinds.TIP_RECEIPT && ev.tags().eventIds().any { it.toHex() in notes } }
                .conflate()
                .collect { emit(summaries(notes)) }
        } finally {
            if (subId != null) withContext(NonCancellable) { quietly { engine.unsubscribe(subId) } }
        }
    }.flowOn(Dispatchers.IO)

    override fun observeProfileReceipts(recipient: String, since: Long): Flow<Set<String>> = flow {
        val info = paymentInfo(recipient)
        if (info !is PaymentInfo.Enabled) {
            emit(emptySet())
            return@flow
        }
        val filter = profileReceiptFilter(recipient, info, since)
        emit(profileIntentIds(filter, recipient, info))
        quietly { engine.fetch(filter) }
        emit(profileIntentIds(filter, recipient, info))
        // best-effort: without relays the local receipts stay and no live updates arrive
        val subId = try {
            engine.subscribe(filter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            engine.events
                .filter { ev -> ev.kind().asU16().toInt() == Kinds.TIP_RECEIPT && ev.tags().publicKeys().any { it.toHex() == recipient } }
                .conflate()
                .collect { emit(profileIntentIds(filter, recipient, info)) }
        } finally {
            if (subId != null) withContext(NonCancellable) { quietly { engine.unsubscribe(subId) } }
        }
    }.flowOn(Dispatchers.IO)

    override fun observeAnonymousProfileReceipts(recipient: String, since: Long, relays: List<String>): Flow<Set<String>> = flow {
        // the local copy only: paymentInfo() may ask the relays over the user's connection
        val info = cachedPaymentInfo(recipient)
        if (info !is PaymentInfo.Enabled) return@flow
        val filter = withContext(engine.ffiDispatcher) { profileReceiptFilter(recipient, info, since) }
        val confirmed = LinkedHashSet<String>()
        // over a connection of its own, and never saved into the engine database
        detached.subscribe(filter, relays).collect { event ->
            val ids = withContext(engine.ffiDispatcher) { validProfileIntentIds(listOf(event), recipient, info) }
            if (confirmed.addAll(ids)) emit(confirmed.toSet())
        }
    }

    private fun profileReceiptFilter(recipient: String, info: PaymentInfo.Enabled, since: Long): Filter =
        Filter().kind(Kind(Kinds.TIP_RECEIPT.toUShort()))
            .author(PublicKey.parse(info.watcherPubkey))
            .pubkey(PublicKey.parse(recipient))
            .since(Timestamp.fromSecs(since.coerceAtLeast(0).toULong()))
            .limit(PROFILE_RECEIPTS.toULong())

    private suspend fun profileIntentIds(filter: Filter, recipient: String, info: PaymentInfo): Set<String> =
        validProfileIntentIds(engine.query(filter), recipient, info)

    /** Intent ids of the valid profile receipts (no `e`) to [recipient] among [events]. */
    private fun validProfileIntentIds(events: List<Event>, recipient: String, info: PaymentInfo): Set<String> =
        events
            .mapNotNull { TipReceipt.parse(RustConvert.toTips(it)) }
            .filter { it.noteId == null && ReceiptValidator.isValid(it, recipient, info) }
            .map { it.intentId }
            .toSet()

    override suspend fun tippers(noteId: String, author: String): List<Tipper> {
        val summary = summaries(mapOf(noteId to author))[noteId] ?: return emptyList()
        val intents = HashMap<String, Event>()
        for (filter in intentFilters(summary.receipts.map { it.intentId }.distinct())) {
            var chunk = engine.query(filter)
            if (chunk.size < filter.idCount()) {
                quietly { engine.fetch(filter) }
                chunk = engine.query(filter)
            }
            chunk.forEach { intents[it.id().toHex()] = it }
        }
        return summary.receipts.map { r ->
            Tipper(r, intents[r.intentId]?.let { TipIntent.parse(RustConvert.toTips(it)) }?.comment ?: "")
        }
    }

    /** Local receipts for [notes], validated per spec 3.4 against each author's payment info. A receipt without `e` (a profile tip) counts for no note. */
    private suspend fun summaries(notes: Map<String, String>): Map<String, TipSummary> {
        val receipts = engine.query(receiptFilter(notes.keys))
            .mapNotNull { TipReceipt.parse(RustConvert.toTips(it)) }
            .mapNotNull { r -> r.noteId?.takeIf { it in notes }?.let { it to r } }
        if (receipts.isEmpty()) return emptyMap()
        val infos = HashMap<String, PaymentInfo>()
        val valid = receipts.filter { (noteId, r) ->
            val author = notes.getValue(noteId)
            val info = infos.getOrPut(author) { paymentInfo(author) }
            ReceiptValidator.isValid(r, author, info)
        }
        return valid.groupBy({ it.first }, { it.second }).mapValues { (_, rs) ->
            val sorted = rs.sortedByDescending { it.createdAt }
            TipSummary(ReceiptValidator.total(sorted), sorted.size, sorted)
        }
    }

    companion object {
        /** Relays cap filter ids; a well-tipped note is fetched in slices of this many intents. */
        const val INTENT_CHUNK = 50

        /** One `ids` filter per [INTENT_CHUNK] intent ids, in receipt order; empty for no ids. */
        fun intentFilters(intentIds: List<String>): List<Filter> =
            intentIds.chunked(INTENT_CHUNK).map { ids -> Filter().ids(ids.map { EventId.parse(it) }) }

        private fun Filter.idCount(): Int = asRecord().ids?.size ?: 0

        /** Relays cap filter sizes; a screen never shows more notes than this at once. */
        const val MAX_NOTES = 100

        /** Receipts asked for while a profile tip is pending: a short window, never the recipient's whole history. */
        const val PROFILE_RECEIPTS = 100
    }
}
