package com.monostr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import com.monostr.app.session.SwitchResult
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.net.NetworkMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Spec «Only one account online» on the device (account switching Task 11): after a switch nothing of account A stays open or is used
 * by B. Every relay and web server here is a loopback one inside the test process ([LoopbackRelay], [KeepAliveWeb]), so the checks
 * are made where the sockets end, as the Squatter of [TorLeakScenarios] counts them; nothing leaves the emulator but the default
 * relays a fresh account starts with before its list is set. Tor stays off (its own isolation: [TorAccountIsolationTest]). Keys are
 * made here for the run and never printed.
 */
@RunWith(AndroidJUnit4::class)
class AccountLeakScenarios {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val deps get() = EntryPointAccessors.fromApplication(app, NostrSessionEntryPoint::class.java)
    private val session get() = deps.session()

    private val keyA = Keys.generate()
    private val keyB = Keys.generate()
    private val pkA get() = keyA.publicKey().toHex()
    private val pkB get() = keyB.publicKey().toHex()

    /** An HTTP/1.1 server that keeps every connection open until the client closes it; [open] holds those not closed yet. */
    private class KeepAliveWeb : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val url = "http://127.0.0.1:${listener.localPort}/a"
        val open: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        private val ids = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    val id = ids.incrementAndGet()
                    open += id
                    thread(isDaemon = true) {
                        runCatching {
                            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (true) {
                                // one request per loop: its head up to the empty line; EOF (null) is the client closing
                                val first = input.readLine() ?: break
                                while (input.readLine()?.isNotEmpty() == true) Unit
                                if (first.isEmpty()) continue
                                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: text/plain\r\n\r\nok".toByteArray()); flush() }
                            }
                        }
                        runCatching { socket.close() }
                        open -= id
                    }
                }
            }
        }

        override fun close() = listener.close()
    }

    private suspend fun until(what: String, limitMs: Long = 20_000, check: suspend () -> Boolean) {
        try {
            withTimeout(limitMs) { while (!check()) delay(100) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting: $what")
        }
    }

    /** [key] logs in (added and active) with [relay] as its only relay, and is connected to it. */
    private suspend fun add(key: Keys, relay: LoopbackRelay): Ready {
        session.loginLocal(LocalSigner(key.secretKey().toHex()))
        assertTrue(session.applyRelays(listOf(relay.url)))
        return connected(relay)
    }

    /** The running session, connected to [relay]. */
    private suspend fun connected(relay: LoopbackRelay): Ready {
        val ready = session.requireReady()
        ready.engine.connect()
        until("connected to ${relay.url}") { ready.engine.connectedRelayUrls().isNotEmpty() && relay.open.isNotEmpty() }
        return ready
    }

    private suspend fun switchTo(pubkey: String): Ready {
        assertEquals(SwitchResult.Done, session.switchTo(pubkey))
        val ready = session.requireReady()
        assertEquals(pubkey, ready.pubkey)
        return ready
    }

    @Before fun start() = runBlocking {
        // no background run of its own while the steps are taken: the schedule a login makes runs at once otherwise
        MainActivity.inForeground.set(true)
        runCatching { session.logout() }
        withTimeout(10_000) { deps.networkGate().awaitKnown() }
        // with Tor on, every connection would go to the SOCKS port, not to the loopback relays
        assertEquals(NetworkMode.Direct, deps.networkGate().mode.value)
    }

    @After fun end() = runBlocking<Unit> {
        runCatching { session.logout() }
        BackgroundCheckWorker.cancel(app)
        MainActivity.inForeground.set(false)
    }

    /** A's relay sockets and the HTTP connection a request of A left in the shared pool are closed by the switch, and stay closed. */
    @Test fun noSocketOfAOpenAfterSwitch() = runBlocking<Unit> {
        LoopbackRelay().use { relayA ->
            LoopbackRelay().use { relayB ->
                KeepAliveWeb().use { web ->
                    add(keyB, relayB)
                    val a = add(keyA, relayA)
                    assertEquals(pkA, a.pubkey)
                    assertTrue("B's sockets closed when A was added", relayB.open.isEmpty())
                    // as a picture of A's feed: the connection stays in the pool for the next request
                    withContext(Dispatchers.IO) {
                        deps.http().newCall(Request.Builder().url(web.url).build()).execute().use { assertEquals("ok", it.body?.string()) }
                    }
                    assertEquals("A's HTTP connection waits in the pool", 1, web.open.size)
                    val ofA = relayA.connections.get()

                    switchTo(pkB)
                    // closed before the switch returns (the server side notices within moments)
                    until("A's relay sockets closed", 5_000) { relayA.open.isEmpty() }
                    until("A's pooled HTTP connection closed", 5_000) { web.open.isEmpty() }
                    connected(relayB)
                    // B runs for a while: nothing of A dials again, B's own pictures take a new connection
                    withContext(Dispatchers.IO) {
                        deps.http().newCall(Request.Builder().url(web.url).build()).execute().use { assertEquals("ok", it.body?.string()) }
                    }
                    delay(5_000)
                    assertEquals("A's relay was dialed again after the switch", ofA, relayA.connections.get())
                    assertTrue(relayA.open.isEmpty())
                    assertEquals("only B's own connection", 1, web.open.size)
                    assertTrue("A's engine is closed", a.engine.connectedRelayUrls().isEmpty())
                }
            }
        }
    }

    /** A background run after a switch starts only the active account (B): A's relay is never reached, B's is asked. */
    @Test fun workerRunChecksOnlyActiveAccount() = runBlocking<Unit> {
        LoopbackRelay().use { relayA ->
            LoopbackRelay().use { relayB ->
                add(keyA, relayA)
                add(keyB, relayB)
                switchTo(pkA)
                connected(relayA)
                switchTo(pkB)
                connected(relayB)
                // as in a process the system started for the run: no session, the app not visible
                session.release()
                until("all sockets closed", 5_000) { relayA.open.isEmpty() && relayB.open.isEmpty() }
                val ofA = relayA.connections.get()
                val ofB = relayB.connections.get()
                val reqsOfB = relayB.reqs.size
                MainActivity.inForeground.set(false)

                val work = WorkManager.getInstance(app)
                val request = OneTimeWorkRequestBuilder<BackgroundCheckWorker>().build()
                work.enqueue(request)
                until("the run ends", 120_000) { withContext(Dispatchers.IO) { work.getWorkInfoById(request.id).get() }?.state?.isFinished == true }
                assertEquals(WorkInfo.State.SUCCEEDED, withContext(Dispatchers.IO) { work.getWorkInfoById(request.id).get() }?.state)

                assertTrue("B's relay was never reached", relayB.connections.get() > ofB)
                assertTrue("B's relay was never asked", relayB.reqs.size > reqsOfB)
                assertEquals("A's relay was reached by the run", ofA, relayA.connections.get())
                // the run closes the session it built
                until("B's run sockets closed", 10_000) { relayB.open.isEmpty() }
                assertEquals(pkB, deps.sessions().session.first()?.pubkey)
            }
        }
    }

    /**
     * NIP-42 on one relay both accounts use (strfry asks right after connecting): after the switch every AUTH it sees carries B,
     * and no connection ever carried AUTH of both accounts; the connections A authenticated on are closed.
     */
    @Test fun bNeverAuthenticatesOnAsRelayConnection() = runBlocking<Unit> {
        LoopbackRelay(authChallenge = "challenge").use { relay ->
            add(keyB, relay)
            add(keyA, relay)
            until("A authenticated") { relay.auths.any { it.pubkey == pkA } }
            val ofA = relay.auths.filter { it.pubkey == pkA }.map { it.connection }.toSet()
            val before = relay.auths.size

            switchTo(pkB)
            until("A's connections closed", 5_000) { relay.open.intersect(ofA).isEmpty() }
            connected(relay)
            until("B authenticated after the switch") { relay.auths.drop(before).any { it.pubkey == pkB } }
            // a reconnect or a late challenge answer of A would show here
            delay(3_000)

            val after = relay.auths.drop(before)
            assertEquals("AUTH after the switch", setOf(pkB), after.map { it.pubkey }.toSet())
            assertTrue("B authenticated on a connection of A", after.none { it.connection in ofA })
            val mixed = relay.auths.groupBy { it.connection }.filterValues { list -> list.map { it.pubkey }.toSet().size > 1 }
            assertEquals("connections that carried AUTH of both accounts", emptyMap<Int, List<LoopbackRelay.Auth>>(), mixed)
        }
    }
}
