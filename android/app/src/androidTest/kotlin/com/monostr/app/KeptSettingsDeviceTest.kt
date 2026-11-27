package com.monostr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.KeptSettings
import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.SecretNames
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.work.TipCheckWorker
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys
import java.io.File

/** Backlog 121 with the real session, key store and NIP-44: «Log out» with the switch on, then the same key again. */
@RunWith(AndroidJUnit4::class)
class KeptSettingsDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private val session get() = entry.session()
    private val tips get() = EntryPointAccessors.fromApplication(ctx, TipCheckWorker.Deps::class.java).settings()
    private val secrets by lazy { KeystoreSecretStore(ctx) }
    private val keys = Keys.generate()
    private val pubkey = keys.publicKey().toHex()
    private fun login() = runBlocking { session.loginLocal(LocalSigner(keys.secretKey().toHex())) }
    private fun packages() = File(ctx.noBackupFilesDir, KeptSettings.DIR).listFiles().orEmpty().toList()

    @After fun out() = runBlocking {
        runCatching { session.removeAccount(pubkey) }
        runCatching { session.logout() }
        Unit
    }

    @Test fun settingsRelaysAndViewKeyComeBackWithTheSameKeyAndNotWithoutTheSwitch() = runBlocking {
        runCatching { session.logout() }
        val before = packages().size
        login()
        session.applyRelays(listOf("ws://127.0.0.1:9"))
        tips.setOnboardingSeen()
        secrets.put(SecretNames.viewKey(pubkey), "22".repeat(32))

        session.removeAccount(pubkey, keepSettings = true)
        assertNull("the view key left with the account", secrets.get(SecretNames.viewKey(pubkey)))
        assertEquals(before + 1, packages().size)
        assertFalse("no pubkey in the package's name", packages().any { pubkey.take(16) in it.name })

        login()
        assertTrue("the intro was seen before", tips.onboardingSeen.first())
        assertEquals(listOf("ws://127.0.0.1:9"), entry.relayStore().relays.first())
        assertEquals("22".repeat(32), secrets.get(SecretNames.viewKey(pubkey)))
        assertEquals("taken, so gone", before, packages().size)

        // without the switch nothing stays, also no older package
        session.removeAccount(pubkey, keepSettings = true)
        login()
        session.removeAccount(pubkey, keepSettings = false)
        assertEquals(before, packages().size)
        login()
        assertFalse(tips.onboardingSeen.first())
        assertNull(secrets.get(SecretNames.viewKey(pubkey)))
    }
}
