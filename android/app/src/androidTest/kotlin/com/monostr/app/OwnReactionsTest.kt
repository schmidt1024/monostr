package com.monostr.app

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.repo.NostrOwnReactions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey

/** v0.13.9: a like is remembered (also one stored before the session started), shows on every screen and is sent once. */
@RunWith(AndroidJUnit4::class)
class OwnReactionsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @After fun logout() { runBlocking { runCatching { entry.session().logout() } } }

    @Test
    fun aLikeIsRememberedSharedAndSentOnce() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
        val ready = entry.session().requireReady()
        val fresh = EventBuilder.textNote("own reactions fresh note").signWithKeys(alice)
        val earlier = EventBuilder.textNote("own reactions liked earlier").signWithKeys(alice)
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(fresh)
            ready.engine.save(earlier)
            // a like from before this session (another device, an earlier start): read as a start reads it
            ready.engine.save(EventBuilder.reaction(earlier, "+").signWithKeys(me))
            (ready.ownReactions as NostrOwnReactions).load(fetch = false)
        }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
        rule.waitUntil(30_000) { rule.onAllNodesWithText("own reactions fresh note").fetchSemanticsNodes().isNotEmpty() }
        val freshTag = "note-like-${fresh.id().toHex().take(8)}"
        rule.waitUntil(15_000) { nodes("note-like-${earlier.id().toHex().take(8)}").isNotEmpty() }
        rule.onNodeWithTag("note-like-${earlier.id().toHex().take(8)}").assertIsSelected()

        rule.onNodeWithTag(freshTag).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(freshTag).assertIsSelected()
        rule.onNodeWithTag(freshTag).performClick() // a second tap sends nothing
        rule.waitForIdle()
        val likes = runBlocking {
            ready.engine.query(Filter().kind(Kind(7u)).author(PublicKey.parse(me.publicKey().toHex()))).count { ev ->
                ev.tags().toVec().any { t -> t.asVec().let { it.size >= 2 && it[0] == "e" && it[1] == fresh.id().toHex() } }
            }
        }
        assertEquals(1, likes)

        // the thread is another screen with its own controller: it shows the like as well
        rule.onAllNodesWithText("own reactions fresh note")[0].performClick()
        rule.waitUntil(15_000) { nodes("thread-list").isNotEmpty() }
        rule.waitUntil(15_000) { nodes(freshTag).isNotEmpty() }
        rule.onNodeWithTag(freshTag).assertIsSelected()
    }
}
