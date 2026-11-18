package com.monostr.app.ui.common

import androidx.compose.material3.SnackbarDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnackbarDurationTest {
    @Test
    fun `an undo goes away by itself`() {
        assertEquals(SnackbarDuration.Long, snackbarDuration(undo = true, retry = false))
    }

    @Test
    fun `a retry waits for an answer`() {
        assertEquals(SnackbarDuration.Indefinite, snackbarDuration(undo = false, retry = true))
    }

    @Test
    fun `a plain message is short`() {
        assertEquals(SnackbarDuration.Short, snackbarDuration(undo = false, retry = false))
    }
}
