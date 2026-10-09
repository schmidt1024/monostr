package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Spec 11.4 §2: the last searches typed on this device, newest first; texts and hashtags only, never links. */
interface RecentSearchesStore {
    val recent: Flow<List<String>>
    /** Trims [query]; blank is ignored; an entry equal ignoring case moves to the front with the new spelling; at most [MAX]. */
    suspend fun add(query: String)
    suspend fun remove(query: String)
    suspend fun clear()

    companion object {
        const val MAX = 10
        /** A pasted query longer than this is cut: the list lives in the shared settings file. */
        const val MAX_LENGTH = 200

        /** A private key typed into the search must never be kept (or shown) on the device. */
        fun isSecret(query: String): Boolean = query.trim().lowercase().let { it.startsWith("nsec1") || it.startsWith("ncryptsec1") }
    }
}

class PrefsRecentSearchesStore(private val store: DataStore<Preferences>) : RecentSearchesStore {
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    override val recent: Flow<List<String>> = data.map { decode(it[RECENT]) }

    override suspend fun add(query: String) {
        // the separator cannot be typed, but a pasted query might carry it
        val q = query.replace(SEP, ' ').trim().take(RecentSearchesStore.MAX_LENGTH)
        if (q.isEmpty() || RecentSearchesStore.isSecret(q)) return
        store.edit { p -> p[RECENT] = encode((listOf(q) + decode(p[RECENT]).filterNot { it.equals(q, ignoreCase = true) }).take(RecentSearchesStore.MAX)) }
    }

    override suspend fun remove(query: String) {
        store.edit { p -> p[RECENT] = encode(decode(p[RECENT]).filterNot { it.equals(query.trim(), ignoreCase = true) }) }
    }

    override suspend fun clear() {
        store.edit { it.remove(RECENT) }
    }

    // one string, entries separated by a character no search can contain
    private fun decode(raw: String?): List<String> = raw?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()
    private fun encode(list: List<String>): String = list.joinToString(SEP.toString())

    private companion object {
        val RECENT = stringPreferencesKey("search.recent")
        const val SEP = '\u001F'
    }
}
