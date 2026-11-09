package com.monostr.nostr.net

import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.Connection
import rust.nostr.sdk.ConnectionMode
import rust.nostr.sdk.RelayOptions
import rust.nostr.sdk.RelayUrl
import java.io.IOException

private const val LOOPBACK = "127.0.0.1"

/**
 * Whether this process ever gave a relay a direct route, whoever asked: session, temporary relay, [com.monostr.nostr.DetachedSender], background run.
 * A relay task of rust-nostr that once had one cannot be cancelled and can dial again on it later (`docs/testing/tor-leak-test.md`, F1), so a
 * process where this is true cannot go to Tor in place. It never goes back to false. No relays, or routes through a proxy only, leave it false.
 */
object DirectRelayRoute {
    @Volatile private var used = false
    val wasUsed: Boolean get() = used

    /** Before the relay enters the pool: whoever reads this after a dial has seen it. */
    internal fun noteRoute(socksPort: Int?) { if (socksPort == null) used = true }
}

/**
 * The SOCKS port a session's engine is built with: none in [NetworkMode.Direct], [NetworkMode.Tor.sessionPort] in Tor. Waits at most
 * [waitMs] for the mode; still unknown then throws [IOException], so no engine is built without a known route.
 */
suspend fun NetworkGate.sessionSocksPort(waitMs: Long): Int? =
    when (val mode = withTimeoutOrNull(waitMs) { awaitKnown() }) {
        null, NetworkMode.Unknown -> throw IOException("network mode unknown")
        NetworkMode.Direct -> null
        is NetworkMode.Tor -> mode.sessionPort
    }

/**
 * Relays added with `addRelay`, `addReadRelay` or `addWriteRelay` go through the loopback SOCKS [port], by host name; null changes
 * nothing. Does not reach relays added with their own options: see [addTemporaryRelay].
 */
internal fun ClientOptions.throughSocks(port: Int?): ClientOptions =
    if (port == null) this else connection(Connection().addr("$LOOPBACK:$port"))

/**
 * The only ways a relay enters a pool: the client's route ([throughSocks]) was fixed with this [socksPort], and adding the relay is what puts it to use.
 * `NoBareClientsTest` holds every other caller to that.
 */
internal suspend fun Client.addRoutedRelay(url: RelayUrl, socksPort: Int?): Boolean {
    DirectRelayRoute.noteRoute(socksPort)
    return addRelay(url)
}

internal suspend fun Client.addRoutedReadRelay(url: RelayUrl, socksPort: Int?): Boolean {
    DirectRelayRoute.noteRoute(socksPort)
    return addReadRelay(url)
}

internal suspend fun Client.addRoutedWriteRelay(url: RelayUrl, socksPort: Int?): Boolean {
    DirectRelayRoute.noteRoute(socksPort)
    return addWriteRelay(url)
}

/**
 * Adds a relay that reads and never writes. Per-relay options replace the client's connection setting, and their default is a direct
 * connection, so the route has to be set here (found by Tor Task 4, `docs/testing/tor-spike.md` section 5). The only place that builds
 * relay options: `NoBareClientsTest` holds every other one to that.
 */
internal suspend fun Client.addTemporaryRelay(url: RelayUrl, socksPort: Int?): Boolean {
    DirectRelayRoute.noteRoute(socksPort)
    val options = RelayOptions().read(true).write(false)
    return addRelayWithOpts(url, if (socksPort == null) options else options.connectionMode(ConnectionMode.Proxy(LOOPBACK, socksPort.toUShort())))
}
