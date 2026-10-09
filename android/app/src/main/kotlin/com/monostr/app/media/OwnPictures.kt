package com.monostr.app.media

import com.monostr.nostr.model.Note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * The one rule for "may this picture leave the media server?" (spec 4.3 and 8): only the configured
 * server, only an address that names its SHA-256, and never while another own note in the local
 * database or the own profile still shows it. The local database does not know every own note;
 * the rest is an accepted risk (spec 12).
 */
class OwnPictures(
    private val server: StateFlow<String>,
    /** The user's own notes in the local database. */
    private val ownNotes: suspend () -> List<Note>,
    /** The fields of the own kind 0 as published now; null when they could not be read (not the same as "none"). */
    private val profileFields: suspend () -> Map<String, String>?,
    private val delete: suspend (server: String, sha256: String) -> Unit,
) {
    /** The picture addresses of [note] that sit on the configured server. */
    fun candidates(note: Note): List<String> = note.media.map { it.url }.filter { sha256Of(it, server.value) != null }.distinct()

    /**
     * Deletes the blobs behind [urls] unless something still shows them; [exceptNoteId] is the note being
     * deleted, which does not protect its own pictures. Returns how many deletes failed.
     * A lookup that fails must never read as "nothing uses it": when the own notes or the profile cannot be
     * read, nothing is deleted and every candidate counts as failed.
     */
    suspend fun remove(urls: List<String>, exceptNoteId: String? = null): Int {
        val base = server.value
        val hashes = urls.mapNotNull { sha256Of(it, base) }.distinct()
        if (hashes.isEmpty()) return 0
        // the hash, not the address: the same blob may be named with another extension
        val inUse = try {
            val fields = profileFields() ?: return hashes.size
            ownNotes().filter { it.id != exceptNoteId }.map { it.content.lowercase() } + fields.values.map { it.lowercase() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return hashes.size
        }
        var failed = 0
        for (hash in hashes) {
            if (inUse.any { hash in it }) continue
            try {
                delete(base, hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        return failed
    }

    companion object {
        private val HASH = Regex("^[0-9a-f]{64}$")

        /** The SHA-256 a Blossom address on [server] names (`<server>/<64 hex>[.ext]`), else null. */
        fun sha256Of(url: String, server: String): String? {
            val prefix = server.trim().trimEnd('/') + "/"
            if (!url.startsWith(prefix)) return null
            val name = url.substring(prefix.length)
            if ('/' in name || '?' in name || '#' in name) return null
            return name.substringBefore('.').takeIf { HASH.matches(it) }
        }
    }
}
