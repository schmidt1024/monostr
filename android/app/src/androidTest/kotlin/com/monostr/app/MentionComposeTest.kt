package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
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
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata

/** Spec 8: `@` autocomplete picks a follow, the field shows `@name`, the sent note carries the npub and a p tag. Dead relay: checked in the local database. */
@RunWith(AndroidJUnit4::class)
class MentionComposeTest {
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
    fun atMentionPicksAFollowAndTheNoteCarriesItsPTag() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val ts = System.currentTimeMillis() % 100_000
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"mtest$ts","display_name":"Mention Test $ts"}""")).signWithKeys(alice))
        }
        rule.onNodeWithTag("fab-compose").performClick()
        rule.waitUntil(10_000) { nodes("compose-text").isNotEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("hello @mtest$ts")
        val pk8 = alice.publicKey().toHex().take(8)
        rule.waitUntil(10_000) { nodes("mention-$pk8").isNotEmpty() }
        rule.onNodeWithTag("mention-$pk8").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("hello @Mention Test $ts", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("compose-send").performClick()
        val npub = alice.publicKey().toBech32()
        val mine = Filter().kind(Kind(1u)).author(me.publicKey()).limit(20u)
        rule.waitUntil(15_000) { runBlocking { ready.engine.query(mine) }.any { it.content().contains(npub) } }
        val ev = runBlocking { ready.engine.query(mine) }.first { it.content().contains(npub) }
        assertEquals("hello nostr:$npub", ev.content())
        assertTrue(ev.tags().toVec().map { it.asVec() }.any { it.size >= 2 && it[0] == "p" && it[1] == alice.publicKey().toHex() })
    }
}
