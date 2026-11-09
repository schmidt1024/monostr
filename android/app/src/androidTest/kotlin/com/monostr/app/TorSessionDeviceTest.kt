package com.monostr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.net.TorNotReadyException
import com.monostr.app.net.TorState
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.net.NetworkMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tor plan Task 6, the real session on the real Tor, with the app's own singletons. In name order: the first test needs a process in
 * which Tor never ran, the second starts it. The app's activity is not started; the test is the foreground owner itself.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TorSessionDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val deps get() = EntryPointAccessors.fromApplication(app, NostrSessionEntryPoint::class.java)
    private val session get() = deps.session()
    private val switch get() = deps.torSwitchControl()
    private val gate get() = deps.networkGate()
    private val store get() = deps.torSwitchStore()

    private fun key() = LocalSigner(Keys.generate().secretKey().toHex())
    private fun active(): Ready = (session.state.value as SessionState.Active).ready

    private fun torProcesses(): List<String> = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }
        .mapNotNull { runCatching { File(it, "cmdline").readText().trim('\u0000') }.getOrNull() }
        .filter { it.endsWith(":tor") }

    private fun libtorMapped() = File("/proc/self/maps").readText().let { "libtor" in it }

    /** Records every connection to a port someone else could hold. */
    private class Squatter(port: Int) : AutoCloseable {
        private val listener = ServerSocket(port, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val accepted = AtomicInteger(0)
        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val s = runCatching { listener.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    runCatching { s.close() }
                }
            }
        }
        override fun close() = listener.close()
    }

    /** As the activity would: a login schedules the background check, which otherwise finds no visible app, runs now and releases the session. */
    @Before
    fun visible() = MainActivity.inForeground.set(true)

    @After
    fun backToDirect() = runBlocking<Unit> {
        MainActivity.inForeground.set(false)
        switch.turnOff()
        switch.onBackground()
        if (session.state.value is SessionState.Active) session.logout()
    }

    @Test
    fun a_switchOffNothingOfTorRunsAndTheEngineIsNotHeld() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        assertEquals(NetworkMode.Direct, gate.mode.value)
        assertFalse(store.enabled.value)
        switch.onForeground() // what the activity does
        session.loginLocal(key())
        assertTrue(active().engine.routeOpen.value)
        switch.onBackground()
        Thread.sleep(1_000)
        assertEquals(emptyList<String>(), torProcesses())
        assertFalse("libtor in the app's process", libtorMapped())
        session.logout()
    }

    @Test
    fun b_aSquatterHearsNothingAndTheEngineConnectsOnlyAfterConnected() = runBlocking<Unit> {
        val tor = deps.torController()
        val ports = gate.torMode()
        switch.onForeground()
        var first: Ready
        val heard: Int
        Squatter(ports.sessionPort).use { shared ->
            Squatter(ports.isolatedPort).use { isolated ->
                // both ports taken: bootstrap cannot bind, the state goes Failed while the gate names these ports
                switch.turnOn()
                assertEquals(ports, gate.mode.value)
                assertTrue(store.enabled.value)
                withTimeout(30_000) { tor.get().state.first { it == TorState.Failed } }

                session.loginLocal(key())
                first = active()
                assertFalse("held while Failed", first.engine.routeOpen.value)
                // everything that could reach for the port
                first.engine.connect()
                first.engine.reconnectAll()
                first.engine.fetch(Filter().kind(Kind(1u)), Duration.ofMillis(200))
                first.engine.attachTemporary(listOf("wss://relay.damus.io"))
                assertFalse(first.engine.signAndPublish(EventBuilder.textNote("nothing leaves")).sentToAny)
                val http = runCatching { deps.http().newCall(Request.Builder().url("https://example.com/").build()).execute().close() }
                assertTrue("http: $http", http.exceptionOrNull() is TorNotReadyException)
                // the session's own sender, through start()'s wiring
                val detached = runCatching {
                    first.tips.resendAnonymousIntent(EventBuilder.textNote("nothing leaves").signWithKeys(Keys.generate()).asJson(), listOf("wss://relay.damus.io"))
                }
                assertTrue("detached: $detached", detached.exceptionOrNull() is IOException)

                // the restore path itself, with the squatters up: what a start of the process with the switch on does
                session.release()
                session.restore()
                first = active()
                assertFalse("restored with Tor Failed: must be held", first.engine.routeOpen.value)
                Thread.sleep(1_000)
                assertEquals("heard during restore", 0, shared.accepted.get() + isolated.accepted.get())

                // logout during the wait: quick, keeps the switch and the mode
                val logout = withTimeoutOrNull(5_000) { session.logout() }
                assertEquals("logout waited for Tor", Unit, logout)
                assertTrue(session.state.value is SessionState.LoggedOut)
                assertTrue(store.enabled.value)
                assertEquals(ports, gate.mode.value)

                // the login after logout waits as well
                session.loginLocal(key())
                assertFalse(active().engine.routeOpen.value)
                Thread.sleep(12_000) // past rust-nostr's first retry
                heard = shared.accepted.get() + isolated.accepted.get()
            }
        }
        assertEquals("the squatter heard from us", 0, heard)

        // Tor gets its ports: the way out of Failed
        val second = active()
        assertFalse(second.engine.routeOpen.value)
        assertTrue(second.engine.connectedRelayUrls().isEmpty())
        tor.get().retry()
        withTimeout(150_000) { tor.get().state.first { it == TorState.Connected } }
        withTimeout(10_000) { second.engine.routeOpen.first { it } }
        val connected = withTimeoutOrNull(120_000) { while (second.engine.connectedRelayUrls().isEmpty()) kotlinx.coroutines.delay(500); true }
        assertEquals("no relay connected through Tor", true, connected)
        assertFalse("the session logged out during the wait came back", first.engine.routeOpen.value)
        assertTrue(first.engine.connectedRelayUrls().isEmpty())

        // logged out and in again with Tor running: the login itself goes through Tor
        session.logout()
        assertTrue(store.enabled.value)
        assertEquals(ports, gate.mode.value)
        session.loginLocal(key())
        assertEquals(ports, gate.mode.value)
        val third = active()
        withTimeout(10_000) { third.engine.routeOpen.first { it } }

        // the switch off: the same running session is rebuilt, direct, and Tor's state no longer holds it
        switch.turnOff()
        assertEquals(NetworkMode.Direct, gate.mode.value)
        assertFalse(store.enabled.value)
        val direct = active()
        assertTrue(direct !== third)
        assertTrue(direct.engine.routeOpen.value)
        assertFalse(third.engine.routeOpen.value)
    }

    @Test
    fun c_turningOnRebuildsARunningSessionHeldUntilConnected() = runBlocking<Unit> {
        switch.onForeground()
        session.loginLocal(key())
        val direct = active()
        assertTrue(direct.engine.routeOpen.value)
        switch.turnOn()
        assertFalse("the direct engine stopped", direct.engine.routeOpen.value)
        val tor = active()
        assertTrue(tor !== direct)
        // turning off stopped Tor at once, so it has a bootstrap to do and this engine must wait for it
        assertFalse("the rebuilt engine must be held until Connected", tor.engine.routeOpen.value)
        assertTrue(tor.engine.connectedRelayUrls().isEmpty())
        withTimeout(150_000) { deps.torController().get().state.first { it == TorState.Connected } }
        withTimeout(10_000) { tor.engine.routeOpen.first { it } }
    }
}
