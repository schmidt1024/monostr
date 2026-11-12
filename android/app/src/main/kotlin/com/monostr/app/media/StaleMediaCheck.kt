package com.monostr.app.media

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.monostr.app.data.MediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Drops a picture from Coil's caches once its URL is gone (owner 2026-11-12: a picture removed from our media server kept
 * showing from the cache).
 *
 * Why a check after the fact: Coil 3's default cache strategy serves a disk entry without asking the network, whatever the
 * headers say, so a stale copy never meets the 404 by itself. Revalidating inside the load would put a round trip (through
 * Tor: seconds) in front of every cached picture while scrolling. Instead, after a picture was shown from the DISK cache,
 * one HEAD goes out in the background, so the picture shows at once and nothing waits for it:
 * - only for our own media hosts (the default server and the one the user uploads to): a foreign host is never asked more
 *   than the load itself asked it (privacy first; it would learn that we looked again), and pictures we host are the ones
 *   the user can delete;
 * - once per URL per process; a memory-cache hit is never checked (it came from disk or network earlier in this run);
 * - at most [PARALLEL] at a time, over the app's gated client (Tor when Tor is on);
 * - only a definitive 404 or 410 evicts (disk and every memory entry of the URL); anything else keeps the copy. The shown
 *   frame stays until the cell is composed again, then the load meets the 404 and the placeholder shows.
 */
class StaleMediaCheck(
    private val scope: CoroutineScope,
    /** Origins (`https://host[:port]`) we may ask; read on every candidate, so a changed media server counts at once. */
    private val origins: () -> Collection<String>,
    /** The HTTP status of a HEAD on the URL, or null when no answer came (offline, Tor not ready). */
    private val status: suspend (String) -> Int?,
    private val evict: (url: String, diskKey: String?) -> Unit,
) {
    private val checked = HashSet<String>()
    private var asked = 0
    private val permits = Semaphore(PARALLEL)

    /** Called on the main thread for every successful load: cheap until a candidate is found. */
    fun onShown(url: String, source: DataSource, diskKey: String?) {
        // the source first: most loads come from memory or the network, and the origins are a set built per call (perf review)
        if (source != DataSource.DISK || !isCandidate(url, source, origins())) return
        synchronized(checked) {
            if (checked.size >= MAX_REMEMBERED) checked.clear()
            // a run asks at most [MAX_PER_RUN]: a long scroll through old own pictures must not keep the circuit busy
            if (asked >= MAX_PER_RUN || !checked.add(url)) return
            asked++
        }
        scope.launch {
            val code = permits.withPermit { status(url) }
            when {
                // no answer: forget it, the next disk hit asks again
                code == null -> synchronized(checked) { checked.remove(url); asked-- }
                isGone(code) -> evict(url, diskKey)
            }
        }
    }

    companion object {
        const val PARALLEL = 2
        /** Bounds the memory of a long session; a cleared set only means a URL may be asked once more. */
        const val MAX_REMEMBERED = 4_000
        /** HEADs one run of the app sends at most (each one small, without a body). */
        const val MAX_PER_RUN = 100

        fun isCandidate(url: String, source: DataSource, origins: Collection<String>): Boolean =
            source == DataSource.DISK && origins.any { url.length > it.length && url.startsWith(it, ignoreCase = true) && url[it.length] == '/' }

        /** Only these say the file is gone for good; 403, 5xx, a timeout may pass. */
        fun isGone(code: Int): Boolean = code == 404 || code == 410

        /** The default server plus the user's upload server, as stored (see [MediaServer.normalize]). */
        fun ownOrigins(uploadServer: String?): Set<String> = setOfNotNull(MediaServer.DEFAULT, uploadServer?.let(MediaServer::normalize))

        /** A HEAD over [http], no body read; IO failures (also the gate's refusals) are no answer. */
        fun headStatus(http: OkHttpClient, url: String): Int? = try {
            http.newCall(Request.Builder().url(url).head().header("Cache-Control", "no-cache").build()).execute().use { it.code }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Disk entry by its key, memory entries by the URL whatever size they were decoded at. */
        fun evictFrom(loader: ImageLoader, url: String, diskKey: String?) {
            loader.diskCache?.remove(diskKey ?: url)
            loader.memoryCache?.let { mem -> mem.keys.filter { it.key == url }.forEach { mem.remove(it) } }
        }
    }
}

/** Feeds [StaleMediaCheck] from the loader's events. */
class StaleMediaListener(private val check: StaleMediaCheck) : coil3.EventListener() {
    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val url = request.data as? String ?: return
        check.onShown(url, result.dataSource, result.diskCacheKey)
    }
}
