package com.monostr.app

import androidx.compose.ui.test.assert
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
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Timestamp

/** Spec 11: the menu, the deletion of an own note, collapsed long notes, the wordmark. Needs network (default relays). */
@RunWith(AndroidJUnit4::class)
class NoteMenuTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    /** A fresh key per run: the kind 1 and kind 5 this test publishes to the real relays never lands on a shared, well-known account. */
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

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    private fun seedAndShow(vararg events: Event) {
        val ready = entry.session().requireReady()
        runBlocking { events.forEach { ready.engine.save(it) } }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
    }

    @Test
    fun theMenuOffersDeletionOnlyForTheOwnNoteAndConfirmingRemovesTheCard() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val foreign = EventBuilder.textNote("Not mine ${System.currentTimeMillis()}").signWithKeys(alice)
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-menu-test"}""")).signWithKeys(alice))
        }
        val text = "Delete me ${System.currentTimeMillis()}"
        val own = runBlocking { ready.publish.post(text) }
        assertTrue("the own note reached no relay", own.sentToAny)
        seedAndShow(foreign)
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText(foreign.content()).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("note-menu-${foreign.id().toHex().take(8)}").performClick()
        rule.onNodeWithTag("note-menu-copy-link").assertIsDisplayed()
        assertTrue(nodes("note-menu-delete").isEmpty())
        Espresso.pressBack()

        rule.onNodeWithTag("note-menu-${own.eventId.take(8)}").performClick()
        rule.onNodeWithTag("note-menu-delete").performClick()
        rule.onNodeWithText(str(R.string.note_delete_title)).assertIsDisplayed()
        rule.onNodeWithTag("note-delete-confirm").performClick()
        // the outcome reaches the screen in front (the session's message queue, not the starting screen's state)
        rule.waitUntil(30_000) { rule.onAllNodesWithText(str(R.string.note_deleted)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
        // the request is stored locally only once a relay accepted it
        val requests = runBlocking { ready.engine.query(Filter().kind(Kind(5u)).author(me.publicKey())) }
        assertEquals(1, requests.size)
        assertEquals(listOf(listOf("e", own.eventId), listOf("k", "1")), requests[0].tags().toVec().map { it.asVec() })
        rule.onNodeWithText(foreign.content()).assertIsDisplayed()
    }

    @Test
    fun longNotesCollapseAndStayExpanded() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val now = System.currentTimeMillis() / 1000
        fun at(text: String, secondsAgo: Long) = EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs((now - secondsAgo).toULong())).signWithKeys(alice)
        val long = at(lines(30), 10)
        val twelve = at("twelve\n" + lines(11), 20)
        val fillers = (1..15).map { at("filler $it", 30L + it) }
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-long-test"}""")).signWithKeys(alice))
        }
        seedAndShow(long, twelve, *fillers.toTypedArray())
        val more = "note-more-${long.id().toHex().take(8)}"
        val longText = "note-text-${long.id().toHex().take(8)}"
        val twelveText = "note-text-${twelve.id().toHex().take(8)}"
        rule.waitUntil(30_000) { nodes(more).isNotEmpty() }
        // 12 lines stay whole: the missing button means something only once that note is laid out on screen
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(twelveText))
        rule.onNodeWithTag(twelveText).assertIsDisplayed()
        rule.waitForIdle()
        assertTrue(nodes("note-more-${twelve.id().toHex().take(8)}").isEmpty())
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(more))
        val collapsed = rule.onNodeWithTag(longText).fetchSemanticsNode().size.height
        rule.onNodeWithTag(more).performClick()
        rule.waitUntil(5_000) { nodes(more).isEmpty() }
        // the last line is shown: the semantics text holds all 30 lines even when cut, so the height proves the expansion
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(longText))
        rule.onNodeWithTag(longText).assert(hasText("line 30", substring = true))
        rule.waitForIdle()
        val expanded = rule.onNodeWithTag(longText).fetchSemanticsNode().size.height
        assertTrue("Show more vanished without expanding ($collapsed -> $expanded)", expanded > collapsed * 2)
        rule.onNodeWithTag("feed-list").performScrollToNode(hasText("filler 15"))
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("note-text-${long.id().toHex().take(8)}"))
        assertTrue("the note collapsed again after scrolling", nodes(more).isEmpty())

        // the wordmark and the feed tab bring the feed back to its top
        rule.onNodeWithTag("feed-list").performScrollToNode(hasText("filler 15"))
        rule.onNodeWithTag("feed-wordmark").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("note-text-${long.id().toHex().take(8)}")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("feed-list").performScrollToNode(hasText("filler 15"))
        rule.onNodeWithTag("tab-feed").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("note-text-${long.id().toHex().take(8)}")).fetchSemanticsNodes().isNotEmpty() }
    }
}
