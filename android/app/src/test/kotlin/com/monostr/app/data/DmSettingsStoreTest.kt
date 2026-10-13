package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DmSettingsStoreTest {
    @TempDir lateinit var dir: Path

    private fun store() =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            dir.resolve("dm.preferences_pb").toFile()
        }

    @Test
    fun `defaults and round trip`() = runTest {
        val s = PrefsDmSettingsStore(store())
        assertEquals(listOf("wss://relay.monostr.com"), s.relays.first())
        assertFalse(s.previewName.first()); assertFalse(s.previewText.first())
        s.setRelays(listOf("wss://one.example"))
        s.setPreviewName(true); s.setPreviewText(true)
        assertEquals(listOf("wss://one.example"), s.relays.first())
        assertTrue(s.previewName.first()); assertTrue(s.previewText.first())
        assertFalse(s.listAdopted.first())
        s.setListAdopted(true); assertTrue(s.listAdopted.first())
        s.setListAdopted(false); assertFalse(s.listAdopted.first())
    }

    @Test
    fun `setRelays normalises and drops invalid urls`() = runTest {
        val s = PrefsDmSettingsStore(store())
        s.setRelays(listOf(" wss://two.example/ ", "bad url"))
        assertEquals(listOf("wss://two.example"), s.relays.first())
        s.setRelays(emptyList())
        assertEquals(emptyList<String>(), s.relays.first())
    }

    @Test
    fun `a stored preview name from before 0_18 is kept`() = runTest {
        val st = store()
        st.edit { it[booleanPreferencesKey("dm_preview_name")] = true }
        assertTrue(PrefsDmSettingsStore(st).previewName.first())
    }
}
