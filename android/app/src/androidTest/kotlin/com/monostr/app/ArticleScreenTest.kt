package com.monostr.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.model.AddressRef
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Tag

/**
 * Spec 2026-10-29 article reader: a tap on an article card in the feed opens the reader (title and a heading of
 * the Markdown body), and the like publishes a kind 7 with the article's `a` tag to the loopback relay. The article
 * sits in the local database; nothing leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class ArticleScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

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

    private fun showInFeed(text: String) {
        var attempts = 0
        while (rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && attempts < 4) {
            rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
            attempts++
            runCatching { rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        }
    }

    @Test
    fun cardOpensReaderAndLikePublishesWithCoordinate() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val bob = Keys.generate()
        val ts = System.currentTimeMillis()
        val d = "reader-$ts"
        val article = EventBuilder(Kind(30023u), "# Heading $ts\n\nSome **bold** body text.")
            .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "Reader article $ts"))))
            .signWithKeys(bob)
        val ref = AddressRef(30023, bob.publicKey().toHex(), d)
        val note = EventBuilder.textNote("read this $ts\n\nnostr:${ref.toBech32()}").signWithKeys(alice)
        LoopbackRelay().use { relay ->
            runBlocking {
                entry.session().applyRelays(listOf(relay.url))
                ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-art-$ts"}""")).signWithKeys(alice))
                ready.engine.save(article)
                ready.engine.save(note)
            }
            showInFeed("read this $ts")
            val card = "address-card-${d.take(8)}"
            rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(card))
            rule.waitUntil(15_000) { nodes("address-card-generic").isNotEmpty() }
            // the card frame (not the person row) opens the reader
            rule.onNodeWithTag(card).performTouchInput { click(Offset(centerX, height - 4.dp.toPx())) }
            rule.waitUntil(15_000) { nodes("article-screen").isNotEmpty() && nodes("article-title").isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("Reader article $ts").fetchSemanticsNodes().isNotEmpty())
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Heading $ts").fetchSemanticsNodes().isNotEmpty() }

            val like = "note-like-${article.id().toHex().take(8)}"
            rule.onNodeWithTag("article-body").performScrollToNode(hasTestTag(like))
            rule.onNodeWithTag(like).performClick()
            rule.waitUntil(15_000) { relay.events.any { it.contains("\"kind\":7") && it.contains(ref.coordinate) } }
            assertTrue("the like names the author", relay.events.any { it.contains("\"kind\":7") && it.contains(bob.publicKey().toHex()) })
        }
    }
}
