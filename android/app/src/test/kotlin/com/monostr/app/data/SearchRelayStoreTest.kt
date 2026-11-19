package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SearchRelayStoreTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `configured lists only what the user set, never the defaults`() = runTest {
        // controller ruling 2026-11-19: only explicitly set search relays count as own for NIP-42 AUTH
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("c.preferences_pb").toFile() }
        val s = PrefsSearchRelayStore(store)
        assertEquals(emptyList<String>(), s.configured.first())
        s.set(listOf("wss://one.example/"))
        assertEquals(listOf("wss://one.example"), s.configured.first())
        s.set(emptyList())
        assertEquals(emptyList<String>(), s.configured.first())
        s.reset()
        assertEquals(emptyList<String>(), s.configured.first())
        scope.cancel()
    }

    @Test
    fun `configured never lists a default, however it is spelled or stored`() = runTest {
        // review fix round 1 (ruling C): the editor stores the whole visible list, defaults included, at the first edit
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("d.preferences_pb").toFile() }
        val s = PrefsSearchRelayStore(store)
        s.set(listOf("wss://search.nos.today", "wss://search.monostr.com", "wss://one.example", "WSS://Search.NOS.today/", "wss://search.monostr.com:443"))
        assertEquals(listOf("wss://one.example"), s.configured.first())
        scope.cancel()
    }

    @Test
    fun `defaults, empty set allowed, reset restores defaults`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("t.preferences_pb").toFile() }
        val s = PrefsSearchRelayStore(store)
        assertEquals(PrefsSearchRelayStore.DEFAULT_SEARCH_RELAYS, s.relays.first())
        s.set(listOf("wss://one.example/", "bad url"))
        assertEquals(listOf("wss://one.example"), s.relays.first())
        s.set(emptyList())
        assertEquals(emptyList<String>(), s.relays.first())
        s.reset()
        assertEquals(PrefsSearchRelayStore.DEFAULT_SEARCH_RELAYS, s.relays.first())
        scope.cancel()
    }
}
