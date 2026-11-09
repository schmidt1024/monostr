package com.monostr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.monostr.app.net.TorState
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.net.NetworkMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import rust.nostr.sdk.Keys
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tor plan Task 9, the real worker on the real WorkManager, session and Tor. In name order: the first test needs a process in which Tor
 * never ran. In this process the instrumentation keeps the app alive; a run with no visible app and a process started for it is checked by
 * hand, see the report of the task.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TorWorkerDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val deps get() = EntryPointAccessors.fromApplication(app, NostrSessionEntryPoint::class.java)
    private val session get() = deps.session()
    private val switch get() = deps.torSwitchControl()
    private val gate get() = deps.networkGate()
    private val tor get() = deps.torController().get()
    private val work get() = WorkManager.getInstance(app)

    private fun key() = LocalSigner(Keys.generate().secretKey().toHex())
    private fun active(): Ready = (session.state.value as SessionState.Active).ready

    private fun torProcesses(): List<String> = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }
        .mapNotNull { runCatching { File(it, "cmdline").readText().trim('\u0000') }.getOrNull() }
        .filter { it.endsWith(":tor") }

    private suspend fun noTorProcessWithin(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (torProcesses().isNotEmpty() && System.currentTimeMillis() < until) delay(50)
        assertEquals(emptyList<String>(), torProcesses())
    }

    private fun libtorMapped() = File("/proc/self/maps").readText().let { "libtor" in it }

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

    /** The periodic run a login or a switch schedules must not run beside the one under test; it enqueues off the caller's thread. */
    private fun noPeriodicRun() {
        Thread.sleep(1_000)
        BackgroundCheckWorker.cancel(app)
    }

    /** As with the app gone from the screen; a run that begins while the activity is visible does nothing. */
    private suspend fun runWorker(limitMs: Long = 300_000): WorkInfo {
        MainActivity.inForeground.set(false)
        try {
            val request = OneTimeWorkRequestBuilder<BackgroundCheckWorker>().build()
            work.enqueue(request)
            return withTimeout(limitMs) { work.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished } }
        } finally {
            MainActivity.inForeground.set(true)
        }
    }

    @Before
    fun visible() = MainActivity.inForeground.set(true)

    @After
    fun backToDirect() = runBlocking<Unit> {
        switch.turnOff()
        switch.onBackground()
        session.logout()
        BackgroundCheckWorker.cancel(app)
        MainActivity.inForeground.set(false)
    }

    @Test
    fun a_switchOffTheWorkerTouchesNothingOfTor() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        assertEquals(NetworkMode.Direct, gate.mode.value)
        session.loginLocal(key())
        session.release()
        noPeriodicRun()
        val info = runWorker()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(emptyList<String>(), torProcesses())
        assertFalse("libtor in the app's process", libtorMapped())
        assertTrue("the session the run built is closed", session.state.value is SessionState.Loading)
    }

    @Test
    fun b_notConnectedWithinTheBudgetEndsWithoutSessionAndWithoutTor() = runBlocking<Unit> {
        val ports = gate.torMode()
        session.loginLocal(key())
        session.release()
        val heard = Squatter(ports.sessionPort).use { shared ->
            Squatter(ports.isolatedPort).use { isolated ->
                switch.turnOn()
                noPeriodicRun()
                assertEquals(TorState.Off, tor.state.value)
                val began = System.currentTimeMillis()
                val info = runWorker()
                val took = System.currentTimeMillis() - began
                assertEquals(WorkInfo.State.SUCCEEDED, info.state)
                assertTrue("ended after $took ms, the budget is 90 s", took in 85_000..120_000)
                assertTrue("a session started without Tor", session.state.value is SessionState.Loading)
                assertEquals(TorState.Off, tor.state.value)
                noTorProcessWithin(5_000)
                shared.accepted.get() + isolated.accepted.get()
            }
        }
        assertEquals("the squatter heard from us", 0, heard)
    }

    @Test
    fun c_aRunStartsTorThenTheSessionWorksAndStopsBoth() = runBlocking<Unit> {
        session.loginLocal(key())
        session.release()
        switch.turnOn()
        noPeriodicRun()
        val seen = Collections.synchronizedList(ArrayList<Pair<TorState, Boolean>>())
        val torSeen = AtomicInteger(0)
        val watch = CoroutineScope(Dispatchers.Default)
        watch.launch { combine(tor.state, session.state) { t, s -> t to (s is SessionState.Active) }.collect { seen += it } }
        watch.launch { while (true) { if (torProcesses().isNotEmpty()) torSeen.incrementAndGet(); delay(200) } }
        val info = runWorker()
        watch.cancel()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertTrue("Tor never connected: $seen", seen.any { it.first == TorState.Connected })
        assertTrue("the session never started: $seen", seen.any { it.second })
        val firstSession = seen.indexOfFirst { it.second }
        assertEquals("the session started before Connected: $seen", TorState.Connected, seen[firstSession].first)
        assertTrue(":tor was never seen", torSeen.get() > 0)
        assertTrue(session.state.value is SessionState.Loading)
        assertEquals(TorState.Off, tor.state.value)
        noTorProcessWithin(5_000)
    }

    @Test
    fun d_aRestoreThatWaitedForASwitchWasNotTheOneThatStartedTheSession() = runBlocking<Unit> {
        session.loginLocal(key())
        noPeriodicRun()
        val told = AtomicInteger(0)
        session.stop()
        session.close()
        assertTrue("reads Loading during the switch", session.state.value is SessionState.Loading)
        val waiting = launch(Dispatchers.Default) { session.restore { told.incrementAndGet() } }
        delay(300)
        session.rebuild()
        waiting.join()
        assertEquals(0, told.get())
        assertTrue(session.state.value is SessionState.Active)

        session.release()
        session.restore { told.incrementAndGet() }
        assertEquals(1, told.get())
        session.restore { told.incrementAndGet() }
        assertEquals(1, told.get())
    }

    @Test
    fun e_sameValueLeavesTheScheduleAloneAnotherPeriodChangesIt() = runBlocking<Unit> {
        BackgroundCheckWorker.cancel(app)
        suspend fun periodic(): WorkInfo = work.getWorkInfosForUniqueWork("background-check").get().first { !it.state.isFinished }
        suspend fun waitFor(minutes: Long): WorkInfo = withTimeout(5_000) {
            while (runCatching { periodic().periodicityInfo?.repeatIntervalMillis }.getOrNull() != TimeUnit.MINUTES.toMillis(minutes)) delay(50)
            periodic()
        }
        BackgroundCheckWorker.schedule(app, torOn = false)
        val first = waitFor(15)

        BackgroundCheckWorker.schedule(app, torOn = false)
        delay(1_000)
        val again = periodic()
        assertEquals("a start of the app with the same value", first.id, again.id)
        assertEquals(first.generation, again.generation)
        assertEquals(first.nextScheduleTimeMillis, again.nextScheduleTimeMillis)

        BackgroundCheckWorker.schedule(app, torOn = true)
        val hourly = waitFor(60)
        assertEquals(first.id, hourly.id)
        assertEquals(first.generation + 1, hourly.generation)
        assertNotEquals(first.nextScheduleTimeMillis, hourly.nextScheduleTimeMillis)

        BackgroundCheckWorker.schedule(app, torOn = true)
        delay(1_000)
        assertEquals(hourly.generation, periodic().generation)
        assertEquals(hourly.nextScheduleTimeMillis, periodic().nextScheduleTimeMillis)

        BackgroundCheckWorker.schedule(app, torOn = false)
        assertEquals(first.nextScheduleTimeMillis, waitFor(15).nextScheduleTimeMillis)
        BackgroundCheckWorker.cancel(app)
    }

    @Test
    fun f_aRunSharesTorWithTheAppAndLeavesItsSessionAndGrace() = runBlocking<Unit> {
        session.loginLocal(key())
        switch.onForeground()
        switch.turnOn()
        noPeriodicRun()
        val ready = active()
        withTimeout(150_000) { tor.state.first { it == TorState.Connected } }

        // a run under the app: the owner stays, as does a session it did not build
        assertEquals(WorkInfo.State.SUCCEEDED, runWorker().state)
        assertEquals(TorState.Connected, tor.state.value)
        assertSame(ready, active())
        assertEquals(1, torProcesses().size)

        // a run during the app's grace: no new bootstrap, and it does not end the grace
        switch.onBackground()
        assertEquals(WorkInfo.State.SUCCEEDED, runWorker().state)
        assertEquals(TorState.Connected, tor.state.value)
        assertSame(ready, active())
        assertEquals(1, torProcesses().size)
    }
}
