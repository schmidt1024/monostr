package com.monostr.app.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountBusyTest {
    @Test
    fun `busy while any tracked block runs`() = runTest(UnconfinedTestDispatcher()) {
        val gate = AccountBusy()
        val release = CompletableDeferred<Unit>()
        assertFalse(gate.busy.value)
        val job = launch { gate.track { release.await() } }
        assertTrue(gate.busy.value)
        release.complete(Unit)
        job.join()
        assertFalse(gate.busy.value)
    }

    @Test
    fun `exception still releases`() = runTest(UnconfinedTestDispatcher()) {
        val gate = AccountBusy()
        val result = runCatching { gate.track { error("boom") } }
        assertTrue(result.isFailure)
        assertFalse(gate.busy.value)
    }

    @Test
    fun `cancel releases and the block value is returned`() = runTest(UnconfinedTestDispatcher()) {
        val gate = AccountBusy()
        val job = launch { gate.track { CompletableDeferred<Unit>().await() } }
        assertTrue(gate.busy.value)
        job.cancel()
        job.join()
        assertFalse(gate.busy.value)
        assertEquals(7, gate.track { 7 })
    }

    @Test
    fun `parallel blocks counted`() = runTest(UnconfinedTestDispatcher()) {
        val gate = AccountBusy()
        val a = CompletableDeferred<Unit>()
        val b = CompletableDeferred<Unit>()
        val ja = launch { gate.track { a.await() } }
        val jb = launch { gate.track { gate.track { b.await() } } }
        a.complete(Unit)
        ja.join()
        assertTrue(gate.busy.value)
        b.complete(Unit)
        jb.join()
        assertFalse(gate.busy.value)
    }
}
