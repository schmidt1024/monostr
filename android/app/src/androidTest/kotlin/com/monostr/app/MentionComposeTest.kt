package com.monostr.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
        // drafts of earlier tests (left composers) must not show up here
        runBlocking { entry.drafts().expire(Keys.parse(secret).publicKey().toHex(), now = Long.MAX_VALUE) }
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

    @Test
    fun backspaceIntoAShownMentionRemovesItWholeAndTypingGoesOnAfterIt() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val ts = System.currentTimeMillis() % 100_000
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"mback$ts","display_name":"Back Test $ts"}""")).signWithKeys(alice))
        }
        rule.onNodeWithTag("fab-compose").performClick()
        rule.waitUntil(10_000) { nodes("compose-text").isNotEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("hello @mback$ts")
        val pk8 = alice.publicKey().toHex().take(8)
        rule.waitUntil(10_000) { nodes("mention-$pk8").isNotEmpty() }
        rule.onNodeWithTag("mention-$pk8").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("hello @Back Test $ts", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // the cursor sits after the space behind the token: two backspaces take the space, then the whole mention
        rule.onNodeWithTag("compose-text").requestFocus()
        rule.onNodeWithTag("compose-text").performKeyInput { pressKey(Key.Backspace); pressKey(Key.Backspace) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("@Back Test", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("bye")
        rule.onNodeWithTag("compose-send").performClick()
        val mine = Filter().kind(Kind(1u)).author(me.publicKey()).limit(20u)
        rule.waitUntil(15_000) { runBlocking { ready.engine.query(mine) }.any { it.content() == "hello bye" } }
    }

    /** Logs in, stores a followed person called [display], opens the composer and picks them after "hello ". */
    private fun pickedMention(handle: String, display: String) {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"$handle","display_name":"$display"}""")).signWithKeys(alice))
        }
        rule.onNodeWithTag("fab-compose").performClick()
        rule.waitUntil(10_000) { nodes("compose-text").isNotEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("hello @$handle")
        val pk8 = alice.publicKey().toHex().take(8)
        rule.waitUntil(10_000) { nodes("mention-$pk8").isNotEmpty() }
        rule.onNodeWithTag("mention-$pk8").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("hello @$display", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sendsExactly(content: String) {
        rule.onNodeWithTag("compose-send").performClick()
        val ready = entry.session().requireReady()
        val mine = Filter().kind(Kind(1u)).author(me.publicKey()).limit(20u)
        rule.waitUntil(15_000) { runBlocking { ready.engine.query(mine) }.any { it.content() == content } }
    }

    /** Review fix: the keyboard sees "@name"; rewriting it to a shorter "@nam" takes the whole mention, no plain-text leftover. */
    @Test
    fun aKeyboardRewriteOfTheShownNameRemovesTheWholeMention() {
        val ts = System.currentTimeMillis() % 100_000
        pickedMention("mrew$ts", "Rewrite $ts")
        val shown = "hello @Rewrite $ts"
        rule.onNodeWithTag("compose-text").performTextReplacement(shown.dropLast(1))
        rule.waitUntil(5_000) { rule.onAllNodesWithText("@Rewrite", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("bye")
        sendsExactly("hello bye")
    }

    /** Backspace sent to the focused field as a key event, so the soft keyboard (Gboard on the AVD) handles it first. */
    @Test
    fun backspaceThroughTheSoftKeyboardRemovesTheWholeMention() {
        val ts = System.currentTimeMillis() % 100_000
        pickedMention("mime$ts", "Ime Test $ts")
        rule.onNodeWithTag("compose-text").performClick()
        rule.waitForIdle()
        Thread.sleep(1_500) // the keyboard comes up
        repeat(2) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 67").close()
            Thread.sleep(700)
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("@Ime Test", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("bye")
        sendsExactly("hello bye")
    }

    /** The text survives a recreation (rotation); after an offline send the field is frozen for "send again". */
    @Test
    fun theTextSurvivesRecreationAndFreezesAfterAnOfflineSend() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val ts = System.currentTimeMillis() % 100_000
        runBlocking { entry.session().applyRelays(listOf("ws://127.0.0.1:9")) }
        rule.onNodeWithTag("fab-compose").performClick()
        rule.waitUntil(10_000) { nodes("compose-text").isNotEmpty() }
        rule.onNodeWithTag("compose-text").performTextInput("kept $ts")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(15_000) { nodes("compose-text").isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("kept $ts", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("compose-send").performClick()
        val mine = Filter().kind(Kind(1u)).author(me.publicKey()).limit(20u)
        rule.waitUntil(15_000) { runBlocking { ready.engine.query(mine) }.any { it.content() == "kept $ts" } }
        val frozen = hasTestTag("compose-text") and SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false)
        rule.waitUntil(15_000) { rule.onAllNodes(frozen).fetchSemanticsNodes().isNotEmpty() }
    }
}
