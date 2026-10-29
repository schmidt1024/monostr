package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import com.monostr.nostr.model.AddressRef
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.startsWith
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
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Nip19Event
import rust.nostr.sdk.Tag

/**
 * Spec 2026-10-29 naddr: a note with a `nostr:naddr1…` shows a card (the article sits in the local
 * database, so no relay is needed), a note that also quotes shows the quote card and an «event» link.
 * Tapping an article card opens the in-app reader (spec 2026-10-29 article reader); any other kind (here a
 * NIP-99 classified, 30402) still opens the njump.me link. Dead relay: nothing leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class AddressCardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun restore() {
        runCatching { Intents.release() }
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
    fun articleCardOpensReaderOtherKindOpensWebLinkAndQuoteKeepsEventLink() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val bob = Keys.generate()
        val ts = System.currentTimeMillis()
        val d = "article-$ts"
        val article = EventBuilder(Kind(30023u), "body")
            .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "Card article $ts"))))
            .signWithKeys(bob)
        val ref = AddressRef(30023, bob.publicKey().toHex(), d)
        val naddr = "nostr:" + ref.toBech32()
        val plain = EventBuilder.textNote("see this $ts\n\n$naddr").signWithKeys(alice)
        // not an article: its card keeps opening the browser
        val ld = "listing-$ts"
        val listing = EventBuilder(Kind(30402u), "for sale")
            .tags(listOf(Tag.parse(listOf("d", ld)), Tag.parse(listOf("title", "Card listing $ts"))))
            .signWithKeys(bob)
        val listingNaddr = "nostr:" + AddressRef(30402, bob.publicKey().toHex(), ld).toBech32()
        val other = EventBuilder.textNote("listing here $ts\n\n$listingNaddr").signWithKeys(alice)

        val quoted = EventBuilder.textNote("quoted original $ts").signWithKeys(bob)
        val uri = Nip19Event(quoted.id(), bob.publicKey(), Kind(1u), emptyList()).toNostrUri()
        val both = EventBuilder(Kind(1u), "both $ts\n\n$uri\n\n$naddr")
            .tags(listOf(Tag.parse(listOf("q", quoted.id().toHex(), "", bob.publicKey().toHex()))))
            .signWithKeys(alice)
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-addr-$ts"}""")).signWithKeys(alice))
            ready.engine.save(article)
            ready.engine.save(quoted)
            ready.engine.save(plain)
            ready.engine.save(listing)
            ready.engine.save(other)
            ready.engine.save(both)
        }
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

        showInFeed("see this $ts")
        val card = "address-card-${d.take(8)}"
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(card))
        rule.waitUntil(15_000) { nodes("address-card-generic").isNotEmpty() }
        rule.onNodeWithText("Card article $ts").assertIsDisplayed()
        assertTrue("the naddr left the note text", rule.onAllNodesWithText(naddr, substring = true).fetchSemanticsNodes().isEmpty())

        // a card of another kind: tap its bottom padding (the person row above opens the profile, only the card frame opens the link)
        showInFeed("listing here $ts")
        val listingCard = "address-card-${ld.take(8)}"
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(listingCard))
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Card listing $ts").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(listingCard).performTouchInput { click(Offset(centerX, height - 4.dp.toPx())) }
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith("https://njump.me/naddr1"))))

        // the second note has a quote: the quote card stays the card, the naddr reads as the «event» link
        showInFeed("both $ts")
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("quote-${quoted.id().toHex().take(8)}"))
        rule.waitUntil(15_000) { rule.onAllNodesWithText("quoted original $ts").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(ctx.getString(R.string.address_ref_label), substring = true).fetchSemanticsNodes().let {
            assertTrue("the naddr reads as the event link", it.isNotEmpty())
        }

        // the article card opens the in-app reader, no browser
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(card))
        rule.onNodeWithTag(card).performTouchInput { click(Offset(centerX, height - 4.dp.toPx())) }
        rule.waitUntil(15_000) { nodes("article-screen").isNotEmpty() }
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith("https://njump.me/naddr1"))), androidx.test.espresso.intent.VerificationModes.times(1))
    }
}
