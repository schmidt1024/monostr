package com.monostr.app.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.flow.update
import org.junit.jupiter.api.Test

class NotificationAccountTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val peer = "c".repeat(64)

    @Test
    fun `notification of inactive account switches first`() {
        assertEquals(OpenAction.SwitchThenOpen(a, peer), resolveOpen(active = b, known = setOf(a, b), account = a, peer = peer))
    }

    @Test
    fun `notification of the active account or without account opens directly`() {
        assertEquals(OpenAction.Open(peer), resolveOpen(b, setOf(a, b), b, peer))
        assertEquals(OpenAction.Open(peer), resolveOpen(b, setOf(a, b), null, peer))
    }

    @Test
    fun `unknown account ignored`() {
        assertEquals(OpenAction.Ignore, resolveOpen(b, setOf(b), a, peer))
    }

    @Test
    fun `tags and request codes differ per account`() {
        assertNotEquals(AccountNotifications.dmTag(a), AccountNotifications.dmTag(b))
        assertNotEquals(AccountNotifications.requestCode(a, peer), AccountNotifications.requestCode(b, peer))
        assertTrue(AccountNotifications.belongs(AccountNotifications.dmTag(a), a))
        assertTrue(AccountNotifications.belongs(AccountNotifications.tipTag(a), a))
        assertFalse(AccountNotifications.belongs(AccountNotifications.dmTag(b), a))
        assertFalse(AccountNotifications.belongs(null, a))
    }

    @Test
    fun `target of A is not consumed while B is ready and is consumed when A is ready`() {
        val target = PendingOpen(a, peer, messages = false)
        assertFalse(shouldConsume(target, b))
        assertTrue(shouldConsume(target, a))
        assertFalse(shouldConsume(null, a))
    }

    @Test
    fun `switch done leaves the target until A is ready and removal clears it`() {
        val pending = kotlinx.coroutines.flow.MutableStateFlow<PendingOpen?>(PendingOpen(a, peer, false))
        // switchTo(a) returned Done but B's nav host is still composed: nothing consumes
        assertFalse(shouldConsume(pending.value, b))
        // removal of B leaves A's target; removal of A clears it in one write
        pending.update { if (it?.account == b) null else it }
        assertEquals(PendingOpen(a, peer, false), pending.value)
        pending.update { if (it?.account == a) null else it }
        assertEquals(null, pending.value)
    }
}
