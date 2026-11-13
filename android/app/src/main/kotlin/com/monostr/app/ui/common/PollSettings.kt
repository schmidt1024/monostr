package com.monostr.app.ui.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Polls spec 2026-11-12: whether the «Your vote is public» sheet was shown; once per install, before the very first vote. */
interface PollSettings {
    val firstVoteShown: Flow<Boolean>
    suspend fun setFirstVoteShown()
}

class PrefsPollSettings(private val store: DataStore<Preferences>) : PollSettings {
    override val firstVoteShown: Flow<Boolean> = store.data.map { it[FIRST_VOTE] ?: false }

    override suspend fun setFirstVoteShown() { store.edit { it[FIRST_VOTE] = true } }

    private companion object {
        val FIRST_VOTE = booleanPreferencesKey("polls.first_vote_shown")
    }
}
