package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A map; [failOn] makes reading that name fail as a passing Keystore error does. */
private class FlakySecrets : SecretStore {
    val values = HashMap<String, String>()
    var failOn: String? = null
    override fun put(name: String, value: String) { values[name] = value }
    override fun get(name: String) = values[name]
    override fun getOrThrow(name: String): String? {
        if (name == failOn) throw SecretUnavailableException(java.security.ProviderException("busy"))
        return values[name]
    }
    override fun remove(name: String) { values.remove(name) }
}

class AccountMigrationTest {
    @TempDir lateinit var dir: Path

    private val p = "a1".repeat(32)
    private val nsec = "11".repeat(32)
    private val viewKey = "ab".repeat(32)

    private val registry = setOf("accounts", "active_account", "accounts.migrated")

    /** One install: a global file, the per-account files, the secrets. [name] keeps two installs of one test apart. */
    private inner class Install(name: String = "x") {
        private val root = dir.resolve(name).toFile().apply { mkdirs() }
        private fun file(n: String, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) { root.resolve("$n.preferences_pb") }

        val global = file("monostr", CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val prefs = AccountPrefs(
            global = global,
            open = { pk, scope -> file("monostr-$pk", scope) },
            deleteFile = { pk -> root.resolve("monostr-$pk.preferences_pb").delete() },
        )
        val accounts = PrefsAccountsStore(global)
        val secrets = FlakySecrets()
        val migration = AccountMigration(accounts, global, prefs, secrets)

        /** The single-account layout of older versions. */
        suspend fun legacySession(type: SignerType, pkg: String? = null) = global.edit {
            it[stringPreferencesKey("session.pubkey")] = p
            it[stringPreferencesKey("session.signer")] = type.name
            if (pkg != null) it[stringPreferencesKey("session.amber_package")] = pkg
        }

        /** Settings written the old way: straight into the global file. */
        suspend fun oldSettings() {
            PrefsUiSettingsStore(global).setAccent(Accent.CHAMPAGNE)
            PrefsTipSettingsStore(global).setSetup(MoneroSetup("4addr", "https://w.example", "c".repeat(64), "mainnet", 1))
            PrefsDraftStore(global).save(p, "note", Draft("hello", updatedAt = 100))
        }

        suspend fun globalKeys() = global.data.first().asMap().keys.map { it.name }.toSet()
        suspend fun accountMap() = prefs.storeFor(p).data.first().asMap().mapKeys { it.key.name }
        suspend fun globalMap() = global.data.first().asMap().mapKeys { it.key.name }
    }

    @Test
    fun `the switch hint flag stays global when the first account logs in after all were removed`() = runTest {
        val i = Install()
        i.oldSettings()
        i.accounts.markSwitchHintSeen()

        i.migration.adoptGlobalInto(p)

        assertTrue(i.accounts.switchHintSeen())
        assertFalse(i.accountMap().containsKey("accounts.switch_hint_seen"))
        assertTrue(i.globalKeys().contains("accounts.switch_hint_seen"))
    }

    @Test
    fun `migrates local session`() = runTest {
        val i = Install()
        i.legacySession(SignerType.LOCAL)
        PrefsUiSettingsStore(i.global).setAccent(Accent.CHAMPAGNE)
        i.secrets.put(KeystoreSecretStore.LEGACY_NSEC, nsec)

        i.migration.run()

        assertEquals(listOf(SessionInfo(p, SignerType.LOCAL, null)), i.accounts.accounts.first())
        assertEquals(p, i.accounts.session.first()?.pubkey)
        assertEquals(nsec, i.secrets.get(SecretNames.nsec(p)))
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_NSEC))
        i.prefs.activate(p)
        assertEquals(Accent.CHAMPAGNE, PrefsUiSettingsStore(i.prefs).accent.first())
        assertEquals(registry, i.globalKeys())
    }

    @Test
    fun `app start activates the migrated account`() = runTest {
        val i = Install()
        i.legacySession(SignerType.LOCAL)
        PrefsUiSettingsStore(i.global).setAccent(Accent.CHAMPAGNE)

        i.migration.runAndActivate()

        assertEquals(pk16(p), i.prefs.activePk16.value)
        assertEquals(Accent.CHAMPAGNE, PrefsUiSettingsStore(i.prefs).accent.first())
    }

    @Test
    fun `a secret step failure then login as another account never hands it the old secrets`() = runTest {
        val i = Install()
        val b = "b2".repeat(32)
        val bNsec = "33".repeat(32)
        i.legacySession(SignerType.LOCAL)
        i.oldSettings()
        i.secrets.put(KeystoreSecretStore.LEGACY_NSEC, nsec)
        i.secrets.put(KeystoreSecretStore.LEGACY_VIEW_KEY, viewKey)
        i.secrets.failOn = KeystoreSecretStore.LEGACY_VIEW_KEY

        // first start: the view key read hits a passing Keystore error, the app still starts with A's settings
        assertTrue(runCatching { i.migration.runAndActivate() }.exceptionOrNull() is SecretUnavailableException)
        assertEquals(pk16(p), i.prefs.activePk16.value)
        i.secrets.failOn = null

        // the user logs in as B instead, without the old names cleared (worst case; the login path also clears them)
        i.accounts.save(SessionInfo(b, SignerType.LOCAL, null))
        i.secrets.put(SecretNames.nsec(b), bNsec)

        i.migration.run()

        assertEquals(bNsec, i.secrets.get(SecretNames.nsec(b)), "B keeps its own key")
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_NSEC))
        // the old secrets reach the account they belong to, never the active one
        assertEquals(nsec, i.secrets.get(SecretNames.nsec(p)))
        assertEquals(viewKey, i.secrets.get(SecretNames.viewKey(p)))
        assertNull(i.secrets.get(SecretNames.viewKey(b)))
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_VIEW_KEY))
        assertEquals(true, i.global.data.first()[booleanPreferencesKey("accounts.migrated")])
    }

    @Test
    fun `migrates amber session with monero and drafts`() = runTest {
        val i = Install()
        i.legacySession(SignerType.AMBER, "com.greenart7c3.nostrsigner")
        i.oldSettings()
        i.secrets.put(KeystoreSecretStore.LEGACY_VIEW_KEY, viewKey)

        i.migration.run()

        i.prefs.activate(p)
        assertEquals("4addr", PrefsTipSettingsStore(i.prefs).setup.first()?.address)
        assertEquals(Accent.CHAMPAGNE, PrefsUiSettingsStore(i.prefs).accent.first())
        assertEquals("hello", PrefsDraftStore(i.prefs).load(p, "note")?.text)
        assertEquals(registry, i.globalKeys())
        assertEquals(viewKey, i.secrets.get(SecretNames.viewKey(p)))
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_VIEW_KEY))
        assertNull(i.secrets.get(SecretNames.nsec(p)), "an Amber account has no nsec")
        assertEquals(SessionInfo(p, SignerType.AMBER, "com.greenart7c3.nostrsigner"), i.accounts.session.first())
    }

    @Test
    fun `interrupted after copy completes on rerun`() = runTest {
        val clean = Install("clean")
        clean.legacySession(SignerType.LOCAL)
        clean.oldSettings()
        clean.secrets.put(KeystoreSecretStore.LEGACY_NSEC, nsec)
        clean.secrets.put(KeystoreSecretStore.LEGACY_VIEW_KEY, viewKey)
        clean.migration.run()

        val crashed = Install("crashed")
        crashed.legacySession(SignerType.LOCAL)
        crashed.oldSettings()
        crashed.secrets.put(KeystoreSecretStore.LEGACY_NSEC, nsec)
        crashed.secrets.put(KeystoreSecretStore.LEGACY_VIEW_KEY, viewKey)
        // what a crash leaves: registry moved, the account file written, the global copies and the old secrets still there,
        // the new nsec already put
        crashed.accounts.migrateLegacy()
        val copied = crashed.global.data.first()
        crashed.prefs.storeFor(p).edit { a ->
            copied.asMap().forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                if (k.name !in registry) a[k as Preferences.Key<Any>] = v
            }
        }
        crashed.secrets.put(SecretNames.nsec(p), nsec)
        assertFalse(crashed.globalMap().containsKey("accounts.migrated"))

        crashed.migration.run()

        assertEquals(clean.globalMap(), crashed.globalMap())
        assertEquals(clean.accountMap(), crashed.accountMap())
        assertEquals(clean.secrets.values, crashed.secrets.values)
        assertEquals(registry, crashed.globalKeys())
    }

    @Test
    fun `logged out moves nothing`() = runTest {
        val i = Install()
        PrefsUiSettingsStore(i.global).setAccent(Accent.CHAMPAGNE)

        i.migration.runAndActivate()

        assertEquals(Accent.CHAMPAGNE, PrefsUiSettingsStore(i.prefs).accent.first(), "inactive prefs read the global file")
        assertEquals(true, i.global.data.first()[booleanPreferencesKey("accounts.migrated")])
        assertTrue("ui.accent" in i.globalKeys())
        assertEquals(emptyList<SessionInfo>(), i.accounts.accounts.first())
        assertTrue(i.accountMap().isEmpty())
    }

    @Test
    fun `second run is no-op`() = runTest {
        val i = Install()
        i.legacySession(SignerType.LOCAL)
        i.oldSettings()
        i.secrets.put(KeystoreSecretStore.LEGACY_NSEC, nsec)
        i.migration.run()
        val global = i.globalMap()
        val account = i.accountMap()
        // anything that shows up later in the old places is not picked up again
        i.secrets.put(KeystoreSecretStore.LEGACY_NSEC, "22".repeat(32))
        PrefsUiSettingsStore(i.global).setAccent(Accent.MONO)

        i.migration.run()

        assertEquals(account, i.accountMap())
        assertEquals(global + ("ui.accent" to Accent.MONO.name), i.globalMap())
        assertEquals(nsec, i.secrets.get(SecretNames.nsec(p)))
        assertEquals("22".repeat(32), i.secrets.get(KeystoreSecretStore.LEGACY_NSEC))
    }

    @Test
    fun `adopting global settings keeps the registry in the global file`() = runTest {
        val i = Install()
        i.migration.run() // logged out: settings stay global
        PrefsUiSettingsStore(i.global).setAccent(Accent.CHAMPAGNE)
        i.accounts.save(SessionInfo(p, SignerType.LOCAL, null))

        i.migration.adoptGlobalInto(p)

        assertEquals(registry, i.globalKeys())
        assertEquals(Accent.CHAMPAGNE.name, i.accountMap()["ui.accent"])
        assertFalse(i.accountMap().keys.any { it in registry })
    }

    @Test
    fun `old secrets of an owner removed meanwhile go and never reach the active account`() = runTest {
        val i = Install()
        val b = "b2".repeat(32)
        val bNsec = "33".repeat(32)
        i.legacySession(SignerType.LOCAL)
        i.secrets.put(KeystoreSecretStore.LEGACY_NSEC, nsec)
        i.secrets.put(KeystoreSecretStore.LEGACY_VIEW_KEY, viewKey)
        i.secrets.failOn = KeystoreSecretStore.LEGACY_NSEC
        assertTrue(runCatching { i.migration.run() }.exceptionOrNull() is SecretUnavailableException)
        i.secrets.failOn = null
        // worst case: A left the registry and B came in without the account steps' migration gate
        i.accounts.save(SessionInfo(b, SignerType.LOCAL, null))
        i.secrets.put(SecretNames.nsec(b), bNsec)
        i.accounts.remove(p)

        i.migration.run()

        assertEquals(bNsec, i.secrets.get(SecretNames.nsec(b)))
        assertNull(i.secrets.get(SecretNames.viewKey(b)))
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_NSEC))
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_VIEW_KEY))
        assertNull(i.secrets.get(SecretNames.nsec(p)))
        assertEquals(registry, i.globalKeys(), "the recorded owner leaves with the finished migration")
    }
}
