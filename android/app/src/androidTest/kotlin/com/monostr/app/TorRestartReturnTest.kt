package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys
import java.io.File

/** What the process before a restart left in its private file, read by the real activity. */
@RunWith(AndroidJUnit4::class)
class TorRestartReturnTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private val file get() = File(ctx.noBackupFilesDir, "tor-return")
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Before fun loggedIn() {
        runBlocking {
            runCatching { entry.session().logout() }
            entry.session().loginLocal(LocalSigner(Keys.generate().secretKey().toHex()))
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
        }
    }

    @After fun out() {
        file.delete()
        runBlocking { runCatching { entry.session().logout() } }
    }

    private fun startWith(route: String, block: () -> Unit) {
        file.writeText(route)
        ActivityScenario.launch(MainActivity::class.java).use { block() }
    }

    @Test
    fun theStoredRouteIsWhereTheNewProcessGoesOnce() = startWith("settings?section=privacy") {
        rule.waitUntil(30_000) { has("settings-tor") }
        assertFalse("taken, one time", file.exists())
    }

    @Test
    fun aRouteThatNoLongerResolvesLandsOnTheFeed() = startWith("gone/since/an/older/version") {
        rule.waitUntil(30_000) { has("fab-compose") }
        rule.waitForIdle()
        assertFalse(has("settings-tor"))
        assertFalse(file.exists())
    }
}
