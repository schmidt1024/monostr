package com.monostr.nostr.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * How the app may reach the network. [Unknown] (before the setting is read) blocks exactly like a [Tor] whose process is not
 * connected: nothing may open a direct connection until the mode is known.
 */
sealed interface NetworkMode {
    data object Unknown : NetworkMode
    data object Direct : NetworkMode
    /** Loopback SOCKS ports of the embedded Tor: one shared circuit, one isolated per stream. Stopped Tor = refused connection. */
    data class Tor(val sessionPort: Int, val isolatedPort: Int) : NetworkMode
}

/** The single source of truth for Direct or Tor; every connection asks it (Tasks 3 and 4). */
class NetworkGate(private val pickPorts: () -> Pair<Int, Int> = ::freeLoopbackPorts) {
    private val state = MutableStateFlow<NetworkMode>(NetworkMode.Unknown)
    val mode: StateFlow<NetworkMode> = state

    private var ports: NetworkMode.Tor? = null

    fun set(mode: NetworkMode) { state.value = mode }

    /** Moves away from [NetworkMode.Unknown] only; a [set] that came first wins. */
    internal fun setIfUnknown(mode: NetworkMode) { state.compareAndSet(NetworkMode.Unknown, mode) }

    suspend fun awaitKnown(): NetworkMode = state.first { it != NetworkMode.Unknown }

    /** The Tor mode with loopback ports chosen on first use and kept for the life of the process. */
    @Synchronized
    fun torMode(): NetworkMode.Tor = ports ?: pickPorts().let { (a, b) -> NetworkMode.Tor(a, b) }.also { ports = it }

    /** A new pair for the rest of the process, when the old one is taken, as the mode at once; throws with everything as it was. */
    @Synchronized
    fun newTorMode() {
        val tor = pickPorts().let { (a, b) -> NetworkMode.Tor(a, b) }
        ports = tor
        state.value = tor
    }

    companion object {
        /** Two distinct free loopback ports (both sockets are held open until both are known, so they cannot coincide). */
        fun freeLoopbackPorts(): Pair<Int, Int> {
            val lo = InetAddress.getByName("127.0.0.1")
            ServerSocket(0, 1, lo).use { a -> ServerSocket(0, 1, lo).use { b -> return a.localPort to b.localPort } }
        }
    }
}

/**
 * Start-up: read the setting and open the gate. Any failure leaves the gate [NetworkMode.Unknown] (everything stays blocked)
 * and never reaches the caller; cancellation is rethrown. A [NetworkGate.set] made meanwhile is not overwritten.
 */
suspend fun NetworkGate.initFrom(torEnabled: suspend () -> Boolean) {
    try {
        setIfUnknown(if (torEnabled()) torMode() else NetworkMode.Direct)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("NetworkGate", "network mode stays unknown, everything blocked (${e.javaClass.simpleName})")
    }
}
