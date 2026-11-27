package com.monostr.app.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.data.AccountMigration
import com.monostr.app.data.AccountPrefs
import com.monostr.app.data.PrefsAccountsStore
import com.monostr.app.data.SecretNames
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import com.monostr.app.data.pk16
import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.SecretUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
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
    var onRemove: (String) -> Unit = {}
    override fun remove(name: String) { onRemove(name); values.remove(name) }
}

/** Spec «Switch flow», «Adding an account», «Removing an account»: the account flows of [NostrSession], with a fake engine. */
class AccountLifecycleTest {
    @TempDir lateinit var dir: Path

    private val a = "a1".repeat(32)
    private val b = "b2".repeat(32)
    private val c = "c3".repeat(32)
    private val accent = stringPreferencesKey("ui.accent")
    private val relays = stringPreferencesKey("relays")

    private inner class Install {
        private val root = dir.toFile()
        private fun file(n: String) = File(root, "$n.preferences_pb")

        val global: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file("monostr") }
        val prefs = AccountPrefs(
            global = global,
            open = { pk, scope -> PreferenceDataStoreFactory.create(scope = scope) { file("monostr-$pk") } },
            deleteFile = { pk -> AccountPrefs.deleteFiles(file("monostr-$pk")) },
        )
        val accounts = PrefsAccountsStore(global)
        val secrets = FlakySecrets()
        val busy = AccountBusy()
        val migration = AccountMigration(accounts, global, prefs, secrets)
        val host = FakeHost()
        val lifecycle = AccountLifecycle(accounts, prefs, migration, secrets, busy, host)

        fun settingsFile(pk: String) = file("monostr-${pk16(pk)}")
        /** Stands for the DM database and the engine folder of an account. */
        fun dataFile(pk: String) = File(root, "dms-${pk16(pk)}.db")

        inner class FakeHost : AccountLifecycle.Host<String> {
            val log = ArrayList<String>()
            val failStart = HashSet<String>()
            val noSigner = HashSet<String>()
            val startedWith = ArrayList<SignerType>()
            override var running: String? = null
            override fun pubkeyOf(session: String) = session
            override suspend fun stopPendingWork() { log += "stop" }
            override suspend fun close() {
                running?.let { log += "close:${it.take(2)} prefs=${prefs.activePk16.value?.take(2)}" }
                running = null
            }
            override suspend fun evictConnections() { log += "evict" }
            /** Each account change with the length of [log] then: what had happened before it. */
            val changes = ArrayList<Pair<String?, Int>>()
            override fun accountChanging(next: String?) { changes += next?.take(2) to log.size }
            /** A key account without a stored key (or listed in [noSigner]) has no usable signer; an Amber account always has one. */
            override suspend fun start(info: SessionInfo): String? {
                startedWith += info.signerType
                val usable = info.signerType == SignerType.AMBER || (info.pubkey !in noSigner && secrets.get(SecretNames.nsec(info.pubkey)) != null)
                return startAs(info.pubkey).takeIf { usable }
            }
            fun startAs(pk: String): String {
                log += "start:${pk.take(2)} prefs=${prefs.activePk16.value?.take(2)} active=${(runCatchingActive() ?: "").take(2)}"
                if (pk in failStart) throw IOException("engine")
                dataFile(pk).createNewFile()
                return pk
            }
            private fun runCatchingActive(): String? = kotlinx.coroutines.runBlocking { accounts.session.first()?.pubkey }
            override suspend fun discard(session: String) { log += "discard:${session.take(2)}" }
            override suspend fun activate(session: String) { running = session; log += "activate:${session.take(2)}" }
            override suspend fun restore() {
                val pk = accounts.session.first()?.pubkey
                log += "restore:${pk?.take(2)}"
                if (pk != null) running = pk
            }
            override fun scheduleBackgroundCheck() { log += "schedule" }
            override suspend fun failed() { log += "failed" }
            override suspend fun beforeRemoveRunning(session: String) { log += "drafts:${session.take(2)}" }
            override suspend fun wipe(pubkey: String) { log += "wipe:${pubkey.take(2)}"; dataFile(pubkey).delete() }
            override fun dropNotifications(pubkey: String) { log += "notifications:${pubkey.take(2)}" }
            override suspend fun loggedOut() { log += "loggedOut" }
        }

        suspend fun loginLocal(pk: String) = lifecycle.login(
            SessionInfo(pk, SignerType.LOCAL, null),
            start = { host.startAs(pk) },
            persist = { secrets.put(SecretNames.nsec(pk), "nsec-$pk") },
        )
    }

    /** A logged in with its own accent and relays, then B added. */
    private suspend fun Install.twoAccounts() {
        loginLocal(a)
        prefs.edit { it[accent] = "MONO"; it[relays] = "wss://a-only.example" }
        loginLocal(b)
        host.log.clear()
    }

    @Test
    fun `login second account keeps first`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.prefs.edit { it[accent] = "MONO"; it[relays] = "wss://a-only.example" }
        i.host.log.clear()
        val switched = async(start = CoroutineStart.UNDISPATCHED) { i.lifecycle.switched.first() }
        assertEquals(SwitchResult.Done, i.loginLocal(b))
        assertEquals(b, switched.await())
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(b, i.accounts.session.first()?.pubkey)
        assertEquals(b, i.host.running)
        // A keeps everything; B starts from A's look with the next accent, never A's relays
        assertEquals("MONO", i.prefs.storeFor(a).data.first()[accent])
        assertEquals("wss://a-only.example", i.prefs.storeFor(a).data.first()[relays])
        assertEquals("nsec-$a", i.secrets.get(SecretNames.nsec(a)))
        assertTrue(i.dataFile(a).exists())
        assertEquals("ORANGE", i.prefs.storeFor(b).data.first()[accent])
        assertNull(i.prefs.storeFor(b).data.first()[relays])
        // one account online: A closed before B started, with B's settings and registry entry not yet in place
        assertEquals(listOf("stop", "close:a1 prefs=a1", "evict", "start:b2 prefs=b2 active=a1", "activate:b2"), i.host.log)
    }

    @Test
    fun `switch closes old engine before starting new`() = runTest {
        val i = Install()
        i.twoAccounts()
        val switched = async(start = CoroutineStart.UNDISPATCHED) { i.lifecycle.switched.first() }
        assertEquals(SwitchResult.Done, i.lifecycle.switchTo(a))
        assertEquals(a, switched.await())
        assertEquals(listOf("stop", "close:b2 prefs=b2", "evict", "start:a1 prefs=a1 active=a1", "activate:a1"), i.host.log)
        assertEquals(a, i.host.running)
        assertEquals("MONO", i.prefs.data.first()[accent])
    }

    @Test
    fun `switch to the running account does nothing`() = runTest {
        val i = Install()
        i.twoAccounts()
        assertEquals(SwitchResult.Done, i.lifecycle.switchTo(b))
        assertEquals(emptyList<String>(), i.host.log)
    }

    @Test
    fun `switch refused while busy`() = runTest {
        val i = Install()
        i.twoAccounts()
        val result = i.busy.track { i.lifecycle.switchTo(a) }
        assertEquals(SwitchResult.Busy, result)
        assertEquals(emptyList<String>(), i.host.log)
        assertEquals(b, i.host.running)
        assertEquals(b, i.accounts.session.first()?.pubkey)
        // adding an account while busy is refused too
        assertEquals(SwitchResult.Busy, i.busy.track { i.loginLocal(c) })
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
    }

    @Test
    fun `active account written before start, restore reads it`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.lifecycle.switchTo(a)
        assertTrue("start:a1 prefs=a1 active=a1" in i.host.log)
        // the next app start reads the registry from the file
        assertEquals(a, PrefsAccountsStore(i.global).session.first()?.pubkey)
        assertEquals(pk16(a), i.prefs.activePk16.value)
    }

    @Test
    fun `a write after the switch lands in the new account, the old session closed before`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.lifecycle.switchTo(a)
        // the old session (and its scope) closed while B's file was still active, so no late write of B can reach A
        assertEquals("close:b2 prefs=b2", i.host.log[1])
        i.prefs.edit { it[relays] = "wss://after.example" }
        assertEquals("wss://after.example", i.prefs.storeFor(a).data.first()[relays])
        assertNull(i.prefs.storeFor(b).data.first()[relays])
    }

    @Test
    fun `a failed switch brings the previous account back`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.host.failStart += a
        assertEquals(SwitchResult.Failed, i.lifecycle.switchTo(a))
        assertEquals(b, i.accounts.session.first()?.pubkey)
        assertEquals(pk16(b), i.prefs.activePk16.value)
        // the background check the switch cancelled runs again for B (final review M1)
        assertEquals(listOf("restore:b2", "schedule"), i.host.log.takeLast(2))
        assertEquals(b, i.host.running)
        // an account without a usable key fails the same way, and stays on the device
        i.host.failStart.clear(); i.host.noSigner += a
        assertEquals(SwitchResult.Failed, i.lifecycle.switchTo(a))
        assertEquals(b, i.accounts.session.first()?.pubkey)
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
        // only the unusable key went; A's settings and data stay
        assertNull(i.secrets.get(SecretNames.nsec(a)))
        assertTrue(i.settingsFile(a).exists())
        assertTrue(i.dataFile(a).exists())
    }

    @Test
    fun `login of an account already on the device switches to it`() = runTest {
        val i = Install()
        i.twoAccounts()
        assertEquals(SwitchResult.Done, i.loginLocal(a))
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(a, i.host.running)
        assertEquals("MONO", i.prefs.storeFor(a).data.first()[accent])
    }

    /** Final review M8: the key or Amber of the running account entered again; nothing changes, and the caller can say so. */
    @Test
    fun `login of the running account changes nothing and says so`() = runTest {
        val i = Install()
        i.twoAccounts()
        assertEquals(SwitchResult.AlreadyActive, i.loginLocal(b))
        assertEquals(SwitchResult.AlreadyActive, i.lifecycle.login(SessionInfo(b, SignerType.AMBER, "pkg"), start = { error("no start") }, persist = {}))
        assertEquals(emptyList<String>(), i.host.log)
        assertEquals(b, i.host.running)
        assertEquals(SessionInfo(b, SignerType.LOCAL, null), i.accounts.accounts.first()[1])
    }

    @Test
    fun `failed first login gives the adopted settings back to the global file`() = runTest {
        val i = Install()
        // logged out at update time: the look waits in the global file for the first account
        i.global.edit { it[accent] = "CHAMPAGNE" }
        i.prefs.activate(null)
        i.host.failStart += a
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { i.loginLocal(a) } }
        assertEquals("CHAMPAGNE", i.global.data.first()[accent])
        assertFalse(i.settingsFile(a).exists())
        assertEquals(emptyList<SessionInfo>(), i.accounts.accounts.first())
        assertNull(i.secrets.get(SecretNames.nsec(a)))
        assertNull(i.prefs.activePk16.value)
        // the next try adopts them and they leave the global file
        i.host.failStart.clear()
        i.loginLocal(a)
        assertEquals("CHAMPAGNE", i.prefs.storeFor(a).data.first()[accent])
        assertNull(i.global.data.first()[accent])
    }

    @Test
    fun `failed add leaves no trace of the new account and brings the first back`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.prefs.edit { it[accent] = "MONO" }
        i.host.failStart += b
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { i.loginLocal(b) } }
        assertEquals(listOf(a), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(a, i.accounts.session.first()?.pubkey)
        assertEquals(pk16(a), i.prefs.activePk16.value)
        assertFalse(i.settingsFile(b).exists())
        assertNull(i.secrets.get(SecretNames.nsec(b)))
        // the background check the add cancelled runs again for A (final review M1)
        assertEquals(listOf("restore:a1", "schedule"), i.host.log.takeLast(2))
        assertEquals("MONO", i.prefs.data.first()[accent])
    }

    @Test
    fun `failed persist removes the half added account`() = runTest {
        val i = Install()
        i.loginLocal(a)
        val thrown = runCatching {
            i.lifecycle.login(SessionInfo(b, SignerType.LOCAL, null), start = { i.host.startAs(b) }, persist = {
                i.secrets.put(SecretNames.nsec(b), "nsec-$b")
                throw IOException("disk full")
            })
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertTrue("discard:b2" in i.host.log)
        assertEquals(listOf(a), i.accounts.accounts.first().map { it.pubkey })
        assertNull(i.secrets.get(SecretNames.nsec(b)))
        assertFalse(i.settingsFile(b).exists())
        assertFalse(i.dataFile(b).exists())
    }

    @Test
    fun `remove active keeps other account intact`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.prefs.edit { it[accent] = "MONO" }
        i.loginLocal(b)
        i.prefs.edit { it[relays] = "wss://b-only.example" }
        i.lifecycle.switchTo(a)
        i.secrets.put(SecretNames.viewKey(a), "view-a")
        i.secrets.put(SecretNames.viewKey(b), "view-b")
        i.host.log.clear()
        val switched = async(start = CoroutineStart.UNDISPATCHED) { i.lifecycle.switched.first() }
        // logged where it is emitted: the fresh activity of B must not wait for A's erase (final review M6)
        backgroundScope.launch(Dispatchers.Unconfined) { i.lifecycle.switched.collect { i.host.log += "switched:${it.take(2)}" } }
        i.lifecycle.remove(a)
        assertEquals(b, switched.await())
        // B is active and running
        assertEquals(listOf(b), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(b, i.accounts.session.first()?.pubkey)
        assertEquals(b, i.host.running)
        assertEquals(pk16(b), i.prefs.activePk16.value)
        // nothing of A stays
        assertFalse(i.settingsFile(a).exists())
        assertNull(i.secrets.get(SecretNames.nsec(a)))
        assertNull(i.secrets.get(SecretNames.viewKey(a)))
        assertFalse(i.dataFile(a).exists())
        // B untouched
        assertTrue(i.settingsFile(b).exists())
        assertEquals("wss://b-only.example", i.prefs.data.first()[relays])
        assertEquals("nsec-$b", i.secrets.get(SecretNames.nsec(b)))
        assertEquals("view-b", i.secrets.get(SecretNames.viewKey(b)))
        assertTrue(i.dataFile(b).exists())
        // the draft pictures go while A still runs; A is closed before B starts, B is shown, then A's files go; shared caches stay
        assertEquals(
            listOf("drafts:a1", "stop", "close:a1 prefs=a1", "notifications:a1", "evict", "start:b2 prefs=b2 active=b2", "activate:b2", "switched:b2", "wipe:a1"),
            i.host.log,
        )
    }

    @Test
    fun `remove inactive account leaves the running one alone`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.lifecycle.remove(a)
        assertEquals(listOf("notifications:a1", "wipe:a1"), i.host.log)
        assertEquals(listOf(b), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(b, i.host.running)
        assertFalse(i.settingsFile(a).exists())
        assertNull(i.secrets.get(SecretNames.nsec(a)))
    }

    @Test
    fun `remove last goes logged out and clears shared caches`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.prefs.edit { it[accent] = "MONO" }
        i.host.log.clear()
        i.lifecycle.remove(a)
        assertEquals(listOf("drafts:a1", "stop", "close:a1 prefs=a1", "notifications:a1", "evict", "wipe:a1", "loggedOut"), i.host.log)
        assertEquals(emptyList<SessionInfo>(), i.accounts.accounts.first())
        assertNull(i.accounts.session.first())
        assertNull(i.prefs.activePk16.value)
        assertNull(i.host.running)
        assertFalse(i.settingsFile(a).exists())
        assertNull(i.secrets.get(SecretNames.nsec(a)))
        // the login screen shows the defaults
        assertNull(i.prefs.data.first()[accent])
    }

    @Test
    fun `remove of a stale active account starts the next one`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.lifecycle.switchTo(a)
        i.host.close()
        i.host.log.clear()
        // A's key became unusable: restore removes it, B comes up
        i.lifecycle.remove(a)
        assertEquals(b, i.host.running)
        assertEquals(listOf(b), i.accounts.accounts.first().map { it.pubkey })
        assertFalse("drafts:a1" in i.host.log.joinToString())
    }

    @Test
    fun `stale key at restore keeps the account and its data`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.lifecycle.switchTo(a)
        i.secrets.put(SecretNames.viewKey(a), "view-a")
        // the next app start: A's key is unusable for good
        i.host.close()
        i.host.log.clear()
        val switched = async(start = CoroutineStart.UNDISPATCHED) { i.lifecycle.switched.first() }
        i.lifecycle.keyUnusable(SessionInfo(a, SignerType.LOCAL, null))
        assertEquals(listOf("failed"), i.host.log, "nothing starts, the background check is not touched")
        assertTrue(switched.isActive, "no switched from a restore")
        switched.cancel()
        assertNull(i.host.running)
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(a, i.accounts.session.first()?.pubkey)
        assertTrue(i.settingsFile(a).exists())
        assertEquals("MONO", i.prefs.storeFor(a).data.first()[accent])
        assertTrue(i.dataFile(a).exists())
        assertEquals("view-a", i.secrets.get(SecretNames.viewKey(a)))
        assertNull(i.secrets.get(SecretNames.nsec(a)))
    }

    @Test
    fun `re-login with the same key brings the account back with its history`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.lifecycle.switchTo(a)
        i.host.close()
        i.lifecycle.keyUnusable(SessionInfo(a, SignerType.LOCAL, null))
        assertEquals(SwitchResult.Done, i.loginLocal(a))
        assertEquals(a, i.host.running)
        assertEquals("nsec-$a", i.secrets.get(SecretNames.nsec(a)))
        assertTrue(i.dataFile(a).exists())
        assertEquals("MONO", i.prefs.data.first()[accent])
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
    }

    @Test
    fun `amber re-login of an existing account updates the package and drops the key`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.accounts.updateDisplay(a, "Alice", null)
        i.loginLocal(b)
        val result = i.lifecycle.login(SessionInfo(a, SignerType.AMBER, "pkg2"), start = { error("existing account: no new start path") }, persist = {})
        assertEquals(SwitchResult.Done, result)
        assertEquals(SessionInfo(a, SignerType.AMBER, "pkg2", name = "Alice"), i.accounts.accounts.first()[0])
        assertNull(i.secrets.get(SecretNames.nsec(a)))
        assertEquals(a, i.host.running)
        assertTrue(i.dataFile(a).exists())
    }

    @Test
    fun `removing the active account skips a next one without a usable key and keeps it`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.prefs.edit { it[accent] = "MONO" }
        i.loginLocal(b)
        i.loginLocal(c)
        i.host.noSigner += a
        i.lifecycle.remove(c)
        assertEquals(b, i.host.running)
        assertEquals(b, i.accounts.session.first()?.pubkey)
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
        assertTrue(i.settingsFile(a).exists())
        assertTrue(i.dataFile(a).exists())
        assertFalse(i.settingsFile(c).exists())
    }

    @Test
    fun `removing the active account with only unusable others ends on the login screen with them kept`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.host.noSigner += a
        i.lifecycle.remove(b)
        assertEquals(listOf("failed", "wipe:b2"), i.host.log.takeLast(2))
        assertNull(i.host.running)
        assertEquals(listOf(a), i.accounts.accounts.first().map { it.pubkey })
        assertEquals(a, i.accounts.session.first()?.pubkey)
        assertEquals(pk16(a), i.prefs.activePk16.value)
        assertTrue(i.dataFile(a).exists())
        assertFalse(i.settingsFile(b).exists())
    }

    @Test
    fun `account steps wait for an unfinished migration`() = runTest {
        val i = Install()
        // the single account of an older version, its key still under the old name: reading it hits a passing Keystore error
        i.global.edit {
            it[stringPreferencesKey("session.pubkey")] = a
            it[stringPreferencesKey("session.signer")] = SignerType.LOCAL.name
        }
        i.secrets.put(KeystoreSecretStore.LEGACY_NSEC, "nsec-$a")
        i.secrets.failOn = KeystoreSecretStore.LEGACY_NSEC
        assertEquals(SwitchResult.Failed, i.loginLocal(b))
        assertEquals(listOf(a), i.accounts.accounts.first().map { it.pubkey })
        assertNull(i.secrets.get(SecretNames.nsec(b)))
        assertFalse(i.settingsFile(b).exists())
        assertEquals(SwitchResult.Failed, i.lifecycle.switchTo(a))
        assertTrue(runCatching { i.lifecycle.remove(a) }.exceptionOrNull() is SwitchRefusedException)
        assertEquals(listOf(a), i.accounts.accounts.first().map { it.pubkey })
        // the Keystore answers again: the old key reaches its own account, then B can be added
        i.secrets.failOn = null
        assertEquals(SwitchResult.Done, i.loginLocal(b))
        assertEquals("nsec-$a", i.secrets.get(SecretNames.nsec(a)))
        assertNull(i.secrets.get(KeystoreSecretStore.LEGACY_NSEC))
        assertEquals(listOf(a, b), i.accounts.accounts.first().map { it.pubkey })
    }

    private suspend fun Install.amberLogin(pk: String, pkg: String = "pkg2") =
        lifecycle.login(SessionInfo(pk, SignerType.AMBER, pkg), start = { error("existing account: no new start path") }, persist = {})

    @Test
    fun `amber re-login whose start fails keeps the key and the account as it was`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.host.failStart += a
        assertEquals(SwitchResult.Failed, i.amberLogin(a))
        assertEquals(SessionInfo(a, SignerType.LOCAL, null), i.accounts.accounts.first()[0])
        assertEquals("nsec-$a", i.secrets.get(SecretNames.nsec(a)))
        assertEquals(b, i.host.running)
        assertEquals(b, i.accounts.session.first()?.pubkey)
    }

    @Test
    fun `amber re-login over a stale key account removes the stale key once it runs`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.secrets.put(SecretNames.nsec(a), "garbage")
        i.host.noSigner += a
        assertEquals(SwitchResult.Done, i.amberLogin(a))
        assertEquals(a, i.host.running)
        assertEquals(SessionInfo(a, SignerType.AMBER, "pkg2"), i.accounts.accounts.first()[0])
        assertNull(i.secrets.get(SecretNames.nsec(a)))
    }

    @Test
    fun `amber re-login over a working key account removes the key only after the session runs`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.secrets.onRemove = { name -> i.host.log += "remove:$name" }
        assertEquals(SwitchResult.Done, i.amberLogin(a))
        val log = i.host.log
        assertEquals(SignerType.AMBER, i.host.startedWith.last(), "started with the new signer")
        val activated = log.indexOf("activate:a1")
        val removed = log.indexOf("remove:${SecretNames.nsec(a)}")
        assertTrue(activated in 0 until removed, log.toString())
        assertEquals(SessionInfo(a, SignerType.AMBER, "pkg2"), i.accounts.accounts.first()[0])
        assertNull(i.secrets.get(SecretNames.nsec(a)))
    }

    @Test
    fun `key re-login whose start fails leaves no key behind that was not there`() = runTest {
        val i = Install()
        i.loginLocal(a)
        i.loginLocal(b)
        // A moved to Amber earlier; a later key login of A fails to start
        i.amberLogin(a)
        i.lifecycle.switchTo(b)
        i.host.failStart += a
        assertEquals(SwitchResult.Failed, i.loginLocal(a))
        assertNull(i.secrets.get(SecretNames.nsec(a)))
        assertEquals(SessionInfo(a, SignerType.AMBER, "pkg2"), i.accounts.accounts.first()[0])
        assertEquals(b, i.host.running)
    }

    @Test
    fun `the account change is told before the running session closes and connections go`() = runTest {
        val i = Install()
        i.twoAccounts()
        i.host.changes.clear()
        i.lifecycle.switchTo(a)
        assertEquals(listOf<Pair<String?, Int>>("a1" to 0), i.host.changes, "before stop, close and evict")
        i.host.log.clear()
        i.host.changes.clear()
        i.loginLocal(c)
        assertEquals(listOf<Pair<String?, Int>>(c.take(2) to 0), i.host.changes)
        i.host.log.clear()
        i.host.changes.clear()
        i.lifecycle.remove(c)
        val drafts = i.host.log.indexOf("drafts:${c.take(2)}")
        assertEquals(listOf<Pair<String?, Int>>(null to drafts + 1), i.host.changes, "after the drafts' cleanup, before stop, close and evict")
        assertEquals("stop", i.host.log[drafts + 1])
    }
}
