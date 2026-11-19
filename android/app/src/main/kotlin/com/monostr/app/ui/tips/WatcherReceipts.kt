package com.monostr.app.ui.tips

import com.monostr.app.data.WatcherGateway
import com.monostr.tips.PaymentInfo
import com.monostr.tips.RelayHosts
import com.monostr.tips.Watcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-wide: a 10037's watcher → the relays its receipts land on, and which notes are due to be asked there again
 * (backlog 97). One per session, shared by every screen's [TipDecorations], so a note seen on two screens is asked once.
 */
class WatcherReceipts(
    private val watchers: WatcherGateway,
    /** The URL of someone else's watcher, asked without the user doing anything: see [isPublicBaseUrl]; no debug leniency. */
    private val watcherAllowed: (String) -> Boolean = ::isPublicBaseUrl,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Per normalised watcher URL: its filtered relays and when they were read; an empty list is a failure. */
    private val httpRelays = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Note id → when it was last asked, oldest first; guarded by itself. */
    private val asked = LinkedHashMap<String, Long>()
    /**
     * One watcher fetch at a time, session-wide: the rounds of several screens together must not take the whole
     * foreign-relay budget from the lookups the user waits for (quotes, polls, the locator).
     */
    private val fetching = Semaphore(1)

    /** Runs [block] (one watcher fetch) after any other screen's running fetch ended. */
    suspend fun <T> oneFetchAtATime(block: suspend () -> T): T = fetching.withPermit { block() }

    /** Public wss only, distinct, at most [Watcher.MAX_RELAYS]; empty when an HTTP watcher fails, times out or is not allowed. */
    suspend fun relaysOf(info: PaymentInfo.Enabled): List<String> = when (val w = info.watcher) {
        is Watcher.Personal -> capped(w.relays)
        is Watcher.Http -> if (!watcherAllowed(w.url)) emptyList() else httpRelaysOf(w.url)
    }

    private suspend fun httpRelaysOf(url: String): List<String> {
        val key = url.trim().trimEnd('/')
        fresh(key)?.let { return it }
        // concurrent screens make one request; the second finds the first one's answer
        return locks.getOrPut(key) { Mutex() }.withLock {
            fresh(key) ?: run {
                val relays = try {
                    withTimeout(INFO_TIMEOUT_MS) { capped(watchers.info(url).relays) }
                } catch (e: TimeoutCancellationException) {
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                httpRelays[key] = relays to nowMs()
                relays
            }
        }
    }

    private fun fresh(key: String): List<String>? {
        val (relays, at) = httpRelays[key] ?: return null
        val ttl = if (relays.isEmpty()) FAILED_TTL_MS else HTTP_TTL_MS
        return relays.takeIf { nowMs() - at < ttl }
    }

    /** Capped like a personal watcher's tag: someone's own "watcher" must not make every viewer dial hundreds of relays. */
    private fun capped(relays: List<String>): List<String> = relays.filter(RelayHosts::isPublicWss).distinct().take(Watcher.MAX_RELAYS)

    /** Those of [noteIds] not asked within [ASK_EVERY_MS], in input order. */
    fun due(noteIds: Collection<String>): List<String> {
        val now = nowMs()
        return synchronized(asked) { noteIds.filter { id -> asked[id]?.let { now - it >= ASK_EVERY_MS } ?: true } }
    }

    fun markAsked(noteIds: Collection<String>) = mark(noteIds, nowMs())

    /** [noteIds] could not be asked (no free foreign-relay slot): due again after [RETRY_MS] instead of [ASK_EVERY_MS]. */
    fun markRetry(noteIds: Collection<String>) = mark(noteIds, nowMs() - ASK_EVERY_MS + RETRY_MS)

    private fun mark(noteIds: Collection<String>, at: Long) {
        val now = nowMs()
        synchronized(asked) {
            for (id in noteIds) {
                // re-inserted at the end, so the map stays in ask order (a retry mark is older: dropped a little late at worst)
                asked.remove(id)
                asked[id] = at
            }
            if (asked.size > MAX_ASKED) {
                val it = asked.values.iterator()
                while (it.hasNext()) if (now - it.next() >= ASK_EVERY_MS) it.remove() else break
            }
        }
    }

    /** For tests: how many notes the throttle remembers. */
    internal val askedSize: Int get() = synchronized(asked) { asked.size }

    companion object {
        /**
         * https, a public host name or address (by its form; the lookup is checked by the client, see `publicAddressesOnly`)
         * and nothing but scheme, host and port: no user info, path, query or fragment.
         */
        fun isPublicBaseUrl(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return false
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
            if (uri.rawPath.orEmpty().trimEnd('/').isNotEmpty()) return false
            return RelayHosts.isPublic(url)
        }

        /** A note is asked on its watcher's relays at most this often, session-wide. */
        const val ASK_EVERY_MS = 300_000L
        /** A note whose fetch found no free foreign-relay slot is due again after this long. */
        const val RETRY_MS = 30_000L
        /** An HTTP watcher's `/v1/info` relays are kept this long; a failed or late answer only [FAILED_TTL_MS]. */
        const val HTTP_TTL_MS = 86_400_000L
        const val FAILED_TTL_MS = 600_000L
        const val INFO_TIMEOUT_MS = TipSheetController.WATCHER_TIMEOUT_MS
        /** Above this many remembered notes the expired ones are dropped. */
        const val MAX_ASKED = 2_000
    }
}
