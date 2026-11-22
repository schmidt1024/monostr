package com.monostr.app.ui.article

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Backlog 51: the reader's own dark/light choice, one global setting. Null until the first tap: the reader follows the app theme. */
interface ReaderThemeStore {
    val dark: Flow<Boolean?>
    suspend fun setDark(dark: Boolean)
}

class PrefsReaderThemeStore(private val store: DataStore<Preferences>) : ReaderThemeStore {
    override val dark: Flow<Boolean?> = store.data.map { it[KEY] }.distinctUntilChanged()
    override suspend fun setDark(dark: Boolean) { store.edit { it[KEY] = dark } }

    private companion object {
        val KEY = booleanPreferencesKey("reader_dark")
    }
}
