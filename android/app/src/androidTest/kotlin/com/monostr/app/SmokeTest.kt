package com.monostr.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Launches the real app on the device: the login screen renders, a local
 * key logs in (Keystore-backed secret store, rust-nostr engine with LMDB),
 * and the feed screen appears. Runs against whatever relays are configured;
 * the assertions do not need network.
 *
 * State is cleared in [clearState], a `@BeforeClass`, not an instance
 * `@Before`: JUnit4 runs `@BeforeClass` before the class's `@Rule`s are
 * applied, so it runs before `createAndroidComposeRule` launches the
 * activity. An instance `@Before` would run after the rule already launched
 * the activity, which would be too late to affect this run's session
 * restore.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun clearState() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.getSharedPreferences("secrets", 0).edit().clear().commit()
            File(ctx.filesDir, "datastore/monostr.preferences_pb").delete()
        }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginWithLocalKeyReachesFeed() {
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("login-secret")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("login-secret").assertIsDisplayed()
        rule.onNodeWithTag("login-secret").performTextInput("0000000000000000000000000000000000000000000000000000000000000001")
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasTestTag("fab-compose")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasTestTag("setup-skip")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasTestTag("setup-skip")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("fab-compose")).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithTag("fab-compose").assertIsDisplayed()
        rule.onNodeWithTag("feed-wordmark").assertIsDisplayed() // the wordmark image in the top bar
        // spec 11.1: five tabs, bookmarks in the middle; the feed's top bar no longer carries a bookmark icon
        for (tag in listOf("tab-feed", "tab-search", "tab-bookmarks", "tab-notifications", "tab-messages")) rule.onNodeWithTag(tag).assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag("feed-bookmarks")).fetchSemanticsNodes().isEmpty())
        // the screen's title is the one node that is there whether the list is empty, loading or filled
        val bookmarksTitle = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.bookmarks_title)
        rule.onNodeWithTag("tab-bookmarks").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(bookmarksTitle).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tab-bookmarks").performClick() // the active tab does nothing
        rule.onNodeWithText(bookmarksTitle).assertIsDisplayed()
        // one back press lands on the feed: the second tap pushed nothing, and bookmarks sit directly above the feed
        Espresso.pressBack()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("fab-compose")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("fab-compose").assertIsDisplayed()
        rule.onNodeWithTag("tab-bookmarks").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(bookmarksTitle).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tab-feed").performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("fab-compose")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("fab-compose").assertIsDisplayed()
    }
}
