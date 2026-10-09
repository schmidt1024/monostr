package com.monostr.nostr.repo

import com.monostr.nostr.ClientTag
import com.monostr.nostr.NOTE_NOT_IN_DATABASE
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.HashtagTags
import com.monostr.nostr.model.MentionTags
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteAttachment
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
    suspend fun post(text: String, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false): PublishResult
    suspend fun reply(text: String, to: Note, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false): PublishResult
    /** NIP-18 quote (spec 4.1): [text], the pictures, a blank line and `nostr:nevent…` of [of]; tags `q` and `p` plus the text's mentions. An empty [text] is allowed. */
    suspend fun quote(text: String, of: Note, attachments: List<NoteAttachment> = emptyList(), sensitive: Boolean = false): PublishResult
    suspend fun like(note: Note): PublishResult
    suspend fun repost(note: Note): PublishResult
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
    /** Publishes the user's NIP-65 relay list (kind 10002), each relay as read+write. */
    suspend fun relayList(relays: List<String>): PublishResult
    /**
     * Replaces the user's NIP-65 relay list (kind 10002) by [relays] on the user's own request. Unlike
     * [relayList] it is kept locally only when a relay accepted it: a list nobody received must not
     * pass for the published one.
     */
    suspend fun publishRelayList(relays: List<String>): PublishResult
    /** Publishes the user's NIP-17 DM inbox relay list (kind 10050). */
    suspend fun dmRelayList(relays: List<String>): PublishResult
}

class NostrPublishRepository(
    private val engine: NostrEngine,
    /** Relays where other clients and the tip watcher look a relay list up; empty by default so that tests never reach the network. */
    private val indexRelays: List<String> = emptyList(),
    /** Whether a note, reply, quote, like or repost carries the NIP-89 [ClientTag] ("via Monostr"); the user's setting, off in tests. */
    private val clientTag: suspend () -> Boolean = { false },
) : PublishRepository {
    /** The [ClientTag] for the kinds other clients attribute (1, 6, 7), when the user allows it; never on lists, the profile or deletions. */
    private suspend fun viaTag(): List<Tag> = if (clientTag()) listOf(ClientTag.tag()) else emptyList()

    // raw kind 1 builder: the tags are exactly the mentions, the hashtags and the attachments, nothing else is extracted from the content
    override suspend fun post(text: String, attachments: List<NoteAttachment>, sensitive: Boolean): PublishResult =
        engine.signAndSend(EventBuilder(Kind(1u), NoteAttachment.content(text, attachments)).tags(MentionTags.from(text) + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + viaTag()))

    override suspend fun reply(text: String, to: Note, attachments: List<NoteAttachment>, sensitive: Boolean): PublishResult {
        val parent = requireNotNull(engine.eventById(to.id)) { NOTE_NOT_IN_DATABASE }
        // NIP-10: the root is marked "root"; when replying to the root itself it is the only e tag.
        val root = to.rootId?.takeIf { it != to.id }?.let { engine.eventById(it) } ?: parent
        // the reply builder tags only the parent's author (not the parent's other p tags); every mention is added and dedupTags collapses repeats
        val mentions = MentionTags.pubkeys(text).map { Tag.parse(listOf("p", it)) }
        return engine.signAndSend(
            EventBuilder.textNoteReply(NoteAttachment.content(text, attachments), parent, root, null).tags(mentions + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + viaTag()).dedupTags(),
        )
    }

    override suspend fun quote(text: String, of: Note, attachments: List<NoteAttachment>, sensitive: Boolean): PublishResult {
        val hint = relayHint()
        val uri = Nip19Event(EventId.parse(of.id), PublicKey.parse(of.author), Kind(1u), listOfNotNull(hint).map { RelayUrl.parse(it) }).toNostrUri()
        val body = NoteAttachment.content(text, attachments)
        val content = if (body.isEmpty()) uri else "$body\n\n$uri"
        val tags = (listOf(listOf("q", of.id, hint ?: "", of.author), listOf("p", of.author)) + MentionTags.pubkeys(text).map { listOf("p", it) }).distinct()
        // raw kind 1 builder: exactly these tags and the hashtags, nothing else extracted from the content
        return engine.signAndSend(EventBuilder(Kind(1u), content).tags(tags.map { Tag.parse(it) } + HashtagTags.from(text) + attachmentTags(attachments, sensitive) + viaTag()))
    }

    private fun attachmentTags(attachments: List<NoteAttachment>, sensitive: Boolean): List<Tag> =
        NoteAttachment.tags(attachments, sensitive).map { Tag.parse(it) }

    /** First normal relay that is connected; rust-nostr keeps no "seen on" relays, so connected stands in for "knows the note". */
    private suspend fun relayHint(): String? {
        val connected = engine.connectedNormalRelayUrls()
        return engine.relayUrls().firstOrNull { it in connected }
    }

    override suspend fun profile(json: String): PublishResult = engine.signAndPublish(EventBuilder(Kind(0u), json))

    override suspend fun like(note: Note): PublishResult {
        val target = requireNotNull(engine.eventById(note.id)) { NOTE_NOT_IN_DATABASE }
        return engine.signAndSend(EventBuilder.reaction(target, "+").tags(viaTag()))
    }

    override suspend fun repost(note: Note): PublishResult {
        val target = requireNotNull(engine.eventById(note.id)) { NOTE_NOT_IN_DATABASE }
        return engine.signAndSend(EventBuilder.repost(target, null).tags(viaTag()))
    }

    override suspend fun delete(note: Note): PublishResult =
        engine.signAndPublishUnstored(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", note.id)), Tag.parse(listOf("k", "1")))))

    override suspend fun noteLink(note: Note): String {
        val connected = engine.connectedNormalRelayUrls()
        val hints = engine.relayUrls().filter { it in connected }.take(2)
        return Nip19Event(EventId.parse(note.id), PublicKey.parse(note.author), Kind(1u), hints.map { RelayUrl.parse(it) }).toNostrUri()
    }

    override suspend fun resend(eventId: String): PublishResult = engine.resend(eventId)

    // signAndSend (store first) on purpose: the onboarding / offline-resend flow of Plan 5 relies on it
    override suspend fun relayList(relays: List<String>): PublishResult = engine.signAndSend(relayListBuilder(relays))

    override suspend fun publishRelayList(relays: List<String>): PublishResult {
        val own = engine.signAndPublish(relayListBuilder(relays))
        val index = indexRelays.map { it.trim().trimEnd('/') } - engine.relayUrls().toSet()
        if (!own.sentToAny || index.isEmpty()) return own
        // best effort: the list counts as published once one of the user's relays has it
        val more = engine.sendTo(index, own.eventId)
        return PublishResult(own.eventId, own.kind, (own.successRelays + more.successRelays).distinct(), own.failedRelays + more.failedRelays)
    }

    /** `r` tags without a marker: every relay is for reading and writing. */
    private fun relayListBuilder(relays: List<String>): EventBuilder =
        EventBuilder(Kind(10002u), "").tags(relays.map { Tag.parse(listOf("r", it.trim().trimEnd('/'))) })

    // signAndPublish: stored only after a relay OK, so a refused list never gets adopted by DmSync (spec 11)
    override suspend fun dmRelayList(relays: List<String>): PublishResult =
        engine.signAndPublish(EventBuilder(Kind(10050u), "").tags(relays.map { Tag.parse(listOf("relay", it.trim().trimEnd('/'))) }))
}
