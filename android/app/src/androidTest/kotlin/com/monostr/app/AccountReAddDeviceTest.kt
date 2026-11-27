package com.monostr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.EngineDir
import com.monostr.app.data.accountDir
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.io.File

/** Owner 2026-11-27: logged out everywhere and back in without leaving the app; the account got the database that was just deleted. */
@RunWith(AndroidJUnit4::class)
class AccountReAddDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java).session()
    private val keys = Keys.generate()
    private val dir get() = accountDir(ctx.filesDir, keys.publicKey().toHex())
    private fun database() = File(dir, File(dir, EngineDir.MARKER).readText())

    @After fun out() = runBlocking { runCatching { session.logout() }; Unit }

    @Test fun anAccountRemovedAndAddedAgainStartsEmptyAndOnDisk() = runBlocking {
        runCatching { session.logout() }
        session.loginLocal(LocalSigner(keys.secretKey().toHex()))
        session.applyRelays(listOf("ws://127.0.0.1:9"))
        session.requireReady().engine.save(EventBuilder.textNote("before the removal").signWithKeys(keys))
        val before = database()
        assertTrue(File(before, "data.mdb").exists())

        session.logout()
        session.loginLocal(LocalSigner(keys.secretKey().toHex()))
        val engine = session.requireReady().engine
        assertEquals(emptyList<String>(), engine.query(Filter().kind(Kind(1u))).map { it.content() })
        assertNotEquals(before, database())
        engine.save(EventBuilder.textNote("after").signWithKeys(keys))
        assertTrue("the new database is on disk", File(database(), "data.mdb").length() > 0)
    }
}
