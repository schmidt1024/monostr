package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.media.MediaUploader
import com.monostr.app.media.UploadedMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** Spec 2026-10-16 §8.1: what the composer keeps of an unsent note. Only finished uploads are part of it. */
@Serializable
data class Draft(
    val text: String,
    val sensitive: Boolean = false,
    val attachments: List<UploadedMedia> = emptyList(),
    /** Epoch seconds of the last save. */
    val updatedAt: Long,
) {
    /** Blank text and no pictures: nothing to keep, the key goes. */
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/** The composer's targets: a new note, a reply to a note, a quote of a note. */
object DraftTargets {
    const val NOTE = "note"

    fun of(replyTo: String?, quote: String?): String = when {
        replyTo != null -> "reply:$replyTo"
        quote != null -> "quote:$quote"
        else -> NOTE
    }
}

/** Spec 2026-10-16 §8: drafts per account and target; they leave the device with the account at logout, like the DMs. */
interface DraftStore {
    suspend fun load(pubkey: String, target: String): Draft?
    /** Writes [draft]; an empty one removes the key instead. */
    suspend fun save(pubkey: String, target: String, draft: Draft)
    suspend fun remove(pubkey: String, target: String)
    /** Removes the drafts of [pubkey] last saved [maxAgeSeconds] or more before [now] (and unreadable ones); returns the readable ones removed. */
    suspend fun expire(pubkey: String, now: Long, maxAgeSeconds: Long = MAX_AGE_SECONDS): List<Draft>
    suspend fun all(pubkey: String): List<Draft>
    /** Removes every draft of [pubkey] (and unreadable ones); returns the readable ones removed. */
    suspend fun removeAll(pubkey: String): List<Draft>

    companion object {
        const val MAX_AGE_SECONDS = 30 * 86_400L
    }
}

class PrefsDraftStore(private val store: DataStore<Preferences>) : DraftStore {
    /** A failed read yields no drafts instead of crashing the composer (the official DataStore pattern). */
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private fun key(pubkey: String, target: String) = stringPreferencesKey("$PREFIX$pubkey.$target")
    private fun prefix(pubkey: String) = "$PREFIX$pubkey."

    override suspend fun load(pubkey: String, target: String): Draft? = data.first()[key(pubkey, target)]?.let(::decode)

    override suspend fun save(pubkey: String, target: String, draft: Draft) {
        if (draft.isEmpty) return remove(pubkey, target)
        store.edit { it[key(pubkey, target)] = json.encodeToString(Draft.serializer(), draft) }
    }

    override suspend fun remove(pubkey: String, target: String) {
        store.edit { it.remove(key(pubkey, target)) }
    }

    override suspend fun expire(pubkey: String, now: Long, maxAgeSeconds: Long): List<Draft> =
        removeWhere(pubkey) { now - it.updatedAt >= maxAgeSeconds }

    override suspend fun removeAll(pubkey: String): List<Draft> = removeWhere(pubkey) { true }

    private suspend fun removeWhere(pubkey: String, gone: (Draft) -> Boolean): List<Draft> {
        val removed = ArrayList<Draft>()
        store.edit { p ->
            for ((k, v) in p.asMap().entries.toList()) {
                if (!k.name.startsWith(prefix(pubkey))) continue
                val d = (v as? String)?.let(::decode)
                // an unreadable entry can never be restored: it goes as well
                if (d == null || gone(d)) {
                    p.remove(stringPreferencesKey(k.name))
                    d?.let { removed += it }
                }
            }
        }
        return removed
    }

    override suspend fun all(pubkey: String): List<Draft> =
        data.first().asMap().filterKeys { it.name.startsWith(prefix(pubkey)) }.values.mapNotNull { (it as? String)?.let(::decode) }

    private fun decode(raw: String): Draft? = runCatching { json.decodeFromString(Draft.serializer(), raw) }.getOrNull()

    private companion object {
        const val PREFIX = "draft."
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Spec 2026-10-16 §8.2: at session start the drafts of [pubkey] untouched for 30 days go, and with them
 * their pictures — those no remaining draft names; [MediaUploader.discard] deletes only `fresh` blobs
 * that no published own note or the profile shows, silently and best effort.
 */
suspend fun expireDrafts(store: DraftStore, pubkey: String, now: Long, uploader: MediaUploader) {
    val gone = store.expire(pubkey, now)
    if (gone.isEmpty()) return
    val kept = store.all(pubkey).flatMapTo(HashSet()) { d -> d.attachments.map { it.sha256 } }
    gone.flatMap { it.attachments }.distinctBy { it.sha256 }.filter { it.sha256 !in kept }.forEach { uploader.discard(it) }
}

/**
 * Spec 2026-10-16 §8: logout. Every draft of [pubkey] leaves the device, like the DMs. Then their `fresh`
 * pictures are deleted at the server unless a published own note or the profile shows them
 * ([MediaUploader.discardNow]); the caller runs this while the signer still works. Best effort.
 * The keys go first: a draft never names a picture that is already gone.
 */
suspend fun removeDrafts(store: DraftStore, pubkey: String, uploader: MediaUploader) {
    val gone = store.removeAll(pubkey)
    gone.flatMap { it.attachments }.distinctBy { it.sha256 }.forEach { uploader.discardNow(it) }
}
