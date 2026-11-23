package com.monostr.app.ui.article

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Backlog 51: the reader's own dark/light choice, one global setting. Null until the first tap: the reader follows the app theme. */
interface ReaderThemeStore {
    val dark: Flow<Boolean?>
    suspend fun setDark(dark: Boolean)

    /** Backlog 102: the light reader on sepia paper instead of white (default off). */
    val sepia: Flow<Boolean>
    suspend fun setSepia(on: Boolean)

    /** Backlog 102: the reader's text size step, an index into [ReaderText.SCALES] (default 1 = 1.0x). */
    val textStep: Flow<Int>
    suspend fun setTextStep(step: Int)
}

/** The five text sizes of the reader (body and headings scale together); one source of truth for store, sheet and settings. */
object ReaderText {
    val SCALES = listOf(0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
    const val DEFAULT_STEP = 1
    val LAST = SCALES.lastIndex
    fun clamp(step: Int): Int = step.coerceIn(0, LAST)
    fun scale(step: Int): Float = SCALES[clamp(step)]
}

/** The three paper swatches of the reader sheet and what they mean for the two stores: white = light, sepia = light + sepia, dark = dark. */
enum class ReaderSwatch {
    WHITE, SEPIA, DARK;

    /** The (dark, sepia) values this swatch writes; null leaves that store untouched (Dark keeps the sepia choice for the light reader). */
    fun stores(): Pair<Boolean, Boolean?> = when (this) {
        WHITE -> false to false
        SEPIA -> false to true
        DARK -> true to null
    }

    companion object {
        fun of(dark: Boolean, sepia: Boolean): ReaderSwatch = if (dark) DARK else if (sepia) SEPIA else WHITE
    }
}

class PrefsReaderThemeStore(private val store: DataStore<Preferences>) : ReaderThemeStore {
    override val dark: Flow<Boolean?> = store.data.map { it[KEY] }.distinctUntilChanged()
    override suspend fun setDark(dark: Boolean) { store.edit { it[KEY] = dark } }

    override val sepia: Flow<Boolean> = store.data.map { it[SEPIA_KEY] ?: false }.distinctUntilChanged()
    override suspend fun setSepia(on: Boolean) { store.edit { it[SEPIA_KEY] = on } }

    override val textStep: Flow<Int> = store.data.map { ReaderText.clamp(it[STEP_KEY] ?: ReaderText.DEFAULT_STEP) }.distinctUntilChanged()
    override suspend fun setTextStep(step: Int) { store.edit { it[STEP_KEY] = ReaderText.clamp(step) } }

    private companion object {
        val STEP_KEY = intPreferencesKey("reader_text_step")
        val KEY = booleanPreferencesKey("reader_dark")
        val SEPIA_KEY = booleanPreferencesKey("reader_sepia")
    }
}
