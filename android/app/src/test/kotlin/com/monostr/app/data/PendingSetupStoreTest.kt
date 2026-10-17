package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.session.PendingSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec 2026-10-17 §7: one pending setup record per account, JSON under `pending_setup.<pubkey>`. */
class PendingSetupStoreTest {
    @TempDir lateinit var dir: Path
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val setup = PendingSetup(
        name = "Alina",
        relays = listOf("wss://relay.monostr.com", "wss://relay.damus.io"),
        dmRelays = listOf("wss://relay.monostr.com"),
        follows = listOf("c".repeat(64)),
        profileDone = true,
    )
    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("pending.preferences_pb").toFile() }

    @Test
    fun `a saved record loads back unchanged`() = runTest {
        val scope = scope()
        val s = PrefsPendingSetupStore(dataStore(scope))
        assertNull(s.load(alice))
        s.save(alice, setup)
        assertEquals(setup, s.load(alice))
        val next = setup.copy(relaysDone = true)
        s.save(alice, next)
        assertEquals(next, s.load(alice))
        scope.cancel()
    }

    @Test
    fun `remove drops the record of that account only`() = runTest {
        val scope = scope()
        val s = PrefsPendingSetupStore(dataStore(scope))
        s.save(alice, setup)
        s.save(bob, setup.copy(name = "Bob"))
        s.remove(alice)
        assertNull(s.load(alice))
        assertEquals("Bob", s.load(bob)?.name)
        scope.cancel()
    }

    @Test
    fun `a corrupt value loads as null`() = runTest {
        val scope = scope()
        val ds = dataStore(scope)
        ds.edit { it[stringPreferencesKey("pending_setup.$alice")] = "{not json" }
        val s = PrefsPendingSetupStore(ds)
        assertNull(s.load(alice))
        scope.cancel()
    }
}
