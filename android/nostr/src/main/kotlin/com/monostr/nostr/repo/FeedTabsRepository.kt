package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.StartupGate
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** At most this many pinned lists (spec 2026-10-18 §2.1). */
const val MAX_TABS = 5

/** The local copy of the pinned tabs: addresses in tab order, [createdAt] of the event it matches (0 before any). */
data class CachedTabs(val tabs: List<String>, val createdAt: Long)

/** Where the app keeps [CachedTabs] per account (DataStore); it leaves the device with the account at logout. */
interface FeedTabsCache {
    suspend fun load(pubkey: String): CachedTabs?
    suspend fun save(pubkey: String, tabs: CachedTabs)
    suspend fun remove(pubkey: String)
}

/** The lists pinned as home feed tabs (spec §3.3). Every change shows at once and is rolled back when no relay took it. */
interface FeedTabsRepository {
    fun tabs(): Flow<List<ListAddress>>
    suspend fun pin(address: ListAddress): SaveResult
    suspend fun unpin(address: ListAddress): SaveResult
    suspend fun move(address: ListAddress, delta: Int): SaveResult
}

/** The tabs event's content before encryption: `{"v":1,"tabs":[…]}`. */
internal object TabsPayload {
    sealed interface Parsed {
        data class Tabs(val tabs: List<String>) : Parsed
        data object UnknownVersion : Parsed
        data object Invalid : Parsed
    }

    fun encode(tabs: List<String>): String = buildJsonObject {
        put("v", 1)
        put("tabs", JsonArray(tabs.map { JsonPrimitive(it) }))
    }.toString()

    /** Version 1 with the first [MAX_TABS] valid, distinct addresses; anything else is not taken. */
    fun parse(text: String): Parsed {
        val o = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return Parsed.Invalid
        }
        val v = (o["v"] as? JsonPrimitive)?.intOrNull ?: return Parsed.Invalid
        if (v != 1) return Parsed.UnknownVersion
        val arr = o["tabs"] as? JsonArray ?: return Parsed.Invalid
        return Parsed.Tabs(clean(arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }))
    }

    fun clean(tabs: List<String>): List<String> = tabs.filter { ListAddress.parse(it) != null }.distinct().take(MAX_TABS)
}

class NostrFeedTabsRepository(
    private val engine: NostrEngine,
    private val signer: Signer,
    private val cache: FeedTabsCache,
    private val defaultRelays: List<String> = emptyList(),
    /** Where the relay reconcile runs after the local copy is shown (session: the engine's background scope); null (tests) = only [reconcile]. */
    private val reconcileScope: CoroutineScope? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    private val fetchTimeout: Duration = Duration.ofSeconds(6),
    /** Spec perf2 A3: the start reconcile waits until the home feed's first page is in; a change opens it (A2 (5)). */
    private val gate: StartupGate = StartupGate.OPEN,
) : FeedTabsRepository {
    private val privateTags = PrivateTags(signer)
    private val _tabs = MutableStateFlow<CachedTabs?>(null)
    private val mutex = Mutex()
    private val reconcileStarted = AtomicBoolean(false)
    private var lastCreatedAt = 0L

    /** The start reconcile; a change waits for it (bounded by its fetch timeout) so it knows about a newer foreign format. */
    @Volatile private var pendingReconcile: kotlinx.coroutines.Job? = null

    /**
     * The newest relay event has a format this version does not know, or it could not be read even when asked:
     * changes stay local so it is never overwritten (spec §3.3).
     */
    private var foreignFormat = false

    /** The newest relay event, which the silent reconcile could not decrypt; the first change asks the signer once. */
    private var unread: Unread? = null

    private class Unread(val content: String, val createdAt: Long)

    private fun filter() = Filter().kind(Kind(KIND.toUShort())).author(PublicKey.parse(signer.pubkey)).identifier(D_TAG)

    override fun tabs(): Flow<List<ListAddress>> = flow {
        mutex.withLock { loadLocalLocked() }
        val scope = reconcileScope
        if (scope != null && reconcileStarted.compareAndSet(false, true)) pendingReconcile = scope.launch { gate.await(); quietly { reconcile() } }
        emitAll(_tabs.filterNotNull().map { c -> c.tabs.mapNotNull(ListAddress::parse) }.distinctUntilChanged())
    }

    private suspend fun loadLocalLocked(): CachedTabs {
        _tabs.value?.let { return it }
        val local = try {
            cache.load(signer.pubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val tabs = local?.let { it.copy(tabs = TabsPayload.clean(it.tabs)) } ?: CachedTabs(emptyList(), 0)
        _tabs.value = tabs
        return tabs
    }

    /** Reads the relay event; a newer one in a known format replaces the local copy (spec §3.3). Silent: never asks the signer. */
    suspend fun reconcile() {
        fetchWide(engine, filter(), defaultRelays, fetchTimeout)
        val event = newest(engine.query(filter())) ?: return
        val createdAt = event.createdAt().asSecs().toLong()
        val decrypted = privateTags.decryptText(event.content(), interactive = false)
        mutex.withLock {
            lastCreatedAt = maxOf(lastCreatedAt, createdAt)
            loadLocalLocked()
            val text = (decrypted as? Decrypted.Ok)?.value
            if (text == null) {
                unread = Unread(event.content(), createdAt)
                return@withLock
            }
            unread = null
            adoptLocked(text, createdAt)
        }
    }

    /** Takes the relay event's [text] per spec §3.3: a newer version 1 replaces the local copy, an unknown version is never overwritten. */
    private suspend fun adoptLocked(text: String, createdAt: Long) {
        when (val p = TabsPayload.parse(text)) {
            is TabsPayload.Parsed.Tabs -> {
                if (createdAt <= loadLocalLocked().createdAt) return
                foreignFormat = false
                val next = CachedTabs(p.tabs, createdAt)
                _tabs.value = next
                quietly { cache.save(signer.pubkey, next) }
            }
            TabsPayload.Parsed.UnknownVersion -> foreignFormat = true
            TabsPayload.Parsed.Invalid -> Unit
        }
    }

    /** The first change after a silent read failed: one signer prompt (a user action); still unread keeps changes local. */
    private suspend fun readUnreadLocked() {
        val u = unread ?: return
        unread = null
        when (val r = privateTags.decryptText(u.content, interactive = true)) {
            is Decrypted.Ok -> adoptLocked(r.value, u.createdAt)
            else -> foreignFormat = true
        }
    }

    override suspend fun pin(address: ListAddress): SaveResult = change { tabs ->
        val key = address.toString()
        when {
            key in tabs -> tabs
            tabs.size >= MAX_TABS -> null
            else -> tabs + key
        }
    }

    override suspend fun unpin(address: ListAddress): SaveResult = change { tabs -> tabs - address.toString() }

    override suspend fun move(address: ListAddress, delta: Int): SaveResult = change { tabs ->
        val from = tabs.indexOf(address.toString())
        if (from < 0) tabs else tabs.toMutableList().apply { add((from + delta).coerceIn(0, tabs.lastIndex), removeAt(from)) }
    }

    /** [f] gives the new tab list, null for a sixth pin. Shown at once; rolled back when no relay accepted it. */
    private suspend fun change(f: (List<String>) -> List<String>?): SaveResult {
        val pending = pendingReconcile
        // the change waits for the start reconcile; it must not wait for the feed's first page on top of it
        if (pending != null && !pending.isCompleted) gate.open("write")
        pending?.join()
        return changeLocked(f)
    }

    private suspend fun changeLocked(f: (List<String>) -> List<String>?): SaveResult = mutex.withLock {
        readUnreadLocked()
        val before = loadLocalLocked()
        val tabs = f(before.tabs) ?: return@withLock SaveResult.TooMany
        if (tabs == before.tabs) return@withLock SaveResult.Ok
        val ts = maxOf(now(), lastCreatedAt + 1, before.createdAt + 1)
        val next = CachedTabs(tabs, ts)
        _tabs.value = next
        if (foreignFormat) {
            quietly { cache.save(signer.pubkey, next) }
            return@withLock SaveResult.Ok
        }
        val ok = try {
            val content = privateTags.encryptText(TabsPayload.encode(tabs))
            val builder = EventBuilder(Kind(KIND.toUShort()), content)
                .tags(listOf(Tag.parse(listOf("d", D_TAG))))
                .customCreatedAt(Timestamp.fromSecs(ts.toULong()))
            send(builder).sentToAny
        } catch (e: CancellationException) {
            _tabs.value = before
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            _tabs.value = before
            return@withLock SaveResult.Failed
        }
        lastCreatedAt = ts
        quietly { cache.save(signer.pubkey, next) }
        SaveResult.Ok
    }

    companion object {
        const val KIND = 30078
        const val D_TAG = "monostr/feed-tabs"
    }
}
