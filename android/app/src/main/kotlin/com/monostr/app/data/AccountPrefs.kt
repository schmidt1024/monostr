package com.monostr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.coroutines.CoroutineContext

/**
 * One [DataStore] that delegates to the settings file of the active account (`monostr-<pk16>`).
 * With no active account it delegates to [global] (logged-out look, and the settings a first
 * added account adopts). DataStore forbids two instances per file, so stores are cached per pk16.
 *
 * [open] receives a scope owned by this instance so [delete] can cancel it before removing the file.
 *
 * Nothing is read or written before the first [activate] or [initialize]: until the app start knows the active account,
 * a reader would see the (migrated, empty) global file and a write would land there for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountPrefs(
    private val global: DataStore<Preferences>,
    private val open: (pk16: String, scope: CoroutineScope) -> DataStore<Preferences>,
    private val deleteFile: (pk16: String) -> Unit,
    private val scopeContext: CoroutineContext = Dispatchers.IO,
) : DataStore<Preferences> {
    private class Entry(val store: DataStore<Preferences>, val scope: CoroutineScope)

    private val entries = HashMap<String, Entry>()
    /** Accounts whose file is being deleted: nothing may open a second instance on it meanwhile. Guarded by [entries]. */
    private val deleting = HashSet<String>()
    private val _activePk16 = MutableStateFlow<String?>(null)
    val activePk16: StateFlow<String?> = _activePk16.asStateFlow()
    private val ready = MutableStateFlow(false)

    fun activate(pubkey: String?) = synchronized(ready) {
        _activePk16.value = pubkey?.let(::pk16)
        ready.value = true
    }

    /** Like [activate], but only while nothing was activated yet: the app start never overrides a session's choice. */
    fun initialize(pubkey: String?) = synchronized(ready) {
        if (!ready.value) activate(pubkey)
    }

    fun storeFor(pubkey: String): DataStore<Preferences> = entryFor(pk16(pubkey)).store

    /**
     * [pubkey]'s own file for a write bound to that account (final review M2): open while the account is active, or still open since
     * it was this process; null once it was removed (or never ran here). So a late write of an account that was switched away from
     * never lands in the next account's file, and one of a removed account never brings its file back.
     */
    fun liveStoreFor(pubkey: String): DataStore<Preferences>? {
        val pk = pk16(pubkey)
        return synchronized(entries) {
            entries[pk]?.store ?: if (pk == _activePk16.value && pk !in deleting) entryFor(pk).store else null
        }
    }

    private fun entryFor(pk: String): Entry = synchronized(entries) {
        check(pk !in deleting) { "settings of a removed account" }
        entries.getOrPut(pk) {
            val scope = CoroutineScope(SupervisorJob() + scopeContext)
            Entry(open(pk, scope), scope)
        }
    }

    private fun delegate(pk: String?): DataStore<Preferences> = if (pk == null) global else entryFor(pk).store

    /**
     * Removes the account's settings file, also when no instance was open on it. From the start of the call until the file is gone
     * no instance can open on it ([storeFor] throws), and the instance's scope has finished, so no write in flight brings it back.
     */
    suspend fun delete(pubkey: String) {
        val pk = pk16(pubkey)
        val entry = synchronized(entries) {
            check(_activePk16.value != pk) { "cannot delete the active account's settings" }
            deleting += pk
            entries.remove(pk)
        }
        try {
            if (entry != null) {
                // emptied first: if the file cannot be deleted, nothing of the account is left in it
                runCatching { entry.store.updateData { emptyPreferences() } }
                entry.scope.coroutineContext[Job]?.cancelAndJoin()
            }
            deleteFile(pk)
        } finally {
            synchronized(entries) { deleting -= pk }
        }
    }

    override val data: Flow<Preferences> = ready.flatMapLatest { r ->
        if (r) _activePk16.flatMapLatest { delegate(it).data } else kotlinx.coroutines.flow.emptyFlow()
    }

    /** Target is the store active at call time (after the first activation). */
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        ready.first { it }
        return delegate(_activePk16.value).updateData(transform)
    }

    companion object {
        fun create(context: Context, global: DataStore<Preferences>): AccountPrefs = AccountPrefs(
            global = global,
            open = { pk, scope ->
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                ) { context.preferencesDataStoreFile("monostr-$pk") }
            },
            deleteFile = { pk -> deleteFiles(context.preferencesDataStoreFile("monostr-$pk")) },
        )

        /** A settings file and the scratch file DataStore writes next to it (`<file>.tmp`), left by a write cut off by a crash. */
        fun deleteFiles(file: java.io.File) {
            java.io.File(file.path + ".tmp").delete()
            file.delete()
        }
    }
}

/**
 * The file a store keyed by [pubkey] reads and writes: with [AccountPrefs] that account's own one ([AccountPrefs.liveStoreFor]; null
 * when the account is gone), never the one active at call time; any other store (tests) is used as it is.
 */
internal fun DataStore<Preferences>.ownedBy(pubkey: String): DataStore<Preferences>? =
    if (this is AccountPrefs) liveStoreFor(pubkey) else this
