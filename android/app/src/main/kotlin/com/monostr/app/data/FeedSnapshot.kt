package com.monostr.app.data

import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The home feed's last shown first page with its profiles, one file per account: shown at once on
 * the next start while the database and the relays are still read (user 2026-10-19: speed first).
 * It holds what the account's database held when it was written (a note withdrawn since shows until
 * the first fresh page replaces it); logout deletes it.
 */
class FeedSnapshot(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    @Serializable
    data class Item(val note: Note, val author: Profile, val repostedAuthor: Profile)

    @Serializable
    private data class Stored(val version: Int, val items: List<Item>)

    /** Set by [delete] (logout): a write still pending from the feed must not bring the file back. */
    @Volatile private var closed = false

    /** Empty when there is none or it cannot be read (an older format, a broken file). */
    suspend fun read(): List<Item> = withContext(io) {
        try {
            if (!file.exists()) return@withContext emptyList()
            val stored = JSON.decodeFromString(Stored.serializer(), file.readText())
            if (stored.version == VERSION) stored.items else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Best effort; written to a temp file and moved, so a crash mid-write never leaves half a file. */
    suspend fun write(items: List<Item>) = withContext(io) {
        if (closed) return@withContext
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSON.encodeToString(Stored.serializer(), Stored(VERSION, items.take(MAX_ITEMS))))
            if (closed || !tmp.renameTo(file)) tmp.delete()
            if (closed) file.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the next start simply has no snapshot
        }
    }

    fun delete() {
        closed = true
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    companion object {
        const val MAX_ITEMS = 30
        /** Raise it whenever the stored classes or what NoteMapper puts into them change meaning. */
        private const val VERSION = 1
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
