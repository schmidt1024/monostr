package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AccountsStoreTest {
    @TempDir lateinit var dir: Path

    private val store by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            dir.resolve("g.preferences_pb").toFile()
        }
    }
    private val a = SessionInfo("a".repeat(64), SignerType.LOCAL, null)
    private val b = SessionInfo("b".repeat(64), SignerType.AMBER, "com.greenart7c3.nostrsigner")

    @Test
    fun `add keeps order and replaces same pubkey`() = runTest {
        val s = PrefsAccountsStore(store)
        s.add(a); s.add(b)
        assertEquals(listOf(a, b), s.accounts.first())
        val a2 = a.copy(name = "Al|ice\nx", picture = "https://x/p.png?a=1&b=2")
        s.add(a2)
        assertEquals(listOf(a2, b), s.accounts.first())
        assertNull(s.session.first())
    }

    @Test
    fun `a login again keeps the shown name and picture`() = runTest {
        val s = PrefsAccountsStore(store)
        s.save(a.copy(name = "Alice", picture = "https://x/a.png"))
        s.save(a)
        assertEquals(listOf(a.copy(name = "Alice", picture = "https://x/a.png")), s.accounts.first())
        s.add(b.copy(name = "Bob"))
        s.add(b.copy(amberPackage = "other.signer"))
        assertEquals(b.copy(amberPackage = "other.signer", name = "Bob"), s.accounts.first()[1])
        // updateDisplay still clears them
        s.updateDisplay(a.pubkey, null, null)
        assertEquals(a, s.accounts.first()[0])
    }

    @Test
    fun `save adds and activates`() = runTest {
        val s = PrefsAccountsStore(store)
        s.save(a); s.save(b)
        assertEquals(listOf(a, b), s.accounts.first())
        assertEquals(b, s.session.first())
        s.setActive(a.pubkey)
        assertEquals(a, s.session.first())
        s.setActive(null)
        assertNull(s.session.first())
    }

    @Test
    fun `remove active clears active`() = runTest {
        val s = PrefsAccountsStore(store)
        s.save(a); s.add(b)
        s.remove(b.pubkey)
        assertEquals(a, s.session.first())
        s.clear()
        assertNull(s.session.first())
        assertEquals(emptyList<SessionInfo>(), s.accounts.first())
    }

    @Test
    fun `updateDisplay sets name and picture`() = runTest {
        val s = PrefsAccountsStore(store)
        s.save(a)
        s.updateDisplay(a.pubkey, "Al", "https://x/p.png")
        assertEquals(a.copy(name = "Al", picture = "https://x/p.png"), s.session.first())
    }

    @Test
    fun `legacy single session becomes one active account`() = runTest {
        val p = "c".repeat(64)
        store.edit {
            it[stringPreferencesKey("session.pubkey")] = p
            it[stringPreferencesKey("session.signer")] = "AMBER"
            it[stringPreferencesKey("session.amber_package")] = "com.greenart7c3.nostrsigner"
        }
        val s = PrefsAccountsStore(store)
        val expected = SessionInfo(p, SignerType.AMBER, "com.greenart7c3.nostrsigner")
        assertEquals(listOf(expected), s.accounts.first())
        assertEquals(p, s.session.first()?.pubkey)
        s.migrateLegacy(); s.migrateLegacy()
        val keys = store.data.first().asMap().keys.map { it.name }
        assertEquals(setOf("accounts", "active_account"), keys.toSet())
        assertEquals(listOf(expected), s.accounts.first())
        assertEquals(expected, s.session.first())
    }

    @Test
    fun `switch hint flag starts unset and stays set across account changes`() = runTest {
        val s = PrefsAccountsStore(store)
        assertEquals(false, s.switchHintSeen())
        s.save(a); s.add(b)
        assertEquals(false, s.switchHintSeen())
        s.markSwitchHintSeen()
        assertEquals(true, s.switchHintSeen())
        // removing and re-adding accounts does not bring the hint back
        s.remove(b.pubkey); s.remove(a.pubkey); s.add(a); s.add(b)
        assertEquals(true, s.switchHintSeen())
        assertEquals(listOf(a, b), s.accounts.first())
    }
}
