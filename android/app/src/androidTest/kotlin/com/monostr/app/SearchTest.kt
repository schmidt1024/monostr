package com.monostr.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.model.Profile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Tag

/** Spec 8: hashtag search finds a seeded local note; an npub opens the profile. Both the search relays and the normal session relays point at a dead port, so nothing leaves the device. */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)

    // this test points BOTH the search relays and the normal session relays at a dead port (the
    // hashtag leg goes through the session relays via engine.fetch, not the search relays) and
    // logs in with the shared local-signer secret; it never undoes any of that on its own, so all
    // three are restored here, in order: search relays reset, session relays back to the
    // defaults, then logout - without the logout the session stays Active for whatever test class
    // the runner picks next in the same instrumentation process, e.g. SmokeTest's own login
    // screen would never appear
    @After fun restore() {
        runBlocking {
            runCatching { entry.searchRelays().reset() }
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    private fun reachFeed() {
        rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() || nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("login-secret").isNotEmpty()) {
            rule.onNodeWithTag("login-secret").performTextInput(secret)
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    @Test
    fun hashtagFindsSeededNoteAndNpubOpensProfile() {
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val tag = "monostrtest${System.currentTimeMillis() % 100_000}"
        val text = "seeded #$tag"
        runBlocking {
            entry.searchRelays().set(listOf("ws://127.0.0.1:9"))
            // the hashtag leg (SearchRepository.hashtag()) fetches on the normal session relays,
            // not the search relays above - point those at the same dead port too, so the
            // "nothing leaves the device" claim below actually holds
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.textNote(text).tags(listOf(Tag.hashtag(tag))).signWithKeys(alice))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-$tag","display_name":"Alice Seeded"}""")).signWithKeys(alice))
        }
        rule.onNodeWithTag("tab-search").performClick()
        rule.waitUntil(10_000) { nodes("search-field").isNotEmpty() }
        rule.onNodeWithTag("search-field").performTextInput("#$tag")
        rule.onNodeWithTag("search-field").performImeAction()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("search-list").performScrollToNode(hasText(text))
        rule.onAllNodesWithText(text)[0].assertIsDisplayed()

        // local people search: the seeded profile shows without any relay
        rule.onNodeWithTag("search-field").performTextClearance()
        rule.onNodeWithTag("search-field").performTextInput("alice-$tag")
        rule.onNodeWithTag("search-field").performImeAction()
        val pk8 = alice.publicKey().toHex().take(8)
        rule.waitUntil(15_000) { nodes("search-person-$pk8").isNotEmpty() }

        // npub opens the profile directly
        rule.onNodeWithTag("search-field").performTextClearance()
        rule.onNodeWithTag("search-field").performTextInput(alice.publicKey().toBech32())
        rule.onNodeWithTag("search-field").performImeAction()
        val short = Profile.shortPubkey(alice.publicKey().toHex())
        rule.waitUntil(15_000) { rule.onAllNodesWithText(short).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun recentSearchesListTheLastQueriesAndCanBeRemoved() {
        reachFeed()
        runBlocking {
            entry.searchRelays().set(listOf("ws://127.0.0.1:9"))
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
        }
        rule.onNodeWithTag("tab-search").performClick()
        rule.waitUntil(10_000) { nodes("search-field").isNotEmpty() }
        // two searches, then the empty field lists them newest first (spec 11.4 §2)
        rule.onNodeWithTag("search-field").performTextInput("monero")
        rule.onNodeWithTag("search-field").performImeAction()
        rule.waitUntil(15_000) { nodes("search-field").isNotEmpty() }
        rule.onNodeWithTag("search-field").performTextClearance()
        rule.onNodeWithTag("search-field").performTextInput("#nostr")
        rule.onNodeWithTag("search-field").performImeAction()
        rule.waitUntil(15_000) { nodes("search-field").isNotEmpty() }
        rule.onNodeWithTag("search-field").performTextClearance()
        rule.waitUntil(15_000) { nodes("search-recent-1").isNotEmpty() }
        rule.onNodeWithTag("search-recent-0").assertTextContains("#nostr")
        rule.onNodeWithTag("search-recent-1").assertTextContains("monero")
        // the X takes one entry out
        rule.onNodeWithTag("search-recent-remove-0").performClick()
        rule.waitUntil(5_000) { nodes("search-recent-1").isEmpty() }
        rule.onNodeWithTag("search-recent-0").assertTextContains("monero")
        // a tapped entry fills the field and runs; the list is gone while the field is filled
        rule.onNodeWithTag("search-recent-0").performClick()
        rule.waitUntil(15_000) { nodes("search-recent-0").isEmpty() }
        rule.onNodeWithTag("search-field").assertTextContains("monero")
        // and "clear all" empties the list
        rule.onNodeWithTag("search-field").performTextClearance()
        rule.waitUntil(15_000) { nodes("search-recent-clear").isNotEmpty() }
        rule.onNodeWithTag("search-recent-clear").performClick()
        rule.waitUntil(5_000) { nodes("search-recent-0").isEmpty() }
    }
}
