package com.monostr.app.data

import com.monostr.monero.Address
import com.monostr.tips.event.EventSigner
import com.monostr.tips.watcher.WatcherClient
import com.monostr.tips.watcher.WatcherInfo
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** What the app needs from watchers (spec 3.6); a fake replaces it in tests. */
interface WatcherGateway {
    /** `GET /v1/info`, cached per URL for 24 h. */
    suspend fun info(url: String): WatcherInfo
    /** `POST /v1/accounts`; returns the watcher's Nostr pubkey. The view key leaves the device only here, over TLS. */
    suspend fun register(url: String, address: Address, viewSecret: ByteArray): String
    /** `DELETE /v1/accounts`. */
    suspend fun unregister(url: String)
}

/**
 * One [WatcherClient] per watcher URL, all sharing one [OkHttpClient], so the `/v1/info`
 * cache is hit (spec 5.5). Lives as long as the session: the signer is the session's.
 */
class WatcherClients(private val http: OkHttpClient, private val signer: EventSigner) : WatcherGateway {
    private val clients = ConcurrentHashMap<String, WatcherClient>()

    private fun client(url: String): WatcherClient = clients.getOrPut(url.trim().trimEnd('/')) { WatcherClient(url.trim(), signer, http) }

    override suspend fun info(url: String): WatcherInfo = client(url).info()
    override suspend fun register(url: String, address: Address, viewSecret: ByteArray): String = client(url).register(address, viewSecret)
    override suspend fun unregister(url: String) = client(url).unregister()
}
