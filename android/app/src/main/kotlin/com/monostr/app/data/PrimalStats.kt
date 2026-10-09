package com.monostr.app.data

import com.monostr.nostr.repo.FollowCounts
import com.monostr.nostr.repo.FollowStatsIndexer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Primal's cache server knows every contact list it ever indexed: its `user_profile` stats are the
 * follower number the Primal app shows. Not a Nostr relay (the REQ carries a `cache` call instead of a
 * filter), so it is spoken to directly: one connection per lookup, closed after the EOSE or [timeout].
 * A proprietary service: [enabled] (the setting, read per lookup) keeps it from being contacted at all.
 */
class PrimalStats(
    private val http: OkHttpClient,
    private val url: String = CACHE_URL,
    private val timeout: Duration = Duration.ofSeconds(6),
    private val enabled: suspend () -> Boolean = { true },
) : FollowStatsIndexer {
    override suspend fun stats(pubkey: String): FollowCounts? = if (!enabled()) null else withTimeoutOrNull(timeout.toMillis()) {
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString().take(16)
            val done = AtomicBoolean(false)
            fun finish(socket: WebSocket, result: FollowCounts?) {
                if (done.compareAndSet(false, true)) {
                    socket.close(1000, null)
                    cont.resume(result)
                }
            }
            val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                var found: FollowCounts? = null
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""["REQ","$id",{"cache":["user_profile",{"pubkey":"$pubkey"}]}]""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
                    when ((frame.getOrNull(0) as? JsonPrimitive)?.contentOrNull) {
                        "EVENT" -> parse(frame.getOrNull(2))?.let { found = it }
                        "EOSE" -> finish(webSocket, found)
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(webSocket, found)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket, found)
            })
            cont.invokeOnCancellation { socket.cancel() }
        }
    }

    /** The stats event (kind 10000105) carries a JSON object in `content`; anything else is not the answer. */
    private fun parse(event: JsonElement?): FollowCounts? {
        val obj = (event as? JsonObject) ?: return null
        if (obj["kind"]?.jsonPrimitive?.longOrNull != STATS_KIND) return null
        val content = obj["content"]?.jsonPrimitive?.content ?: return null
        val stats = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        val followers = stats["followers_count"]?.jsonPrimitive?.longOrNull
        val following = stats["follows_count"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        if (followers == null && following == null) return null
        return FollowCounts(following, followers)
    }

    companion object {
        const val CACHE_URL = "wss://cache2.primal.net/v1"
        const val STATS_KIND = 10000105L
    }
}
