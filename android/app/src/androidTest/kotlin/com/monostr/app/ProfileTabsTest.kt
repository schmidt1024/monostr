package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys

/** Profile tabs: "Posts" leaves the own reply out, "Replies" shows it alone, with its "Replying to" line. */
@RunWith(AndroidJUnit4::class)
class ProfileTabsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun texts(text: String) = rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun logout() { runBlocking { runCatching { entry.session().logout() } } }

    private fun reachFeed() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        rule.onNodeWithTag("login-secret").performTextInput(secret)
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    @Test
    fun postsAndRepliesShowInTheirOwnTab() {
        reachFeed()
        val ready = entry.session().requireReady()
        val other = Keys.generate()
        val root = EventBuilder.textNote("tabs root of another account").signWithKeys(other)
        runBlocking {
            ready.engine.save(root)
            ready.engine.save(EventBuilder.textNote("tabs own post").signWithKeys(me))
        }
        val reply = EventBuilder.textNoteReply("tabs own reply", root, null, null).signWithKeys(me)
        runBlocking { ready.engine.save(reply) }
        rule.onNodeWithTag("feed-profile").performClick()
        rule.waitUntil(15_000) { nodes("profile-tabs").isNotEmpty() }
        rule.waitUntil(15_000) { texts("tabs own post").isNotEmpty() }
        assertTrue("the reply shows under Posts", texts("tabs own reply").isEmpty())
        rule.onNodeWithTag("profile-tab-replies").performClick()
        rule.waitUntil(15_000) { texts("tabs own reply").isNotEmpty() }
        assertTrue("the post shows under Replies", texts("tabs own post").isEmpty())
        // v0.13.1: the row names whom it answers
        rule.waitUntil(15_000) { nodes("note-reply-to-${reply.id().toHex().take(8)}").isNotEmpty() }
    }
}
