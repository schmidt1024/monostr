package com.monostr.app

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
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

/** Emoji reactions (2026-10-11): the user's own quick list in their order, a long press reacts, the emoji replaces the heart. */
@RunWith(AndroidJUnit4::class)
class ReactionsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @After fun cleanUp() {
        runBlocking {
            runCatching { entry.session().logout() }
            entry.uiSettings().setQuickReactions(emptyList())
        }
    }

    @Test
    fun ownOrderInSettingsThenALongPressReacts() {
        runBlocking { runCatching { entry.session().logout() }; entry.uiSettings().setQuickReactions(listOf("🔥", "🚀")) }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }

        // Settings → Reactions: move 🚀 to the front
        rule.onNodeWithContentDescription(ctx.getString(R.string.feed_settings)).performClick()
        rule.waitUntil(15_000) { nodes("settings-reaction-up-1").isNotEmpty() }
        rule.onNodeWithTag("settings-reaction-up-1").performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { entry.uiSettings().quickReactions.first() } == listOf("🚀", "🔥") }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }

        val ready = entry.session().requireReady()
        val note = EventBuilder.textNote("reactions test note").signWithKeys(alice)
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(note)
        }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
        rule.waitUntil(30_000) { rule.onAllNodesWithText("reactions test note").fetchSemanticsNodes().isNotEmpty() }
        val like = "note-like-${note.id().toHex().take(8)}"
        rule.onNodeWithTag(like).performTouchInput { longClick() }
        rule.waitUntil(10_000) { nodes("reaction-sheet").isNotEmpty() }
        rule.onNodeWithTag("reaction-quick-0", useUnmergedTree = true).performClick() // 🚀, first in the user's order
        rule.waitUntil(10_000) { nodes("$like-emoji").isNotEmpty() || rule.onAllNodes(hasTestTag("$like-emoji"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("$like-emoji", useUnmergedTree = true).assertTextEquals("🚀")
        rule.onNodeWithTag(like).assertIsSelected()
        val reactions = runBlocking {
            ready.engine.query(Filter().kind(Kind(7u)).author(PublicKey.parse(me.publicKey().toHex()))).filter { ev ->
                ev.tags().toVec().any { t -> t.asVec().let { it.size >= 2 && it[0] == "e" && it[1] == note.id().toHex() } }
            }.map { it.content() }
        }
        assertEquals(listOf("🚀"), reactions)
    }
}
