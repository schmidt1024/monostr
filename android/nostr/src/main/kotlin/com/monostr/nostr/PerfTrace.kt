package com.monostr.nostr

/**
 * Spec perf 3.9: timing marks kept in the code. Off unless the app was built with `-Pmonostr.perf=true`
 * (the app sets [enabled] from `BuildConfig.PERF`); off, a mark costs one volatile read. Lines go to
 * `System.out` (logcat tag `System.out`): R8 strips `android.util.Log` in release builds.
 */
object PerfTrace {
    @Volatile var enabled: Boolean = false

    private val loadedAt = System.nanoTime()

    /** Milliseconds since the process started; the app sets the Android clock, the default counts from the first use of this object (JVM tests). */
    @Volatile var sinceStart: () -> Long = { (System.nanoTime() - loadedAt) / 1_000_000 }

    /** Where the lines go; tests replace it. */
    @Volatile var sink: (String) -> Unit = { println(it) }

    fun mark(name: String) {
        if (enabled) sink("MonostrPerf @${sinceStart()}ms $name")
    }
}
