package com.monostr.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.net.AndroidTorEngine
import com.monostr.app.net.TorController
import com.monostr.app.net.TorEngine
import com.monostr.app.net.TorState
import com.monostr.app.net.torrcFor
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import net.freehaven.tor.control.TorControlConnection

/** Real tor in `:tor`, on the device. Needs network except where noted; a skip here is not a pass. */
@RunWith(AndroidJUnit4::class)
class TorBootstrapTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val torProcess = "${ctx.packageName}:tor"
    private val three = 180_000L

    @Before fun noTorLeft() = runBlocking { AndroidTorEngine(ctx).stop() }

    @After fun cleanUp() = runBlocking {
        scope.cancel()
        AndroidTorEngine(ctx).stop()
    }

    private fun assumeNetwork() {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("check.torproject.org", 443), 8_000) } }.isSuccess
        assumeTrue("no network on the device", reachable)
    }

    private fun torPid(): Int? =
        ctx.getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.processName == torProcess }?.pid

    private fun listens(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1_000) }
    }.isSuccess

    private fun controller(engine: TorEngine = AndroidTorEngine(ctx), ports: NetworkMode.Tor, graceMs: Long = 300_000) =
        TorController(engine, scope, graceMs = graceMs) { ports }

    private fun freePorts(): NetworkMode.Tor = NetworkGate().torMode()

    private suspend fun TorController.awaitConnected() = withTimeout(three) { state.first { it == TorState.Connected } }

    /** An exit closing during TLS happens with Tor: only that gets three tries, and is logged. A connect or SOCKS error fails at once. */
    private fun fetchIsTor(port: Int): String {
        repeat(2) {
            try {
                return fetchOnce(port)
            } catch (e: SSLException) {
                Log.w("TorBootstrapTest", "SSLException retry: ${e.javaClass.simpleName}")
            }
        }
        return fetchOnce(port)
    }

    /** Can time out, unlike a blocking sleep inside withTimeout: a hung `:tor` fails the test and does not hang the run. */
    private suspend fun awaitWhile(ms: Long, condition: () -> Boolean) = withTimeout(ms) { while (condition()) delay(25) }

    private fun fetchOnce(port: Int): String {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)))
            // as the app under Tor (GatedHttp): the default 10 s fails on a slow circuit
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
        return client.newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { it.body!!.string() }
    }

    private fun socksListeners(): String {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(File(ctx.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket").absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        return socket.use {
            val control = TorControlConnection(it.inputStream, it.outputStream)
            control.launchThread(true)
            control.authenticate(ByteArray(0))
            control.getInfo("net/listeners/socks")
        }
    }

    private fun libtorMappedHere() = File("/proc/self/maps").readText().contains("libtor.so")

    @Test fun reachesConnectedAndFetchesAPageThroughEitherPort() = runBlocking {
        assumeNetwork()
        val ports = freePorts()
        val c = controller(ports = ports)
        val started = SystemClock.elapsedRealtime()
        c.acquire("test")
        c.awaitConnected()
        Log.i("TorBootstrapTest", "connected after ${SystemClock.elapsedRealtime() - started} ms")
        assertEquals(setOf("\"127.0.0.1:${ports.sessionPort}\"", "\"127.0.0.1:${ports.isolatedPort}\""), socksListeners().split(' ').toSet())
        for (port in listOf(ports.sessionPort, ports.isolatedPort)) {
            val page = fetchIsTor(port)
            assertTrue(page, page.contains("\"IsTor\":true"))
        }
        assertFalse("default SOCKS port", listens(9050))
        assertFalse("default HTTP tunnel", listens(8118))
        assertFalse("libtor.so must stay out of the main process", libtorMappedHere())
    }

    @Test fun stoppingEndsTheProcessAndClosesThePorts() = runBlocking {
        assumeNetwork()
        val ports = freePorts()
        val c = controller(ports = ports, graceMs = 1)
        c.acquire("test")
        c.awaitConnected()
        assertTrue(listens(ports.sessionPort))
        c.release("test")
        withTimeout(5_000) { c.state.first { it == TorState.Off } }
        awaitWhile(5_000) { torPid() != null || listens(ports.sessionPort) }
        assertNull(torPid())
    }

    /** The real thing: a new tor each round, no rebind before the old process is gone, same ports. */
    @Test fun restartsCleanlyWithoutWaiting() = runBlocking {
        assumeNetwork()
        val ports = freePorts()
        val c = controller(ports = ports, graceMs = 1)
        repeat(3) {
            c.acquire("test")
            c.awaitConnected()
            val page = fetchIsTor(ports.sessionPort)
            assertTrue(page, page.contains("\"IsTor\":true"))
            c.release("test")
            withTimeout(5_000) { c.state.first { it == TorState.Off } }
            // no pause: the controller holds the new start until the old stop is through
        }
    }

    /** No network needed: stopping during start. */
    @Test fun stoppingDuringBootstrapLeavesNothingAndTheNextStartWorks() = runBlocking {
        val ports = freePorts()
        val c = controller(ports = ports, graceMs = 1)
        for (wait in listOf(0L, 100L, 500L, 1_500L)) {
            c.acquire("test")
            SystemClock.sleep(wait)
            c.release("test")
            withTimeout(5_000) { c.state.first { it == TorState.Off } }
        }
        // the controller runs the last stop in the background: it ends with no process
        awaitWhile(10_000) { torPid() != null }
        assertFalse(listens(ports.sessionPort))
    }

    /** Fail closed: Tor killed while connected is Failed at once, no connection on its ports, and it comes back on them. */
    @Test fun killedWhileConnectedIsFailedThenComesBackVerified() = runBlocking {
        assumeNetwork()
        val ports = freePorts()
        val c = controller(ports = ports)
        val seen = CopyOnWriteArrayList<TorState>()
        scope.launch { c.state.collect { seen += it } }
        c.acquire("test")
        c.awaitConnected()
        val pid = torPid()!!
        seen.clear()
        Process.killProcess(pid)
        withTimeout(5_000) { c.state.first { it == TorState.Failed } }
        assertFalse(listens(ports.sessionPort))
        c.awaitConnected()
        assertNotEquals(pid, torPid())
        assertEquals(TorState.Failed, seen.first())
        assertFalse(seen.toString(), TorState.Off in seen)
        val page = fetchIsTor(ports.sessionPort)
        assertTrue(page, page.contains("\"IsTor\":true"))
    }

    /** No network needed. Another app on our port: Failed, never Connected, no tor left, and our other port never opens. */
    @Test fun aPortHeldByAnotherAppIsFailed() = runBlocking {
        val ports = freePorts()
        ServerSocket(ports.sessionPort, 1, InetAddress.getByName("127.0.0.1")).use {
            val c = controller(ports = ports)
            val seen = CopyOnWriteArrayList<TorState>()
            scope.launch { c.state.collect { seen += it } }
            val started = SystemClock.elapsedRealtime()
            c.acquire("test")
            withTimeout(three) { c.state.first { it == TorState.Failed } }
            Log.i("TorBootstrapTest", "squatted port failed after ${SystemClock.elapsedRealtime() - started} ms")
            assertFalse(seen.toString(), TorState.Connected in seen)
            awaitWhile(5_000) { torPid() != null }
            assertFalse(listens(ports.isolatedPort))
        }
    }

    /** Fail closed: tor bootstraps fine but on ports the gate does not send traffic to. It is checked at 100 %, and stopped. */
    @Test fun listenersOnOtherPortsAreFailedAndStopped() = runBlocking {
        assumeNetwork()
        val ports = freePorts()
        val others = freePorts()
        val c = controller(AndroidTorEngine(ctx) { torrcFor(others) }, ports)
        val seen = CopyOnWriteArrayList<TorState>()
        scope.launch { c.state.collect { seen += it } }
        c.acquire("test")
        withTimeout(three) { c.state.first { it == TorState.Failed } }
        assertFalse(seen.toString(), TorState.Connected in seen)
        assertTrue("came to the check at the end of the bootstrap: $seen", seen.any { it is TorState.Connecting && (it.progress ?: 0) > 0 })
        awaitWhile(5_000) { torPid() != null || listens(others.sessionPort) }
    }

    /**
     * What a new main process can find: a `:tor` nobody is bound to any more, still alive on the ports of the process before.
     * Here the binding goes by an unbind, and SIGSTOP keeps the service from ending its process, as a slow or stuck one might.
     *
     * Does not prove: the death of a real main process. An instrumented test cannot kill its own process, the system then
     * force-stops the package. That part was checked by hand (task 5 report, `kill -9` of main), and a new process picking new
     * ports is [NetworkGate]'s own.
     */
    @Test fun aTorLeftOnOtherPortsIsNotAdopted() = runBlocking {
        assumeNetwork()
        val stale = freePorts()
        File(ctx.getDir("TorService", Context.MODE_PRIVATE), "torrc").writeText(torrcFor(stale))
        val oldBinding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(ctx.bindService(Intent(ctx, TorProcessService::class.java), oldBinding, Context.BIND_AUTO_CREATE))
        awaitWhile(30_000) { !listens(stale.sessionPort) }
        val stalePid = torPid()!!
        Os.kill(stalePid, OsConstants.SIGSTOP)
        ctx.unbindService(oldBinding)
        delay(1_000) // well past the 300 ms in which a running service ends its process
        assertEquals("the stale tor must still be there, or this test proves nothing", stalePid, torPid())
        assertTrue(listens(stale.sessionPort))

        val fresh = freePorts()
        val c = controller(ports = fresh)
        c.acquire("new")
        c.awaitConnected()
        assertNotEquals(stalePid, torPid())
        assertFalse("old port still open", listens(stale.sessionPort))
        assertFalse("old port still open", listens(stale.isolatedPort))
        assertEquals(setOf("\"127.0.0.1:${fresh.sessionPort}\"", "\"127.0.0.1:${fresh.isolatedPort}\""), socksListeners().split(' ').toSet())
        val page = fetchIsTor(fresh.sessionPort)
        assertTrue(page, page.contains("\"IsTor\":true"))
    }
}
