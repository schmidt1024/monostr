package com.monostr.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.monostr.app.MainActivity
import com.monostr.app.net.torConnected
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.SessionState
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.net.NetworkMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How long a run waits for Tor (spec 5.7). Set by measuring on the Pixel in Tor plan Task 11. */
internal const val TOR_BUDGET_MS = 90_000L

/**
 * The work under Tor ends here, with [TOR_BUDGET_MS] inside the about 10 minutes after which WorkManager stops a worker. The checks are
 * idempotent (watermarks), and the system stopping a run would do no harm either, but this way one that hangs on Tor costs less battery.
 */
internal const val TOR_WORK_LIMIT_MS = 7 * 60_000L

/** Only a gate whose setting cannot be read waits this long. */
private const val UNKNOWN_WAIT_MS = 10_000L

/** Tor is connected and the engine follows at once; this is for a run that overtook a switch, whose engine stays held. */
private const val ROUTE_WAIT_MS = 10_000L

private val runs = AtomicLong()

/**
 * One per run, not per work: a periodic work keeps its id, and WorkManager starts it again as soon as the system stopped it, while the
 * stopped run may still be closing its session. With one name, that run's release would take Tor from under the new one.
 */
private fun nextRunOwner() = "worker-${runs.incrementAndGet()}"

/** Spec 5.7: less often with Tor. */
fun backgroundIntervalMinutes(torOn: Boolean): Long = if (torOn) 60 else 15

/**
 * UPDATE keeps the time of the last run, but still cancels and schedules the job again and raises the work's generation: only for a
 * period that really changes. [currentIntervalMs] null: nothing is scheduled.
 */
internal fun schedulePolicy(currentIntervalMs: Long?, wantedMinutes: Long): ExistingPeriodicWorkPolicy =
    if (currentIntervalMs != null && currentIntervalMs != TimeUnit.MINUTES.toMillis(wantedMinutes)) ExistingPeriodicWorkPolicy.UPDATE
    else ExistingPeriodicWorkPolicy.KEEP

/** At most this long a start in the background waits for WorkManager's queue ([BackgroundCheckWorker.scheduleAtStart]); normally about 40 ms. */
internal const val START_QUEUE_WAIT_MS = 2_000L

/**
 * Reads the current period from [current], then enqueues with [schedulePolicy]. Done once the enqueue went through WorkManager's serial
 * queue, or failed: both are queued there behind WorkManager's own start-up cleanup, so done also means that cleanup has run.
 */
internal fun scheduleWith(
    current: ListenableFuture<Long?>,
    wantedMinutes: Long,
    enqueue: (ExistingPeriodicWorkPolicy) -> ListenableFuture<*>,
): CompletableFuture<Unit> {
    val done = CompletableFuture<Unit>()
    current.addListener({
        val interval = valueOrNull(current)
        runCatching { enqueue(schedulePolicy(interval, wantedMinutes)) }
            .onSuccess { it.addListener({ done.complete(Unit) }, Runnable::run) }
            .onFailure { done.complete(Unit) }
    }, Runnable::run)
    return done
}

/** The value of a future that is complete, null when it failed or was cancelled. */
internal fun <T> valueOrNull(done: Future<T>): T? = try {
    done.get()
} catch (e: ExecutionException) {
    null
} catch (e: CancellationException) {
    null
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    null
}

/** Waits at most [timeoutMs] for [done]; false when it did not come or the thread was interrupted (the flag stays set). */
internal fun awaitWorkQueue(done: Future<*>, timeoutMs: Long): Boolean = try {
    done.get(timeoutMs, TimeUnit.MILLISECONDS)
    true
} catch (e: TimeoutException) {
    false
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    false
} catch (e: ExecutionException) {
    // it went through WorkManager's queue, if with an error
    true
} catch (e: CancellationException) {
    false
}

/** A [CompletableFuture] that is also a [ListenableFuture], as WorkManager's are: listeners run on their executor once it completes. */
internal class CompletableListenable<T> : CompletableFuture<T>(), ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor) {
        whenComplete { _, _ -> executor.execute(listener) }
    }
}

/** What [BackgroundCheckWorker.scheduleAtStart] did. */
enum class StartQueue { NOT_WAITED, THROUGH, TIMED_OUT }

/** What a run needs of Tor. Only called with the gate on Tor, so with the switch off nothing of Tor is loaded. */
internal class RunTor(val acquire: (String) -> Unit, val release: (String) -> Unit, val connected: () -> Flow<Boolean>)

/**
 * One run through the gate (spec 5.7). Direct: [check] as ever, its answer counts. Tor: Tor first, the session only once it is connected,
 * and whatever happens Tor is released in the end and nothing was dialed before. True: done until the next run, false: try again soon.
 *
 * Under Tor the answer is always true: a retry comes after WorkManager's backoff, 30 s and growing, and each would mean another bootstrap
 * against the hourly interval, while the next run finds what this one missed (watermarks).
 */
internal suspend fun checkThroughGate(
    mode: StateFlow<NetworkMode>,
    hasAccount: suspend () -> Boolean,
    visible: () -> Boolean,
    tor: RunTor,
    budgetMs: Long = TOR_BUDGET_MS,
    workLimitMs: Long = TOR_WORK_LIMIT_MS,
    owner: String = nextRunOwner(),
    check: suspend () -> Boolean,
): Boolean {
    // a process started only for this run: MonostrApp is reading the marker
    val known = withTimeoutOrNull(UNKNOWN_WAIT_MS) { mode.first { it != NetworkMode.Unknown } } ?: return true
    if (known !is NetworkMode.Tor) return check()
    // the schedule also runs while logged out, where a run finds nothing to do: not worth a bootstrap
    if (!hasAccount()) return true
    tor.acquire(owner)
    try {
        whileOnTor(mode) {
            if (withTimeoutOrNull(budgetMs) { tor.connected().first { it } } != null && !visible() && mode.value is NetworkMode.Tor) {
                withTimeoutOrNull(workLimitMs) { check() }
            }
        }
    } finally {
        // after the session, which the check closes first; what is still bootstrapping is cancelled here
        tor.release(owner)
    }
    return true
}

/** The switch going off ends [block]: Tor must end before long (spec 5.4), and the session that block is using is gone anyway. */
private suspend fun whileOnTor(mode: StateFlow<NetworkMode>, block: suspend () -> Unit) = coroutineScope {
    val work = launch { block() }
    val left = launch { mode.first { it !is NetworkMode.Tor } }
    select { work.onJoin {}; left.onJoin {} }
    coroutineContext.cancelChildren()
}

/**
 * One check with the session. The session is closed afterwards only if this run built it and it is still the same one: state Loading at
 * the start says nothing, a Tor switch shows it as well while it builds the session this run then waits for. And never under a visible app.
 */
internal suspend fun <R : Any> checkSession(
    restore: suspend (onStarted: (R) -> Unit) -> Unit,
    current: () -> R?,
    route: (R) -> Flow<Boolean>,
    visible: () -> Boolean,
    release: suspend () -> Unit,
    work: suspend (R) -> Boolean,
): Boolean {
    var built: R? = null
    try {
        restore { built = it }
        val ready = current() ?: return true
        // Tor on and not connected: nothing can be checked, and never direct
        withTimeoutOrNull(ROUTE_WAIT_MS) { route(ready).first { it } } ?: return true
        return work(ready)
    } finally {
        if (built != null && built === current() && !visible()) withContext(NonCancellable) { release() }
    }
}

/**
 * Runs every 15 minutes, 60 with Tor ([backgroundIntervalMinutes]), while the app is not visible (no push server, spec 5.7): one session
 * start for the DM check ([checkDms]) and the tip check ([checkTips]). Until 2026-11-02 they were two periodic workers, each starting the
 * engine, its relay connections and everything behind the startup gate on its own. The foreground app syncs both itself,
 * so a run while it is visible does nothing. Uses Hilt entry points instead of hilt-work.
 */
class BackgroundCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // a due run during a cold start would add its lookups beside the feed (spec perf2 A7)
        if (MainActivity.inForeground.get()) return Result.success()
        val deps = EntryPointAccessors.fromApplication(applicationContext, NostrSessionEntryPoint::class.java)
        val controller = deps.torController()
        val done = checkThroughGate(
            deps.networkGate().mode,
            hasAccount = { deps.sessions().session.first() != null },
            visible = MainActivity.inForeground::get,
            tor = RunTor(
                acquire = { controller.get().acquire(it) },
                release = { controller.get().releaseRun(it) },
                connected = { torConnected(controller) },
            ),
        ) { check() }
        return if (done) Result.success() else Result.retry()
    }

    private suspend fun check(): Boolean {
        val dmDeps = EntryPointAccessors.fromApplication(applicationContext, DmCheckWorker.Deps::class.java)
        val tipDeps = EntryPointAccessors.fromApplication(applicationContext, TipCheckWorker.Deps::class.java)
        val session = dmDeps.session()
        // the process may have been woken only for this job; track whether we are the ones starting the engine
        var startedHere = false
        val wallStart = android.os.SystemClock.elapsedRealtime()
        val cpuStart = android.os.Process.getElapsedCpuTime()
        try {
            return checkSession(
                restore = { onStarted -> session.restore { startedHere = true; onStarted(it) } },
                current = { (session.state.value as? SessionState.Active)?.ready },
                route = { it.engine.routeOpen },
                visible = MainActivity.inForeground::get,
                release = { session.release() },
            ) { ready ->
                // spec perf2 A2 (4): a worker session has no feed to protect
                ready.startupGate.open("worker")
                // both parts run whatever the other did; one that failed asks for a retry of the whole run (both are idempotent:
                // watermarks). Tips first: short, about money, and a DM sync that hangs must not keep them back.
                val tips = runPart { checkTips(applicationContext, session, ready, tipDeps.settings()) }
                // logged out while the tip part ran: nothing of that account may be posted any more
                if ((session.state.value as? SessionState.Active)?.ready !== ready) return@checkSession true
                val dms = runPart { checkDms(applicationContext, session, ready, dmDeps.dmSettings()) }
                dms && tips
            }
        } finally {
            PerfTrace.mark("worker: ${android.os.SystemClock.elapsedRealtime() - wallStart} ms wall, process cpu ${android.os.Process.getElapsedCpuTime() - cpuStart} ms, engine started here: $startedHere")
        }
    }

    private suspend fun runPart(part: suspend () -> Boolean): Boolean = try {
        part()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val WORK_NAME = "background-check"

        /** Logout: no check runs (and so no DM or tip notification appears) until the next login schedules it again. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * [torOn]: what the switch says now. Called at every start of the app with the same value, which leaves a running schedule
         * alone; only another period changes it ([schedulePolicy]). Reads the current one without blocking the caller.
         */
        fun schedule(context: Context, torOn: Boolean): Future<Unit> {
            val manager = WorkManager.getInstance(context)
            // the two workers this one replaces
            manager.cancelUniqueWork(DmCheckWorker.WORK_NAME)
            manager.cancelUniqueWork(TipCheckWorker.WORK_NAME)
            val minutes = backgroundIntervalMinutes(torOn)
            val request = PeriodicWorkRequestBuilder<BackgroundCheckWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val existing = manager.getWorkInfosForUniqueWork(WORK_NAME)
            val current = CompletableListenable<Long?>()
            existing.addListener({
                current.complete(valueOrNull(existing)?.firstOrNull { !it.state.isFinished }?.periodicityInfo?.repeatIntervalMillis)
            }, Runnable::run)
            return scheduleWith(current, minutes) { policy -> manager.enqueueUniquePeriodicWork(WORK_NAME, policy, request).result }
        }

        /**
         * At every start of the process, also one the system started for a due run of this work. WorkManager queued its start-up cleanup
         * (ForceStopRunnable) when it was created, before Application.onCreate. That cleanup reschedules work, and so cancels the job
         * that started this process, in three cases:
         * - reconcile: a work whose job JobScheduler does not list as pending, which includes the job running right now (every cold
         *   start for a due run, `Reconciling jobs`);
         * - a force stop as WorkManager reads it: an ApplicationExitInfo with REASON_USER_REQUESTED since its last check, which a swipe
         *   from recents (REMOVE TASK) or `am kill` (KILL BACKGROUND) leave as well (`Application was force-stopped, rescheduling`);
         * - work left RUNNING by a process that died, reset to ENQUEUED and scheduled again.
         * If the system delivered that job before the cleanup ran, the cancel stops the run, the restarted job is finished at once, and
         * the worker carries on with no job at all: the process is frozen 10 s later, a Tor run never ends, the work stays RUNNING and
         * nothing schedules it again (Pixel 2026-11-07). The system delivers the job only after onCreate, so the start waits here until
         * the cleanup and this scheduling have gone through WorkManager's serial queue: about 40 ms on the emulator. Not for a start for
         * the screen ([wait] false), whose first frame this would delay: a run there sees the visible app and ends at once, and a visible
         * process is not frozen. What remains: a start for the screen in the same new process as a due job, and the user leaving the app
         * within about 10 s, before that run ends.
         */
        fun scheduleAtStart(context: Context, torOn: Boolean, wait: Boolean): StartQueue {
            val done = schedule(context, torOn)
            if (!wait) return StartQueue.NOT_WAITED
            return if (awaitWorkQueue(done, START_QUEUE_WAIT_MS)) StartQueue.THROUGH else StartQueue.TIMED_OUT
        }
    }
}
