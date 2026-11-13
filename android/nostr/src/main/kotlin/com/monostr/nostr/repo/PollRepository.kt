package com.monostr.nostr.repo

import com.monostr.nostr.ClientTag
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.HashtagTags
import com.monostr.nostr.model.MentionTags
import com.monostr.nostr.model.NoPreview
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.PollVote
import com.monostr.nostr.model.PollVotes
import com.monostr.tips.Kinds
import com.monostr.tips.RelayHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.security.SecureRandom
import java.time.Duration

/** NIP-88 polls (spec 2026-11-12): the responses to a poll, an own vote, a new poll. */
interface PollRepository {
    /** The parsed kind 1018 responses to [poll]: the local database plus one fetch from the poll's relays and the read relays. Never throws but on cancellation. */
    suspend fun votes(poll: Note): List<PollVote>

    /**
     * Publishes the own response (kind 1018, no PoW) naming [optionIds] to the user's write relays and to the poll's
     * public `relay` tags. [PublishResult.sentToAny] is true when one of the user's write relays took it; the poll's
     * relays are only tried afterwards (a vote none of the user's relays took is rolled back, never sent on). Throws
     * [IllegalArgumentException] for a note that is no poll.
     */
    suspend fun vote(poll: Note, optionIds: List<String>): PublishResult

    /**
     * Publishes a single-choice poll (kind 1068) with [choices] (2 to 4, already trimmed) that ends at [endsAt] (unix seconds).
     * [noPreview]: the link card was put away while writing; the poll says so with [com.monostr.nostr.model.NoPreview], as a note does.
     */
    suspend fun create(text: String, choices: List<String>, endsAt: Long, noPreview: Boolean = false): PublishResult
}

class NostrPollRepository(
    private val engine: NostrEngine,
    /** NIP-13 proof of work for a new poll, as for a note; a vote never carries any. */
    private val powBits: suspend () -> Int = { 0 },
    /** Which relay may be named in a public event or asked on a poll's say-so; tests open it for their loopback relay. */
    private val hintAllowed: (String) -> Boolean = RelayHosts::isPublicWss,
    /** Whether the event carries the NIP-89 [ClientTag] ("via Monostr"); the user's setting, off in tests. */
    private val clientTag: suspend () -> Boolean = { false },
    /** The app-wide budget for relays a stranger's poll names; they are leased for one fetch and released again. Null: attached and detached directly. */
    private val foreign: ForeignRelays? = null,
) : PollRepository {
    private suspend fun viaTag(): List<Tag> = if (clientTag()) listOf(ClientTag.tag()) else emptyList()

    /** The poll's own relays that may be used: public wss only (a poll from outside must not make us dial a LAN host or hidden service). */
    private fun pollRelays(poll: Note): List<String> =
        poll.poll?.relays.orEmpty().filter { !RelayHosts.isOnionHost(it) && hintAllowed(it) }.map { it.trim().trimEnd('/') }.distinct().take(MAX_POLL_RELAYS)

    override suspend fun votes(poll: Note): List<PollVote> {
        val p = poll.poll ?: return emptyList()
        val found = LinkedHashMap<String, PollVote>()
        fun take(events: List<rust.nostr.sdk.Event>) {
            for (ev in events) {
                val vote = PollVotes.parse(NoteMapper.decoded(ev), poll.id) ?: continue
                found.putIfAbsent(vote.id, vote)
            }
        }
        try {
            // inside the try: a broken id must not break the never-throws contract
            var filter = Filter().kind(Kind(Kinds.POLL_RESPONSE.toUShort())).event(EventId.parse(poll.id))
            if (p.endsAt != null) filter = filter.until(Timestamp.fromSecs(p.endsAt.toULong()))
            take(engine.query(filter))
            val named = pollRelays(poll)
            // the poll's relays are a stranger's choice: leased for this fetch only, never left attached
            val held = if (foreign != null) foreign.lease(named) else engine.attachTemporary(named)
            try {
                val relays = (held + engine.readRelayUrls()).distinct()
                take(engine.fetchFrom(relays, filter, Duration.ofSeconds(FETCH_SECONDS), attach = false))
            } finally {
                withContext(NonCancellable) { if (foreign != null) foreign.release(held) else engine.detachTemporary(held) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // an engine or network failure: what the local database had is the answer
        }
        return found.values.toList()
    }

    override suspend fun vote(poll: Note, optionIds: List<String>): PublishResult {
        requireNotNull(poll.poll) { "vote needs a poll" }
        require(optionIds.isNotEmpty()) { "vote needs an option" }
        val tags = listOf(listOf("e", poll.id)) + optionIds.map { listOf("response", it) }
        val own = engine.signAndPublish(EventBuilder(Kind(Kinds.POLL_RESPONSE.toUShort()), "").tags(tags.map { Tag.parse(it) } + viaTag()))
        // a vote nobody accepted was rolled back by the engine and cannot be sent on
        if (!own.sentToAny) return own
        val extra = pollRelays(poll) - engine.relayUrls().toSet()
        if (extra.isEmpty()) return own
        val more = engine.sendTo(extra, own.eventId)
        return PublishResult(own.eventId, own.kind, (own.successRelays + more.successRelays).distinct(), own.failedRelays + more.failedRelays)
    }

    override suspend fun create(text: String, choices: List<String>, endsAt: Long, noPreview: Boolean): PublishResult {
        require(choices.size in 2..4 && choices.none { it.isBlank() }) { "a poll has two to four choices" }
        val ids = LinkedHashSet<String>()
        while (ids.size < choices.size) ids += randomId()
        val options = ids.zip(choices).map { (id, label) -> listOf("option", id, label) }
        val relays = engine.writeRelayUrls().filter { !RelayHosts.isOnionHost(it) && hintAllowed(it) }.map { listOf("relay", it) }
        val tags = options + listOf(listOf("polltype", "singlechoice"), listOf("endsAt", endsAt.toString())) + relays
        val builder = EventBuilder(Kind(Kinds.POLL.toUShort()), text).tags(tags.map { Tag.parse(it) } + MentionTags.from(text) + HashtagTags.from(text) + previewTag(noPreview) + viaTag())
        // the user's PoW setting, as for a note: rust-nostr mines the nonce when the event is built
        val bits = powBits()
        return engine.signAndSend(if (bits > 0) builder.pow(bits.coerceAtMost(MAX_POW).toUByte()) else builder)
    }

    private fun previewTag(noPreview: Boolean): List<Tag> = if (noPreview) listOf(Tag.parse(NoPreview.TAG)) else emptyList()

    private fun randomId(): String = String(CharArray(ID_LENGTH) { ID_ALPHABET[RANDOM.nextInt(ID_ALPHABET.length)] })

    private companion object {
        const val FETCH_SECONDS = 6L
        const val MAX_POLL_RELAYS = 3
        const val ID_LENGTH = 8
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        val RANDOM = SecureRandom()
    }
}
