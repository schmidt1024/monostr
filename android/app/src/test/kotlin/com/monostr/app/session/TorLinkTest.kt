package com.monostr.app.session

import com.monostr.app.net.TorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The session's half of "the engine runs only while Tor is connected"; the engine's half is EngineHeldTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class TorLinkTest {
    @Test
    fun `the engine does not start before Connected, stops when Tor leaves it, starts again when it returns`() = runTest {
        val state = MutableStateFlow<TorState>(TorState.Connecting(null))
        val log = ArrayList<String>()
        val job = launch { followTor(state, { log += "hold" }) { log += "open" } }
        advanceUntilIdle()
        state.value = TorState.Connecting(40)
        advanceUntilIdle()
        assertEquals(listOf("hold"), log, "progress changes nothing")
        state.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(listOf("hold", "open"), log)
        state.value = TorState.Failed
        advanceUntilIdle()
        state.value = TorState.Connecting(null)
        advanceUntilIdle()
        assertEquals(listOf("hold", "open", "hold"), log)
        state.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(listOf("hold", "open", "hold", "open"), log)
        job.cancel()
    }

    @Test
    fun `logout during the wait - nothing opens when Tor connects afterwards`() = runTest {
        val state = MutableStateFlow<TorState>(TorState.Connecting(null))
        val log = ArrayList<String>()
        val job = launch { followTor(state, { log += "hold" }) { log += "open" } }
        advanceUntilIdle()
        job.cancel() // what engine.close() does to its scope
        state.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(listOf("hold"), log)
    }

    @Test
    fun `a hold does not wait for an open still talking to rust`() = runTest {
        val state = MutableStateFlow<TorState>(TorState.Connected)
        val log = ArrayList<String>()
        val stuck = CompletableDeferred<Unit>()
        val job = launch { followTor(state, { log += "hold" }) { log += "open"; stuck.await() } }
        advanceUntilIdle()
        state.value = TorState.Failed
        advanceUntilIdle()
        assertEquals(listOf("open", "hold"), log)
        job.cancel()
    }
}
