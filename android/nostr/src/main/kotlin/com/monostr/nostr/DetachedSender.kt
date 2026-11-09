package com.monostr.nostr

import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import com.monostr.nostr.net.addRoutedReadRelay
import com.monostr.nostr.net.addRoutedWriteRelay
import com.monostr.nostr.net.awaitConnected
import com.monostr.nostr.net.throughSocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.RelayMessage
import com.monostr.tips.RelayHosts
import rust.nostr.sdk.RelayUrl
import java.io.IOException
import java.time.Duration

/**
 * Sends one finished event over its own short-lived connection, or holds a subscription over
 * such a connection: a fresh rust-nostr client without signer and without the engine's database,
 * connected to the given relays only. Nothing ties that connection to the user's session: it cannot answer a
 * NIP-42 challenge and shares no socket with the engine. Used for anonymous tip intents and the
 * receipts of anonymous profile tips (protocol 0.2). What it does not hide is the device's IP address, unless the [gate] is in
 * [NetworkMode.Tor]: then it goes through the isolated port, which never shares a circuit with the session. [torReady] says whether
 * that port is ours (Tor connected): otherwise another local app could be listening there, so nothing is opened. Asked in Tor only.
 */
class DetachedSender(private val ffi: CoroutineDispatcher = Dispatchers.IO, private val gate: NetworkGate, private val torReady: Flow<Boolean> = flowOf(false)) {
    /** [torOnly]: outside [NetworkMode.Tor] it throws [IOException] before anything exists, and a later change of mode ends the send as it always does. */
    suspend fun send(event: Event, relays: List<String>, connectTimeout: Duration = Duration.ofSeconds(5), torOnly: Boolean = false): PublishResult = withContext(ffi) {
        val id = event.id().toHex()
        val kind = event.kind().asU16().toInt()
        val targets = relays.mapNotNull { runCatching { RelayUrl.parse(it.trim()) }.getOrNull() }.filterNot { RelayHosts.isOnionHost(it.toString()) } // a watcher's relay is never a hidden service (Tor 7.4)
        if (targets.isEmpty()) return@withContext PublishResult(id, kind, emptyList(), emptyMap())
        withClient(torOnly) { client, port, ensureRoute ->
            try {
                targets.forEach { ignoringFailure { client.addRoutedWriteRelay(it, port) } }
                // adding does not suspend, so a change of mode during the loop has not stopped anything yet: connect would dial
                ensureRoute()
                // connect() only starts the connections; one relay is enough, see awaitConnected. This client is thrown away
                // afterwards, so a relay still connecting when the wait ends costs nothing.
                ignoringFailure { client.connect() }
                awaitConnected(targets, connectTimeout) { url -> runCatching { client.relay(url).isConnected() }.getOrDefault(false) }
                val connected = targets.filter { url -> runCatching { client.relay(url).isConnected() }.getOrDefault(false) }
                val unreached = (targets - connected.toSet()).associate { it.toString() to NOT_CONNECTED }
                if (connected.isEmpty()) return@withClient PublishResult(id, kind, emptyList(), unreached)
                // a cancelled sendEventTo still gets its first poll, which writes
                ensureRoute()
                val out = client.sendEventTo(connected, event)
                PublishResult(id, kind, out.success.map { it.toString() }, out.failed.mapKeys { it.key.toString() } + unreached)
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
        val targets = relays.mapNotNull { runCatching { RelayUrl.parse(it.trim()) }.getOrNull() }.filterNot { RelayHosts.isOnionHost(it.toString()) } // a watcher's relay is never a hidden service (Tor 7.4)
        if (targets.isEmpty()) return@channelFlow
        withClient { client, port, ensureRoute ->
            targets.forEach { ignoringFailure { client.addRoutedReadRelay(it, port) } }
            ensureRoute()
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

    /**
     * Waits until [subscribe] can run, so that a caller who retries does not do so while the mode is unknown. Never times out:
     * a mode that stays unknown keeps everything blocked, and the wait ends with the caller.
     */
    suspend fun awaitKnownMode() { gate.awaitKnown() }

    /**
     * Runs [block] with a fresh client that has no identity, on the route of the current mode; always disconnects and shuts it down
     * afterwards. [NetworkMode.Unknown] and a Tor not ready throw [IOException] before anything exists. A change of mode, or Tor no
     * longer ready, ends [block] with an [IOException]: its route is fixed at build and the pool would keep reconnecting over it, and nobody else closes this client when
     * the switch is turned (a receipts watch lives outside the session). [block] calls its last argument before it opens or writes
     * anything: that returns only while the route still holds.
     */
    private suspend fun <T> withClient(torOnly: Boolean = false, block: suspend (Client, socksPort: Int?, ensureRoute: suspend () -> Unit) -> T): T {
        val mode = gate.mode.value
        val port = when (mode) {
            NetworkMode.Unknown -> throw IOException("network mode unknown: nothing was sent")
            NetworkMode.Direct -> {
                if (torOnly) throw IOException("this send goes through Tor only: nothing was sent")
                null
            }
            is NetworkMode.Tor -> {
                if (!torReady.first()) throw IOException("tor is not connected: nothing was sent")
                mode.isolatedPort
            }
        }
        val client = ClientBuilder().opts(ClientOptions().automaticAuthentication(false).throughSocks(port)).build()
        try {
            return coroutineScope {
                // undispatched: it watches before block runs, so no change can fall in between
                val modeChange = launch(start = CoroutineStart.UNDISPATCHED) { gate.mode.first { it != mode }; throw IOException("network mode changed") }
                val torLost = if (port == null) null else launch(start = CoroutineStart.UNDISPATCHED) { torReady.firstOrNull { !it }?.let { throw IOException("tor is not connected any more") } }
                block(client, port) {
                    // the mode may have changed with the watcher not yet run: it is about to end this scope
                    if (gate.mode.value != mode || (port != null && !torReady.first())) awaitCancellation()
                    currentCoroutineContext().ensureActive()
                }.also { modeChange.cancel(); torLost?.cancel() }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { client.disconnect() }
                runCatching { client.shutdown() }
            }
        }
    }
}

/** A failure of [block] does not matter to the caller; a cancellation does, it carries the change of mode. */
private inline fun ignoringFailure(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
