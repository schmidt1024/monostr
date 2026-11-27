package com.monostr.app

import android.os.Handler
import android.os.Looper

/**
 * Perf builds only (owner 2026-11-26, chat arrow «did nothing» while the main thread stalled 3.3 s): a background thread posts a
 * probe to the main thread every [periodMs]; while a probe waits longer than [thresholdMs], the main thread's stack goes to [report]
 * (at most [maxSamples] per stall, a stall's stack may move), and when the probe finally runs, how long the stall lasted.
 * The logic is driven by [tick] so tests need no threads or looper.
 */
class MainThreadWatchdog(
    private val post: (Runnable) -> Unit,
    private val mainStack: () -> Array<StackTraceElement>,
    private val report: (String) -> Unit,
    private val now: () -> Long,
    private val thresholdMs: Long = 700,
    private val maxSamples: Int = 3,
    private val maxFrames: Int = 40,
) {
    private val lock = Any()
    private var waitingSince: Long? = null
    private var samples = 0

    /** One check: posts a probe when none is waiting, else samples the stack of a stall past the threshold. */
    fun tick() {
        val sample: Long
        synchronized(lock) {
            val since = waitingSince
            if (since == null) {
                waitingSince = now()
                samples = 0
                post(Runnable { probeRan() })
                return
            }
            val waited = now() - since
            if (waited < thresholdMs || samples >= maxSamples) return
            samples++
            sample = waited
        }
        val frames = mainStack().take(maxFrames).joinToString("\n") { "    at $it" }
        report("main thread blocked ${sample} ms\n$frames")
    }

    private fun probeRan() {
        val stalled: Long
        synchronized(lock) {
            val since = waitingSince ?: return
            stalled = if (samples > 0) now() - since else -1
            waitingSince = null
            samples = 0
        }
        if (stalled >= 0) report("main thread free after ${stalled} ms")
    }

    companion object {
        /** Starts the watchdog on a daemon thread for the real main looper. */
        fun start(report: (String) -> Unit, periodMs: Long = 250) {
            val main = Handler(Looper.getMainLooper())
            val dog = MainThreadWatchdog(
                post = { main.post(it) },
                mainStack = { Looper.getMainLooper().thread.stackTrace },
                report = report,
                now = { android.os.SystemClock.uptimeMillis() },
            )
            Thread({
                while (true) {
                    dog.tick()
                    try { Thread.sleep(periodMs) } catch (e: InterruptedException) { return@Thread }
                }
            }, "main-watchdog").apply { isDaemon = true }.start()
        }
    }
}
