package com.monostr.app.net

import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Tor is on but cannot carry this request (yet), or the mode is not known: nothing was sent. The UI can tell this from a network failure. */
class TorNotReadyException(message: String = "tor is not ready") : IOException(message)

/** Only for a gate whose setting cannot be read: the normal wait is one file stat at start. */
private const val UNKNOWN_WAIT_MS = 10_000L
private const val WAIT_STEP_MS = 50L
private const val TOR_CONNECT_TIMEOUT_S = 30

/**
 * The one HTTP client of the app (timeouts as before); everything else takes it or a `newBuilder()` of it, which keeps all the
 * parts below. The route comes from the [gate] per connection:
 * - [ProxySelector]: the SOCKS port in Tor mode, so OkHttp hands the name to the proxy unresolved; with Tor not [torReady] no port at
 *   all, for a redirect or retry too, as another local app may be listening there: the direct route it falls to refuses. In Direct what
 *   it always was.
 * - Application interceptor: waits out [NetworkMode.Unknown] off the main thread (all calls run off it), fails at once in Tor not
 *   [torReady].
 *
 * OkHttp asks the selector only when `java.net.URI` finds a host; where it finds none (on the JDK a name with an underscore or a
 * last label starting with a digit, Android's parser is more lenient) the call goes straight to the direct route. So the direct
 * route itself is closed unless the mode is Direct: its [Dns] and its [SocketFactory] refuse, for
 * redirects too, which no application interceptor sees. Then a network interceptor refuses a pooled connection that does not
 * match the mode, one left over from before a switch (see [dropAllConnections]), before a byte is written.
 */
fun gatedOkHttp(gate: NetworkGate, torReady: () -> Boolean): OkHttpClient = gatedOkHttp(gate, UNKNOWN_WAIT_MS, torReady)

internal fun gatedOkHttp(gate: NetworkGate, unknownWaitMs: Long, torReady: () -> Boolean): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .proxySelector(GateProxySelector(gate, torReady, unknownWaitMs))
        .dns(GateDns(gate, unknownWaitMs))
        .socketFactory(GateSocketFactory(gate))
        .addInterceptor(GateInterceptor(gate, torReady, unknownWaitMs))
        .addNetworkInterceptor(RouteCheckInterceptor(gate))
        .build()

/** Also reaches clients derived with `newBuilder()`: they share the dispatcher and the pool. */
fun OkHttpClient.dropAllConnections() {
    dispatcher.cancelAll()
    connectionPool.evictAll()
}

/** Blocks the calling (never the main) thread until the mode is known, the wait ends or [canceled]; may still return Unknown. */
private fun NetworkGate.blockUntilKnown(waitMs: Long, canceled: () -> Boolean = { false }): NetworkMode {
    var left = waitMs
    while (left > 0 && !canceled()) {
        val step = minOf(left, WAIT_STEP_MS)
        val known = try {
            runBlocking { withTimeoutOrNull(step) { awaitKnown() } }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while waiting for the network mode")
        }
        if (known != null) return known
        left -= step
    }
    return mode.value
}

private fun torProxy(port: Int) = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port))

private class GateProxySelector(private val gate: NetworkGate, private val torReady: () -> Boolean, private val unknownWaitMs: Long) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = when (val mode = gate.mode.value.let { if (it == NetworkMode.Unknown) gate.blockUntilKnown(unknownWaitMs) else it }) {
        // still unknown: the direct route refuses, see GateDns and GateSocketFactory
        NetworkMode.Unknown -> listOf(Proxy.NO_PROXY)
        NetworkMode.Direct -> system(uri)
        is NetworkMode.Tor -> listOf(if (torReady()) torProxy(mode.sessionPort) else Proxy.NO_PROXY)
    }

    /** As OkHttp does without a selector, including its guard: the JDK's selector throws on a URI without a host. */
    private fun system(uri: URI): List<Proxy> =
        if (uri.host == null) listOf(Proxy.NO_PROXY) else getDefault()?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        if (gate.mode.value == NetworkMode.Direct) getDefault()?.connectFailed(uri, sa, ioe)
    }
}

private class GateInterceptor(private val gate: NetworkGate, private val torReady: () -> Boolean, private val unknownWaitMs: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val mode = gate.mode.value.let { current ->
            if (current != NetworkMode.Unknown) return@let current
            val known = gate.blockUntilKnown(unknownWaitMs) { chain.call().isCanceled() }
            if (chain.call().isCanceled()) throw IOException("Canceled")
            known
        }
        when (mode) {
            NetworkMode.Direct -> Unit
            is NetworkMode.Tor -> {
                if (!torReady()) throw TorNotReadyException()
                // the proxy answers the connect only once the circuit stands
                return chain.withConnectTimeout(TOR_CONNECT_TIMEOUT_S, TimeUnit.SECONDS).proceed(chain.request())
            }
            NetworkMode.Unknown -> throw TorNotReadyException("network mode unknown")
        }
        return chain.proceed(chain.request())
    }
}

/** No SOCKS route ever asks this; a lookup here is a direct route, or the check of a link preview in Direct. */
private class GateDns(private val gate: NetworkGate, private val unknownWaitMs: Long) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val mode = gate.mode.value.let { if (it == NetworkMode.Unknown) gate.blockUntilKnown(unknownWaitMs) else it }
        if (mode != NetworkMode.Direct) throw TorNotReadyException("no local name lookup in $mode")
        // a hidden service has no DNS name: asking the system resolver would tell the network which one it is (spec 2026-11-06 Tor 7.4)
        if (hostname.trimEnd('.').endsWith(".onion", ignoreCase = true)) throw UnknownHostException("a .onion address needs Tor")
        return Dns.SYSTEM.lookup(hostname)
    }
}

/** OkHttp opens SOCKS routes with `Socket(proxy)`, not through here: every socket from this factory is a direct one. */
private class GateSocketFactory(private val gate: NetworkGate) : SocketFactory() {
    private val system = getDefault()

    private fun allowed() { if (gate.mode.value != NetworkMode.Direct) throw TorNotReadyException("no direct socket in ${gate.mode.value}") }

    override fun createSocket(): Socket { allowed(); return system.createSocket() }
    override fun createSocket(host: String, port: Int): Socket { allowed(); return system.createSocket(host, port) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket { allowed(); return system.createSocket(host, port, localHost, localPort) }
    override fun createSocket(host: InetAddress, port: Int): Socket { allowed(); return system.createSocket(host, port) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket { allowed(); return system.createSocket(address, port, localAddress, localPort) }
}

/** Sees the connection a request is about to use. A wrong one is closed, so it cannot be picked from the pool again. */
private class RouteCheckInterceptor(private val gate: NetworkGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val allowed = when (val mode = gate.mode.value) {
            NetworkMode.Direct -> true
            NetworkMode.Unknown -> false
            is NetworkMode.Tor -> chain.connection()?.route()?.proxy == torProxy(mode.sessionPort)
        }
        if (!allowed) {
            runCatching { chain.connection()?.socket()?.close() }
            throw TorNotReadyException("connection does not match the network mode")
        }
        return chain.proceed(chain.request())
    }
}
