package com.monostr.app.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.ui.FakeRelayModeStore
import com.monostr.app.ui.FakeRelayStore
import com.monostr.nostr.RelayList
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Spec 2026-11-05 relay read/write: what the session does with the modes. The engine's side is in EngineRelayModesTest. */
class RelayModesSessionTest {
    @TempDir lateinit var dir: Path

    private val a = "wss://a.example"
    private val b = "wss://b.example"
    private val c = "wss://c.example"

    private class FakeRunning(var pool: List<String>, var published: RelayList? = null) : RunningRelays {
        val calls = ArrayList<String>()
        var applied: Map<String, RelayMode>? = null
        var freshReads = ArrayList<Boolean>()
        override suspend fun relayUrls() = pool
        override suspend fun removeRelays(urls: List<String>) { if (urls.isNotEmpty()) calls += "remove:$urls"; pool = pool - urls.toSet() }
        override suspend fun addRelays(urls: List<String>) { if (urls.isNotEmpty()) calls += "add:$urls"; pool = pool + urls }
        override suspend fun applyRelayModes(modes: Map<String, RelayMode>) { calls += "modes"; applied = modes }
        override suspend fun connect() { calls += "connect" }
        override suspend fun restartDms() { calls += "dms" }
        override suspend fun ownRelayList(fresh: Boolean): RelayList? { freshReads += fresh; return published }
    }

    private class Fixture(relays: List<String>, modes: Map<String, RelayMode> = emptyMap(), published: RelayList? = null, active: Boolean = true) {
        val relayStore = FakeRelayStore(relays)
        val modeStore = FakeRelayModeStore(modes, relayStore)
        val running = FakeRunning(relays, published)
        val session = SessionRelays(relayStore, modeStore, Mutex()) { running.takeIf { active } }
    }

    @Test
    fun `no stored mode reads as an empty map, a stored BOTH stays`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("t.preferences_pb").toFile() }
        val store = PrefsRelayStore(prefs)
        assertEquals(emptyMap<String, RelayMode>(), store.modes.first())
        val modes = mapOf(a to RelayMode.BOTH, b to RelayMode.READ, c to RelayMode.WRITE)
        store.setModes(modes)
        assertEquals(modes, store.modes.first())
        assertEquals(modes, PrefsRelayStore(prefs).modes.first(), "read back by a new store")
        store.setModes(mapOf(" $a/ " to RelayMode.WRITE, "not a url" to RelayMode.READ))
        assertEquals(mapOf(a to RelayMode.WRITE), store.modes.first(), "normalised, an invalid URL is not stored")
        store.setModes(emptyMap())
        assertEquals(emptyMap<String, RelayMode>(), store.modes.first())
        assertEquals(PrefsRelayStore.DEFAULT_RELAYS, store.relays.first(), "the relay list is a value of its own")
        scope.cancel()
    }

    @Test
    fun `a broken stored entry is dropped, the others stay`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("t.preferences_pb").toFile() }
        prefs.updateData { p ->
            p.toMutablePreferences().apply {
                set(androidx.datastore.preferences.core.stringSetPreferencesKey("relay_modes"), setOf("WRITE $a", "NEVER $b", "READ", "READ $c"))
            }
        }
        assertEquals(mapOf(a to RelayMode.WRITE, c to RelayMode.READ), PrefsRelayStore(prefs).modes.first())
        scope.cancel()
    }

    @Test
    fun `a removed relay loses its mode`() = runTest {
        val f = Fixture(listOf(a, b, c), mapOf(a to RelayMode.WRITE, b to RelayMode.READ))
        assertTrue(f.session.apply(listOf(b, c)))
        assertEquals(mapOf(b to RelayMode.READ), f.modeStore.state.value)
        assertEquals(listOf(b, c), f.relayStore.state.value)
        assertEquals(mapOf(b to RelayMode.READ), f.running.applied)
        // back in the list it starts over as both
        assertTrue(f.session.apply(listOf(a, b, c)))
        assertEquals(mapOf(b to RelayMode.READ), f.modeStore.state.value)
    }

    @Test
    fun `a change that leaves no reader or writer is refused and nothing is stored`() = runTest {
        val modes = mapOf(a to RelayMode.WRITE, b to RelayMode.READ)
        val f = Fixture(listOf(a, b), modes)
        assertFalse(f.session.setRelayMode(a, RelayMode.READ), "no writer left")
        assertFalse(f.session.setRelayMode(b, RelayMode.WRITE), "no reader left")
        assertEquals(modes, f.modeStore.state.value)
        assertEquals(emptyList<String>(), f.running.calls)
    }

    @Test
    fun `a mode for a relay outside the list is refused`() = runTest {
        val f = Fixture(listOf(a, b))
        assertFalse(f.session.setRelayMode(c, RelayMode.READ))
        assertEquals(emptyMap<String, RelayMode>(), f.modeStore.state.value)
    }

    @Test
    fun `a mode-only change reconnects nothing and does not restart DMs`() = runTest {
        val f = Fixture(listOf(a, b))
        assertTrue(f.session.setRelayMode("$a/ ", RelayMode.WRITE))
        assertEquals(mapOf(a to RelayMode.WRITE), f.modeStore.state.value, "stored under the normalised URL")
        assertEquals(listOf("modes"), f.running.calls)
        assertEquals(mapOf(a to RelayMode.WRITE), f.running.applied)
        // and the same when the list comes back unchanged with other markers
        f.running.calls.clear()
        f.running.published = RelayList(read = listOf(b), write = listOf(a, b))
        assertEquals(AdoptResult.ADOPTED, f.session.adopt())
        assertEquals(listOf("modes"), f.running.calls)
    }

    @Test
    fun `a mode is stored without a running session`() = runTest {
        val f = Fixture(listOf(a, b), active = false)
        assertTrue(f.session.setRelayMode(a, RelayMode.READ))
        assertEquals(mapOf(a to RelayMode.READ), f.modeStore.state.value)
    }

    @Test
    fun `removing a relay is refused when the rest would have no reader or writer`() = runTest {
        val modes = mapOf(a to RelayMode.WRITE, b to RelayMode.READ, c to RelayMode.READ)
        val f = Fixture(listOf(a, b, c), modes)
        assertFalse(f.session.apply(listOf(b, c)), "the only writer")
        assertEquals(listOf(a, b, c), f.relayStore.state.value)
        assertEquals(modes, f.modeStore.state.value)
        assertEquals(emptyList<String>(), f.running.calls)
        assertTrue(f.session.apply(listOf(a, b)), "a reader stays")
        assertEquals(listOf(a, b), f.relayStore.state.value)
        assertEquals(mapOf(a to RelayMode.WRITE, b to RelayMode.READ), f.modeStore.state.value)
    }

    @Test
    fun `a list change removes, sets the modes, adds and restarts DMs, in that order`() = runTest {
        val f = Fixture(listOf(a, b), mapOf(a to RelayMode.WRITE))
        assertTrue(f.session.apply(listOf("$b/", c)))
        assertEquals(listOf(b, c), f.relayStore.state.value, "normalised")
        assertEquals(listOf("remove:[$a]", "modes", "add:[$c]", "connect", "dms"), f.running.calls)
    }

    @Test
    fun `adopting stores the markers, a broken list gives MARKERS_IGNORED`() = runTest {
        val f = Fixture(listOf(a), published = RelayList(read = listOf("$b/", c), write = listOf(c, a)))
        assertEquals(AdoptResult.ADOPTED, f.session.adopt())
        assertEquals(listOf(true), f.running.freshReads, "the list as the relays have it now")
        assertEquals(setOf(a, b, c), f.relayStore.state.value.toSet())
        assertEquals(mapOf(a to RelayMode.WRITE, b to RelayMode.READ, c to RelayMode.BOTH), f.modeStore.state.value)
        assertEquals(mapOf(a to RelayMode.WRITE, b to RelayMode.READ, c to RelayMode.BOTH), f.running.applied)

        val broken = Fixture(listOf(a), mapOf(a to RelayMode.BOTH), published = RelayList(read = listOf(b, c), write = emptyList()))
        assertEquals(AdoptResult.MARKERS_IGNORED, broken.session.adopt())
        assertEquals(setOf(b, c), broken.relayStore.state.value.toSet(), "the relays are taken")
        assertEquals(mapOf(b to RelayMode.BOTH, c to RelayMode.BOTH), broken.modeStore.state.value, "as the message says: every relay reads and writes")
        assertEquals(mapOf(b to RelayMode.BOTH, c to RelayMode.BOTH), broken.running.applied)
    }

    @Test
    fun `a publish after MARKERS_IGNORED replaces the broken markers`() = runTest {
        val f = Fixture(listOf(a), published = RelayList(read = listOf(b, c), write = emptyList()))
        assertEquals(AdoptResult.MARKERS_IGNORED, f.session.adopt())
        assertEquals(setOf(RelayEntry(b, RelayMode.BOTH), RelayEntry(c, RelayMode.BOTH)), f.session.entriesForPublish().toSet())
    }

    @Test
    fun `adopting nothing gives NONE and changes nothing`() = runTest {
        val modes = mapOf(a to RelayMode.WRITE, b to RelayMode.READ)
        val none = Fixture(listOf(a, b), modes)
        assertEquals(AdoptResult.NONE, none.session.adopt())
        val empty = Fixture(listOf(a, b), modes, published = RelayList(emptyList(), emptyList()))
        assertEquals(AdoptResult.NONE, empty.session.adopt())
        val unusable = Fixture(listOf(a, b), modes, published = RelayList(read = listOf("https://not.a.relay"), write = emptyList()))
        assertEquals(AdoptResult.NONE, unusable.session.adopt())
        listOf(none, empty, unusable).forEach {
            assertEquals(listOf(a, b), it.relayStore.state.value)
            assertEquals(modes, it.modeStore.state.value)
            assertEquals(emptyList<String>(), it.running.calls)
        }
        assertEquals(AdoptResult.NONE, Fixture(listOf(a), published = RelayList(listOf(b), listOf(b)), active = false).session.adopt())
    }

    @Test
    fun `a URL that cannot work does not decide over the markers`() = runTest {
        // the only reader is unusable: what stays has no reader, so its markers are ignored too
        val f = Fixture(listOf(a), published = RelayList(read = listOf("https://not.a.relay"), write = listOf(b)))
        assertEquals(AdoptResult.MARKERS_IGNORED, f.session.adopt())
        assertEquals(listOf(b), f.relayStore.state.value)
        assertEquals(mapOf(b to RelayMode.BOTH), f.modeStore.state.value)
    }

    @Test
    fun `ensureRelays switches read on for a named write-only relay, keeps its write, adds a new one as both`() = runTest {
        val f = Fixture(listOf(a, b), mapOf(a to RelayMode.WRITE, b to RelayMode.READ))
        f.session.ensure(listOf("$a/", b, c, "ws://local.example", "wss://localhost"))
        assertEquals(listOf(a, b, c), f.relayStore.state.value)
        assertEquals(mapOf(a to RelayMode.BOTH, b to RelayMode.READ), f.modeStore.state.value, "a read-only relay stays as set, the new one has no stored mode")
        assertEquals(mapOf(a to RelayMode.BOTH, b to RelayMode.READ), f.running.applied)
        assertEquals(listOf("modes", "add:[$c]", "connect", "dms"), f.running.calls)
    }

    @Test
    fun `ensureRelays with only a write-only relay named does not restart DMs, and with nothing to do touches nothing`() = runTest {
        val f = Fixture(listOf(a, b), mapOf(a to RelayMode.WRITE))
        f.session.ensure(listOf(a))
        assertEquals(mapOf(a to RelayMode.BOTH), f.modeStore.state.value)
        assertEquals(listOf("modes"), f.running.calls)
        f.running.calls.clear()
        f.session.ensure(listOf(a, b))
        assertEquals(emptyList<String>(), f.running.calls)
    }

    @Test
    fun `back to defaults clears the modes`() = runTest {
        // every default relay read only and the writer elsewhere: with the old modes the default list would be refused
        val f = Fixture(listOf(a, b, c), mapOf(a to RelayMode.READ, b to RelayMode.READ, c to RelayMode.WRITE))
        assertTrue(f.session.resetTo(listOf("$a/", b)))
        assertEquals(emptyMap<String, RelayMode>(), f.modeStore.state.value)
        assertEquals(emptyMap<String, RelayMode>(), f.running.applied)
        assertEquals(listOf(a, b), f.relayStore.state.value)
    }

    @Test
    fun `back to defaults is one step, a removed write-only relay leaves before any relay can read again`() = runTest {
        val f = Fixture(listOf(a, b, c), mapOf(c to RelayMode.WRITE, a to RelayMode.WRITE))
        assertTrue(f.session.resetTo(listOf(a, b, "wss://d.example")))
        // one modes step only, after c is gone: the engine replays the live subscriptions to the relays in the pool at that moment
        assertEquals(listOf("remove:[$c]", "modes", "add:[wss://d.example]", "connect", "dms"), f.running.calls)
    }

    @Test
    fun `back to defaults stays locked as one step`() = runTest {
        val lock = Mutex()
        val relayStore = FakeRelayStore(listOf(a, b))
        val modeStore = FakeRelayModeStore(mapOf(a to RelayMode.WRITE), relayStore)
        val held = ArrayList<Boolean>()
        val running = object : RunningRelays by FakeRunning(listOf(a, b)) {
            override suspend fun removeRelays(urls: List<String>) { held += lock.isLocked }
            override suspend fun applyRelayModes(modes: Map<String, RelayMode>) { held += lock.isLocked }
            override suspend fun addRelays(urls: List<String>) { held += lock.isLocked }
        }
        assertTrue(SessionRelays(relayStore, modeStore, lock) { running }.resetTo(listOf(b)))
        assertEquals(listOf(true, true, true), held)
        assertFalse(lock.isLocked)
    }

    @Test
    fun `a storage failure on the way back to defaults reaches the caller and leaves the list`() = runTest {
        val modes = mapOf(a to RelayMode.WRITE)
        val f = Fixture(listOf(a, b), modes)
        f.modeStore.failSet = true
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { f.session.resetTo(listOf(b)) } }
        assertEquals(listOf(a, b), f.relayStore.state.value)
        assertEquals(modes, f.modeStore.state.value)
        assertEquals(emptyList<String>(), f.running.calls)
    }

    @Test
    fun `at the start stored modes that leave no reader or writer are ignored`() = runTest {
        // a crash between the two store edits: the modes of the new list with the old list
        val broken = Fixture(listOf(a, b), mapOf(a to RelayMode.READ, b to RelayMode.READ, c to RelayMode.WRITE), active = false)
        val repaired = mapOf(a to RelayMode.BOTH, b to RelayMode.READ)
        assertEquals(repaired, broken.session.startupModes(), "as restrictive as possible: one relay relaxed, the stranger dropped")
        assertEquals(repaired, broken.modeStore.state.value, "the store is repaired, so the settings row and the engine agree")
        assertEquals(listOf(a, b), broken.relayStore.state.value)
        val good = mapOf(a to RelayMode.READ, b to RelayMode.WRITE)
        assertEquals(good, Fixture(listOf(a, b), good, active = false).session.startupModes())
        assertEquals(emptyMap<String, RelayMode>(), Fixture(listOf(a, b), active = false).session.startupModes())
    }

    @Test
    fun `publishing gives the chosen mode, else the published marker, else both`() = runTest {
        val f = Fixture(
            listOf(a, b, c),
            mapOf(a to RelayMode.WRITE),
            published = RelayList(read = listOf(a, b), write = listOf(a)),
        )
        assertEquals(
            listOf(RelayEntry(a, RelayMode.WRITE), RelayEntry(b, RelayMode.READ), RelayEntry(c, RelayMode.BOTH)),
            f.session.entriesForPublish(),
        )
        assertEquals(listOf(false), f.running.freshReads, "the stored list")
    }

    @Test
    fun `a kept marker never publishes a list without a writer, the chosen mode stays`() = runTest {
        // published A=read, B=both, and the user sets B=read
        val published = RelayList(read = listOf(a, b), write = listOf(b))
        val entries = Fixture(listOf(a, b), mapOf(b to RelayMode.READ), published = published).session.entriesForPublish()
        assertEquals(listOf(RelayEntry(a, RelayMode.BOTH), RelayEntry(b, RelayMode.READ)), entries)
        // the same with the chosen relay first in the list
        val reversed = Fixture(listOf(b, a), mapOf(b to RelayMode.READ), published = published).session.entriesForPublish()
        assertEquals(listOf(RelayEntry(b, RelayMode.READ), RelayEntry(a, RelayMode.BOTH)), reversed)
    }

    @Test
    fun `a kept marker never publishes a list without a reader, the chosen mode stays`() = runTest {
        val published = RelayList(read = listOf(b), write = listOf(a, b))
        val entries = Fixture(listOf(b, a), mapOf(b to RelayMode.WRITE), published = published).session.entriesForPublish()
        assertEquals(listOf(RelayEntry(b, RelayMode.WRITE), RelayEntry(a, RelayMode.BOTH)), entries)
    }

    @Test
    fun `a list change is one atomic write of list and modes`() = runTest {
        val f = Fixture(listOf(a, b), mapOf(a to RelayMode.WRITE))
        assertTrue(f.session.apply(listOf(b, c)))
        assertEquals(1, f.modeStore.atomicWrites)
        assertEquals(0, f.modeStore.modeOnlyWrites, "no separate modes write to crash between")
        assertEquals(0, f.relayStore.separateSets)
    }

    @Test
    fun `the prefs store writes list and modes together`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("t.preferences_pb").toFile() }
        val store = PrefsRelayStore(prefs)
        store.setListAndModes(listOf("$a/", b, "not a url"), mapOf(a to RelayMode.READ, b to RelayMode.WRITE))
        assertEquals(listOf(a, b), PrefsRelayStore(prefs).relays.first())
        assertEquals(mapOf(a to RelayMode.READ, b to RelayMode.WRITE), PrefsRelayStore(prefs).modes.first())
        store.setListAndModes(listOf(c), emptyMap())
        assertEquals(listOf(c), store.relays.first())
        assertEquals(emptyMap<String, RelayMode>(), store.modes.first())
        scope.cancel()
    }
}
