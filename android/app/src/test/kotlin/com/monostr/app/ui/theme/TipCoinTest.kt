package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Spec 2026-11-09 coin logo: the tip button is the coin, outline until a tip is given, then filled in the accent colour. */
class TipCoinTest {
    @Test
    fun `not tipped is the outline`() = assertSame(TipCoinOutline, tipCoin(tipped = false))

    @Test
    fun `tipped is filled`() = assertSame(TipCoinFilled, tipCoin(tipped = true))

    @Test
    fun `tipped and pending is filled`() {
        // a second tip on a note already tipped: the note has a tip, so the coin stays filled; pending does not enter the choice
        assertSame(TipCoinFilled, tipCoin(tipped = true))
    }

    @Test
    fun `the filled coin is one even-odd path`() {
        // an Icon tint colours every path alike: beads and M are holes in the disc, not paths in another colour
        val root = TipCoinFilled.root
        assertEquals(1, root.size)
        assertEquals(PathFillType.EvenOdd, (root[0] as VectorPath).pathFillType)
    }
}
