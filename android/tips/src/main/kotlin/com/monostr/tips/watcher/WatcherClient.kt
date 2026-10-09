package com.monostr.tips.watcher

import com.monostr.monero.Address
import com.monostr.monero.MoneroKeys
import com.monostr.monero.toHex
import com.monostr.tips.event.EventSigner
import com.monostr.tips.event.isHex64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** HTTP client for the watcher API (docs/protocol/monostr-tips.md, "Watcher-HTTP-API"). */
class WatcherClient(
    baseUrl: String,
    private val signer: EventSigner,
    http: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val allowCleartext: Boolean = false,
) {
    private val base: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) { "invalid watcher base url: $baseUrl" }

    // A watcher must never receive a redirect that moves the request (and, for `register`, the
    // view key) to a different scheme or host: a 3xx on POST is surfaced as WatcherException.Http
    // instead of being followed. newBuilder() shares the connection pool and dispatcher.
    private val http: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private var cachedInfo: Pair<WatcherInfo, Long>? = null

    /** `GET /v1/info`, cached for [maxAgeSeconds] (spec 5.5: 24 h). */
    suspend fun info(maxAgeSeconds: Long = INFO_MAX_AGE): WatcherInfo {
        cachedInfo?.let { (info, fetchedAt) ->
            if (clock() - fetchedAt < maxAgeSeconds) return info
        }
        val endpoint = base.newBuilder().addPathSegments("v1/info").build()
        val request = Request.Builder().url(endpoint).get().build()
        val info = execute(request) { body -> json.decodeFromString(WatcherInfo.serializer(), body) }
        cachedInfo = info to clock()
        return info
    }

    /**
     * `POST /v1/accounts`: registers [address] with its private view key at the watcher.
     * Verifies locally that the key belongs to the address before anything leaves the device.
     * The view key only ever leaves the device over TLS, unless [allowCleartext] was set for a
     * loopback or emulator-host watcher. Returns the watcher's Nostr pubkey to put into the
     * Payment-Info event.
     */
    suspend fun register(address: Address, viewSecret: ByteArray): String {
        require(MoneroKeys.viewKeyMatches(address, viewSecret)) { "view key does not match the address" }
        requireHttpsOrLocal(base)
        val bodyText = json.encodeToString(RegisterRequest.serializer(), RegisterRequest(address.encode(), viewSecret.toHex()))
        val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
        val request = authorizedAccountsRequest("POST", bodyBytes)
            .post(bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request) { body ->
            val pubkey = json.decodeFromString(RegisterResponse.serializer(), body).watcherPubkey
            if (!isHex64(pubkey)) throw WatcherException.Protocol("invalid watcher pubkey")
            pubkey
        }
    }

    /** `DELETE /v1/accounts`: removes the caller's account at the watcher. */
    suspend fun unregister() {
        val request = authorizedAccountsRequest("DELETE", null).delete().build()
        execute(request) { }
    }

    private fun requireHttpsOrLocal(url: HttpUrl) {
        if (url.scheme == "https") return
        if (allowCleartext) return
        if (url.host in LOOPBACK_HOSTS) return
        throw IllegalArgumentException("watcher url must use https")
    }

    /** Builds a NIP-98 authorized request builder for `/v1/accounts`, using the canonical endpoint URL as the `u` tag. */
    private suspend fun authorizedAccountsRequest(method: String, bodyBytes: ByteArray?): Request.Builder {
        val endpoint = base.newBuilder().addPathSegments("v1/accounts").build()
        val auth = Nip98.authorization(signer, endpoint.toString(), method, bodyBytes, clock())
        return Request.Builder().url(endpoint).header("Authorization", auth)
    }

    private suspend fun <T> execute(request: Request, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val (code, isSuccessful, text) = try {
            http.newCall(request).execute().use { r ->
                val body = r.body?.source()?.let { source ->
                    if (source.request(MAX_BODY_BYTES + 1)) {
                        throw WatcherException.Protocol("watcher response too large")
                    }
                    source.readUtf8()
                } ?: ""
                Triple(r.code, r.isSuccessful, body)
            }
        } catch (e: IOException) {
            throw WatcherException.Network(e)
        }
        if (!isSuccessful) {
            val serverMessage = runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
            throw WatcherException.Http(code, serverMessage ?: "HTTP $code")
        }
        try {
            parse(text)
        } catch (e: SerializationException) {
            throw WatcherException.Protocol("invalid watcher response: ${e.message}")
        }
    }

    companion object {
        const val INFO_MAX_AGE = 86_400L
        const val MAX_BODY_BYTES = 64 * 1024L
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost", "10.0.2.2")
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
