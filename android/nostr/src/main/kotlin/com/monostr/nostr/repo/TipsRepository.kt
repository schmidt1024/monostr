package com.monostr.nostr.repo

import com.monostr.monero.Address
import com.monostr.nostr.DetachedSender
import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.RustConvert
import com.monostr.nostr.StartupGate
import com.monostr.nostr.net.NetworkGate
import com.monostr.tips.Kinds
import com.monostr.tips.TipSender
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Payto
import com.monostr.tips.Watcher
import com.monostr.tips.WatcherStatus
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
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** Valid receipts of one note (spec 3.4), newest first, with the sum of the known amounts in piconero; [amountsKnown] is false when a receipt carries none (protocol 0.5). */
data class TipSummary(val total: Long, val count: Int, val receipts: List<TipReceipt>, val amountsKnown: Boolean = true)

/** One valid receipt with the comment of its intent (spec 5.6). */
data class Tipper(val receipt: TipReceipt, val comment: String)

/** A tip intent signed with a one-time key: the publish outcome and the signed event (JSON) for a later resend. */
data class AnonymousIntent(val result: PublishResult, val eventJson: String)

/** The user's own Monero payto entry (kind 10133, NIP-A3) as the relays know it. */
sealed class OwnPayto {
    /** [address]: the Monero entry, or null when the event has none or a completed fetch proved there is no event. */
    data class Known(val address: String?) : OwnPayto()
    /** No local event and the relays did not answer completely: writing now could replace an event we never saw. */
    data object Unknown : OwnPayto()
}

/**
 * What the own relays say about receiving Monero ([TipsRepository.ownMonero]). [FOUND]: a payto entry or a tip address;
 * [NONE]: most relays answered and none has either; [UNKNOWN]: no complete answer.
 */
enum class OwnMonero { FOUND, NONE, UNKNOWN }

/** Outcome of [TipsRepository.setOwnPayto]. */
enum class PaytoWrite { Published, NotPublished, Unknown }

/** What [TipsRepository.fetchWatcherReceipts] did. */
enum class WatcherFetch {
    /** At least one of the relays was asked. */
    Asked,
    /** Nothing to ask: no notes, or every relay is one of the viewer's read relays (asked already). */
    OwnRelaysOnly,
    /** No relay could be held: the foreign-relay budget had no free slot (or none of the relays could be attached). */
    NoSlot,
    /** The lookup failed (a relay or database error, or no relay could be attached). */
    Failed,
}

interface TipsRepository {
    /** The recipient's payment info (kind 10037): local first, relays when missing or older than [maxAgeSeconds]. Malformed ⇒ Disabled. */
    suspend fun paymentInfo(pubkey: String, maxAgeSeconds: Long = 3600): PaymentInfo
    /** The local copy only, no relay round trip: the fallback when the relays are too slow (spec 7.3). */
    suspend fun cachedPaymentInfo(pubkey: String): PaymentInfo

    /**
     * The newest payment info of [pubkey] read over a connection of its own (no session, no AUTH, under Tor its own circuit), for an
     * anonymous tip: asking over the session right before an anonymous intent would let a relay link the two. Stored like a fetched
     * one. Null when no relay answered in time or the route is not known yet; the caller falls back to the cached copy.
     */
    suspend fun paymentInfoUnlinked(pubkey: String): PaymentInfo? = null

    /**
     * Who signs the next intent (tips 0.6: the payment id is derived from this key, so it is chosen before the intent is
     * prepared). Public: the session's key. Anonymous: a fresh one-time key per call, kept only in the returned value.
     */
    suspend fun tipSender(anonymous: Boolean): TipSender

    /**
     * Intent ids of the tip receipts to [noteIds] found on [relays] (a tip's watcher relays) since [since], signed by
     * [watcherPubkey]. The watcher publishes a receipt only to its own relays and the recipient's write relays: a tipper
     * whose relays are elsewhere learns this way that their tip arrived. [detached]: over a connection of its own, nothing
     * stored (an anonymous tip); else over the session, which keeps the receipts like any fetched event.
     */
    suspend fun noteReceiptsOn(relays: List<String>, noteIds: Collection<String>, watcherPubkey: String, since: Long, detached: Boolean): Set<String> = emptySet()

    /**
     * One-shot: the receipts to [noteIds] signed by [watcherPubkey] on [relays] (a note author's watcher relays), and
     * that watcher's retractions of them, stored like any fetch. The viewer's read relays are left out (asked already).
     * See [WatcherFetch] for the outcomes; a closed route (Tor not connected) still counts as [WatcherFetch.Asked]: the
     * fetches then return nothing at once, so callers check whether anything may leave before they ask.
     */
    suspend fun fetchWatcherReceipts(relays: List<String>, noteIds: Collection<String>, watcherPubkey: String): WatcherFetch = WatcherFetch.Failed
    /**
     * Signs and publishes the user's own payment info (spec 3.1). It is kept locally only when a relay
     * accepted it: a copy nobody else can see would still make this device's tip sheet pay to it.
     */
    suspend fun publishPaymentInfo(address: Address, watcher: Watcher, amountsHidden: Boolean): PublishResult
    /** Spec 0.4 §2.2: only [relays], the newest valid status by [watcherPubkey]; null when none or unreachable. */
    suspend fun watcherStatus(watcherPubkey: String, relays: List<String>): WatcherStatus?
    /** The Monero payto entry of [pubkey] from the local kind 10133 only; [paymentInfo] fetches that event alongside. */
    suspend fun cachedPayto(pubkey: String): String?
    /** Pulls the kind 10133 of [pubkey] from [relays] (its own write relays) into the database; never throws except on cancellation. */
    suspend fun fetchPayto(pubkey: String, relays: List<String>)
    /** The own Monero payto entry after one relay fetch of the own kind 10133. */
    suspend fun ownPayto(): OwnPayto
    /**
     * Whether this account already receives Monero, after one relay fetch of the own kinds 10037 and 10133 (the request
     * [paymentInfo] makes for the own key). Only an answer of more than half of the relays counts as [OwnMonero.NONE].
     */
    suspend fun ownMonero(): OwnMonero = OwnMonero.UNKNOWN
    /**
     * Sets (or with null removes) the Monero entry of the own kind 10133, keeping every other tag as it is.
     * Reads like [ownPayto] first: [PaytoWrite.Unknown] and nothing sent when that is unknown; nothing sent and
     * [PaytoWrite.Published] when the entry already is [address]. [address] is expected trimmed.
     */
    suspend fun setOwnPayto(address: String?): PaytoWrite
    /**
     * How many times [setOwnPayto] published a change in this process: a screen that shows the own payto reads again
     * when it changed since its last read, without waiting for its usual pause between reads.
     */
    val ownPaytoWrites: Int get() = 0
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
    suspend fun sendAnonymousIntent(prepared: PreparedTip, watcherRelays: List<String>, torOnly: Boolean = false): AnonymousIntent
    /** Sends an anonymous intent again, the same way: its own connection, [relays] only. */
    suspend fun resendAnonymousIntent(eventJson: String, relays: List<String>, torOnly: Boolean = false): PublishResult
    /** Sends the stored public intent [intentId] to the watcher's [relays] only: the user's own relays may not be the ones the watcher listens to. */
    suspend fun resendIntentToWatcher(intentId: String, relays: List<String>): PublishResult
    /**
     * Valid receipts per note for [notes] = note id → author pubkey. Emits the local state at
     * once, again after one relay fetch, then on every receipt that arrives live. Notes without
     * valid receipts are absent from the map.
     */
    fun observeReceipts(notes: Map<String, String>): Flow<Map<String, TipSummary>>

    /** What [observeReceipts] emits first: the stored valid receipts of [notes], never a relay round trip (spec perf2 A4). */
    suspend fun localReceipts(notes: Map<String, String>): Map<String, TipSummary>

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
    /** The default gate stays unknown, so its sender only ever fails: a real one is passed in, and nothing can go direct by omission. */
    private val detached: DetachedSender = DetachedSender(engine.ffiDispatcher, NetworkGate()),
    private val fetchTimeout: Duration = Duration.ofSeconds(8),
    /** Spec perf2 A3: the receipts' relay part (fetch + subscription) waits until the home feed's first page is in. */
    private val gate: StartupGate = StartupGate.OPEN,
    /**
     * Whether stagenet/testnet payment info counts. Off in release builds: test coins are worthless, and a recipient
     * with a stagenet watcher could otherwise show tip counts and totals as if they were real XMR and be tipped there.
     */
    private val testNetworks: Boolean = true,
    /** The session's budget of foreign relays; without one a fetch of [fetchWatcherReceipts] attaches its relays on its own. */
    private val foreign: ForeignRelays? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : TipsRepository {
    /** One payment info fetch per pubkey at a time, shared by every caller; its stamp is the freshness (spec perf 3.4). */
    private val infoRefresh = RefreshGate<String>(engine.backgroundScope, ttlSeconds = 3600, now = now)

    private fun infoFilter(pubkey: String) = Filter().kind(Kind(Kinds.PAYMENT_INFO.toUShort())).author(PublicKey.parse(pubkey)).limit(1u)

    /** The relay fetch of [paymentInfo]: kind 10037 and the payto kind 10133 in one filter (both replaceable: one each, no limit). */
    private fun infoFetchFilter(pubkey: String) =
        Filter().kinds(listOf(Kind(Kinds.PAYMENT_INFO.toUShort()), Kind(Kinds.PAYTO.toUShort()))).author(PublicKey.parse(pubkey))

    private fun paytoFilter(pubkey: String) = Filter().kind(Kind(Kinds.PAYTO.toUShort())).author(PublicKey.parse(pubkey)).limit(1u)

    private fun receiptFilter(noteIds: Collection<String>) =
        Filter().kind(Kind(Kinds.TIP_RECEIPT.toUShort())).events(noteIds.take(MAX_NOTES).map { EventId.parse(it) })

    /**
     * The watchers' retractions (kind 5) of the stored receipts [filter] finds: a watcher publishes a receipt when it sees
     * the payment and withdraws it when the payment never confirms (protocol spec §3.4); [Withdrawn] then leaves it out.
     */
    private suspend fun fetchRetractions(filter: Filter) {
        val ids = engine.query(filter).map { it.id() }
        for (chunk in ids.chunked(MAX_NOTES)) quietly { engine.fetch(Filter().kind(Kind(5u)).events(chunk), fetchTimeout) }
    }

    override suspend fun paymentInfo(pubkey: String, maxAgeSeconds: Long): PaymentInfo {
        val filter = infoFilter(pubkey)
        val stored = engine.query(filter).firstOrNull()
        val last = infoRefresh.fetchedAt(pubkey)
        // a missing info is cached like a present one: no relay round trip per call for users without Monero
        if (last != null && last + maxAgeSeconds >= now()) return parsePaymentInfo(stored)
        val fetch: suspend () -> Unit = { quietly { engine.fetch(infoFetchFilter(pubkey), fetchTimeout) } }
        // spec perf 3.4: a stored info answers at once and is refreshed behind it; only a forced read (maxAge 0) or a missing info waits
        if (stored != null && maxAgeSeconds > 0) {
            infoRefresh.launch(pubkey, force = true, block = fetch)
            return parsePaymentInfo(stored)
        }
        infoRefresh.await(pubkey, force = true, block = fetch)
        return parsePaymentInfo(engine.query(filter).firstOrNull())
    }

    /** What was asked so far counts as not asked: the answers came from no relay (the engine was held, Tor plan Task 6). */
    fun forgetFetched() = infoRefresh.forget()

    override suspend fun cachedPaymentInfo(pubkey: String): PaymentInfo = parsePaymentInfo(engine.query(infoFilter(pubkey)).firstOrNull())

    override suspend fun paymentInfoUnlinked(pubkey: String): PaymentInfo? {
        val relays = engine.readRelayUrls().take(UNLINKED_RELAYS)
        if (relays.isEmpty()) return null
        val filter = Filter().kind(Kind(Kinds.PAYMENT_INFO.toUShort())).author(PublicKey.parse(pubkey))
        val events = try {
            detached.fetch(filter, relays, UNLINKED_CONNECT, UNLINKED_ANSWER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val newest = events.filter { it.author().toHex() == pubkey && it.kind().asU16().toInt() == Kinds.PAYMENT_INFO }.maxByOrNull { it.createdAt().asSecs() } ?: return null
        // the database keeps the newest of a replaceable kind: a stored newer one stays, and that is what counts
        quietly { engine.save(newest) }
        return parsePaymentInfo(engine.query(infoFilter(pubkey)).firstOrNull() ?: newest)
    }

    override suspend fun cachedPayto(pubkey: String): String? =
        engine.query(paytoFilter(pubkey)).firstOrNull()?.let { Payto.moneroAddress(tipsTags(it)) }

    override suspend fun fetchPayto(pubkey: String, relays: List<String>) {
        if (relays.isEmpty()) return
        quietly {
            // held, not attached by fetchFrom: relays the engine already knows stay, the ones attached here go again;
            // NonCancellable: a cancellation landing on the attach's own return would lose `held` and leak the relays
            val held = withContext(NonCancellable) { engine.attachTemporary(relays) }
            try {
                engine.fetchFrom(relays, paytoFilter(pubkey), fetchTimeout, attach = false)
            } finally {
                engine.detachTemporary(held)
            }
        }
    }

    /** The own kind 10133 after one relay fetch: [event] the newest stored one, [state] what it says. */
    private class OwnPaytoRead(val event: Event?, val state: OwnPayto, val writeOnlyAnswered: Boolean)

    private suspend fun readOwnPayto(): OwnPaytoRead {
        val self = requireNotNull(engine.pubkey) { "payto needs a logged-in user" }
        val filter = paytoFilter(self)
        val fetch = engine.fetchOwn(filter, fetchTimeout)
        // the database keeps the newest version, whether it was stored before or just fetched
        val event = engine.query(filter).firstOrNull()
        val state = when {
            event != null -> OwnPayto.Known(Payto.moneroAddress(tipsTags(event)))
            fetch.completed -> OwnPayto.Known(null)
            else -> OwnPayto.Unknown
        }
        return OwnPaytoRead(event, state, fetch.writeOnlyAnswered)
    }

    override suspend fun ownPayto(): OwnPayto = readOwnPayto().state

    override suspend fun ownMonero(): OwnMonero {
        val self = requireNotNull(engine.pubkey) { "needs a logged-in user" }
        val fetch = engine.fetchOwn(infoFetchFilter(self), fetchTimeout)
        // the database keeps the newest of each, whether stored before or just fetched
        return when {
            cachedPayto(self) != null || cachedPaymentInfo(self) is PaymentInfo.Enabled -> OwnMonero.FOUND
            // a wrong «none» only shows the intro once more, so most relays are enough (one dead relay must not leave it open)
            fetch.mostAnswered -> OwnMonero.NONE
            else -> OwnMonero.UNKNOWN
        }
    }

    override suspend fun setOwnPayto(address: String?): PaytoWrite {
        val read = readOwnPayto()
        // the newest version may live only on a write-only relay (spec 2026-11-05 goal 5)
        if (!read.writeOnlyAnswered) return PaytoWrite.Unknown
        val state = read.state as? OwnPayto.Known ?: return PaytoWrite.Unknown
        if (state.address == address) return PaytoWrite.Published
        val base = read.event
        val tags = if (base == null) emptyList() else tipsTags(base)
        // replaceable: the new event must be strictly newer than the one it replaces
        val createdAt = maxOf(now(), (base?.createdAt()?.asSecs()?.toLong() ?: 0L) + 1)
        val result = engine.signAndPublish(RustConvert.toBuilder(Payto.build(Payto.withMonero(tags, address), createdAt)))
        if (result.sentToAny) paytoWriteCount.incrementAndGet()
        return if (result.sentToAny) PaytoWrite.Published else PaytoWrite.NotPublished
    }

    private val paytoWriteCount = java.util.concurrent.atomic.AtomicInteger()
    override val ownPaytoWrites: Int get() = paytoWriteCount.get()

    /** The tags of [event], read on the FFI dispatcher (rust-nostr call). */
    private suspend fun tipsTags(event: Event): List<List<String>> = withContext(engine.ffiDispatcher) { RustConvert.toTips(event).tags }

    /** [event] as payment info; missing or malformed ⇒ Disabled. `toTips` is a rust-nostr call: on the FFI dispatcher. */
    private suspend fun parsePaymentInfo(event: Event?): PaymentInfo {
        if (event == null) return PaymentInfo.Disabled
        val info = withContext(engine.ffiDispatcher) { PaymentInfo.parse(RustConvert.toTips(event)) }
        return if (!testNetworks && info is PaymentInfo.Enabled && info.network != com.monostr.monero.Network.MAINNET) PaymentInfo.Disabled else info
    }

    /** When the payment info of [pubkey] was last fetched from relays in this process; null if never (for tests). */
    internal fun lastFetchAt(pubkey: String): Long? = infoRefresh.fetchedAt(pubkey)

    override suspend fun publishPaymentInfo(address: Address, watcher: Watcher, amountsHidden: Boolean): PublishResult =
        engine.signAndPublish(RustConvert.toBuilder(PaymentInfo.build(address, watcher, now(), amountsHidden)))

    override suspend fun watcherStatus(watcherPubkey: String, relays: List<String>): WatcherStatus? {
        if (relays.isEmpty()) return null
        return try {
            val filter = Filter().kind(Kind(Kinds.WATCHER_STATUS.toUShort())).author(PublicKey.parse(watcherPubkey)).limit(5u)
            // NonCancellable: a cancellation landing on the attach's own return would lose `held` and leak the relays
            val held = withContext(NonCancellable) { engine.attachTemporary(relays) }
            try {
                val events = engine.fetchFrom(relays, filter, attach = false)
                withContext(engine.ffiDispatcher) {
                    events.map { RustConvert.toTips(it) }
                        .filter { it.pubkey == watcherPubkey }
                        .mapNotNull { WatcherStatus.parse(it) }
                        .maxByOrNull { it.createdAt }
                }
            } finally {
                engine.detachTemporary(held)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun disablePaymentInfo(): PublishResult = engine.signAndPublish(RustConvert.toBuilder(PaymentInfo.buildDisabled(now())))

    override suspend fun tipSender(anonymous: Boolean): TipSender = if (!anonymous) {
        TipSender(requireNotNull(engine.pubkey) { "a public tip needs a logged-in user" })
    } else {
        withContext(engine.ffiDispatcher) { Keys.generate().let { TipSender(it.publicKey().toHex(), it.secretKey().toHex()) } }
    }

    override suspend fun noteReceiptsOn(relays: List<String>, noteIds: Collection<String>, watcherPubkey: String, since: Long, detached: Boolean): Set<String> {
        if (relays.isEmpty() || noteIds.isEmpty()) return emptySet()
        val filter = Filter().kind(Kind(Kinds.TIP_RECEIPT.toUShort())).events(noteIds.take(MAX_NOTES).map { EventId.parse(it) })
            .author(PublicKey.parse(watcherPubkey)).since(Timestamp.fromSecs(since.coerceAtLeast(0).toULong()))
        val events = try {
            if (detached) {
                this.detached.fetch(filter, relays, UNLINKED_CONNECT, UNLINKED_ANSWER)
            } else {
                // held, not fetchFrom's own attach (see AuthorRelaysRepository.fetchNotes)
                val held = withContext(NonCancellable) { engine.attachTemporary(relays) }
                try {
                    engine.fetchFrom(relays, filter, fetchTimeout, attach = false)
                } finally {
                    withContext(NonCancellable) { engine.detachTemporary(held) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptySet()
        }
        return withContext(engine.ffiDispatcher) {
            events.mapNotNull { TipReceipt.parse(RustConvert.toTips(it)) }
                .filter { it.watcherPubkey == watcherPubkey && it.noteId in noteIds }
                .map { it.intentId }
                .toSet()
        }
    }

    override suspend fun fetchWatcherReceipts(relays: List<String>, noteIds: Collection<String>, watcherPubkey: String): WatcherFetch {
        val ids = noteIds.distinct()
        if (ids.isEmpty() || relays.isEmpty()) return WatcherFetch.OwnRelaysOnly
        try {
            val own = engine.readRelayUrls().map { it.trimEnd('/') }.toSet()
            val rest = relays.map { it.trim().trimEnd('/') }.filter { it !in own }.distinct()
            if (rest.isEmpty()) return WatcherFetch.OwnRelaysOnly
            // NonCancellable: a cancellation landing on the lease's own return would lose `held` and leak the relays
            val held = withContext(NonCancellable) { foreign?.lease(rest) ?: engine.attachTemporary(rest) }
            if (held.isEmpty()) return if (foreign != null) WatcherFetch.NoSlot else WatcherFetch.Failed
            try {
                val author = PublicKey.parse(watcherPubkey)
                for (chunk in ids.chunked(MAX_NOTES)) {
                    val filter = Filter().kind(Kind(Kinds.TIP_RECEIPT.toUShort())).events(chunk.map { EventId.parse(it) }).author(author)
                    quietly { engine.fetchFrom(held, filter, fetchTimeout, attach = false) }
                    val stored = engine.query(filter).map { it.id() }
                    for (retractable in stored.chunked(MAX_NOTES)) {
                        quietly { engine.fetchFrom(held, Filter().kind(Kind(5u)).author(author).events(retractable), fetchTimeout, attach = false) }
                    }
                }
                return WatcherFetch.Asked
            } finally {
                withContext(NonCancellable) { if (foreign != null) foreign.release(held) else engine.detachTemporary(held) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return WatcherFetch.Failed
        }
    }

    override suspend fun sendIntent(prepared: PreparedTip, watcherRelays: List<String>): PublishResult {
        require(!prepared.intent.anonymous) { "an anonymous intent must not go out under the user's key" }
        // the pid was derived from this key: signed by another (an account switch since prepare) it would be invalid
        require(prepared.sender.secretHex == null && prepared.sender.pubkey == engine.pubkey) { "the intent was prepared for another key" }
        if (watcherRelays.isEmpty()) return engine.signAndSend(prepared.unsignedEvent)
        val watcherStart = System.nanoTime()
        // the watcher's relays first: see NostrEngine.signAndSendFirstTo
        val (own, more) = engine.signAndSendFirstTo(prepared.unsignedEvent, watcherRelays)
        if (com.monostr.nostr.PerfTrace.enabled) com.monostr.nostr.PerfTrace.mark("tip intent to watcher relays ${(System.nanoTime() - watcherStart) / 1_000_000}ms, ok=${more.successRelays} failed=${more.failedRelays}")
        return PublishResult(own.eventId, own.kind, (own.successRelays + more.successRelays).distinct(), own.failedRelays + more.failedRelays)
    }

    override suspend fun sendAnonymousIntent(prepared: PreparedTip, watcherRelays: List<String>, torOnly: Boolean): AnonymousIntent {
        require(watcherRelays.isNotEmpty()) { "an anonymous intent needs the watcher's relays" }
        require(prepared.intent.anonymous) { "the intent is not marked anonymous" }
        val secret = requireNotNull(prepared.sender.secretHex) { "an anonymous intent needs its one-time key" }
        // the one-time key from tipSender, never stored: nothing links two anonymous tips to each other or to the user,
        // and the pid was derived from exactly this key (tips 0.6)
        val (event, json) = withContext(engine.ffiDispatcher) {
            val signed = RustConvert.toBuilder(prepared.unsignedEvent).signWithKeys(Keys.parse(secret))
            signed to signed.asJson()
        }
        return AnonymousIntent(detached.send(event, watcherRelays, torOnly = torOnly), json)
    }

    override suspend fun resendAnonymousIntent(eventJson: String, relays: List<String>, torOnly: Boolean): PublishResult {
        val event = withContext(engine.ffiDispatcher) { Event.fromJson(eventJson) }
        return detached.send(event, relays, torOnly = torOnly)
    }

    override suspend fun resendIntentToWatcher(intentId: String, relays: List<String>): PublishResult = engine.sendTo(relays, intentId)

    override suspend fun localReceipts(notes: Map<String, String>): Map<String, TipSummary> =
        if (notes.isEmpty()) emptyMap() else withContext(Dispatchers.IO) { summaries(notes) }

    override fun observeReceipts(notes: Map<String, String>): Flow<Map<String, TipSummary>> = flow {
        if (notes.isEmpty()) {
            emit(emptyMap())
            return@flow
        }
        val filter = receiptFilter(notes.keys)
        emit(summaries(notes))
        gate.await()
        quietly { engine.fetch(filter) }
        emit(summaries(notes))
        // after the receipts are shown: one more round trip, not a later first paint
        fetchRetractions(filter)
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
            engine.incoming
                .filter { inc -> inc.json.kind == Kinds.TIP_RECEIPT && inc.json.tagValues("e").any { it in notes } }
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
        fetchRetractions(filter)
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
            engine.incoming
                .filter { inc -> inc.json.kind == Kinds.TIP_RECEIPT && recipient in inc.json.tagValues("p") }
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
        // a caller's retry only comes back here: nothing is tried, nothing fails, until the route is known
        detached.awaitKnownMode()
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
        validProfileIntentIds(Withdrawn.filter(engine, engine.query(filter)), recipient, info)

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
        val intentIds = summary.receipts.map { it.intentId }.distinct()
        for ((ids, filter) in intentIds.chunked(INTENT_CHUNK).zip(intentFilters(intentIds))) {
            var chunk = engine.query(filter)
            if (chunk.size < filter.idCount()) {
                // spec wave 2 B4: the sheet fills from the first relays that have every intent
                quietly { engine.fetch(filter, exit = Exit.AllIds(ids.toSet())) }
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
        val receipts = Withdrawn.filter(engine, engine.query(receiptFilter(notes.keys)))
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
            TipSummary(ReceiptValidator.total(sorted), sorted.size, sorted, sorted.all { it.amount != null })
        }
    }

    companion object {
        /** [paymentInfoUnlinked]: each relay is a connection of its own, so only a few of the read relays are asked. */
        const val UNLINKED_RELAYS = 4
        private val UNLINKED_CONNECT: Duration = Duration.ofMillis(1_500)
        private val UNLINKED_ANSWER: Duration = Duration.ofMillis(1_200)

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
