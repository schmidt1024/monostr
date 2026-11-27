package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AccountScopedStoresTest {
    @TempDir lateinit var dir: Path

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private fun file(name: String, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("$name.preferences_pb").toFile() }

    private fun prefs() = AccountPrefs(
        global = file("global", CoroutineScope(SupervisorJob() + Dispatchers.IO)),
        open = { pk, scope -> file("monostr-$pk", scope) },
        deleteFile = { pk -> dir.resolve("monostr-$pk.preferences_pb").toFile().delete() },
    )

    @Test
    fun `relays dm relays tips and accent are per account`() = runTest {
        val p = prefs()
        val relays = PrefsRelayStore(p)
        val dm = PrefsDmSettingsStore(p)
        val tips = PrefsTipSettingsStore(p)
        val ui = PrefsUiSettingsStore(p)
        p.activate(a)
        relays.set(listOf("wss://a.example"))
        dm.setRelays(listOf("wss://dm-a.example"))
        tips.setPresets(listOf(111L, 222L))
        ui.setAccent(Accent.ORANGE)

        p.activate(b)
        assertEquals(PrefsRelayStore.DEFAULT_RELAYS, relays.relays.first())
        assertEquals(PrefsDmSettingsStore.DEFAULT_RELAYS, dm.relays.first())
        assertEquals(Presets.DEFAULT, tips.presets.first())
        assertEquals(Accent.DEFAULT, ui.accent.first())

        p.activate(a)
        assertEquals(listOf("wss://a.example"), relays.relays.first())
        assertEquals(listOf("wss://dm-a.example"), dm.relays.first())
        assertEquals(listOf(111L, 222L), tips.presets.first())
        assertEquals(Accent.ORANGE, ui.accent.first())
    }

    /**
     * Final review M2: a store keyed by a pubkey writes to that account's own file, never to the one active at call time. A
     * composer's last save of A that runs after the switch to B lands in A's file; once A is removed nothing brings its file back.
     */
    @Test
    fun `a draft of A saved after the switch to B lands in A's file, never in B's`() = runTest {
        val p = prefs()
        val drafts = PrefsDraftStore(p)
        val setup = PrefsPendingSetupStore(p)
        val tabs = PrefsFeedTabsStore(p)
        p.activate(a)
        drafts.save(a, DraftTargets.NOTE, Draft("first words", updatedAt = 1))
        p.activate(b)
        drafts.save(a, DraftTargets.NOTE, Draft("the last edit", updatedAt = 2))
        setup.save(a, com.monostr.app.session.PendingSetup("A", emptyList(), emptyList(), emptyList()))
        tabs.save(a, com.monostr.nostr.repo.CachedTabs(listOf("t"), 3))
        assertEquals(emptyList<String>(), p.storeFor(b).data.first().asMap().keys.map { it.name })
        assertEquals("the last edit", drafts.load(a, DraftTargets.NOTE)?.text)
        assertEquals(setOf("draft.$a.note", "pending_setup.$a", "feed_tabs.$a"), p.storeFor(a).data.first().asMap().keys.map { it.name }.toSet())
        // A removed: a save still in flight writes nothing, its file stays gone
        p.delete(a)
        drafts.save(a, DraftTargets.NOTE, Draft("too late", updatedAt = 4))
        setup.save(a, com.monostr.app.session.PendingSetup("A", emptyList(), emptyList(), emptyList()))
        tabs.save(a, com.monostr.nostr.repo.CachedTabs(listOf("t"), 5))
        assertFalse(dir.resolve("monostr-${pk16(a)}.preferences_pb").toFile().exists())
        assertEquals(emptyList<String>(), p.storeFor(b).data.first().asMap().keys.map { it.name })
    }

    /** The global file is for the account registry only; every other store goes through AccountPrefs. */
    @Test
    fun onlyRegistryUsesGlobalPrefs() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val allowed = setOf("app/src/main/kotlin/com/monostr/app/di/AppModule.kt", "app/src/main/kotlin/com/monostr/app/data/Prefs.kt")
        val found = File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            val path = f.relativeTo(root).path
            if (path in allowed) emptyList() else f.readLines().withIndex().filter { (_, l) -> "monostrPrefs" in l }.map { (i, l) -> "$path:${i + 1}: ${l.trim()}" }
        }.toList()
        assertEquals(emptyList<String>(), found)
    }
}
