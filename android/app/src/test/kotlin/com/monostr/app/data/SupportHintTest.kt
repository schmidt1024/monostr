package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SupportHintTest {
    @TempDir lateinit var dir: Path
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val day = 86_400L

    private fun store() = PrefsHintStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { dir.resolve("hints.preferences_pb").toFile() },
    )

    @Test
    fun `the hint shows from day 14 on and never again once done`() {
        val first = 1_000_000L
        assertFalse(SupportHint.visible(null, done = false, now = first + 100 * day)) // no login recorded
        assertFalse(SupportHint.visible(first, done = false, now = first))
        assertFalse(SupportHint.visible(first, done = false, now = first + 14 * day - 1))
        assertTrue(SupportHint.visible(first, done = false, now = first + 14 * day))
        assertTrue(SupportHint.visible(first, done = false, now = first + 400 * day))
        assertFalse(SupportHint.visible(first, done = true, now = first + 14 * day))
        assertFalse(SupportHint.visible(first, done = true, now = first + 400 * day))
    }

    @Test
    fun `a second login keeps the first login time, per account`() = runTest {
        val s = store()
        assertNull(s.firstLoginAt(alice))
        s.markFirstLogin(alice, 1_000)
        s.markFirstLogin(alice, 5_000)
        s.markFirstLogin(bob, 7_000)
        assertEquals(1_000L, s.firstLoginAt(alice))
        assertEquals(7_000L, s.firstLoginAt(bob))
    }

    @Test
    fun `done and dismissed are remembered per account`() = runTest {
        val s = store()
        assertFalse(s.supportHintDone(alice).first())
        s.setSupportHintDone(alice)
        assertTrue(s.supportHintDone(alice).first())
        assertFalse(s.supportHintDone(bob).first())
        assertFalse(s.dismissed(alice, "dm_missing").first())
        s.dismiss(alice, "dm_missing")
        assertTrue(s.dismissed(alice, "dm_missing").first())
        assertFalse(s.dismissed(alice, "dm_second").first())
        assertFalse(s.dismissed(bob, "dm_missing").first())
    }

    @Test
    fun `an unreadable store hides the hint instead of throwing`() = runTest {
        val broken = object : HintStore {
            override suspend fun firstLoginAt(pubkey: String): Long? = throw java.io.IOException("corrupt")
            override suspend fun markFirstLogin(pubkey: String, at: Long) = Unit
            override fun supportHintDone(pubkey: String) = kotlinx.coroutines.flow.flow<Boolean> { throw java.io.IOException("corrupt") }
            override suspend fun setSupportHintDone(pubkey: String) = Unit
            override fun dismissed(pubkey: String, hint: String) = kotlinx.coroutines.flow.flowOf(false)
            override suspend fun dismiss(pubkey: String, hint: String) = Unit
        }
        assertFalse(SupportHint.observe(broken, alice) { 1_000_000L * 10 }.first())
    }
}
