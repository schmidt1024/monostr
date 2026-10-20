package com.monostr.app.data

import com.monostr.nostr.repo.CachedList
import com.monostr.nostr.repo.OwnListsCache
import com.monostr.nostr.repo.OwnListsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The account's own directory below [filesDir] (or below `noBackupFilesDir`, for what must not be backed up). */
fun accountDir(filesDir: File, pubkey: String): File = File(filesDir, "nostr-${pubkey.take(16)}")

/**
 * Spec perf 3.7: the own lists' summary, a JSON file in the account's directory (it holds decrypted private
 * members). [privateDir] must be `noBackupFilesDir`: unlike `filesDir` it is neither backed up nor part of a
 * device-to-device transfer, so the private members never leave this phone. Logout removes it ([remove]); a write still pending from that session is dropped until the
 * account's next session read the file again ([load]).
 */
class FileOwnListsCache(private val privateDir: File, private val io: CoroutineDispatcher = Dispatchers.IO) : OwnListsCache {
    /** Accounts removed since their last [load]. */
    private val removed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun file(pubkey: String) = File(accountDir(privateDir, pubkey), FILE)

    override suspend fun load(pubkey: String): List<CachedList>? = withContext(io) {
        removed.remove(pubkey)
        val f = file(pubkey)
        try {
            if (f.exists()) OwnListsJson.decode(f.readText()) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Test hook: runs after the new content is written, before it is moved into place. */
    internal var beforeCommit: () -> Unit = {}

    /** Best effort; written to its own temp file and moved, so a crash or a second save never leaves half a file. */
    override suspend fun save(pubkey: String, lists: List<CachedList>) {
        withContext(io) {
            if (pubkey in removed) return@withContext
            val f = file(pubkey)
            var tmp: File? = null
            try {
                f.parentFile?.mkdirs()
                tmp = File.createTempFile(f.name, ".tmp", f.parentFile)
                tmp.writeText(OwnListsJson.encode(lists))
                beforeCommit()
                // a logout that came in while writing wins: nothing of this write may stay behind
                if (pubkey in removed || !tmp.renameTo(f)) tmp.delete()
                if (pubkey in removed) f.delete()
            } catch (e: CancellationException) {
                tmp?.delete()
                throw e
            } catch (e: Exception) {
                tmp?.delete()
                // the next start reads the database instead
            }
        }
    }

    override suspend fun remove(pubkey: String) {
        withContext(io) {
            removed += pubkey
            val f = file(pubkey)
            f.delete()
            f.parentFile?.listFiles { _, n -> n.startsWith(f.name) && n.endsWith(".tmp") }?.forEach { it.delete() }
        }
    }

    companion object {
        const val FILE = "own-lists.json"
    }
}

/**
 * One session's access to the own lists' summary. A collector of the own lists may outlive its session (it runs on a
 * screen's scope, not the engine's): after [close] (logout, before [OwnListsCache.remove]) its late [load] would clear
 * the removed mark and its [save] write the decrypted private members again; both do nothing then. [close] waits for
 * a call already running, so none is left in flight when the file goes.
 */
class SessionOwnListsCache(private val delegate: OwnListsCache) : OwnListsCache {
    private val lock = Mutex()
    @Volatile private var closed = false

    override suspend fun load(pubkey: String): List<CachedList>? = lock.withLock { if (closed) null else delegate.load(pubkey) }

    override suspend fun save(pubkey: String, lists: List<CachedList>) = lock.withLock { if (!closed) delegate.save(pubkey, lists) }

    override suspend fun remove(pubkey: String) = delegate.remove(pubkey)

    suspend fun close() = lock.withLock { closed = true }
}
