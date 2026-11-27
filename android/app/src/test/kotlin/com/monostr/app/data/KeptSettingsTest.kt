package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.monostr.app.ui.FakeSecrets
import com.monostr.nostr.Signer
import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Backlog 121: an account's settings stay as a package only its own key opens. */
class KeptSettingsTest {
    @TempDir lateinit var tmp: File
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stores = HashMap<String, DataStore<Preferences>>()
    private fun store(pubkey: String) = stores.getOrPut(pubkey) { PreferenceDataStoreFactory.create(scope = scope) { File(tmp, "prefs-$pubkey.preferences_pb") } }
    private val secrets = FakeSecrets()
    private val kept by lazy { KeptSettings(File(tmp, "kept"), secrets, ::store) }

    @AfterEach fun done() = scope.cancel()

    /** Stands for NIP-44 to oneself: what one key sealed only that key opens. [declines]: an Amber that says no. */
    private class SelfSigner(override val pubkey: String, var declines: Boolean = false) : Signer {
        var requests = 0
        override suspend fun sign(event: UnsignedEvent): Event = error("not used")
        override suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String {
            requests++
            if (declines) throw IllegalStateException("declined")
            require(peerPubkey == pubkey)
            return "$pubkey|$plaintext"
        }
        override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String {
            requests++
            if (declines) throw IllegalStateException("declined")
            require(payload.startsWith("$pubkey|")) { "not mine" }
            return payload.substringAfter('|')
        }
        override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = error("not used")
    }

    private suspend fun fill(pubkey: String) {
        store(pubkey).edit {
            it[stringPreferencesKey("ui.theme")] = "DARK"
            it[booleanPreferencesKey("media.blur")] = false
            it[longPreferencesKey("monero.registered_at")] = 1_795_000_000L
            it[stringSetPreferencesKey("relays")] = setOf("wss://one.example", "wss://two.example")
            it[stringPreferencesKey("pending_setup.$pubkey")] = "{}"
        }
        secrets.put(SecretNames.viewKey(pubkey), "11".repeat(32))
    }

    private fun packages() = File(tmp, "kept").listFiles().orEmpty().toList()

    @Test
    fun `settings and view key come back after the account's files are gone, with one request each way`() = runBlocking<Unit> {
        fill(alice)
        val signer = SelfSigner(alice)
        kept.keep(signer)
        assertEquals(1, signer.requests)
        // the removal
        store(alice).edit { it.clear() }
        secrets.remove(SecretNames.viewKey(alice))
        assertTrue(kept.exists(alice))

        assertTrue(kept.restore(signer))
        assertEquals(2, signer.requests)
        val back = store(alice).data.first()
        assertEquals("DARK", back[stringPreferencesKey("ui.theme")])
        assertEquals(false, back[booleanPreferencesKey("media.blur")])
        assertEquals(1_795_000_000L, back[longPreferencesKey("monero.registered_at")])
        assertEquals(setOf("wss://one.example", "wss://two.example"), back[stringSetPreferencesKey("relays")])
        assertNull(back[stringPreferencesKey("pending_setup.$alice")], "a pending setup is not kept")
        assertEquals("11".repeat(32), secrets.get(SecretNames.viewKey(alice)))
        kept.drop(alice)
        assertFalse(kept.exists(alice))
        assertEquals(emptyList<File>(), packages())
    }

    @Test
    fun `the package shows neither the pubkey nor a setting nor the view key`() = runBlocking<Unit> {
        fill(alice)
        kept.keep(SelfSigner(alice))
        val file = packages().single()
        assertFalse(alice.take(16) in file.name)
        val text = file.readText().replace(alice, "") // the stand-in signer writes the pubkey, NIP-44 does not
        for (secret in listOf("DARK", "one.example", "11".repeat(32), "ui.theme")) assertFalse(secret in text, secret)
        assertFalse(secrets.get(KeptSettings.SALT)!! in file.name)
    }

    @Test
    fun `another account neither finds nor opens the package`() = runBlocking<Unit> {
        fill(alice)
        kept.keep(SelfSigner(alice))
        assertFalse(kept.exists(bob))
        assertFalse(kept.restore(SelfSigner(bob)))
        // even at the right name: the key does not open it
        packages().single().copyTo(File(tmp, "kept/copy"))
        val stolen = KeptSettings(File(tmp, "kept"), secrets, ::store)
        assertFalse(stolen.restore(SelfSigner(bob)))
        assertTrue(store(bob).data.first().asMap().isEmpty())
    }

    @Test
    fun `a signer that declines keeps nothing and says so, and a declined restore leaves the package`() = runBlocking<Unit> {
        fill(alice)
        assertThrows(KeepSettingsFailedException::class.java) { runBlocking { kept.keep(SelfSigner(alice, declines = true)) } }
        assertEquals(emptyList<File>(), packages())
        kept.keep(SelfSigner(alice))
        store(alice).edit { it.clear() }
        assertFalse(kept.restore(SelfSigner(alice, declines = true)))
        assertTrue(kept.exists(alice))
        assertTrue(store(alice).data.first().asMap().isEmpty())
    }

    @Test
    fun `a damaged package restores nothing`() = runBlocking<Unit> {
        fill(alice)
        kept.keep(SelfSigner(alice))
        store(alice).edit { it.clear() }
        packages().single().let { it.writeText(it.readText().replace("\"data\":\"", "\"data\":\"AAAA")) }
        assertFalse(kept.restore(SelfSigner(alice)))
        assertTrue(store(alice).data.first().asMap().isEmpty())
    }
}
