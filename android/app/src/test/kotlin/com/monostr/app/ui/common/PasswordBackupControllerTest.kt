package com.monostr.app.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordBackupControllerTest {
    private val hex = "11".repeat(32)

    private fun controller(encrypt: suspend (String, String) -> String = { _, _ -> "ncryptsec1fake" }) =
        PasswordBackupController(hex, encrypt)

    @Test fun shortPasswordCannotCreate() {
        val c = controller()
        c.setPassword("1234567"); c.setRepeat("1234567")
        assertTrue(c.state.value.tooShort)
        assertFalse(c.state.value.canCreate)
        c.setPassword("12345678"); c.setRepeat("12345678")
        assertTrue(c.state.value.canCreate)
    }

    @Test fun mismatchOnlyWhenRepeatIsFilledAndDiffers() {
        val c = controller()
        c.setPassword("password1")
        assertFalse(c.state.value.mismatch)
        c.setRepeat("password")
        assertTrue(c.state.value.mismatch)
        assertFalse(c.state.value.canCreate)
        c.setRepeat("password1")
        assertFalse(c.state.value.mismatch)
    }

    @Test fun createPassesSecretAndPasswordUnchanged() = runTest {
        var got: Pair<String, String>? = null
        val c = controller { s, p -> got = s to p; "ncryptsec1fake" }
        c.setPassword("password1 "); c.setRepeat("password1 ")
        c.create()
        assertEquals(PasswordBackupState.Step.Done, c.state.value.step)
        assertEquals("ncryptsec1fake", c.state.value.ncryptsec)
        assertEquals(hex to "password1 ", got)
    }

    @Test fun failureGoesToFailedAndCanRetry() = runTest {
        val c = controller { _, _ -> throw IllegalStateException("boom") }
        c.setPassword("password1"); c.setRepeat("password1")
        c.create()
        assertEquals(PasswordBackupState.Step.Failed, c.state.value.step)
        assertNull(c.state.value.ncryptsec)
        assertTrue(c.state.value.canCreate)
    }

    @Test fun secondCreateWhileBusyIsIgnored() = runTest {
        var calls = 0
        val gate = CompletableDeferred<String>()
        val c = controller { _, _ -> calls++; gate.await() }
        c.setPassword("password1"); c.setRepeat("password1")
        val first = launch { c.create() }
        advanceUntilIdle()
        assertEquals(PasswordBackupState.Step.Busy, c.state.value.step)
        c.create()
        assertEquals(1, calls)
        gate.complete("ncryptsec1fake")
        first.join()
        assertEquals(PasswordBackupState.Step.Done, c.state.value.step)
    }

    @Test fun toStringHidesSecrets() = runTest {
        val c = controller()
        c.setPassword("password1"); c.setRepeat("password1")
        c.create()
        val s = c.state.value.toString()
        assertFalse(s.contains("password1"))
        assertFalse(s.contains("ncryptsec1fake"))
    }
}
