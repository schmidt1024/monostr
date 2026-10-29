package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.tips.Kinds
import com.monostr.tips.RelayHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import java.time.Duration

interface ArticleRepliesRepository {
    /**
     * The replies under the article [ref], oldest first: NIP-22 comments (kind 1111, `#A` = coordinate) and old-style
     * kind 1 notes (`#a` = coordinate). The first emission is the database; the relays follow and every change after them
     * emits again. [allowForeign] false (a reader opened from a direct message): only the user's own relays are asked, no
     * hint and no author relay. Withdrawn notes are left out.
     */
    fun observe(ref: AddressRef, allowForeign: Boolean = true): Flow<List<Note>>
}

/**
 * Spec 2026-10-29 article reader: the replies list has its own `#A`/`#a` fetch and is not routed through the thread
 * repository. After the database: the own relays, then (only with [allowForeign]) the naddr's hint relays and up to
 * [MAX_AUTHOR_RELAYS] read relays of the author's stored kind 10002 (replies are sent to them, NIP-65), leased like the
 * hints of [NostrAddressableRepository].
 */
class NostrArticleRepliesRepository(
    private val engine: NostrEngine,
    private val timeout: Duration = Duration.ofSeconds(8),
    private val relayFilter: (String) -> Boolean = RelayHosts::isPublicWss,
    private val foreign: ForeignRelays? = null,
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : ArticleRepliesRepository {

    override fun observe(ref: AddressRef, allowForeign: Boolean): Flow<List<Note>> = channelFlow {
        val coordinate = ref.coordinate
        val changed = Channel<Unit>(Channel.CONFLATED)
        var last: List<Note>? = null
        suspend fun emitIfNew() {
            val now = read(coordinate)
            if (now != last) { last = now; send(now) }
        }
        emitIfNew()
        launch {
            for (u in changed) { delay(windowMs); emitIfNew() }
        }
        // listening before the REQ goes out, so nothing between the fetch and the subscription is lost
        launch {
            var subIds = emptyList<String>()
            try {
                engine.incoming
                    .onSubscription {
                        subIds = listOf(commentsFilter(coordinate, since = now() - OVERLAP_SECONDS), notesFilter(coordinate, since = now() - OVERLAP_SECONDS)).mapNotNull { f ->
                            try { engine.subscribe(f) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                        }
                    }
                    .collect { inc -> if (touches(inc.json.kind, inc.json.tags, coordinate)) changed.trySend(Unit) }
            } finally {
                withContext(NonCancellable) { subIds.forEach { id -> quietly { engine.unsubscribe(id) } } }
            }
        }
        launch { engine.published.collect { ev -> if (touches(ev.kind, ev.tags, coordinate)) changed.trySend(Unit) } }
        // the fetch: own relays first (a failure of one request must not cost the other), then the foreign ones
        quietly { engine.fetch(commentsFilter(coordinate), timeout) }
        quietly { engine.fetch(notesFilter(coordinate), timeout) }
        emitIfNew()
        if (allowForeign) {
            askForeign(ref)
            emitIfNew()
        }
    }

    private suspend fun read(coordinate: String): List<Note> {
        val rows = (engine.queryBoth(commentsFilter(coordinate)) + engine.queryBoth(notesFilter(coordinate))).distinctBy { it.second.id }
        return withContext(kotlinx.coroutines.Dispatchers.Default) {
            Withdrawn.filterDecoded(engine, rows).mapNotNull { (_, ev) -> NoteMapper.note(ev) }.sortedBy { it.createdAt }
        }
    }

    /** The hint relays of the naddr and the author's read relays, in one round on top of the own relays. */
    private suspend fun askForeign(ref: AddressRef) {
        val own = engine.relayUrls()
        val extra = (ref.relays.filter(relayFilter).take(MAX_HINTS) + readRelays(ref.pubkey).take(MAX_AUTHOR_RELAYS)).filter { it !in own }.distinct()
        if (extra.isEmpty()) return
        val held = if (foreign != null) foreign.lease(extra) else engine.attachTemporary(extra)
        if (held.isEmpty()) return
        try {
            withTimeoutOrNull(CONNECT_WAIT_MS) { while (!engine.connectedRelayUrls().containsAll(held)) delay(50) }
            quietly { engine.fetchFrom(held, commentsFilter(ref.coordinate), timeout, attach = false) }
            quietly { engine.fetchFrom(held, notesFilter(ref.coordinate), timeout, attach = false) }
        } finally {
            withContext(NonCancellable) {
                try {
                    if (foreign != null) foreign.release(held) else engine.detachTemporary(held)
                } catch (e: Exception) {
                    // the engine may be closed by now
                }
            }
        }
    }

    /** Read relays (no marker or `read`) of the author's newest stored kind 10002, public wss only; the database alone is read. */
    private suspend fun readRelays(pubkey: String): List<String> {
        val list = engine.query(Filter().kind(Kind(10002u)).authors(listOf(PublicKey.parse(pubkey))).limit(1u))
            .filter { it.author().toHex() == pubkey }.maxByOrNull { it.createdAt().asSecs() } ?: return emptyList()
        return list.tags().toVec().map { it.asVec() }
            .filter { it.size >= 2 && it[0] == "r" && (it.getOrNull(2) == null || it[2] == "read") }
            .mapNotNull { runCatching { RelayUrl.parse(it[1].trim()) }.getOrNull()?.toString()?.trimEnd('/') }
            .filter(relayFilter).distinct()
    }

    private fun touches(kind: Int, tags: List<List<String>>, coordinate: String): Boolean = when (kind) {
        Kinds.COMMENT -> tags.any { it.size >= 2 && it[0] == "A" && it[1] == coordinate }
        1 -> tags.any { it.size >= 2 && it[0] == "a" && it[1] == coordinate }
        else -> false
    }

    internal companion object {
        const val WINDOW_MS = 150L
        const val OVERLAP_SECONDS = 60L
        const val MAX_HINTS = 3
        const val MAX_AUTHOR_RELAYS = 2
        const val CONNECT_WAIT_MS = 2_000L

        /** Comments of an article: kind 1111 with the root scope `A` = coordinate. */
        fun commentsFilter(coordinate: String, since: Long? = null): Filter = coordinateFilter(listOf(Kinds.COMMENT), coordinate, "A", since)

        /** Old-style replies: kind 1 naming the coordinate in `a`. */
        fun notesFilter(coordinate: String, since: Long? = null): Filter = coordinateFilter(listOf(1), coordinate, "a", since)
    }
}
