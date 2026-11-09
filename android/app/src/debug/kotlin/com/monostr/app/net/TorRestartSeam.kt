package com.monostr.app.net

import java.util.concurrent.atomic.AtomicInteger

/**
 * The instrumentation suite runs in the app's process, so a real end of the process would end the suite. Only its runner sets [inProcess]
 * (`MonostrTestRunner`, not with `-e realEnd true`, which the leak test uses); the call is recorded and the switch goes on in place. Debug only: `src/release` has no such thing.
 */
object TorRestartSeam {
    @Volatile var inProcess = false
    val recorded = AtomicInteger()
}

internal fun directRouteEndsProcess(used: () -> Boolean): () -> Boolean = {
    val ends = used()
    if (ends && TorRestartSeam.inProcess) {
        TorRestartSeam.recorded.incrementAndGet()
        false
    } else {
        ends
    }
}
