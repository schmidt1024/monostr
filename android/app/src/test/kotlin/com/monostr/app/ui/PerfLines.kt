package com.monostr.app.ui

import com.monostr.nostr.PerfTrace

/**
 * Runs [block] with PerfTrace on and its lines captured (also readable inside [block]); restores PerfTrace afterwards.
 * The app's copy of the nostr test helper of the same name: test sources do not cross the module boundary (R14).
 */
fun withPerfLines(block: MutableList<String>.() -> Unit): List<String> {
    val lines = ArrayList<String>()
    val enabled = PerfTrace.enabled
    val sink = PerfTrace.sink
    val since = PerfTrace.sinceStart
    PerfTrace.enabled = true
    PerfTrace.sink = { lines += it }
    try {
        lines.block()
    } finally {
        PerfTrace.enabled = enabled
        PerfTrace.sink = sink
        PerfTrace.sinceStart = since
    }
    return lines
}
