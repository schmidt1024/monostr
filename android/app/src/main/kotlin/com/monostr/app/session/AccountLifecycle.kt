package com.monostr.app.session

import androidx.datastore.preferences.core.Preferences
import com.monostr.app.data.Accent
import com.monostr.app.data.AccountMigration
import com.monostr.app.data.AccountPrefs
import com.monostr.app.data.AccountsStore
import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.SecretNames
import com.monostr.app.data.SecretStore
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of [NostrSession.switchTo]. */
sealed interface SwitchResult {
    data object Done : SwitchResult
    /** A publish, upload, tip or DM send of the active account runs ([AccountBusy]); nothing changed. */
    data object Busy : SwitchResult
    /** The account could not start; the previous one runs again (or the login screen explains the failed restore). */
    data object Failed : SwitchResult
    /** A login with the key or Amber of the account that runs: nothing changed (only [AccountLifecycle.login]). */
    data object AlreadyActive : SwitchResult
}

/** A login of an account already on this device that could not switch to it. */
class SwitchRefusedException(val result: SwitchResult) : IllegalStateException("account switch: $result")

/**
 * Spec «Switch flow», «Adding an account», «Removing an account»: the account steps of [NostrSession], apart from Android and
 * the engine ([Host]). Only one account is online: every step closes the running session before the next account's settings
 * go live and its session starts. The caller holds the session's mutex for every call.
 */
internal class AccountLifecycle<R : Any>(
    private val accounts: AccountsStore,
    private val prefs: AccountPrefs,
    private val migration: AccountMigration,
    private val secrets: SecretStore,
    private val busy: AccountBusy,
    private val host: Host<R>,
    private val accentAfter: (String?) -> String = Accent::nextPreset,
) {
    /** What the steps need of the running session. */
    interface Host<R : Any> {
        /** The running session, if any. */
        val running: R?
        fun pubkeyOf(session: R): String
        /** Cancels a pending Amber request and a background check run in flight. */
        suspend fun stopPendingWork()
        /** Closes the running session, its scopes included, so nothing of it writes any more; a no-op without one. */
        suspend fun close()
        /**
         * Drops the shared HTTP client's connections: no request of the next account rides one opened for the last. Returns once
         * they are closed; called on the caller's thread, the main one from the screens, so the closing (network I/O) runs off it.
         */
        suspend fun evictConnections()
        /**
         * The account changes to [next] (null: to the login screen or a successor not yet known). Called before the running session
         * closes and before [evictConnections], so no request of the running account pools a connection the next one could take
         * before its fresh Tor route (spec «Tor isolation per account»).
         */
        fun accountChanging(next: String?)
        /** Builds [info]'s session from its stored key; null when it has none usable for good. Throws when the start fails, leaving nothing open. */
        suspend fun start(info: SessionInfo): R?
        /** Closes a started session that is not shown (its login failed afterwards). */
        suspend fun discard(session: R)
        /** Shows the session and schedules its background check. */
        suspend fun activate(session: R)
        /** Starts the persisted active account again; the login screen explains a failure. */
        suspend fun restore()
        /** Schedules the background check again ([stopPendingWork] cancelled it), after a failed step brought the previous account back. */
        fun scheduleBackgroundCheck()
        /** The active account's session could not start: the login screen explains it, the next restore tries again. */
        suspend fun failed()
        /** Removal steps that need the account's own running session (its drafts' pictures on the server). */
        suspend fun beforeRemoveRunning(session: R)
        /** Deletes the account's data on disk (DM database, engine and private folders); best effort. */
        suspend fun wipe(pubkey: String)
        /** The visible notifications of [pubkey] only go (active or not), and a pending tap on one of them. */
        fun dropNotifications(pubkey: String)
        /** No account is left: the shared caches go, the login screen shows. */
        suspend fun loggedOut()
    }

    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The new active pubkey after a switch away from a running account (switch, add, removal of the active one). */
    val switched: SharedFlow<String> = _switched.asSharedFlow()

    suspend fun switchTo(pubkey: String): SwitchResult {
        if (!migrated()) return SwitchResult.Failed
        val info = accounts.accounts.first().firstOrNull { it.pubkey == pubkey } ?: return SwitchResult.Failed
        return switchWith(info)
    }

    /** The switch to [info]'s account, started with [info]'s signer (for a re-login not yet the stored one). */
    private suspend fun switchWith(info: SessionInfo): SwitchResult {
        val pubkey = info.pubkey
        val running = host.running
        if (running != null && host.pubkeyOf(running) == pubkey) return SwitchResult.Done
        if (running != null && busy.busy.value) return SwitchResult.Busy
        val previous = accounts.session.first()?.pubkey
        host.accountChanging(pubkey)
        closeRunning()
        accounts.setActive(pubkey)
        prefs.activate(pubkey)
        host.evictConnections()
        var keyUnusable = false
        val session = try {
            host.start(info).also { if (it == null) keyUnusable = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (session == null) {
            withContext(NonCancellable) {
                // the account stays with all its data; a login with its key or Amber brings it back
                if (keyUnusable) dropUnusableKey(info)
                bestEffort { accounts.setActive(previous) }
                prefs.activate(previous)
                if (running != null) restorePrevious()
            }
            return SwitchResult.Failed
        }
        host.activate(session)
        if (running != null) _switched.tryEmit(pubkey)
        return SwitchResult.Done
    }

    /**
     * Restore found no usable key for the active account (nsec missing or unreadable for good, Amber package missing): only that
     * secret goes. The account keeps its registry entry and all its data until the user removes it; a login with its key or
     * Amber brings it back ([login] of an existing account). Nothing starts and no other account takes over.
     */
    suspend fun keyUnusable(info: SessionInfo) {
        dropUnusableKey(info)
        host.failed()
    }

    /**
     * A login with a key or Amber, also for a further account. An account already on this device takes the new key or signer
     * (its data stays) and is switched to. A new one starts with the logged-out (global) settings when it is the first, else
     * with [NewAccountSettings] of the active one. The running session closes before [start] builds the new one; [persist]
     * stores its key and records, then the registry lists it as active. When anything fails, nothing of the new account stays
     * (registry, key, settings file, data) and the previous account comes back; [onFailure] runs first, with the new account's
     * settings still active. Failed while the migration of older versions is unfinished.
     */
    suspend fun login(info: SessionInfo, start: suspend () -> R, persist: suspend () -> Unit, onFailure: suspend () -> Unit = {}): SwitchResult {
        if (!migrated()) return SwitchResult.Failed
        val pubkey = info.pubkey
        val list = accounts.accounts.first()
        val running = host.running
        if (list.any { it.pubkey == pubkey }) {
            if (running != null && host.pubkeyOf(running) == pubkey) return SwitchResult.AlreadyActive
            if (running != null && busy.busy.value) return SwitchResult.Busy
            return reLogin(info, persist)
        }
        if (running != null && busy.busy.value) return SwitchResult.Busy
        val previous = accounts.session.first()?.pubkey
        try {
            var adopted: Map<Preferences.Key<*>, Any> = emptyMap()
            if (migration.isEmpty(pubkey)) {
                val source = previous ?: list.firstOrNull()?.pubkey
                // the global copies leave only once the login succeeded: a failed one loses nothing of them
                if (source == null) adopted = migration.copyGlobalInto(pubkey)
                else NewAccountSettings.copy(prefs.storeFor(source), prefs.storeFor(pubkey), accentAfter)
            }
            host.accountChanging(pubkey)
            closeRunning()
            prefs.activate(pubkey)
            host.evictConnections()
            val session = start()
            try {
                persist()
                accounts.add(info)
                accounts.setActive(pubkey)
            } catch (e: Throwable) {
                withContext(NonCancellable) { host.discard(session) }
                throw e
            }
            bestEffort { migration.dropFromGlobal(adopted) }
            host.activate(session)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                bestEffort { onFailure() }
                prefs.activate(previous)
                bestEffort { accounts.remove(pubkey) }
                bestEffort { accounts.setActive(previous) }
                bestEffort { secrets.remove(SecretNames.nsec(pubkey)) }
                bestEffort { prefs.delete(pubkey) }
                host.wipe(pubkey)
                if (running != null) restorePrevious()
            }
            throw e
        }
        if (running != null) _switched.tryEmit(pubkey)
        return SwitchResult.Done
    }

    /**
     * A login of an account already on this device (its key became unusable, or it moves to Amber or back to a key): it starts
     * with the new signer, and only once that session runs does the registry take the new signer (the shown name stays) and an
     * nsec left behind by a move to Amber go. A failed start changes nothing: the stored signer and key stay, and a key written
     * by [persist] that was not stored before goes again.
     */
    private suspend fun reLogin(info: SessionInfo, persist: suspend () -> Unit): SwitchResult {
        val nsec = SecretNames.nsec(info.pubkey)
        // a read that fails now counts as present: the key a key login writes is the one this pubkey has anyway
        val hadKey = try { secrets.getOrThrow(nsec) != null } catch (e: CancellationException) { throw e } catch (e: Exception) { true }
        val result = try {
            persist()
            switchWith(info)
        } catch (e: Throwable) {
            withContext(NonCancellable) { if (!hadKey) bestEffort { secrets.remove(nsec) } }
            throw e
        }
        if (result != SwitchResult.Done) {
            if (!hadKey) bestEffort { secrets.remove(nsec) }
            return result
        }
        accounts.add(info)
        if (info.signerType == SignerType.AMBER) secrets.remove(nsec)
        return result
    }

    /**
     * Spec «Removing an account»: nothing of [pubkey] stays on the device. The active account is first switched away from (to the
     * next usable account in the list, or to the login screen when none is left); its files go once the next one runs. The
     * registry entry goes last, so an interrupted removal can be repeated. Refused ([SwitchRefusedException]) while the
     * migration of older versions is unfinished.
     */
    suspend fun remove(pubkey: String) {
        if (!migrated()) throw SwitchRefusedException(SwitchResult.Failed)
        val list = accounts.accounts.first()
        if (list.none { it.pubkey == pubkey }) return
        val running = host.running
        val active = accounts.session.first()?.pubkey
        val runsIt = running != null && host.pubkeyOf(running) == pubkey
        val rest = list.filter { it.pubkey != pubkey }
        if (!runsIt && active != pubkey) {
            host.dropNotifications(pubkey)
            erase(pubkey, last = rest.isEmpty())
            return
        }
        // spec 2026-10-16 §8: the drafts' pictures leave the server while signer and engine still work
        if (runsIt) bestEffort { host.beforeRemoveRunning(running!!) }
        host.accountChanging(null)
        closeRunning()
        host.dropNotifications(pubkey)
        if (rest.isEmpty()) {
            bestEffort { accounts.setActive(null) }
            prefs.activate(null)
            host.evictConnections()
            try {
                erase(pubkey, last = true)
            } finally {
                host.loggedOut()
            }
            return
        }
        host.evictConnections()
        val next = startFirstUsable(rest)
        // the next account is shown at once (its state is Active already); the erase of a large engine database takes a while
        if (next != null) _switched.tryEmit(next)
        erase(pubkey, last = false)
    }

    /**
     * The first of [candidates] whose session starts becomes active and runs; one without a usable key is skipped and kept (only
     * its key goes). A start that fails, or no usable account, ends on the login screen with the account then active kept.
     */
    private suspend fun startFirstUsable(candidates: List<SessionInfo>): String? {
        for (candidate in candidates) {
            bestEffort { accounts.setActive(candidate.pubkey) }
            prefs.activate(candidate.pubkey)
            val session = try {
                host.start(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.failed()
                return null
            }
            if (session != null) {
                host.activate(session)
                return candidate.pubkey
            }
            dropUnusableKey(candidate)
        }
        // none usable: the first stays active (its look on the login screen), signing in again brings it back
        bestEffort { accounts.setActive(candidates.first().pubkey) }
        prefs.activate(candidates.first().pubkey)
        host.failed()
        return null
    }

    /** An nsec that gave no signer is missing or unreadable for good; an Amber account has no secret to drop. */
    private suspend fun dropUnusableKey(info: SessionInfo) {
        if (info.signerType == SignerType.LOCAL) bestEffort { secrets.remove(SecretNames.nsec(info.pubkey)) }
    }

    /** Account steps wait for the migration of older versions: before it, the old secret names may still belong to an account. */
    private suspend fun migrated(): Boolean = try {
        migration.run()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** After a failed switch or add: the previous account runs again, with the background check [closeRunning] cancelled. */
    private suspend fun restorePrevious() {
        host.restore()
        if (host.running != null) host.scheduleBackgroundCheck()
    }

    private suspend fun closeRunning() {
        host.stopPendingWork()
        host.close()
    }

    /** [last]: no account remains, so the key names of an unfinished migration can only be this account's. */
    private suspend fun erase(pubkey: String, last: Boolean) {
        host.wipe(pubkey)
        prefs.delete(pubkey)
        secrets.remove(SecretNames.nsec(pubkey))
        secrets.remove(SecretNames.viewKey(pubkey))
        if (last) {
            bestEffort { secrets.remove(KeystoreSecretStore.LEGACY_NSEC) }
            bestEffort { secrets.remove(KeystoreSecretStore.LEGACY_VIEW_KEY) }
        }
        accounts.remove(pubkey)
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the remaining steps must still run
        }
    }
}
