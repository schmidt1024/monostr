package com.monostr.tips

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolVersionTest {
    @Test
    fun `supportsHiddenAmounts table`() {
        for (v in listOf("0.5", "0.6", "1.0", "0.5.1")) assertEquals(true, ProtocolVersion.supportsHiddenAmounts(v), v)
        for (v in listOf(null, "", "0.4", "x", "0")) assertEquals(false, ProtocolVersion.supportsHiddenAmounts(v), v.toString())
    }
}
