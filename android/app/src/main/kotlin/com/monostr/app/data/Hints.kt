package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * One-time hints per account (spec 4, 6, 11): first login on this device, the support hint, and the
 * inbox cards the user swiped away. Kept across logout, so a returning account is not asked again.
 */
interface HintStore {
    /** Epoch seconds of this account's first login on this device; null before [markFirstLogin]. */
    suspend fun firstLoginAt(pubkey: String): Long?
    /** Sets [firstLoginAt] unless it is already set. */
    suspend fun markFirstLogin(pubkey: String, at: Long)
    fun supportHintDone(pubkey: String): Flow<Boolean>
    suspend fun setSupportHintDone(pubkey: String)
    fun dismissed(pubkey: String, hint: String): Flow<Boolean>
    suspend fun dismiss(pubkey: String, hint: String)
}

class PrefsHintStore(private val store: DataStore<Preferences>) : HintStore {
    private fun firstLogin(pk: String) = longPreferencesKey("hint.first_login.$pk")
    private fun supportDone(pk: String) = booleanPreferencesKey("hint.support_done.$pk")
    private fun dismissedKey(pk: String, hint: String) = booleanPreferencesKey("hint.dismissed.$hint.$pk")

    override suspend fun firstLoginAt(pubkey: String): Long? = store.data.first()[firstLogin(pubkey)]

    override suspend fun markFirstLogin(pubkey: String, at: Long) {
        store.edit { p -> if (p[firstLogin(pubkey)] == null) p[firstLogin(pubkey)] = at }
    }

    override fun supportHintDone(pubkey: String): Flow<Boolean> = store.data.map { it[supportDone(pubkey)] ?: false }

    override suspend fun setSupportHintDone(pubkey: String) {
        store.edit { it[supportDone(pubkey)] = true }
    }

    override fun dismissed(pubkey: String, hint: String): Flow<Boolean> = store.data.map { it[dismissedKey(pubkey, hint)] ?: false }

    override suspend fun dismiss(pubkey: String, hint: String) {
        store.edit { it[dismissedKey(pubkey, hint)] = true }
    }
}

/** Spec 4: the "Enjoying Monostr?" card, once, at the earliest 14 days after the first login; no reminder, no counter. */
object SupportHint {
    const val DELAY_SECONDS = 14 * 86_400L

    fun visible(firstLoginAt: Long?, done: Boolean, now: Long): Boolean =
        !done && firstLoginAt != null && now - firstLoginAt >= DELAY_SECONDS

    /** Live visibility for [pubkey]; an unreadable store (corrupt file) counts as "hidden", never as a crash. */
    fun observe(hints: HintStore, pubkey: String, now: () -> Long): Flow<Boolean> =
        combine(flow { emit(hints.firstLoginAt(pubkey)) }, hints.supportHintDone(pubkey)) { first, done ->
            visible(first, done, now())
        }.catch { emit(false) }
}
