package com.monostr.app.ui

import com.monostr.app.ui.settings.ShowKeyGate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-17 §6: the key screen opens once per passed gate; a restored route has no grant. */
class ShowKeyGateTest {
    @Test fun noGrantNoKey() {
        ShowKeyGate.consume()
        assertFalse(ShowKeyGate.consume())
    }

    @Test fun aGrantIsUsedOnce() {
        ShowKeyGate.grant()
        assertTrue(ShowKeyGate.consume())
        assertFalse(ShowKeyGate.consume())
    }
}
