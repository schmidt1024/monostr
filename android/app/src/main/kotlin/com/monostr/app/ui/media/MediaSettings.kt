package com.monostr.app.ui.media

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Spec 3.2/3.4: how the note cards treat media; provided once by the nav host from UiSettingsStore. */
data class MediaSettings(val blurSensitive: Boolean = true, val mediaOnTap: Boolean = false)

val LocalMediaSettings = compositionLocalOf { MediaSettings() }

/** Notes whose media the user revealed or loaded this session (spec 3.2); process-wide, never persisted. */
object MediaReveals {
    private val _revealed = MutableStateFlow<Set<String>>(emptySet())
    val revealed: StateFlow<Set<String>> = _revealed.asStateFlow()
    fun reveal(noteId: String) = _revealed.update { it + noteId }

    /** Forgets every reveal; called on logout so the next account starts covered again. */
    fun clear() { _revealed.value = emptySet() }

    /** Test-only: this object is a process-wide singleton (by design, spec 3.2), so androidTest
     * classes that reuse the same note id across cases must reset it between tests themselves. */
    @VisibleForTesting
    fun resetForTests() = clear()
}
