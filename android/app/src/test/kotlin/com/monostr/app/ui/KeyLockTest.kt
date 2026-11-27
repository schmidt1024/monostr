package com.monostr.app.ui

import com.monostr.app.ui.settings.KeyLock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyLockTest {
    @Test
    fun `the screen lock is asked for only when the switch is on and the phone has one`() {
        assertTrue(KeyLock.asks(enabled = true, deviceSecure = true))
        assertFalse(KeyLock.asks(enabled = false, deviceSecure = true), "off by default: the warning alone opens the key")
        assertFalse(KeyLock.asks(enabled = true, deviceSecure = false), "no lock on the phone must not lock the key away")
    }
}
