package com.monostr.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** XMR or mXMR (2026-10-11, launch feedback like Stasher): only how amounts read and are typed; piconero inside. */
class AmountUnitTest {
    @Test
    fun `the default presets read as powers of ten in both units`() {
        assertEquals(listOf("0.0001", "0.001", "0.01"), Presets.DEFAULT.map { Presets.format(it, AmountUnit.XMR) })
        assertEquals(listOf("0.1", "1", "10"), Presets.DEFAULT.map { Presets.format(it, AmountUnit.MXMR) })
        assertEquals("0.000000001", Presets.format(1, AmountUnit.MXMR))
    }

    @Test
    fun `typed amounts are read in the chosen unit, a comma works, too many decimals or nothing positive do not`() {
        assertEquals(1_000_000_000L, Presets.parse("1", AmountUnit.MXMR))
        assertEquals(100_000_000L, Presets.parse("0,1", AmountUnit.MXMR))
        assertEquals(1_000_000_000L, Presets.parse("0.001", AmountUnit.XMR))
        assertNull(Presets.parse("0.0000000001", AmountUnit.MXMR)) // below one piconero
        assertNull(Presets.parse("0", AmountUnit.MXMR))
        assertNull(Presets.parse("-1", AmountUnit.XMR))
        assertNull(Presets.parse("abc", AmountUnit.XMR))
        assertEquals(listOf(100_000_000L, 1_000_000_000L), Presets.parseAll(listOf("0.1", "", "1", "1"), AmountUnit.MXMR))
        assertNull(Presets.parseAll(listOf("0.1", "x"), AmountUnit.MXMR))
    }

    @Test
    fun `an amount with its label`() {
        assertEquals("1 mXMR", Presets.withUnit(1_000_000_000L, AmountUnit.MXMR))
        assertEquals("0.001 XMR", Presets.withUnit(1_000_000_000L, AmountUnit.XMR))
    }
}
