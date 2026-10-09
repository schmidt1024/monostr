package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.pluralText
import com.monostr.app.ui.common.uiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class UiTextTest {
    @Test
    fun `res texts compare by id and arguments, plain texts by content`() {
        assertEquals(UiText.Res(R.string.tip_error_amount), uiText(R.string.tip_error_amount))
        assertEquals(UiText.Res(R.string.time_minutes, listOf(5L)), uiText(R.string.time_minutes, 5L))
        assertNotEquals(uiText(R.string.time_minutes, 5L), uiText(R.string.time_minutes, 6L))
        assertNotEquals(uiText(R.string.tip_error_amount), uiText(R.string.tip_no_monero))
        assertEquals(UiText.Plain("wss://relay.example"), UiText.Plain("wss://relay.example"))
    }

    @Test
    fun `plural texts compare by id, count and arguments`() {
        assertEquals(UiText.Plural(R.plurals.dm_pending_summary, 3, listOf(3)), pluralText(R.plurals.dm_pending_summary, 3, 3))
        assertNotEquals(pluralText(R.plurals.dm_pending_summary, 3, 3), pluralText(R.plurals.dm_pending_summary, 1, 1))
    }
}
