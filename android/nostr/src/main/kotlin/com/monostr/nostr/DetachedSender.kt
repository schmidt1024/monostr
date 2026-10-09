package com.monostr.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayUrl
import java.time.Duration

/**
 * Sends one finished event over its own short-lived connection, or holds a subscription over
 * such a connection: a fresh rust-nostr client without signer and without the engine's database,
 * connected to the given relays only. Nothing ties that connection to the user's session: it cannot answer a
 * NIP-42 challenge and shares no socket with the engine. Used for anonymous tip intents and the
 * receipts of anonymous profile tips (protocol 0.2). What it does not hide is the device's IP address.
 */
class DetachedSender(private val ffi: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun send(event: Event, relays: List<String>, connectTimeout: Duration = Duration.ofSeconds(5)): PublishResult = withContext(ffi) {
        val id = event.id().toHex()
        val kind = event.kind().asU16().toInt()
        val targets = relays.mapNotNull { runCatching { RelayUrl.parse(it.trim()) }.getOrNull() }
        if (targets.isEmpty()) return@withContext PublishResult(id, kind, emptyList(), emptyMap())
        withClient { client ->
            try {
                targets.forEach { runCatching { client.addWriteRelay(it) } }
                runCatching { client.tryConnect(connectTimeout) }
                val out = client.sendEventTo(targets, event)
                PublishResult(id, kind, out.success.map { it.toString() }, out.failed.mapKeys { it.key.toString() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PublishResult(id, kind, emptyList(), mapOf("*" to (e.message ?: "send failed")))
            }
        }
    }

    /**
     * A live subscription over a connection of its own: what [relays] have for [filter] and what
     * arrives later, nothing stored anywhere. Like [send], it never runs over the user's session:
     * the relays cannot tell from the connection who is asking. Cold: the connection exists while
     * the flow is collected and goes away with its collector; lost connections are the pool's to
     * bring back (it asks the subscription again). Ends at once without a usable relay; fails when
     * the client stops listening while the collector is still there.
     */
    fun subscribe(filter: Filter, relays: List<String>): Flow<Event> = channelFlow {
        val targets = relays.mapNotNull { runCatching { RelayUrl.parse(it.trim()) }.getOrNull() }
        if (targets.isEmpty()) return@channelFlow
        withClient { client ->
            targets.forEach { runCatching { client.addReadRelay(it) } }
            // connect() only starts the connections; the subscription is sent once a relay is there
            client.connect()
            client.subscribeTo(targets, filter, null)
            client.handleNotifications(object : HandleNotification {
                override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                    // send, not trySend: a full buffer must hold the relay's burst back, not drop events
                    // (this client remembers what it saw; a dropped one would never be delivered again)
                    send(event)
                }

                override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) {}
            })
            // the loop only returns when the client stopped listening: for a collector that is still
            // there this is a failure, so that it can set the subscription up again
            error("the notification loop of the detached subscription ended")
        }
    }.flowOn(ffi)

    /** Runs [block] with a fresh client that has no identity; always disconnects and shuts it down afterwards. */
    private suspend fun <T> withClient(block: suspend (Client) -> T): T {
        val client = ClientBuilder().opts(ClientOptions().automaticAuthentication(false)).build()
        try {
            return block(client)
        } finally {
            withContext(NonCancellable) {
                runCatching { client.disconnect() }
                runCatching { client.shutdown() }
            }
        }
    }
}
