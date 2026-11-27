package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder

/** The first 16 hex chars of a pubkey: the per-account directory and database name. */
fun pk16(pubkey: String): String = pubkey.take(16).also {
    // it names files and directories: never anything but hex, whatever handed the pubkey in (bug bounty 2026-11-26)
    require(it.length == 16 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) { "not a pubkey" }
}

/** All accounts of this install in the order added; [session] is the active one. */
interface AccountsStore : SessionStore {
    val accounts: Flow<List<SessionInfo>>

    /** Appends, or replaces the account with the same pubkey in place (a null name or picture keeps the shown one); does not activate. */
    suspend fun add(info: SessionInfo)

    /** null = none active. */
    suspend fun setActive(pubkey: String?)

    /** Also clears the active account if it was the removed one. */
    suspend fun remove(pubkey: String)

    suspend fun updateDisplay(pubkey: String, name: String?, picture: String?)

    /** Whether the one-time «long-press to switch» hint was shown; a fact of the device, not of an account. */
    suspend fun switchHintSeen(): Boolean

    suspend fun markSwitchHintSeen()
}

/**
 * Accounts in the global store: `accounts` holds one line per account `pubkey|TYPE|package|name|picture`, every field
 * URL-encoded and empty for null; `active_account` the active pubkey. The single-session keys of older installs are
 * read as one active account until [migrateLegacy] (also run inside every write) moves them over.
 */
class PrefsAccountsStore(private val store: DataStore<Preferences>) : AccountsStore {
    private data class State(val accounts: List<SessionInfo>, val active: String?)

    private fun read(p: Preferences): State {
        val raw = p[ACCOUNTS]
        if (raw == null) legacy(p)?.let { return State(listOf(it), it.pubkey) }
        return State(raw.orEmpty().lines().mapNotNull(::decode), p[ACTIVE])
    }

    override val accounts: Flow<List<SessionInfo>> = store.data.map { read(it).accounts }

    override val session: Flow<SessionInfo?> = store.data.map { p ->
        val s = read(p)
        s.accounts.firstOrNull { it.pubkey == s.active }
    }

    override suspend fun add(info: SessionInfo) = edit { s -> s.copy(accounts = upsert(s.accounts, info)) }

    override suspend fun setActive(pubkey: String?) = edit { s -> s.copy(active = pubkey) }

    override suspend fun save(info: SessionInfo) = edit { s -> State(upsert(s.accounts, info), info.pubkey) }

    override suspend fun remove(pubkey: String) = edit { s ->
        State(s.accounts.filter { it.pubkey != pubkey }, s.active.takeIf { it != pubkey })
    }

    override suspend fun clear() = edit { s -> State(s.accounts.filter { it.pubkey != s.active }, null) }

    override suspend fun updateDisplay(pubkey: String, name: String?, picture: String?) = edit { s ->
        s.copy(accounts = s.accounts.map { if (it.pubkey == pubkey) it.copy(name = name, picture = picture) else it })
    }

    override suspend fun switchHintSeen(): Boolean = store.data.first()[HINT_SEEN] == true

    override suspend fun markSwitchHintSeen() {
        store.edit { it[HINT_SEEN] = true }
    }

    /** Moves the single session of older installs into the list; safe to run again. */
    suspend fun migrateLegacy() = edit { it }

    private suspend fun edit(change: (State) -> State) {
        store.edit { p ->
            val next = change(read(p))
            p[ACCOUNTS] = next.accounts.joinToString("\n", transform = ::encode)
            if (next.active != null) p[ACTIVE] = next.active else p.remove(ACTIVE)
            dropLegacy(p)
        }
    }

    /** A login again brings no name or picture: the ones shown in the switcher stay ([updateDisplay] changes them). */
    private fun upsert(list: List<SessionInfo>, info: SessionInfo): List<SessionInfo> =
        if (list.any { it.pubkey == info.pubkey }) {
            list.map { if (it.pubkey == info.pubkey) info.copy(name = info.name ?: it.name, picture = info.picture ?: it.picture) else it }
        } else {
            list + info
        }

    private fun legacy(p: Preferences): SessionInfo? {
        val pubkey = p[L_PUBKEY] ?: return null
        val type = p[L_SIGNER]?.let { runCatching { SignerType.valueOf(it) }.getOrNull() } ?: return null
        return SessionInfo(pubkey, type, p[L_PACKAGE])
    }

    private fun dropLegacy(p: MutablePreferences) {
        p.remove(L_PUBKEY); p.remove(L_SIGNER); p.remove(L_PACKAGE)
    }

    private fun enc(s: String?): String = if (s == null) "" else URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String? = if (s.isEmpty()) null else URLDecoder.decode(s, "UTF-8")

    private fun encode(i: SessionInfo) =
        listOf(i.pubkey, i.signerType.name, i.amberPackage, i.name, i.picture).joinToString("|", transform = ::enc)

    private fun decode(line: String): SessionInfo? {
        val f = line.split('|')
        if (f.size < 2) return null
        val pubkey = dec(f[0]) ?: return null
        val type = dec(f[1])?.let { runCatching { SignerType.valueOf(it) }.getOrNull() } ?: return null
        return SessionInfo(pubkey, type, f.getOrNull(2)?.let(::dec), f.getOrNull(3)?.let(::dec), f.getOrNull(4)?.let(::dec))
    }

    private companion object {
        val ACCOUNTS = stringPreferencesKey("accounts")
        val ACTIVE = stringPreferencesKey("active_account")
        val HINT_SEEN = booleanPreferencesKey("accounts.switch_hint_seen")
        val L_PUBKEY = stringPreferencesKey("session.pubkey")
        val L_SIGNER = stringPreferencesKey("session.signer")
        val L_PACKAGE = stringPreferencesKey("session.amber_package")
    }
}
