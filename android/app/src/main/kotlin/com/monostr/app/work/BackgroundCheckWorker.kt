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
import com.monostr.app.session.SessionState
import com.monostr.nostr.PerfTrace
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes while the app is not visible (no push server, spec 5.7): one session start for the DM check
 * ([checkDms]) and the tip check ([checkTips]). Until 2026-11-02 they were two periodic workers, each starting the
 * engine, its relay connections and everything behind the startup gate on its own. The foreground app syncs both itself,
 * so a run while it is visible does nothing. Uses Hilt entry points instead of hilt-work.
 */
class BackgroundCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // a due run during a cold start would add its lookups beside the feed (spec perf2 A7)
        if (MainActivity.inForeground.get()) return Result.success()
        val dmDeps = EntryPointAccessors.fromApplication(applicationContext, DmCheckWorker.Deps::class.java)
        val tipDeps = EntryPointAccessors.fromApplication(applicationContext, TipCheckWorker.Deps::class.java)
        val session = dmDeps.session()
        // the process may have been woken only for this job; track whether we are the ones starting the engine
        val startedHere = session.state.value !is SessionState.Active
        val wallStart = android.os.SystemClock.elapsedRealtime()
        val cpuStart = android.os.Process.getElapsedCpuTime()
        try {
            session.restore()
            val ready = (session.state.value as? SessionState.Active)?.ready ?: return Result.success()
            // spec perf2 A2 (4): a worker session has no feed to protect
            ready.startupGate.open("worker")
            // both parts run whatever the other did; one that failed asks for a retry of the whole run (both are idempotent:
            // watermarks). Tips first: short, about money, and a DM sync that hangs must not keep them back.
            val tips = runPart { checkTips(applicationContext, session, ready, tipDeps.settings()) }
            // logged out while the tip part ran: nothing of that account may be posted any more
            if ((session.state.value as? SessionState.Active)?.ready !== ready) return Result.success()
            val dms = runPart { checkDms(applicationContext, session, ready, dmDeps.dmSettings()) }
            return if (dms && tips) Result.success() else Result.retry()
        } finally {
            PerfTrace.mark("worker: ${android.os.SystemClock.elapsedRealtime() - wallStart} ms wall, process cpu ${android.os.Process.getElapsedCpuTime() - cpuStart} ms, engine started here: $startedHere")
            // don't tear down an engine the foreground app is now using
            if (startedHere && !MainActivity.inForeground.get()) withContext(NonCancellable) { session.release() }
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

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            // the two workers this one replaces
            manager.cancelUniqueWork(DmCheckWorker.WORK_NAME)
            manager.cancelUniqueWork(TipCheckWorker.WORK_NAME)
            val request = PeriodicWorkRequestBuilder<BackgroundCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
