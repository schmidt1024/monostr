package com.monostr.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.ui.settings.SETTINGS_INDEX
import com.monostr.nostr.LocalSigner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys
import java.io.File

/** Spec 2026-11-11: the Settings overview, its search, and the deep link that must not trap Back. */
@RunWith(AndroidJUnit4::class)
class SettingsOverviewTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private val file get() = File(ctx.noBackupFilesDir, "tor-return")
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun app(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { block() }
    }

    private fun openOverview() {
        rule.waitUntil(60_000) { has("fab-compose") || has("setup-skip") }
        if (has("setup-skip")) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { has("fab-compose") }
        }
        rule.openSettings(ctx)
    }

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

    @Test
    fun torIsOnTheFirstScreenWithItsChip() = app {
        openOverview()
        // a chip row is one node read as words (UX P1-4): the chip text is in its description, the texts are not read twice
        rule.onNodeWithTag("settings-cat-privacy").assertIsDisplayed()
            .assert(androidx.compose.ui.test.hasContentDescription(ctx.getString(R.string.settings_chip_tor_off), substring = true))
    }

    @Test
    fun searchTorOpensPrivacyWithTorVisible() = app {
        openOverview()
        rule.onNodeWithTag("settings-search").performTextInput("tor")
        rule.waitUntil(10_000) { has("settings-result-settings-tor") }
        rule.onNodeWithTag("settings-result-settings-tor").performClick()
        rule.waitUntil(10_000) { has("settings-tor") }
        rule.onNodeWithTag("settings-tor").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(10_000) { has("settings-search") }
        rule.onNodeWithTag("settings-search").assertIsDisplayed()
    }

    @Test
    fun searchWithoutHitsSaysSo() = app {
        openOverview()
        rule.onNodeWithTag("settings-search").performTextInput("qqqq")
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(ctx.getString(R.string.settings_search_none))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(ctx.getString(R.string.settings_search_none)).assertIsDisplayed()
    }

    @Test
    fun searchResultFocusesTheSettingOnItsPage() = app {
        openOverview()
        rule.onNodeWithTag("settings-search").performTextInput("pow")
        rule.waitUntil(10_000) { has("settings-result-settings-pow-filter") }
        rule.onNodeWithTag("settings-result-settings-pow-filter").performClick()
        rule.waitUntil(10_000) { has("settings-pow-filter") }
        rule.waitForIdle()
        rule.onNodeWithTag("settings-pow-filter").assertIsDisplayed()
    }

    @Test
    fun keyLoginSearchOffersShowKeyNotAmber() = app {
        openOverview()
        rule.onNodeWithTag("settings-search").performTextInput("key")
        rule.waitUntil(10_000) { has("settings-result-settings-show-key") }
        rule.onNodeWithTag("settings-result-settings-show-key").assertIsDisplayed()
        rule.onNodeWithTag("settings-search").performTextClearance()
        rule.onNodeWithTag("settings-search").performTextInput("amber")
        rule.waitUntil(10_000) { has("settings-result-page-account") }
        assertTrue("no Amber renewal for a key login", !has("settings-result-settings-amber-renew"))
    }

    @Test
    fun moneroResultsNeedASetup() = app {
        openOverview()
        rule.onNodeWithTag("settings-search").performTextInput("watcher")
        // a fresh account has no setup: «Set up» is offered, «Switch watcher» (only on a set-up page) is not
        rule.waitUntil(10_000) { has("settings-result-settings-monero-setup") }
        rule.waitForIdle()
        assertTrue("no switch-watcher result without a setup", !has("settings-result-settings-monero-watcher"))
        rule.onNodeWithTag("settings-search").performTextClearance()
        rule.onNodeWithTag("settings-search").performTextInput("disable")
        rule.waitForIdle()
        assertTrue("no disable result without a setup", !has("settings-result-settings-monero-disable"))
    }

    @Test
    fun deepLinkThenBackStaysOnOverview() {
        file.writeText("settings?section=translation")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.waitUntil(30_000) { has("settings-page-translation") }
            scenario.recreate()
            rule.waitUntil(15_000) { has("settings-page-translation") }
            androidx.test.espresso.Espresso.pressBack()
            rule.waitUntil(15_000) { has("settings-cat-privacy") }
            rule.waitForIdle()
            assertTrue("no re-redirect", has("settings-cat-privacy") && !has("settings-page-translation"))
        }
    }

    @Test
    fun everyIndexedStringResolves() {
        SETTINGS_INDEX.forEach { e ->
            assertTrue("title of ${e.key}", ctx.getString(e.title).isNotBlank())
            e.hint?.let { assertTrue("hint of ${e.key}", ctx.getString(it).isNotBlank()) }
            assertTrue("page title of ${e.key}", ctx.getString(e.page.title).isNotBlank())
        }
    }

    @Test
    fun signOutAsksFirstAndOffersTheKey() = app {
        openOverview()
        rule.onNodeWithTag("settings-logout").performScrollTo().performClick()
        // UX P1-5: a key login is warned and offered «Show key» instead of Cancel
        rule.waitUntil(10_000) { has("settings-logout-confirm") }
        assertTrue("show key offered for a key login", has("settings-logout-show-key"))
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(10_000) { !has("settings-logout-confirm") }
        assertTrue("still logged in after cancel", entry.session().state.value is com.monostr.app.session.SessionState.Active)
        rule.onNodeWithTag("settings-cat-privacy").assertIsDisplayed()
        rule.onNodeWithTag("settings-logout").performScrollTo().performClick()
        rule.waitUntil(10_000) { has("settings-logout-confirm") }
        rule.onNodeWithTag("settings-logout-confirm").performClick()
        rule.waitUntil(20_000) { entry.session().state.value !is com.monostr.app.session.SessionState.Active }
    }

    @Test
    fun deleteViewKeyAsksFirst() {
        val secrets = com.monostr.app.data.KeystoreSecretStore(ctx)
        secrets.put(com.monostr.app.data.KeystoreSecretStore.SECRET_VIEW_KEY, "00".repeat(32))
        try {
            app {
                rule.waitUntil(60_000) { has("fab-compose") || has("setup-skip") }
                if (has("setup-skip")) {
                    rule.onNodeWithTag("setup-skip").performClick()
                    rule.waitUntil(15_000) { has("fab-compose") }
                }
                rule.openSettingsPage(ctx, com.monostr.app.ui.settings.SettingsPage.MONERO)
                rule.waitUntil(15_000) { has("settings-monero-delete-view-key") }
                rule.onNodeWithTag("settings-monero-delete-view-key").performScrollTo().performClick()
                rule.waitUntil(10_000) { has("settings-monero-delete-view-key-confirm") }
                rule.onNodeWithTag("settings-confirm-cancel").performClick()
                rule.waitUntil(10_000) { !has("settings-monero-delete-view-key-confirm") }
                assertTrue("cancel keeps the key", secrets.get(com.monostr.app.data.KeystoreSecretStore.SECRET_VIEW_KEY) != null)
                assertTrue("row still there", has("settings-monero-delete-view-key"))
                rule.onNodeWithTag("settings-monero-delete-view-key").performScrollTo().performClick()
                rule.waitUntil(10_000) { has("settings-monero-delete-view-key-confirm") }
                rule.onNodeWithTag("settings-monero-delete-view-key-confirm").performClick()
                rule.waitUntil(10_000) { !has("settings-monero-delete-view-key") }
                assertTrue("confirm deletes the key", secrets.get(com.monostr.app.data.KeystoreSecretStore.SECRET_VIEW_KEY) == null)
            }
        } finally {
            secrets.remove(com.monostr.app.data.KeystoreSecretStore.SECRET_VIEW_KEY)
        }
    }
}
