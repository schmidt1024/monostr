package com.monostr.nostr.repo

import com.monostr.tips.Kinds
import rust.nostr.sdk.Filter
import com.monostr.nostr.ClientTag
import com.monostr.nostr.NOTE_NOT_IN_DATABASE
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.NoPreview
import com.monostr.nostr.model.HashtagTags
import com.monostr.nostr.model.MentionTags
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteAttachment
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.model.RelayModes
import com.monostr.tips.RelayHosts
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip19Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag

/** Writes: posts, replies, likes (kind 7 "+") and reposts (kind 6). */
interface PublishRepository {
    /** [attachments] are appended to the text as URLs and described by `imeta` tags (spec 5.2); [sensitive] marks a note with pictures by a `content-warning` tag. */
    /** [noPreview]: the author put the link card away while writing; the note carries [NoPreview.TAG] (owner 2026-11-12). */
    suspend fun post(text: String, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false, noPreview: Boolean = false): PublishResult
    suspend fun reply(text: String, to: Note, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false, noPreview: Boolean = false): PublishResult
    /** NIP-18 quote (spec 4.1): [text], the pictures, a blank line and `nostr:nevent…` of [of]; tags `q` and `p` plus the text's mentions. An empty [text] is allowed. */
    suspend fun quote(text: String, of: Note, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false): PublishResult
    suspend fun like(note: Note): PublishResult = react(note, LIKE)
    /** NIP-25 reaction (kind 7) to [note] with [content]: "+" is a like, anything else an emoji reaction. */
    suspend fun react(note: Note, content: String): PublishResult
    suspend fun repost(note: Note): PublishResult
    /**
     * NIP-22 comment (kind 1111) on [root], an article (kind 30023) or a poll (kind 1068): top level, or a reply to the comment
     * [parent]. Tags and attachments as in [reply].
     */
    suspend fun comment(text: String, root: Note, parent: Note? = null, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false, noPreview: Boolean = false): PublishResult
    /**
     * Unlike (kind 7) or undo repost (kind 6): a NIP-09 request naming every own event of [kind] that targets
     * [noteId]. Stored locally only once a relay accepted it, like [delete]. Null when the database holds none.
     */
    suspend fun withdraw(noteId: String, kind: Int): PublishResult?
    /**
     * NIP-09 deletion request for [note] (kind 5, tags `e` and `k`). Stored locally only once a relay
     * accepted it; the stored request is what removes the note from the local database.
     */
    suspend fun delete(note: Note): PublishResult
    /** `nostr:nevent1…` of [note] with its author, kind 1 and up to two connected own relays as hints. */
    suspend fun noteLink(note: Note): String
    /** Publishes kind 0 with [json] as content; stored locally only once a relay accepted it (spec 7: offline = no local change). */
    suspend fun profile(json: String): PublishResult
    /** Sends the stored event [eventId] again; never signs a new event. */
    suspend fun resend(eventId: String): PublishResult
    /** Publishes the user's NIP-65 relay list (kind 10002); a one-way relay carries a read or write marker. */
    suspend fun relayList(entries: List<RelayEntry>): PublishResult
    /**
     * Replaces the user's NIP-65 relay list (kind 10002) by [relays] on the user's own request. Unlike
     * [relayList] it is kept locally only when a relay accepted it: a list nobody received must not
     * pass for the published one.
     */
    suspend fun publishRelayList(entries: List<RelayEntry>): PublishResult
    /** Publishes the user's NIP-17 DM inbox relay list (kind 10050). */
    suspend fun dmRelayList(relays: List<String>): PublishResult
    /**
     * Spec 2026-10-17 §11: the first NIP-65 relay list (kind 10002) of a new account, same tags as
     * [relayList]. Sent only to the session's relays (no indexers) and kept locally only once a relay
     * accepted it, so a stored list means "already published".
     */
    suspend fun initialRelayList(relays: List<String>): PublishResult
}

class NostrPublishRepository(
    private val engine: NostrEngine,
    /** Relays where other clients and the tip watcher look a relay list up; empty by default so that tests never reach the network. */
    private val indexRelays: List<String> = emptyList(),
    /** Whether a note, reply, quote, like or repost carries the NIP-89 [ClientTag] ("via Monostr"); the user's setting, off in tests. */
    private val clientTag: suspend () -> Boolean = { false },
    /** NIP-13 proof of work for notes, replies and quotes, in leading zero bits; 0 = off (the default, and the user's setting). */
    private val powBits: suspend () -> Int = { 0 },
    /** Which relay may be named as a hint in a public event; tests open it for their loopback relay. A `.onion` never is. */
    private val hintAllowed: (String) -> Boolean = RelayHosts::isPublicWss,
) : PublishRepository {
    /** [b] with the user's proof of work: rust-nostr mines the nonce when the event is built, before any signer sees it. */
    private suspend fun withPow(b: EventBuilder): EventBuilder = powBits().takeIf { it > 0 }?.let { b.pow(it.coerceAtMost(MAX_POW).toUByte()) } ?: b
    /** The [ClientTag] for the kinds other clients attribute (1, 6, 7), when the user allows it; never on lists, the profile or deletions. */
    private suspend fun viaTag(): List<Tag> = if (clientTag()) listOf(ClientTag.tag()) else emptyList()

    // raw kind 1 builder: the tags are exactly the mentions, the hashtags and the attachments, nothing else is extracted from the content
    override suspend fun post(text: String, attachments: List<NoteAttachment>, sensitive: Boolean, noPreview: Boolean): PublishResult =
        engine.signAndSend(withPow(EventBuilder(Kind(1u), NoteAttachment.content(text, attachments)).tags(MentionTags.from(text) + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + previewTag(noPreview) + viaTag())))

    override suspend fun reply(text: String, to: Note, attachments: List<NoteAttachment>, sensitive: Boolean, noPreview: Boolean): PublishResult {
        val parent = requireNotNull(engine.eventById(to.id)) { NOTE_NOT_IN_DATABASE }
        // NIP-10: the root is marked "root"; when replying to the root itself it is the only e tag.
        val root = to.rootId?.takeIf { it != to.id }?.let { engine.eventById(it) } ?: parent
        // the reply builder tags only the parent's author (not the parent's other p tags); every mention is added and dedupTags collapses repeats
        val mentions = MentionTags.pubkeys(text).map { Tag.parse(listOf("p", it)) }
        return engine.signAndSend(
            withPow(EventBuilder.textNoteReply(NoteAttachment.content(text, attachments), parent, root, null).tags(mentions + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + previewTag(noPreview) + viaTag()).dedupTags()),
        )
    }

    override suspend fun quote(text: String, of: Note, attachments: List<NoteAttachment>, sensitive: Boolean): PublishResult {
        val hint = relayHint()
        val uri = Nip19Event(EventId.parse(of.id), PublicKey.parse(of.author), Kind(1u), listOfNotNull(hint).map { RelayUrl.parse(it) }).toNostrUri()
        val body = NoteAttachment.content(text, attachments)
        val content = if (body.isEmpty()) uri else "$body\n\n$uri"
        val tags = (listOf(listOf("q", of.id, hint ?: "", of.author), listOf("p", of.author)) + MentionTags.pubkeys(text).map { listOf("p", it) }).distinct()
        // raw kind 1 builder: exactly these tags and the hashtags, nothing else extracted from the content
        return engine.signAndSend(withPow(EventBuilder(Kind(1u), content).tags(tags.map { Tag.parse(it) } + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + viaTag())))
    }

    private fun previewTag(noPreview: Boolean): List<Tag> = if (noPreview) listOf(Tag.parse(NoPreview.TAG)) else emptyList()

    private fun attachmentTags(attachments: List<NoteAttachment>, sensitive: Boolean): List<Tag> =
        NoteAttachment.tags(attachments, sensitive).map { Tag.parse(it) }

    /**
     * First normal relay that is connected; rust-nostr keeps no "seen on" relays, so connected stands in for "knows the note".
     * The hint goes into public events: a hidden service, a LAN, loopback or `.local` relay (or plain ws) would only leak the
     * user's setup and help no one, so only a public wss relay is named, else none.
     */
    private suspend fun relayHint(): String? {
        val connected = engine.connectedNormalRelayUrls()
        return engine.relayUrls().firstOrNull { it in connected && !RelayHosts.isOnionHost(it) && hintAllowed(it) }
    }

    override suspend fun profile(json: String): PublishResult = engine.signAndPublish(EventBuilder(Kind(0u), json))

    override suspend fun react(note: Note, content: String): PublishResult {
        val target = requireNotNull(engine.eventById(note.id)) { NOTE_NOT_IN_DATABASE }
        articleTags(note)?.let { return engine.signAndSend(EventBuilder(Kind(7u), content).tags(it.map { t -> Tag.parse(t) } + viaTag())) }
        return engine.signAndSend(EventBuilder.reaction(target, content).tags(viaTag()))
    }

    override suspend fun repost(note: Note): PublishResult {
        val target = requireNotNull(engine.eventById(note.id)) { NOTE_NOT_IN_DATABASE }
        // NIP-18: a repost of anything but kind 1 is kind 16. An article can be far larger than a relay takes in one event
        // (relay.monostr.com: 64 KiB), so the content stays empty and the e and a tags carry a relay hint instead (final review I2)
        (articleTags(note, relayHint()) ?: pollTags(note, relayHint()))?.let { return engine.signAndSend(EventBuilder(Kind(16u), "").tags(it.map { t -> Tag.parse(t) } + viaTag())) }
        return engine.signAndSend(EventBuilder.repost(target, null).tags(viaTag()))
    }

    override suspend fun comment(text: String, root: Note, parent: Note?, attachments: List<NoteAttachment>, sensitive: Boolean, noPreview: Boolean): PublishResult {
        // an article is the root by its coordinate too (`A`); a poll (polls task 4) has no address, only its id
        val ref = root.addressRef?.takeIf { root.kind == ARTICLE_KIND }
        require(ref != null || root.kind == Kinds.POLL) { "comment needs an article or a poll" }
        val rootKind = if (ref != null) ARTICLE_KIND else Kinds.POLL
        val rootTags = listOfNotNull(ref?.let { listOf("A", it.coordinate) }, listOf("E", root.id), listOf("K", rootKind.toString()), listOf("P", root.author))
        val scope = if (parent == null) {
            rootTags + listOfNotNull(ref?.let { listOf("a", it.coordinate) }, listOf("e", root.id), listOf("k", rootKind.toString()), listOf("p", root.author))
        } else {
            // the parent's own kind: a comment (1111) or an old-style kind 1 reply under the article (final review I6)
            rootTags + listOf(listOf("e", parent.id), listOf("k", parent.kind.toString()), listOf("p", parent.author))
        }
        val mentions = MentionTags.pubkeys(text).map { listOf("p", it) }
        return engine.signAndSend(
            withPow(EventBuilder(Kind(COMMENT_KIND.toUShort()), NoteAttachment.content(text, attachments)).tags((scope + mentions).distinct().map { Tag.parse(it) } + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + previewTag(noPreview) + viaTag())),
        )
    }


    override suspend fun withdraw(noteId: String, kind: Int): PublishResult? {
        val tags = withdrawTags(noteId, kind) ?: return null
        return engine.signAndPublishUnstored(EventBuilder(Kind(5u), "").tags(tags.map { Tag.parse(it) }))
    }

    /** The tags of the [withdraw] request: one `e` per own event of [kind] whose target (the last `e` tag) is [noteId], and `k`. */
    internal suspend fun withdrawTags(noteId: String, kind: Int): List<List<String>>? {
        val me = PublicKey.parse(requireNotNull(engine.pubkey) { "withdraw needs a logged-in user" })
        val own = engine.query(Filter().kind(Kind(kind.toUShort())).author(me).event(EventId.parse(noteId)))
            .filter { ev -> ev.tags().toVec().map { it.asVec() }.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) == noteId }
        if (own.isEmpty()) return null
        return own.map { listOf("e", it.id().toHex()) } + listOf(listOf("k", kind.toString()))
    }

    override suspend fun delete(note: Note): PublishResult =
        engine.signAndPublishUnstored(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", note.id)), Tag.parse(listOf("k", note.kind.toString())))))

    override suspend fun noteLink(note: Note): String {
        val connected = engine.connectedNormalRelayUrls()
        val hints = engine.relayUrls().filter { it in connected && !RelayHosts.isOnionHost(it) && hintAllowed(it) }.take(2)
        return Nip19Event(EventId.parse(note.id), PublicKey.parse(note.author), Kind(1u), hints.map { RelayUrl.parse(it) }).toNostrUri()
    }

    override suspend fun resend(eventId: String): PublishResult = engine.resend(eventId)

    // signAndSend (store first) on purpose: the onboarding / offline-resend flow of Plan 5 relies on it
    override suspend fun relayList(entries: List<RelayEntry>): PublishResult =
        if (!publishable(entries)) NOTHING_PUBLIC else engine.signAndSend(relayListBuilder(entries))

    override suspend fun publishRelayList(entries: List<RelayEntry>): PublishResult {
        if (!publishable(entries)) return NOTHING_PUBLIC
        val own = engine.signAndPublish(relayListBuilder(entries))
        val index = indexRelays.map { it.trim().trimEnd('/') } - engine.relayUrls().toSet()
        if (!own.sentToAny || index.isEmpty()) return own
        // best effort: the list counts as published once one of the user's relays has it
        val more = engine.sendTo(index, own.eventId)
        return PublishResult(own.eventId, own.kind, (own.successRelays + more.successRelays).distinct(), own.failedRelays + more.failedRelays)
    }

    override suspend fun initialRelayList(relays: List<String>): PublishResult {
        val entries = relays.map { RelayEntry(it, RelayMode.BOTH) }
        return if (!publishable(entries)) NOTHING_PUBLIC else engine.signAndPublish(relayListBuilder(entries))
    }

    private fun relayListBuilder(entries: List<RelayEntry>): EventBuilder =
        EventBuilder(Kind(10002u), "").tags(relayListTags(entries).map { Tag.parse(it) })


    // signAndPublish: stored only after a relay OK, so a refused list never gets adopted by DmSync (spec 11)
    override suspend fun dmRelayList(relays: List<String>): PublishResult =
        if (dmRelayTags(relays).isEmpty()) NOTHING_PUBLIC else engine.signAndPublish(EventBuilder(Kind(10050u), "").tags(dmRelayTags(relays).map { Tag.parse(it) }))
}

/** What a list holding only hidden services publishes: nothing, and certainly not an empty list that would wipe the user's real one. */
private val NOTHING_PUBLIC = PublishResult("", 0, emptyList(), mapOf("*" to "no public relay to publish"))

/** The `r` tags of a kind 10002 list: a `.onion` relay is not in it, it is the user's own and for Tor only (spec 2026-11-06 Tor 7.4). */
internal fun relayListTags(entries: List<RelayEntry>): List<List<String>> =
    entries.filterNot { RelayHosts.isOnionHost(it.url) }.map { RelayModes.tag(it) }

/** What is left of [entries] once onion is out still has a relay to read from and one to write to; a list without either is not published. */
internal fun publishable(entries: List<RelayEntry>): Boolean {
    val left = entries.filterNot { RelayHosts.isOnionHost(it.url) }
    return RelayModes.valid(left.map { it.url }, left.associate { it.url to it.mode })
}

/** The `relay` tags of a kind 10050 list, without hidden services (see [relayListTags]). */
internal fun dmRelayTags(relays: List<String>): List<List<String>> =
    relays.filterNot { RelayHosts.isOnionHost(it) }.map { listOf("relay", it.trim().trimEnd('/')) }

private const val ARTICLE_KIND = 30023
private const val COMMENT_KIND = 1111

/**
 * Reaction and repost tags for an article (kind 30023): shown event, coordinate, author, kind; null for any other note.
 * [hint]: a relay that has the article, on the `e` and `a` tags (NIP-18 for a repost without content).
 */
internal fun articleTags(note: Note, hint: String? = null): List<List<String>>? {
    val ref = note.addressRef?.takeIf { note.kind == ARTICLE_KIND } ?: return null
    return listOf(listOfNotNull("e", note.id, hint), listOfNotNull("a", ref.coordinate, hint), listOf("p", note.author), listOf("k", ARTICLE_KIND.toString()))
}

/**
 * The tags of a repost of a poll (kind 16, empty content, polls task 4): `e` with the relay [hint], `p`, `k` = 1068.
 * Null when [note] is no poll.
 */
internal fun pollTags(note: Note, hint: String? = null): List<List<String>>? {
    if (note.kind != Kinds.POLL) return null
    return listOf(listOfNotNull("e", note.id, hint), listOf("p", note.author), listOf("k", Kinds.POLL.toString()))
}

/** The content of a plain like (NIP-25). */
const val LIKE = "+"

/** The highest proof of work the app asks for (Settings offer 8, 16, 20 bits; older installs may have stored 24). */
const val MAX_POW = 24
