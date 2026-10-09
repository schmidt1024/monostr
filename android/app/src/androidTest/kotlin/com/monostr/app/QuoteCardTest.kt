package com.monostr.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import rust.nostr.sdk.Nip19Event
import rust.nostr.sdk.Tag

/** Spec 8: a quote card shows in the feed and opens the quoted thread; quoting from the repost sheet stores q and p tags. Nothing leaves the device (dead relay). */
@RunWith(AndroidJUnit4::class)
class QuoteCardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

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
    fun quoteCardShowsInFeedOpensThreadAndQuotingStoresQAndPTags() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val bob = Keys.generate()
        val ts = System.currentTimeMillis()
        val quoted = EventBuilder.textNote("quoted original $ts").signWithKeys(bob)
        val uri = Nip19Event(quoted.id(), bob.publicKey(), Kind(1u), emptyList()).toNostrUri()
        val quoting = EventBuilder(Kind(1u), "quoting it $ts\n\n$uri")
            .tags(listOf(Tag.parse(listOf("q", quoted.id().toHex(), "", bob.publicKey().toHex()))))
            .signWithKeys(alice)
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-quote-$ts"}""")).signWithKeys(alice))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"bob-quote-$ts"}""")).signWithKeys(bob))
            ready.engine.save(quoted) // bob is not followed: his note is only reachable through the card
            ready.engine.save(quoting)
        }
        val text = "quoting it $ts"
        var attempts = 0
        while (rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && attempts < 4) {
            rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
            attempts++
            runCatching { rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        }
        val q8 = quoted.id().toHex().take(8)
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("quote-$q8"))
        rule.waitUntil(15_000) { rule.onAllNodesWithText("quoted original $ts").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the nevent left the note text", rule.onAllNodesWithText(uri, substring = true).fetchSemanticsNodes().isEmpty())
        // the card's centre can land on the author row (opens the profile); tap the card's bottom padding, which only the card handles
        rule.onNodeWithTag("quote-$q8").performTouchInput { click(Offset(centerX, height - 4.dp.toPx())) }
        rule.waitUntil(15_000) { nodes("note-text-$q8").isNotEmpty() }
        rule.onNodeWithText(ctx.getString(R.string.thread_title)).assertIsDisplayed()
        Espresso.pressBack()

        rule.waitUntil(15_000) { nodes("feed-list").isNotEmpty() }
        val a8 = quoting.id().toHex().take(8)
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("note-repost-$a8"))
        rule.onNodeWithTag("note-repost-$a8").performClick()
        rule.waitUntil(5_000) { nodes("action-quote").isNotEmpty() }
        rule.onNodeWithTag("action-quote").performClick()
        rule.waitUntil(15_000) { nodes("compose-quote").isNotEmpty() && nodes("quote-$a8").isNotEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("my take $ts")
        rule.onNodeWithTag("compose-send").performClick()
        // dead relay: the quote is stored locally before the send fails, the composer offers "send again"
        val mine = Filter().kind(Kind(1u)).author(me.publicKey()).limit(20u)
        rule.waitUntil(15_000) { runBlocking { ready.engine.query(mine) }.any { it.content().startsWith("my take $ts") } }
        val ev = runBlocking { ready.engine.query(mine) }.first { it.content().startsWith("my take $ts") }
        val tags = ev.tags().toVec().map { it.asVec() }
        assertTrue(tags.any { it.size >= 2 && it[0] == "q" && it[1] == quoting.id().toHex() })
        assertTrue(tags.any { it.size >= 2 && it[0] == "p" && it[1] == alice.publicKey().toHex() })
    }
}
