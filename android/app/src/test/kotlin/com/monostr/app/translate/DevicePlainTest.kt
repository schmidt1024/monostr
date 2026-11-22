package com.monostr.app.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DevicePlainTest {
    @Test
    fun toPlain_spanAndLinkBecomeMarkers() {
        val span = "<span translate=\"no\">⟦0⟧</span>"
        val a = "<a data-i=\"0\">here</a>"
        val p = DevicePlain.toPlain("Hi $span $a")!!
        assertEquals("Hi [0] [1]", p.text)
        assertEquals(listOf(span, a), p.frozen)
    }

    @Test
    fun toPlain_dropsFormatting() {
        assertEquals("big deal", DevicePlain.toPlain("<b>big</b> deal")!!.text)
        assertEquals("a b c", DevicePlain.toPlain("<i>a</i> <s>b</s> <B>c</B>")!!.text)
    }

    @Test
    fun toPlain_brAndEntities() {
        assertEquals("a&b\nc", DevicePlain.toPlain("a&amp;b<br/>c")!!.text)
        assertEquals("<>\"'\n", DevicePlain.toPlain("&lt;&gt;&quot;&#39;<br />")!!.text)
    }

    @Test
    fun toPlain_unknownTag_null() {
        assertNull(DevicePlain.toPlain("<u>x</u>"))
    }

    @Test
    fun toPlain_markerCharsInText_areOrdinary() {
        assertEquals("a ⟦0⟧ b", DevicePlain.toPlain("a ⟦0⟧ b")!!.text)
    }

    @Test
    fun toPlain_footnotesShiftOffset() {
        val p = DevicePlain.toPlain("see [1] and <span translate=\"no\">⟦0⟧</span>")!!
        assertEquals("see [1] and [2]", p.text)
        assertEquals(2, p.offset)
    }

    @Test
    fun fromPlain_footnoteStaysText() {
        val span = "<span translate=\"no\">⟦0⟧</span>"
        val p = DevicePlain.toPlain("see [1] and $span")!!
        assertEquals("siehe [1] und $span", DevicePlain.fromPlain("siehe [1] und [2]", p))
    }

    @Test
    fun fromPlain_rejectsOutOfRangeAsMissing() {
        val p = DevicePlain.toPlain("<span translate=\"no\">⟦0⟧</span>")!!
        assertNull(DevicePlain.fromPlain("x [1]", p))
        assertNull(DevicePlain.fromPlain("x [01]", p))
    }

    @Test
    fun fromPlain_toleratesWhitespaceAroundMarker() {
        val span = "<span translate=\"no\">⟦0⟧</span>"
        val p = DevicePlain.toPlain("a $span b")!!
        assertEquals("x $span y", DevicePlain.fromPlain("x [0] y", p))
        assertEquals("x$span\ny".replace("\n", "<br>"), DevicePlain.fromPlain("x[0]\ny", p))
    }

    @Test
    fun fromPlain_roundTrip() {
        val note = "Hallo https://example.com/x #monero und mehr & <b>"
        val shielded = TokenShield.protect(note)!!
        val plain = DevicePlain.toPlain(shielded.html)!!
        val html = DevicePlain.fromPlain(plain.text, plain)
        assertNotNull(html)
        assertEquals(note, TokenShield.restore(html!!, shielded))
    }

    @Test
    fun fromPlain_rejectsChangedMarker() {
        val plain = DevicePlain.toPlain("<span translate=\"no\">⟦0⟧</span>")!!
        assertNull(DevicePlain.fromPlain("(0)", plain))
    }

    @Test
    fun fromPlain_rejectsMissingMarker() {
        val plain = DevicePlain.toPlain("<span translate=\"no\">⟦0⟧</span> <a data-i=\"0\">x</a>")!!
        assertNull(DevicePlain.fromPlain("only [0]", plain))
    }

    @Test
    fun fromPlain_rejectsDuplicatedMarker() {
        val plain = DevicePlain.toPlain("<span translate=\"no\">⟦0⟧</span>")!!
        assertNull(DevicePlain.fromPlain("[0] [0]", plain))
    }

    @Test
    fun fromPlain_escapesText() {
        assertEquals("a&lt;b", DevicePlain.fromPlain("a<b", Plain("a<b", emptyList())))
        assertEquals("x<br>y", DevicePlain.fromPlain("x\ny", Plain("x\ny", emptyList())))
    }

    @Test
    fun fromPlain_footnoteRenumberedOntoMarker_isNull() {
        val plain = DevicePlain.toPlain("see [1] and <span translate=\"no\">⟦0⟧</span>")!!
        assertEquals(2, plain.offset)
        assertNull(DevicePlain.fromPlain("siehe [2] und [2]", plain))
    }
}
