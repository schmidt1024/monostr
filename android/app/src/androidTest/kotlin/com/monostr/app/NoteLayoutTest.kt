package com.monostr.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.Npub
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Timestamp

/** Spec 11.5: list cards are indented beside the avatar; the focused note of a thread takes the full width. Offline (seeded notes). */
@RunWith(AndroidJUnit4::class)
class NoteLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

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

    private fun follow(alice: Keys, metadata: String?) {
        val ready = entry.session().requireReady()
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            if (metadata != null) ready.engine.save(EventBuilder.metadata(Metadata.fromJson(metadata)).signWithKeys(alice))
        }
    }

    private fun seedAndShow(vararg events: Event) {
        val ready = entry.session().requireReady()
        runBlocking { events.forEach { ready.engine.save(it) } }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
    }

    private fun id8(e: Event) = e.id().toHex().take(8)

    @Test
    fun aLongNameKeepsTimeAndMenuVisible() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"${"x".repeat(80)}"}""")
        val text = "Indented ${System.currentTimeMillis()}"
        val note = EventBuilder.textNote(text).signWithKeys(alice)
        seedAndShow(note)
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        // the avatar sits left of the text column: its right edge is left of the text's left edge
        val avatar = rule.onNodeWithTag("note-avatar-${id8(note)}").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("note-text-${id8(note)}").fetchSemanticsNode().boundsInRoot
        assertTrue("avatar right ${avatar.right} <= text left ${body.left}", avatar.right <= body.left)
        // a name that never ends still leaves the menu and the actions on screen
        rule.onNodeWithTag("note-menu-${id8(note)}").assertIsDisplayed()
        val time = rule.onNodeWithTag("note-reltime-${id8(note)}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithTag("note-menu-${id8(note)}").fetchSemanticsNode().boundsInRoot
        assertTrue("time $time must have width and sit left of the menu $menu", time.width > 0 && time.right <= menu.left + 1)
        rule.onNodeWithTag("note-reply-${id8(note)}").assertIsDisplayed()
    }

    @Test
    fun theNameOpensTheProfileAndTheTextTheThread() {
        // 0.11.9, like X: the author's name leads to the author, the content to the note
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"alice-name"}""")
        val text = "Who wrote this ${System.currentTimeMillis()}"
        val note = EventBuilder.textNote(text).signWithKeys(alice)
        seedAndShow(note)
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("note-name-${id8(note)}", useUnmergedTree = true).performClick()
        rule.waitUntil(15_000) { nodes("profile-menu").isNotEmpty() } // the profile screen
        assertTrue(nodes("note-time-${id8(note)}").isEmpty())
        Espresso.pressBack()
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("note-text-${id8(note)}").performClick()
        rule.waitUntil(15_000) { nodes("note-time-${id8(note)}").isNotEmpty() } // the thread
    }

    @Test
    fun theFocusedNoteIsWholeAndWide() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, metadata = null) // no kind 0: the detail handle falls back to the short npub
        val long = EventBuilder.textNote((1..30).joinToString("\n") { "line $it" }).signWithKeys(alice)
        val reply = EventBuilder.textNoteReply("a reply", long, null, null).signWithKeys(alice)
        seedAndShow(long, reply)
        rule.waitUntil(30_000) { nodes("note-more-${id8(long)}").isNotEmpty() } // collapsed in the feed
        rule.onNodeWithTag("note-text-${id8(long)}").performClick() // opens the thread, focused = long
        rule.waitUntil(15_000) { nodes("note-time-${id8(long)}").isNotEmpty() }
        assertTrue(nodes("note-more-${id8(long)}").isEmpty()) // whole, never collapsed
        val focused = rule.onNodeWithTag("note-text-${id8(long)}").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(Npub.short(alice.publicKey().toHex())).assertIsDisplayed() // the handle without a kind 0
        // the reply sits below thirty lines: bring it into the list before measuring it
        rule.onNodeWithTag("thread-list").performScrollToNode(hasTestTag("note-text-${id8(reply)}"))
        val replyText = rule.onNodeWithTag("note-text-${id8(reply)}").fetchSemanticsNode().boundsInRoot
        assertTrue("focused ${focused.width} wider than reply ${replyText.width}", focused.width > replyText.width + 30)
    }

    @Test
    fun openingAReplyScrollsToIt() {
        reachFeed()
        val alice = Keys.generate()
        follow(alice, """{"name":"alice-layout"}""")
        val root = EventBuilder.textNote("root ${System.currentTimeMillis()}").signWithKeys(alice)
        // increasing created_at: the thread sorts replies by time, so "reply 25" is really the last one
        val base = System.currentTimeMillis() / 1000 - 100
        val replies = (1..25).map { EventBuilder.textNoteReply("reply $it ${"filler ".repeat(20)}", root, null, null).customCreatedAt(Timestamp.fromSecs((base + it).toULong())).signWithKeys(alice) }
        val last = replies.last()
        seedAndShow(root, *replies.toTypedArray())
        rule.waitUntil(30_000) { rule.onAllNodesWithText("root ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // spec "feed without replies" (0.11.8): the feed shows the root alone; the replies wait in the thread
        assertTrue(rule.onAllNodes(hasText("reply ", substring = true)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("note-text-${id8(root)}").performClick()
        rule.waitUntil(15_000) { nodes("note-time-${id8(root)}").isNotEmpty() }
        rule.onNodeWithTag("thread-list").performScrollToNode(hasTestTag("note-text-${id8(last)}"))
        rule.onNodeWithTag("note-text-${id8(last)}").performClick()
        rule.waitUntil(15_000) { nodes("note-time-${id8(last)}").isNotEmpty() }
        rule.onNodeWithTag("note-time-${id8(last)}").assertIsDisplayed() // in view without scrolling
        // the focused note is at the top of the list: its text starts above any other note's text
        val focusedTop = rule.onNodeWithTag("note-text-${id8(last)}").fetchSemanticsNode().boundsInRoot.top
        val others = rule.onAllNodes(hasTestTag("note-text-${id8(replies[23])}")).fetchSemanticsNodes()
        assertTrue(others.isEmpty() || others.first().boundsInRoot.top < focusedTop) // the reply before it may show above, nothing below pushes it down
    }
}
