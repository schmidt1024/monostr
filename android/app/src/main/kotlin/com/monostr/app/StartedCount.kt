package com.monostr.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * The started activity instances of the process. Two can be started at once (two tasks, a switch in Recents): the newer one starts
 * before the older one stops, so a flag set by each instance read «background» while one was on screen.
 */
class StartedCount {
    private val count = AtomicInteger()

    /** True when this start made the app visible. */
    fun start(): Boolean = count.incrementAndGet() == 1

    /** True when this stop left nothing started. */
    fun stop(): Boolean = count.updateAndGet { maxOf(0, it - 1) } == 0
}
