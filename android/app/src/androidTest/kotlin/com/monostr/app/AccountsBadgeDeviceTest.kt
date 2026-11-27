package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.monostrPrefs
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.SwitchResult
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/** Spec 2026-11-25 (owner pick C): the badge on the feed avatar with two accounts, the one-time hint, and the unchanged gestures. */
@RunWith(AndroidJUnit4::class)
class AccountsBadgeDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private val hintKey = booleanPreferencesKey("accounts.switch_hint_seen")

    private val keyA = Keys.generate()
    private val keyB = Keys.generate()

    @Before fun loggedInAsA() {
        runBlocking {
            runCatching { entry.session().logout() }
            ctx.monostrPrefs.edit { it.remove(hintKey) }
            entry.session().loginLocal(LocalSigner(keyA.secretKey().toHex()))
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
        }
    }

    @After fun out() {
        runBlocking { runCatching { entry.session().logout() } }
    }

    private fun waitForFeed() {
        rule.waitUntil(60_000) { has("fab-compose") || has("setup-skip") }
        if (has("setup-skip")) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { has("fab-compose") }
        }
        rule.waitUntil(15_000) { has("feed-profile") }
    }

    private fun addB() = runBlocking {
        entry.session().loginLocal(LocalSigner(keyB.secretKey().toHex()))
        assertEquals(SwitchResult.Done, entry.session().switchTo(keyA.publicKey().toHex()))
    }

    @Test
    fun noBadgeAndNoHintWithOneAccount() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForFeed()
            Thread.sleep(1_500)
            assertFalse(has("feed-accounts-badge"))
            assertFalse(has("switch-hint"))
        }
    }

    @Test
    fun badgeHintOnceAndGesturesWithTwoAccounts() {
        addB()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForFeed()
            rule.waitUntil(15_000) { has("feed-accounts-badge") }
            // the hint shows the first time, and goes by itself after about 6 s
            rule.waitUntil(10_000) { has("switch-hint") }
            rule.waitUntil(12_000) { !has("switch-hint") }
            // a long-press still opens the switcher
            rule.onNodeWithTag("feed-profile").performTouchInput { longClick() }
            rule.waitUntil(10_000) { has("account-switcher") }
            androidx.test.espresso.Espresso.pressBack()
            rule.waitUntil(10_000) { !has("account-switcher") }
            // never again: not after an activity restart either
            scenario.recreate()
            waitForFeed()
            rule.waitUntil(15_000) { has("feed-accounts-badge") }
            Thread.sleep(1_500)
            assertFalse("the hint came back", has("switch-hint"))
            // a tap still opens the profile
            rule.onNodeWithTag("feed-profile").performClick()
            rule.waitUntil(10_000) { !has("feed-profile") }
        }
    }

    @Test
    fun aTapEndsTheHintAndStillWorks() {
        addB()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForFeed()
            rule.waitUntil(15_000) { has("switch-hint") }
            rule.onNodeWithTag("feed-profile").performClick()
            rule.waitUntil(10_000) { !has("feed-profile") }
            assertFalse(has("switch-hint"))
        }
    }
}
