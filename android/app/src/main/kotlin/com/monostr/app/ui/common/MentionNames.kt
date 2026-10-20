package com.monostr.app.ui.common

import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ProfileRepository

/** Spec 2: which pubkeys a batch of notes mentions and the names to show for them (`@name`). Pure except [resolve]. */
object MentionNames {
    /** Mention lookups read the local database only; after the prefetch a year counts as "never stale". */
    const val LOCAL_MAX_AGE = 365L * 86_400

    fun collect(notes: List<Note>): Set<String> =
        notes.flatMap { listOfNotNull(it, it.repostOf) }.flatMap { it.mentionedPubkeys + textMentions(it.content) }.toSet()

    fun collectTexts(texts: List<String>): Set<String> = texts.flatMap(::textMentions).toSet()

    fun textMentions(text: String): List<String> =
        NoteLinks.annotate(text).filterIsInstance<NoteSpan.Mention>().map { it.pubkey }

    /** Queues the missing ones (one batched lookup in the repository), then one local read; names that land later come through [MentionResolver]. */
    suspend fun resolve(profiles: ProfileRepository, pubkeys: Collection<String>): Map<String, String> {
        if (pubkeys.isEmpty()) return emptyMap()
        profiles.prefetch(pubkeys)
        return profiles.getAll(pubkeys, LOCAL_MAX_AGE).mapNotNull { (pk, p) -> named(p)?.let { pk to it } }.toMap()
    }

    fun named(p: Profile): String? = p.displayName?.takeIf { it.isNotBlank() } ?: p.name?.takeIf { it.isNotBlank() }

    fun label(pubkey: String, names: Map<String, String>): String = "@" + (names[pubkey] ?: Profile.shortPubkey(pubkey))

    fun plain(text: String, names: Map<String, String>): String =
        NoteLinks.annotate(text).joinToString("") { if (it is NoteSpan.Mention) label(it.pubkey, names) else it.text }
}
