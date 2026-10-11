package com.monostr.app.data.linkpreview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Spec 3.4: link previews by URL — memory LRU, then one small file per URL in [dir], then the
 * network through [fetch]. Misses are cached too (shorter). Nothing here fetches on its own: only
 * [get] does, and the UI calls it only while the setting is on.
 */
class LinkPreviews(
    private val fetch: suspend (String) -> LinkPreview?,
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    parallel: Int = 4,
) {
    private class Entry(val preview: LinkPreview?, val at: Long)

    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MEMORY
    }
    /** A shared fetch and how many callers wait for it; the last one to leave cancels it (review I1). */
    private class Flight(val job: Deferred<LinkPreview?>) { var waiters = 0 }

    private val inFlight = HashMap<String, Flight>()
    /** Bumped by [clear]; a fetch started before writes nothing afterwards (review I2). Guarded by `memory`. */
    private var generation = 0
    private val lock = Mutex()
    private val gate = Semaphore(parallel)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The preview from memory if it is there and fresh; never touches disk or network. */
    fun cached(url: String): LinkPreview? = synchronized(memory) { memory[url]?.takeIf { fresh(it) }?.preview }

    suspend fun get(url: String): LinkPreview? {
        synchronized(memory) { memory[url]?.takeIf { fresh(it) } }?.let { return it.preview }
        val flight = lock.withLock {
            val gen = synchronized(memory) { generation }
            (inFlight[url] ?: Flight(scope.async(start = CoroutineStart.LAZY) { load(url, gen) }).also { inFlight[url] = it })
                .also { it.waiters++ }
        }
        flight.job.start()
        try {
            return flight.job.await()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    flight.waiters--
                    if (inFlight[url] === flight && (flight.waiters == 0 || flight.job.isCompleted)) {
                        inFlight.remove(url)
                        if (!flight.job.isCompleted) flight.job.cancel()
                    }
                }
            }
        }
    }

    /** Forgets everything and stops running fetches; their results are dropped (logout). */
    suspend fun clear() {
        // memory and generation first, without suspending: a cancelled caller cannot skip them
        synchronized(memory) {
            generation++
            memory.clear()
        }
        withContext(NonCancellable) {
            lock.withLock {
                inFlight.values.forEach { it.job.cancel() }
                inFlight.clear()
            }
            withContext(Dispatchers.IO) { synchronized(memory) { dir.listFiles()?.forEach { it.delete() } } }
        }
    }

    private suspend fun load(url: String, gen: Int): LinkPreview? {
        val file = File(dir, sha256(url))
        withContext(Dispatchers.IO) { readEntry(file, url) }?.takeIf { fresh(it) }?.let { remember(url, it, gen); return it.preview }
        val preview = gate.withPermit { fetch(url) }
        val entry = Entry(preview, now())
        // memory and disk under one lock with the generation check, so a clear() in between leaves no trace
        withContext(Dispatchers.IO) { synchronized(memory) { if (generation == gen) { memory[url] = entry; writeEntry(file, url, entry) } } }
        return preview
    }

    private fun remember(url: String, e: Entry, gen: Int) = synchronized(memory) { if (generation == gen) memory[url] = e }

    private fun fresh(e: Entry) = now() - e.at <= if (e.preview != null) HIT_TTL_MS else MISS_TTL_MS

    // line format: v1, at, requested url, then "MISS" or: final url, title, image ("" = none), large (1/0)
    private fun readEntry(file: File, url: String): Entry? = runCatching {
        if (!file.isFile) return null
        val l = file.readLines()
        if (l.size < 4 || l[0] != "v1" || l[2] != url) return null
        val at = l[1].toLong()
        if (l[3] == "MISS") Entry(null, at)
        else Entry(LinkPreview(l[3], l[4], l[5].ifEmpty { null }, l[6] == "1"), at)
    }.getOrNull()

    private fun writeEntry(file: File, url: String, e: Entry) {
        runCatching {
            dir.mkdirs()
            val p = e.preview
            val lines = if (p == null) listOf("v1", "${e.at}", url, "MISS")
            else listOf("v1", "${e.at}", url, p.url, p.title, p.imageUrl.orEmpty(), if (p.large) "1" else "0")
            // titles are whitespace-collapsed by the parser and URLs carry no newlines, so one value per line holds
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(lines.joinToString("\n"))
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MEMORY = 200
        const val HIT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MISS_TTL_MS = 24L * 60 * 60 * 1000
    }
}
