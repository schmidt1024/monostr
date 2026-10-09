package com.monostr.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata

/** Spec 8: the bookmark toggle publishes the private list, the bookmarks screen lists the note, toggling again removes it. Needs network (default relays). */
@RunWith(AndroidJUnit4::class)
class BookmarkTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    /** A fresh key per run: the kind 10003 this test publishes to the real relays never lands on a shared, well-known account. */
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun str(id: Int) = ctx.getString(id)

    // this test logs in with its own local-signer secret and never logs out on its own;
    // without this, the session stays Active for whatever test class the runner picks next in
    // the same instrumentation process, e.g. SmokeTest's own login screen would never appear
    @After fun logout() { runBlocking { runCatching { entry.session().logout() } } }

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
    fun toggleBookmarkShowsInListAndRemovesAgain() {
        // a previous (aborted) test may have left another account logged in; this test needs its own key
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        reachFeed()
        val ready = entry.session().requireReady()
        assertEquals("logged in with this run's key", me.publicKey().toHex(), ready.engine.pubkey)
        val alice = Keys.generate()
        val note = EventBuilder.textNote("Bookmark me ${System.currentTimeMillis()}").signWithKeys(alice)
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(note)
            // a locally-seeded profile too: alice's key has no metadata anywhere on the real
            // relays, so leaving this out means every render of her note pays a live 8s relay
            // timeout in ProfileRepository.prefetch (it never finds anything to cache and so
            // never stops retrying) - pure incidental network flake, unrelated to what this test
            // actually needs network for (the kind 10003 publish)
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-bookmark-test"}""")).signWithKeys(alice))
        }
        val text = note.content()
        var attempts = 0
        // unlike TipFlowTest (dead relay, refresh fails fast), this test keeps the default
        // relays: refresh() has to connect to them for real before the local-first read
        // surfaces alice's note, which on a cold login can take longer than one round; give it
        // more swipes and a longer per-attempt wait
        while (rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && attempts < 6) {
            rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
            attempts++
            runCatching { rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        }
        rule.onNodeWithTag("feed-list").performScrollToNode(hasText(text))
        val id8 = note.id().toHex().take(8)
        rule.onNodeWithTag("note-bookmark-$id8").performClick()
        // the icon flips at once (optimistic); a failed publish would flip it back and show the snackbar
        rule.waitUntil(30_000) { rule.onAllNodes(hasTestTag("note-bookmark-$id8") and hasStateDescription(str(R.string.note_bookmarked))).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) { runBlocking { ready.bookmarks.state.value.ids.contains(note.id().toHex()) } && rule.onAllNodesWithText(str(R.string.bookmark_failed)).fetchSemanticsNodes().isEmpty() }
        // the icon flips before the relays answer; the list is stored locally only once a relay accepted it
        val listFilter = Filter().kind(Kind(10003u)).author(me.publicKey()).limit(1u)
        rule.waitUntil(30_000) { runBlocking { ready.engine.query(listFilter) }.isNotEmpty() }
        val stored = runBlocking { ready.engine.query(listFilter) }
        assertEquals(1, stored.size)
        assertTrue("content must be NIP-44, never the note id in clear", !stored[0].content().contains(note.id().toHex()))

        rule.onNodeWithTag("tab-bookmarks").performClick()
        rule.waitUntil(15_000) { nodes("bookmarks-list").isNotEmpty() }
        rule.onNodeWithTag("bookmarks-list").performScrollToNode(hasText(text))
        rule.onAllNodesWithText(text)[0].assertIsDisplayed()
        rule.onNodeWithTag("note-bookmark-$id8").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
        assertFalse(runBlocking { ready.bookmarks.state.value.ids.contains(note.id().toHex()) })
    }
}
