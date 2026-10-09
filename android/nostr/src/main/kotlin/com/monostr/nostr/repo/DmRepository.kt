package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnwrappedGift
import rust.nostr.sdk.giftWrap
import java.time.Duration

/** One decrypted NIP-17 message as it left the wrap. `peer` is always the other party. */
data class DmIncoming(val rumorId: String, val peer: String, val outgoing: Boolean, val content: String, val createdAt: Long, val wrapId: String)
/**
 * Outcome of opening one gift wrap. [Locked]: the signer could not decrypt it now (no silent
 * answer, declined, no NIP-44) and may later. [Rejected]: it will never yield a message (not a
 * NIP-44 payload for our key, rumor kind != 14, foreign p, missing or forged id, impersonation).
 */
sealed class Unwrap {
    data class Ok(val message: DmIncoming) : Unwrap()
    data object Locked : Unwrap()
    data object Rejected : Unwrap()
}

/**
 * One wrap fetch. [completed]: the fetch returned before its timeout (every relay answered) and no
 * own relay sent CLOSED meanwhile; only then does [wraps] cover the whole window, and only then may
 * the caller move its sync watermark past it. [authRefused]: own relays whose CLOSED asked for AUTH,
 * each with the engine's sequence of that CLOSED ([NostrEngine.authOkSeq] counts on the same
 * counter), so a caller can tell a refusal an AUTH OK has already healed.
 */
data class WrapFetch(val wraps: List<Event>, val completed: Boolean, val authRefused: Map<String, Long> = emptyMap())

data class DmSendResult(val rumorId: String, val createdAt: Long, val sentToPeer: Boolean, val sentToSelf: Boolean)

interface DmRepository {
    /** Builds the rumor once, wraps it for the peer and for ourselves, sends each wrap to its relays. */
    suspend fun send(peer: String, text: String, peerRelays: List<String>, ownRelays: List<String>): DmSendResult
    /** Unwraps a kind 1059 addressed to us. [silent] never prompts the signer. Never throws except on cancellation; see [Unwrap] for the outcomes. */
    suspend fun unwrap(wrap: Event, silent: Boolean): Unwrap
    /**
     * kind 1059 with #p = me since [since] from [relays] (stored in the DB by the engine).
     * NIP-59 randomises a gift wrap's `created_at` up to ~2 days into the past, so callers must
     * pass [since] already reduced by that window (e.g. last sync time minus 2 days) or a wrap
     * sent after the last sync can still be missed.
     */
    suspend fun fetchWraps(relays: List<String>, since: Long): WrapFetch
    /** Live subscription for the same filter on [relays] (see [fetchWraps] for the [since] margin); returns the subscription id. */
    suspend fun subscribeWraps(relays: List<String>, since: Long): String
    suspend fun unsubscribe(id: String)
    /** Wraps (kind 1059, #p me) delivered by any subscription. */
    fun incomingWraps(): Flow<Event>
}

/** NIP-17 over rust-nostr: rumor (kind 14) → seal (13, our signer) → gift wrap (1059, random key), spec 3.1/3.2. */
class NostrDmRepository(
    private val engine: NostrEngine,
    private val fetchTimeout: Duration = Duration.ofSeconds(10),
    /** How long a fetch an own relay refused for AUTH waits for that relay's AUTH OK before it asks again. */
    private val authWait: Duration = Duration.ofSeconds(5),
) : DmRepository {
    private val me: String get() = requireNotNull(engine.pubkey) { "DMs need a signer" }

    override suspend fun send(peer: String, text: String, peerRelays: List<String>, ownRelays: List<String>): DmSendResult {
        val signer = requireNotNull(engine.nostrSigner)
        val receiver = PublicKey.parse(peer)
        val rumor = EventBuilder.privateMsgRumor(receiver, text).build(PublicKey.parse(me))
        val (forPeer, forSelf) = withContext(engine.ffiDispatcher) {
            giftWrap(signer, receiver, rumor, emptyList()) to giftWrap(signer, PublicKey.parse(me), rumor, emptyList())
        }
        engine.save(forPeer); engine.save(forSelf)
        val toPeer = engine.sendTo(peerRelays, forPeer.id().toHex())
        val toSelf = engine.sendTo(ownRelays, forSelf.id().toHex())
        return DmSendResult(rumor.id()!!.toHex(), rumor.createdAt().asSecs().toLong(), toPeer.sentToAny, toSelf.sentToAny)
    }

    override suspend fun unwrap(wrap: Event, silent: Boolean): Unwrap {
        if (wrap.kind().asU16().toInt() != 1059) return Unwrap.Rejected
        // Set by the signer when decryption failed on its side (not on the payload): only then may
        // the same wrap open later, everything else is final.
        var locked = false
        val signer = engine.unwrapSigner(silent) { locked = true } ?: return Unwrap.Locked
        // Everything past decryption (rumor()/sender(), tag parsing, the id recompute below) stays
        // inside this try: a malicious wrap can make any of those native calls throw, and that must
        // still yield an outcome rather than propagating out of unwrap (which would poison a sync
        // loop that re-hits a stored wrap forever, task-4 review round 1).
        return try {
            val gift = withContext(engine.ffiDispatcher) { UnwrappedGift.fromGiftWrap(signer, wrap) }
            val rumor = gift.rumor()
            if (rumor.kind().asU16().toInt() != 14) return Unwrap.Rejected
            // rust-nostr does not recompute the rumor id from its content, and a rumor parsed from
            // a peer-controlled JSON payload can have no id at all (id() is nullable) or a forged
            // one that clashes with an existing message. Never trust rumor.id(): a rumor with no
            // claimed id is rejected outright, and a claimed id must match the NIP-01 id recomputed
            // from the signed fields.
            val claimedId = rumor.id() ?: return Unwrap.Rejected
            val computedId = EventId(rumor.author(), rumor.createdAt(), rumor.kind(), rumor.tags(), rumor.content())
            if (claimedId.toHex() != computedId.toHex()) return Unwrap.Rejected
            val sender = gift.sender().toHex()
            val recipients = rumor.tags().toVec().map { it.asVec() }.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
            val outgoing = sender == me
            val peer = if (outgoing) recipients.firstOrNull { it != me } ?: return Unwrap.Rejected else sender
            if (!outgoing && me !in recipients) return Unwrap.Rejected
            if (rumor.author().toHex() != sender) return Unwrap.Rejected // NIP-59: the seal's author must be the rumor's author
            Unwrap.Ok(DmIncoming(computedId.toHex(), peer, outgoing, rumor.content(), rumor.createdAt().asSecs().toLong(), wrap.id().toHex()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the signer could not answer (may open later), or not for us / corrupt (never will)
            if (locked) Unwrap.Locked else Unwrap.Rejected
        }
    }

    private fun wrapFilter(since: Long) = Filter().kind(Kind(1059u)).pubkey(PublicKey.parse(me)).since(Timestamp.fromSecs(since.coerceAtLeast(0).toULong()))

    override suspend fun fetchWraps(relays: List<String>, since: Long): WrapFetch {
        if (relays.isEmpty()) return WrapFetch(emptyList(), completed = false)
        val own = relays.mapNotNull { runCatching { RelayUrl.parse(it.trim()).toString().trimEnd('/') }.getOrNull() }.toSet()
        val mark = engine.closedMark()
        // Released in finally: exactly the references this fetch took, never another holder's.
        val held = engine.attachTemporary(relays)
        return try {
            val first = fetchOnce(relays, own, since, mark)
            // On a fresh connection the REQ usually overtakes our NIP-42 AUTH and the relay answers
            // auth-required (strfry: "ERROR: auth-required: …"). Once the relay accepted the AUTH,
            // the same window is asked once more; without an OK in time the refusal stands.
            if (engine.awaitAuthAfterRefusal(mark, own, authWait)) fetchOnce(relays, own, since, engine.closedMark()) else first
        } finally {
            engine.detachTemporary(held)
        }
    }

    private suspend fun fetchOnce(relays: List<String>, own: Set<String>, since: Long, mark: Long): WrapFetch {
        // fetchFrom has no subscription id of its own to match, so any CLOSED from one of these
        // relays after [mark] counts as a refusal (at worst the window is fetched again).
        val started = System.nanoTime()
        val wraps = engine.fetchFrom(relays, wrapFilter(since), fetchTimeout, attach = false)
        // rust-nostr returns what it has when the timeout ends the fetch; only an earlier
        // return means every relay answered (the rule of lookup10050).
        val inTime = System.nanoTime() - started < fetchTimeout.toNanos()
        // The notification loop that records CLOSED runs apart from the fetch: give a frame
        // that came in just before the fetch returned the moment to land.
        if (inTime && !engine.closedSince(mark, own)) withContext(Dispatchers.Default) { delay(CLOSED_SETTLE_MS) }
        return WrapFetch(wraps, completed = inTime && !engine.closedSince(mark, own), authRefused = engine.authRefusalsSince(mark, own))
    }

    override suspend fun subscribeWraps(relays: List<String>, since: Long): String = engine.subscribeTo(relays, wrapFilter(since))

    override suspend fun unsubscribe(id: String) = engine.unsubscribe(id)

    override fun incomingWraps(): Flow<Event> = engine.events.filter { ev ->
        ev.kind().asU16().toInt() == 1059 && ev.tags().toVec().map { it.asVec() }.any { it.size >= 2 && it[0] == "p" && it[1] == me }
    }

    private companion object {
        const val CLOSED_SETTLE_MS = 100L
    }
}
