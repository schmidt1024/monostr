package com.monostr.app.net

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.monostr.app.TorProcessService
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.TorControlConnection
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

private const val TAG = "TorEngine"
private const val CONTROL_RETRY_MS = 30L
private const val POLL_MS = 25L
/** Stopping normally takes about 350 ms (spike). */
private const val GONE_BEFORE_KILL_MS = 1_000L
private const val GONE_AFTER_KILL_MS = 2_000L

/** Exactly as tested in the spike. `HTTPTunnelPort 0`: the defaults would open a tunnel on 8118. */
internal fun torrcFor(ports: NetworkMode.Tor): String =
    "SocksPort 127.0.0.1:${ports.sessionPort} IsolateDestAddr\nSocksPort 127.0.0.1:${ports.isolatedPort}\nHTTPTunnelPort 0\n"

/** [reply] of `GETINFO net/listeners/socks`: `"127.0.0.1:1" "127.0.0.1:2"`. True only for exactly our two listeners. */
internal fun socksListenersMatch(reply: String, ports: NetworkMode.Tor): Boolean =
    reply.split(' ').filter { it.isNotEmpty() }.map { it.trim('"') }.sorted() ==
        listOf("127.0.0.1:${ports.sessionPort}", "127.0.0.1:${ports.isolatedPort}").sorted()

/** From a `STATUS_CLIENT` event or `GETINFO status/bootstrap-phase`, the same format. */
internal fun bootstrapProgress(line: String): Int? =
    if (" BOOTSTRAP " !in line) null else Regex("""PROGRESS=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()

/**
 * The highest progress so far. The event thread and the one reading `status/bootstrap-phase` race: an older, lower value
 * arriving last must not replace 100, or the bootstrap would never be seen as finished.
 */
internal class BootstrapProgress {
    private val highest = MutableStateFlow<Int?>(null)
    val percent: StateFlow<Int?> = highest

    fun see(line: String) {
        val new = bootstrapProgress(line) ?: return
        highest.update { maxOf(it ?: new, new) }
    }
}

/** Tor listens elsewhere than the gate sends traffic: never to be reported as connected, and no point in trying again as it is. */
private class WrongPortsException : RuntimeException()

/**
 * Tor in its own `:tor` process, see docs/testing/tor-spike.md. The caller keeps start and stop in turn ([TorController]).
 *
 * Fail closed on ports: the app was given two ports by the gate, and only tor saying it listens on exactly those two gives
 * [TorState.Connected]. Another app holds one of them, or tor has others: Failed.
 *
 * A `:tor` that outlived a previous main process runs on the ports of that process, as those are chosen per process. So a
 * start first ends any `:tor` there is, then writes the torrc, then binds.
 *
 * Touches no `TorService` static here, as its static initialiser loads libtor.so: this process only uses the class name.
 * Logs carry exception types only.
 */
class AndroidTorEngine internal constructor(context: Context, private val torrc: (NetworkMode.Tor) -> String) : TorEngine {
    constructor(context: Context) : this(context, ::torrcFor)

    private val app = context.applicationContext
    private val activities = app.getSystemService(ActivityManager::class.java)
    private val torProcessName = "${app.packageName}:tor"
    private val torDir: File get() = app.getDir("TorService", Context.MODE_PRIVATE)

    @Volatile private var binding: Binding? = null

    private class Binding : ServiceConnection {
        /** Counts every time the service comes up; null while it is down. A restart by the system gives a new value. */
        val up = MutableStateFlow<Long?>(null)
        private var count = 0L

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { up.value = ++count }
        override fun onServiceDisconnected(name: ComponentName?) { up.value = null }
        override fun onBindingDied(name: ComponentName?) { up.value = null }
    }

    override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> = withContext(Dispatchers.IO) {
        endTorProcess(killAtOnce = true)
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        // Tor would end on this as well, but silently: without the check Failed comes only with the bootstrap timeout
        for (port in listOf(ports.sessionPort, ports.isolatedPort)) ServerSocket(port, 1, loopback).close()
        File(torDir, "torrc").writeText(torrc(ports))
        val new = Binding()
        binding = new // before binding: stop must also undo a bind that returned false
        if (!app.bindService(Intent(app, TorProcessService::class.java), new, Context.BIND_AUTO_CREATE)) {
            throw IOException("cannot bind")
        }
        observe(new, ports)
    }

    override suspend fun stop() = withContext(Dispatchers.IO + NonCancellable) {
        binding?.let { runCatching { app.unbindService(it) } }
        binding = null
        endTorProcess(killAtOnce = false)
    }

    /**
     * One round per tor process. With the service bound, the system starts a killed `:tor` again by itself, on the same
     * torrc; the control socket of the old one is dead then, so each round connects anew and checks the ports anew.
     */
    private fun observe(binding: Binding, ports: NetworkMode.Tor): Flow<TorState> = channelFlow {
        var last: Long? = null
        while (true) {
            val round = binding.up.filterNotNull().first { it != last }
            last = round
            send(TorState.Connecting(null))
            coroutineScope {
                val tor = launch { watchTor(ports) }
                val gone = launch { binding.up.first { it != round } }
                select { tor.onJoin {}; gone.onJoin {} }
                coroutineContext.cancelChildren()
            }
            send(TorState.Failed)
        }
    }

    /** Returns when this tor's control connection has ended, which is when tor has. */
    private suspend fun ProducerScope<TorState>.watchTor(ports: NetworkMode.Tor) {
        val socket = connectControl()
        try {
            coroutineScope {
                // blocking reads do not end on cancellation, a shut socket ends them
                val closer = launch {
                    try { awaitCancellation() } finally { runCatching { socket.shutdownInput() } }
                }
                val progress = BootstrapProgress()
                val control = TorControlConnection(socket.inputStream, socket.outputStream)
                val reader = control.launchThread(true)
                runInterruptible(Dispatchers.IO) {
                    control.authenticate(ByteArray(0))
                    control.addRawEventListener { keyword, data ->
                        if (keyword == "STATUS_CLIENT") progress.see(data)
                    }
                    control.setEvents(listOf("STATUS_CLIENT"))
                    // earlier events have passed before the listener was on
                    progress.see(control.getInfo("status/bootstrap-phase"))
                }
                val states = launch {
                    progress.percent.filterNotNull().takeWhile { it < 100 }.collect { send(TorState.Connecting(it)) }
                    val listeners = runInterruptible(Dispatchers.IO) { control.getInfo("net/listeners/socks") }
                    if (!socksListenersMatch(listeners, ports)) throw WrongPortsException()
                    send(TorState.Connected)
                }
                runInterruptible(Dispatchers.IO) { reader.join() }
                states.cancel()
                closer.cancel()
            }
        } catch (e: IOException) {
            Log.i(TAG, "control connection ended (${e.javaClass.simpleName})")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** It refuses until tor has created the socket, 0.2 to 1.3 s after the bind in the spike. */
    private suspend fun connectControl(): LocalSocket {
        val path = File(torDir, "data/ControlSocket").absolutePath
        while (true) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                return socket
            } catch (_: IOException) {
                runCatching { socket.close() }
            }
            delay(CONTROL_RETRY_MS)
        }
    }

    /** Back only when no `:tor` is left, so that its ports are free again. [IOException] if it will not go. */
    private suspend fun endTorProcess(killAtOnce: Boolean) {
        if (!killAtOnce && awaitGone(GONE_BEFORE_KILL_MS)) return
        torPid()?.let { Process.killProcess(it) } ?: return
        if (!awaitGone(GONE_AFTER_KILL_MS)) throw IOException("tor process stays")
    }

    private suspend fun awaitGone(ms: Long): Boolean {
        val until = System.nanoTime() + ms * 1_000_000
        while (torPid() != null) {
            if (System.nanoTime() > until) return false
            delay(POLL_MS)
        }
        return true
    }

    private fun torPid(): Int? = activities.runningAppProcesses?.firstOrNull { it.processName == torProcessName }?.pid
}
