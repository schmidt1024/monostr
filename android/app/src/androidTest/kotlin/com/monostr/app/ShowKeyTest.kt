package com.monostr.app

import android.view.WindowManager
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/**
 * Spec 2026-10-17 §6/§11: Settings → "Show key" for a key login asks first, then shows the nsec on a
 * FLAG_SECURE screen; leaving it clears the flag. The emulator has no screen lock, so no credential
 * prompt. The loopback relay is the only relay, so the fresh account stays on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ShowKeyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = Keys.generate().secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private lateinit var relay: LoopbackRelay

    @Before fun setUp() {
        relay = LoopbackRelay()
        runBlocking {
            entry.session().logout()
            entry.session().applyRelays(listOf(relay.url))
        }
    }

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
        relay.close()
    }

    private fun secure(): Boolean {
        var flags = 0
        rule.runOnUiThread { flags = rule.activity.window.attributes.flags }
        return (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }

    private fun loginFresh() {
        rule.waitUntil(60_000) { nodes("login-secret").isNotEmpty() }
        rule.onNodeWithTag("login-secret").performTextInput(secret)
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    /** Settings → "Show key" → warning → confirm (no screen lock on the emulator) → the key screen. */
    private fun openShowKey() {
        rule.onNodeWithContentDescription(ctx.getString(R.string.feed_settings)).performClick()
        rule.waitUntil(15_000) { nodes("settings-show-key").isNotEmpty() }
        rule.onNodeWithTag("settings-show-key").performScrollTo().performClick()
        rule.waitUntil(5_000) { nodes("show-key-warning").isNotEmpty() }
        rule.onNodeWithText(ctx.getString(R.string.settings_show_key_warning)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.settings_show_key_confirm)).performClick()
        rule.waitUntil(10_000) { nodes("show-key-nsec").isNotEmpty() }
    }

    @Test
    fun showKeyAsksThenShowsTheNsecOnASecureScreen() {
        loginFresh()
        openShowKey()
        rule.waitForIdle()
        rule.onNodeWithTag("show-key-nsec").assertTextEquals(Keys.parse(secret).secretKey().toBech32())
        assertTrue(secure())

        Espresso.pressBack()
        rule.waitUntil(10_000) { nodes("settings-show-key").isNotEmpty() && nodes("show-key-nsec").isEmpty() }
        rule.waitForIdle()
        assertFalse(secure())
    }

    /** Spec §6: no key without the gate. Leaving the app (here: the stop of a recreation) closes the screen. */
    @Test
    fun recreatingTheActivityClosesTheKeyScreen() {
        loginFresh()
        openShowKey()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(15_000) { nodes("settings-show-key").isNotEmpty() }
        rule.waitForIdle()
        assertTrue(nodes("show-key-nsec").isEmpty())
        assertFalse(secure())
    }
}
