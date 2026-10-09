package com.monostr.app.ui

import com.monostr.app.ui.profile.followersNumber
import com.monostr.app.ui.profile.followersQuantity
import com.monostr.app.ui.profile.numberInto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class FollowCountsLineTest {
    @Test
    fun `full number with separators below ten thousand, compact above`() {
        assertEquals("3,373", followersNumber(3373, Locale.US))
        assertEquals("3.373", followersNumber(3373, Locale.GERMANY))
        assertEquals("12k", followersNumber(12_345, Locale.US))
        assertEquals("1.2M", followersNumber(1_234_567, Locale.US))
    }

    @Test
    fun `the plural case follows the number until it is shown compact`() {
        assertEquals(1, followersQuantity(1))
        assertEquals(9_999, followersQuantity(9_999))
        assertEquals(10_000, followersQuantity(10_001)) // "10k": never a singular form
    }

    @Test
    fun `the number goes where the template's placeholder is, and only that range is bold`() {
        assertEquals("3,373 Followers" to (0 until 5), numberInto("\u0000 Followers", "3,373"))
        assertEquals("Takipçi 12" to (8 until 10), numberInto("Takipçi \u0000", "12"))
        assertEquals("12 von 12" to (0 until 2), numberInto("\u0000 von 12", "12")) // digits elsewhere do not confuse it
    }
}
