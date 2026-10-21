package com.monostr.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Spec perf2 A5: when a relay fetch of this account last completed, per key (`key → epoch seconds`), in a small JSON
 * file beside the account's LMDB. Timestamps only, no content; logout deletes it (spec §8 D7). Best effort: a file that
 * cannot be read counts as empty, a failed write leaves the old one.
 */
class SyncMarks(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Mutex()

    /** Set by [delete] (logout): a write still in flight must not bring the file back. */
    @Volatile private var closed = false

    suspend fun get(key: String): Long? = lock.withLock { withContext(io) { readAll()[key] } }

    /** Every key with its time (0.24.3: the relay-seen file reads all relays at once). */
    suspend fun all(): Map<String, Long> = lock.withLock { withContext(io) { readAll() } }

    suspend fun put(key: String, epochSeconds: Long) = lock.withLock {
        withContext(io) {
            if (closed) return@withContext
            try {
                val next = readAll() + (key to epochSeconds)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString())
                if (closed || !tmp.renameTo(file)) tmp.delete()
                if (closed) file.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the next start simply fetches the whole window again
            }
        }
    }

    fun delete() {
        closed = true
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun readAll(): Map<String, Long> = try {
        if (!file.exists()) emptyMap()
        else Json.parseToJsonElement(file.readText()).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    companion object {
        const val FILE_NAME = "sync-marks.json"
    }
}
