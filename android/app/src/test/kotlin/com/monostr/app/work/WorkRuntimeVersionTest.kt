package com.monostr.app.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Upgrade tripwire for [BackgroundCheckWorker.scheduleAtStart]. Reads the version of the work-runtime that is really on the classpath
 * (the jar's own version file), not the catalog entry.
 */
class WorkRuntimeVersionTest {
    @Test
    fun `work-runtime is the version whose start-up order scheduleAtStart relies on`() {
        val version = javaClass.classLoader!!.getResource("META-INF/androidx.work_work-runtime.version")?.readText()?.trim()
        assertEquals(
            "2.11.2", version,
            "work-runtime changed. Before updating this test, check in the new version's code (bytecode if need be) that " +
                "WorkManagerImpl still queues ForceStopRunnable on the serial task executor when it is created (before Application.onCreate), " +
                "that getWorkInfosForUniqueWork and enqueueUniquePeriodicWork still run on that same serial executor, and that the " +
                "cleanup's reconcile still reschedules a running job. If any of that changed, BackgroundCheckWorker.scheduleAtStart may no " +
                "longer keep the cleanup ahead of the job; recheck with a natural due run under Tor (worker-report.md, 2026-11-07).",
        )
    }
}
