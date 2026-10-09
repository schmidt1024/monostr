package com.monostr.tips

/** JUnit's assertThrows cannot take a suspend lambda; this one can. */
suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}", e)
    }
    throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
}
