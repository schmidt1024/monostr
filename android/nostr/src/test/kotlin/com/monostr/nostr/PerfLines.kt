package com.monostr.nostr

/** Runs [block] with PerfTrace on and its lines captured (also readable inside [block]); restores PerfTrace afterwards. */
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
