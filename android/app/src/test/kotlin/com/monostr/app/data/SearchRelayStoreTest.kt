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
