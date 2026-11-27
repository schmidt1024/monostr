package com.monostr.app

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.net.FreshRoute
import com.monostr.app.net.TorNotReadyException
import com.monostr.app.net.TorState
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.TorControlConnection
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spec «Tor isolation per account»: after a switch, no stream of account B rides a circuit that carried a stream of account A.
 * Both accounts use the default relays, so without a NEWNYM tor (`IsolateDestAddr` only) would hand B the circuits A used to the
 * same relays. Watches `STREAM SUCCEEDED` events on a control connection of its own. Needs network and real relays; a skip is
 * not a pass. Keys are made here for the run and never printed.
 */
@RunWith(AndroidJUnit4::class)
class TorAccountIsolationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val deps get() = EntryPointAccessors.fromApplication(app, NostrSessionEntryPoint::class.java)
    private val session get() = deps.session()
    private val switch get() = deps.torSwitchControl()
    private val gate get() = deps.networkGate()
    private val tor get() = deps.torController().get()

    private class Stream(val account: String, val circuit: String, val target: String)

    private val streams = CopyOnWriteArrayList<Stream>()
    @Volatile private var phase = "none"
    private var control: LocalSocket? = null

    private fun key() = LocalSigner(Keys.generate().secretKey().toHex())
    private fun active(): Ready = (session.state.value as SessionState.Active).ready

    private fun assumeNetwork() {
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("check.torproject.org", 443), 8_000) } }.isSuccess
        assumeTrue("no network on the device", reachable)
    }

    /** `STREAM <id> SUCCEEDED <circuit> <target> …`; jtorctl may or may not hand over the keyword with the rest. */
    private fun see(data: String) {
        val words = data.trim().split(' ').let { if (it.firstOrNull() == "STREAM") it.drop(1) else it }
        if (words.size < 4) return
        // SUCCEEDED names the address tor resolved; NEW still the name asked for
        if (words[1] == "NEW") names[words[0]] = words[3]
        if (words[1] == "SUCCEEDED") streams += Stream(phase, words[2], names[words[0]] ?: words[3])
    }

    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun watchStreams() {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(File(app.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket").absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        control = socket
        val connection = TorControlConnection(socket.inputStream, socket.outputStream)
        connection.launchThread(true)
        connection.authenticate(ByteArray(0))
        connection.addRawEventListener { keyword, data -> if (keyword == "STREAM") see(data) }
        connection.setEvents(listOf("STREAM"))
    }

    /** The relays of the running session are connected, and their streams have had time to show. */
    private suspend fun connected(ready: Ready) {
        withTimeout(120_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(250) }
        delay(10_000)
    }

    /** Through the app's gated client, as pictures and previews go. True when it went out, false when the gate refused it. */
    private suspend fun fetch(): Boolean = withContext(Dispatchers.IO) {
        try {
            deps.http().newCall(Request.Builder().url(PICTURE).build()).execute().use { it.body?.bytes() }
            true
        } catch (e: TorNotReadyException) {
            false
        }
    }

    @Before fun visible() = MainActivity.inForeground.set(true)

    @After fun end() = runBlocking<Unit> {
        runCatching { control?.close() }
        switch.turnOff()
        switch.onBackground()
        session.logout()
        BackgroundCheckWorker.cancel(app)
        MainActivity.inForeground.set(false)
    }

    @Test fun accountBNeverRidesACircuitOfAccountA() = runBlocking<Unit> {
        assumeNetwork()
        withTimeout(10_000) { gate.awaitKnown() }
        switch.onForeground()
        switch.turnOn()
        withTimeout(180_000) { tor.state.first { it == TorState.Connected } }
        watchStreams()

        phase = "A"
        session.loginLocal(key())
        val a = active()
        connected(a)
        // a NEWNYM just now closes tor's window for the switch: B has to wait ~10 s, which is when its HTTP must not go out. A slow
        // picture over Tor can eat that window, then once more
        for (attempt in 1..3) {
            assertTrue(tor.newIdentity())
            val began = android.os.SystemClock.elapsedRealtime()
            assertTrue("A's picture", fetch())
            if (android.os.SystemClock.elapsedRealtime() - began < 5_000) break
            Log.i("TorAccountIsolation", "A's picture took longer than 5 s, again")
        }

        // the add of a further account switches to it: A's session closes, B waits for tor's acknowledgement
        phase = "switch"
        session.loginLocal(key())
        val b = active()
        assertNotEquals(a.pubkey, b.pubkey)
        // what B's screens do at once: load a picture from the same host A used
        var refused = 0
        withTimeout(60_000) { while (!fetch()) { refused++; delay(200) } }
        Log.i("TorAccountIsolation", "B's picture refused $refused times before the fresh route")
        phase = "B"
        connected(b)
        assertEquals(FreshRoute.Ready, tor.freshRoute.value)
        assertTrue(!tor.routePending.value)

        val ofA = streams.filter { it.account == "A" }
        // A's session is closed before the add returns, so what opens during it is B's (it may connect before the call returns)
        val ofB = streams.filter { it.account == "switch" || it.account == "B" }
        Log.i("TorAccountIsolation", "targets A ${ofA.map { it.target }} B ${ofB.map { it.target }}")
        Log.i("TorAccountIsolation", "A ${ofA.size} streams on ${ofA.map { it.circuit }.toSet().size} circuits, B ${ofB.size} on ${ofB.map { it.circuit }.toSet().size}")
        assertTrue("A opened no stream, the test proves nothing", ofA.isNotEmpty())
        assertTrue("B opened no stream, the test proves nothing", ofB.isNotEmpty())
        assertTrue("A and B used the same relays, else reuse was never possible", ofA.map { it.target }.toSet().intersect(ofB.map { it.target }.toSet()).isNotEmpty())
        val shared = ofA.map { it.circuit }.toSet().intersect(ofB.map { it.circuit }.toSet())
        assertEquals("circuits that carried both accounts", emptySet<String>(), shared)
        assertTrue("B's HTTP went out inside the NEWNYM window", refused > 0)
        val host = "monostr.com:443"
        assertTrue("both pictures went through tor", ofA.any { it.target == host } && ofB.any { it.target == host })
    }

    private companion object {
        const val PICTURE = "https://monostr.com/monostr-icon.png"
    }
}
