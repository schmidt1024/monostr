package com.monostr.app.ui.tips

import com.monostr.app.data.HintStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalletNoteOnceTest {
    private class MemHints : HintStore {
        val done = MutableStateFlow(emptySet<String>())
        override suspend fun firstLoginAt(pubkey: String): Long? = null
        override suspend fun markFirstLogin(pubkey: String, at: Long) {}
        override fun supportHintDone(pubkey: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setSupportHintDone(pubkey: String) {}
        override fun dismissed(pubkey: String, hint: String): Flow<Boolean> = done.map { "$pubkey/$hint" in it }
        override suspend fun dismiss(pubkey: String, hint: String) { done.value = done.value + "$pubkey/$hint" }
    }

    @Test
    fun `only under Tor, once per account, and not used up while direct`() = runTest {
        val hints = MemHints()
        var tor = false
        val alice = walletNoteOnce({ tor }, hints, "alice")
        assertFalse(alice())
        assertTrue(hints.done.value.isEmpty(), "direct uses nothing up")
        tor = true
        assertTrue(alice())
        assertFalse(alice())
        assertTrue(walletNoteOnce({ tor }, hints, "bob")())
    }
}
