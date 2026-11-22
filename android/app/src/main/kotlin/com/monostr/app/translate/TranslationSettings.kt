package com.monostr.app.translate

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.nostr.PerfTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

/** [DEVICE]: Offline Translator on this phone (backlog 89), nothing goes to a server. */
enum class TranslationMode { MONOSTR, CUSTOM, OFF, DEVICE }

/** A custom translate server as stored: `https://host[:port]`; plain http only for local test hosts. */
object TranslateUrl {
    private val LOCAL = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun normalize(input: String): String? {
        val v = input.trim().trimEnd('/')
        if (v.any { it.isWhitespace() }) return null
        val https = v.startsWith("https://", ignoreCase = true)
        val http = v.startsWith("http://", ignoreCase = true)
        if (!https && !http) return null
        val uri = runCatching { URI(v) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty() || !uri.path.isNullOrEmpty() || uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        if (http && host !in LOCAL) return null
        return (if (https) "https://" else "http://") + host + if (uri.port >= 0) ":${uri.port}" else ""
    }
}

/** Whether Offline Translator offers its service (a package query); the store asks it off the main thread. */
fun interface DeviceAppCheck {
    suspend fun installed(): Boolean
}

interface TranslationSettingsStore {
    /** The effective mode: the stored one, else DEVICE when Offline Translator is installed, else MONOSTR (owner variant B). */
    val mode: Flow<TranslationMode>
    val customUrl: Flow<String>
    val consentGiven: Flow<Boolean>
    suspend fun setMode(m: TranslationMode)
    /** Stores the normalized address; blank clears it. False (nothing stored) for an invalid address. */
    suspend fun setCustomUrl(u: String): Boolean
    suspend fun setConsentGiven()
    /** A fresh installed check (Settings › Translation resumed): the mode follows it while none is stored. */
    fun deviceAppChecked(installed: Boolean) {}
}

/**
 * [deviceApp] is asked once per process (a started check always finishes and counts), on [checkDispatcher], and only while no mode is stored: until it answers,
 * [mode] and [consentGiven] emit nothing (no guessed MONOSTR first), so no translation is sent before the effective
 * mode is known. [deviceAppChecked] replaces the answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsTranslationSettingsStore(
    private val store: DataStore<Preferences>,
    private val deviceApp: DeviceAppCheck = DeviceAppCheck { false },
    private val checkDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TranslationSettingsStore {
    /** Null until the check (or a Settings refresh) has answered. */
    private val installed = MutableStateFlow<Boolean?>(null)
    private val checkLock = Mutex()

    private suspend fun autoMode(): TranslationMode {
        if (installed.value == null) checkLock.withLock {
            if (installed.value == null) {
                // non-cancellable: a collector restarted mid-check (transformLatest) must not throw a finished answer away
                withContext(NonCancellable + checkDispatcher) {
                    val t0 = System.nanoTime()
                    val r = runCatching { deviceApp.installed() }.getOrDefault(false)
                    PerfTrace.mark("translate device check ${(System.nanoTime() - t0) / 1_000_000} installed=$r")
                    installed.compareAndSet(null, r) // a Settings refresh meanwhile is newer
                }
            }
        }
        return autoOf(installed.filterNotNull().first())
    }

    private fun autoOf(installed: Boolean) = if (installed) TranslationMode.DEVICE else TranslationMode.MONOSTR

    /** The preferences with their effective mode; the check runs only while no mode is stored. */
    private val resolved: Flow<Pair<Preferences, TranslationMode>> = store.data.transformLatest { p ->
        val stored = storedMode(p)
        if (stored != null) emit(p to stored) else emitAll(
            flow {
                autoMode()
                emitAll(installed.filterNotNull().map { p to autoOf(it) })
            },
        )
    }

    override val mode: Flow<TranslationMode> = resolved.map { it.second }.distinctUntilChanged()
    override val customUrl: Flow<String> = store.data.map { it[CUSTOM_URL] ?: "" }
    /**
     * The consent names the server it was given for: the sheet shows that host, so switching to another server asks
     * again before any note goes there. A consent stored before the host was kept counts for the Monostr server only.
     * On the device nothing leaves the phone: no consent needed, and a stored server consent stays as it was.
     */
    override val consentGiven: Flow<Boolean> = resolved.map { (p, mode) ->
        mode == TranslationMode.DEVICE ||
            ((p[CONSENT] ?: false) && (p[CONSENT_HOST] ?: TranslateServer.MONOSTR.host) == currentHost(p, mode))
    }.distinctUntilChanged()

    override fun deviceAppChecked(installed: Boolean) { this.installed.value = installed }

    private fun storedMode(p: Preferences): TranslationMode? =
        p[MODE]?.let { n -> TranslationMode.entries.firstOrNull { it.name == n } }

    private fun currentHost(p: Preferences, mode: TranslationMode): String {
        val url = p[CUSTOM_URL] ?: ""
        return if (mode == TranslationMode.CUSTOM && url.isNotBlank()) TranslateServer(url).host else TranslateServer.MONOSTR.host
    }

    override suspend fun setMode(m: TranslationMode) { store.edit { it[MODE] = m.name } }

    override suspend fun setCustomUrl(u: String): Boolean {
        if (u.isBlank()) { store.edit { it[CUSTOM_URL] = "" }; return true }
        val n = TranslateUrl.normalize(u) ?: return false
        store.edit { it[CUSTOM_URL] = n }
        return true
    }

    /** Nothing is stored while the effective mode is DEVICE (stored or auto): no server consent may be recorded behind the user's back. */
    override suspend fun setConsentGiven() {
        // the auto mode is resolved first: the check must not run while the store's write lock is held
        val auto = if (storedMode(store.data.first()) == null) autoMode() else null
        store.edit {
            val mode = storedMode(it) ?: auto ?: autoMode()
            if (mode != TranslationMode.DEVICE) { it[CONSENT] = true; it[CONSENT_HOST] = currentHost(it, mode) }
        }
    }

    companion object {
        private val MODE = stringPreferencesKey("translate.mode")
        private val CUSTOM_URL = stringPreferencesKey("translate.custom_url")
        private val CONSENT = booleanPreferencesKey("translate.consent")
        private val CONSENT_HOST = stringPreferencesKey("translate.consent_host")
    }
}
