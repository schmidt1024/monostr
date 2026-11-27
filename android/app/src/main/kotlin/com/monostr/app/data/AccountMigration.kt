package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * From the single-account layout (all settings and secrets of the one session in the global file and under fixed secret
 * names) to one settings file and secret names per account. Every step can be repeated, so a crash anywhere leaves a state
 * the next [run] completes; [MIGRATED] is set last and makes later runs a no-op.
 */
class AccountMigration(
    private val accounts: PrefsAccountsStore,
    private val global: DataStore<Preferences>,
    private val prefs: AccountPrefs,
    private val secrets: SecretStore,
) {
    @Volatile private var done = false
    /** The app start and the first restore may both run it. */
    private val lock = Mutex()

    /**
     * Logged out at update time: nothing moves, the global settings wait for the first added account ([adoptGlobalInto]).
     * Throws when a secret cannot be read right now ([SecretUnavailableException]): nothing is marked done, the next start retries.
     */
    suspend fun run() {
        if (done) return
        lock.withLock { runLocked() }
    }

    /** App start: migrates, then activates the persisted account's settings, also when the migration failed (see NostrSession.restore). */
    suspend fun runAndActivate() {
        try {
            run()
        } finally {
            prefs.initialize(runCatching { accounts.session.first()?.pubkey }.getOrNull())
        }
    }

    private suspend fun runLocked() {
        if (done) return
        val g = global.data.first()
        if (g[MIGRATED] == true) { done = true; return }
        // The single account of the old layout owns the old secret names, whoever is active by now: after a failed run another
        // account may have been added. Recorded before the registry moves, so a rerun still knows it.
        val owner = g[LEGACY_OWNER] ?: accounts.accounts.first().singleOrNull()?.pubkey
        if (owner != null && g[LEGACY_OWNER] == null) global.edit { it[LEGACY_OWNER] = owner }
        accounts.migrateLegacy()
        val info = owner?.let { o -> accounts.accounts.first().firstOrNull { it.pubkey == o } }
        if (info != null) {
            // An unmigrated install has no account file yet, unless an earlier run crashed after the copy: adopting again
            // finishes the global cleanup and copies nothing that differs.
            adoptGlobalInto(info.pubkey)
            if (info.signerType == SignerType.LOCAL) move(KeystoreSecretStore.LEGACY_NSEC, SecretNames.nsec(info.pubkey))
            move(KeystoreSecretStore.LEGACY_VIEW_KEY, SecretNames.viewKey(info.pubkey))
        }
        // what is left under the old names has no account any more (owner removed, or none logged in): never handed to another
        secrets.remove(KeystoreSecretStore.LEGACY_NSEC)
        secrets.remove(KeystoreSecretStore.LEGACY_VIEW_KEY)
        global.edit { it[MIGRATED] = true; it.remove(LEGACY_OWNER) }
        done = true
    }

    /**
     * Copies every non-registry key of the global file into [pubkey]'s file (a key the account already has keeps its value),
     * then removes the copied keys from the global file, each only while it still holds the copied value: a setting written
     * to the global file meanwhile is not lost.
     */
    suspend fun adoptGlobalInto(pubkey: String) = dropFromGlobal(copyGlobalInto(pubkey))

    /**
     * The copy half of [adoptGlobalInto]: the global file stays as it is, so a login that fails afterwards loses nothing of it
     * (the caller deletes the account's file). Returns what was copied, for [dropFromGlobal] once the login succeeded.
     */
    suspend fun copyGlobalInto(pubkey: String): Map<Preferences.Key<*>, Any> {
        val moving = global.data.first().asMap().filterKeys { it.name !in REGISTRY }
        if (moving.isEmpty()) return moving
        prefs.storeFor(pubkey).edit { a ->
            moving.forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                if (k !in a) a[k as Preferences.Key<Any>] = v
            }
        }
        return moving
    }

    /** Removes the [copied] keys from the global file, each only while it still holds the copied value. */
    suspend fun dropFromGlobal(copied: Map<Preferences.Key<*>, Any>) {
        if (copied.isEmpty()) return
        global.edit { g -> copied.forEach { (k, v) -> if (g[k] == v) g.remove(k) } }
    }

    /** True when [pubkey]'s settings file holds nothing yet. */
    suspend fun isEmpty(pubkey: String): Boolean = prefs.storeFor(pubkey).data.first().asMap().isEmpty()

    /**
     * New name first, then the old one goes: a crash in between leaves both, and the next run finds the new one in place.
     * An existing new name is never overwritten: it belongs to its account (the old secret is then a stale leftover).
     */
    private fun move(from: String, to: String) {
        val value = secrets.getOrThrow(from) ?: return
        if (secrets.getOrThrow(to) == null) secrets.put(to, value)
        secrets.remove(from)
    }

    private companion object {
        val MIGRATED = booleanPreferencesKey("accounts.migrated")
        /** The single account at the first run, while the migration is unfinished; removed when it is done. */
        val LEGACY_OWNER = stringPreferencesKey("accounts.legacy_owner")
        /** Stay in the global file (the switch hint flag is a fact of the device); the `session.*` keys are the old registry, which [PrefsAccountsStore] drops. */
        val REGISTRY = setOf("accounts", "active_account", MIGRATED.name, LEGACY_OWNER.name, "accounts.switch_hint_seen", "session.pubkey", "session.signer", "session.amber_package")
    }
}
