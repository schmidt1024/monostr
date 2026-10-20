package com.monostr.app.data

import com.monostr.nostr.model.ListAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Spec perf 3.7: one [FeedSnapshot] per pinned list, a file per list address in the account's directory, so a list
 * tab shows its last first page at once. A list no longer pinned loses its file ([retain]); logout removes them all
 * ([deleteAll]). The files show whom the user reads in a list, private lists too: the session puts [dir] below
 * `noBackupFilesDir`, never backed up nor transferred to another device.
 */
class ListSnapshots(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val open = HashMap<String, FeedSnapshot>()
    /** Set by [deleteAll] (logout): a tab of the old session gets a snapshot that never writes. */
    @Volatile private var closed = false

    /** The snapshot of [address]; the same instance while the list stays pinned. */
    fun of(address: ListAddress): FeedSnapshot = synchronized(open) {
        val name = fileName(address)
        if (closed) return@synchronized FeedSnapshot(File(dir, name), io).also { it.delete() }
        open.getOrPut(name) { FeedSnapshot(File(dir, name), io) }
    }

    /** Deletes the snapshots of every list not in [pinned]; a write still pending for one of them leaves no file. */
    suspend fun retain(pinned: Collection<ListAddress>) {
        val keep = pinned.map(::fileName).toSet()
        val dropped = synchronized(open) { open.keys.filter { it !in keep }.mapNotNull { open.remove(it) } }
        withContext(io) {
            dropped.forEach { it.delete() }
            dir.listFiles()?.filter { f -> f.name.startsWith(PREFIX) && f.name.removeSuffix(".tmp") !in keep }?.forEach { it.delete() }
        }
    }

    /** Logout: every list snapshot of the account goes. */
    suspend fun deleteAll() {
        val all = synchronized(open) {
            closed = true
            open.values.toList().also { open.clear() }
        }
        withContext(io) {
            all.forEach { it.delete() }
            dir.deleteRecursively()
        }
    }

    companion object {
        /** The directory inside the account's directory. */
        const val DIR = "list-snapshots"
        private const val PREFIX = "list-"

        /** The address holds `:` and any `d` (spaces, slashes): the file is named after its hash. */
        fun fileName(address: ListAddress): String =
            PREFIX + MessageDigest.getInstance("SHA-256").digest(address.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".json"
    }
}
