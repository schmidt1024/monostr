package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.nostr.repo.CachedTabs
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

/** Spec 2026-10-18 §3.3: the pinned tabs per account, JSON under `feed_tabs.<pubkey>`; logout removes them. */
class FeedTabsStoreTest {
    @TempDir lateinit var dir: Path
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val tabs = CachedTabs(listOf("30000:$alice:x", "30000:$bob:y"), 1234)
    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("tabs.preferences_pb").toFile() }

    @Test
    fun `saved tabs load back`() = runTest {
        val scope = scope()
        val s = PrefsFeedTabsStore(dataStore(scope))
        assertNull(s.load(alice))
        s.save(alice, tabs)
        assertEquals(tabs, s.load(alice))
        scope.cancel()
    }

    @Test
    fun `the own list order loads back`() = runTest {
        // backlog 38
        val scope = scope()
        val s = PrefsFeedTabsStore(dataStore(scope))
        val withOrder = tabs.copy(order = listOf("30000:${"a".repeat(64)}:x", "30000:${"a".repeat(64)}:y"))
        s.save(alice, withOrder)
        assertEquals(withOrder, s.load(alice))
        scope.cancel()
    }

    @Test
    fun `remove drops that account only (logout)`() = runTest {
        val scope = scope()
        val s = PrefsFeedTabsStore(dataStore(scope))
        s.save(alice, tabs)
        s.save(bob, tabs.copy(createdAt = 5))
        s.remove(alice)
        assertNull(s.load(alice))
        assertEquals(5L, s.load(bob)?.createdAt)
        scope.cancel()
    }

    @Test
    fun `a corrupt value loads as null`() = runTest {
        val scope = scope()
        val ds = dataStore(scope)
        ds.edit { it[stringPreferencesKey("feed_tabs.$alice")] = "{not json" }
        assertNull(PrefsFeedTabsStore(ds).load(alice))
        scope.cancel()
    }
}
