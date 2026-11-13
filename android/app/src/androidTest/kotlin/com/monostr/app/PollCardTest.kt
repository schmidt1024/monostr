package com.monostr.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.monostrPrefs
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
import rust.nostr.sdk.Tag

/**
 * Polls spec 2026-11-12: a tap on a pill votes; the first vote of the install asks through the «Your vote is public»
 * sheet, later ones go out directly; after a vote a relay took the card shows results with the own choice checked.
 * The only relay is a [LoopbackRelay] in the test process.
 */
@RunWith(AndroidJUnit4::class)
class PollCardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    // unmerged: the own-vote check sits inside a bar that merges its children for screen readers
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
            rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    private fun poll(author: Keys, text: String) = EventBuilder(Kind(1068u), text).tags(
        listOf(
            listOf("option", "o1", "Cake"), listOf("option", "o2", "Feather"),
            listOf("polltype", "singlechoice"), listOf("endsAt", (System.currentTimeMillis() / 1000 + 86_400).toString()),
        ).map { Tag.parse(it) },
    ).signWithKeys(author)

    private fun showPoll(p8: String) {
        var attempts = 0
        while (nodes("poll-$p8").isEmpty() && attempts < 4) {
            rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
            attempts++
            runCatching { rule.waitUntil(10_000) { nodes("poll-option-$p8-o1").isNotEmpty() } }
            runCatching { rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("poll-option-$p8-o1")) }
        }
        rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("poll-option-$p8-o1"))
    }

    @Test
    fun firstVoteAsksOnceThenVotesShowResultsWithTheOwnChoice() {
        runBlocking {
            runCatching { entry.session().logout() }
            ctx.monostrPrefs.edit { it.remove(booleanPreferencesKey("polls.first_vote_shown")) }
        }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val ts = System.currentTimeMillis()
        val first = poll(alice, "first poll $ts")
        val second = poll(alice, "second poll $ts")
        LoopbackRelay().use { relay ->
            runBlocking {
                entry.session().applyRelays(listOf(relay.url))
                ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-poll-$ts"}""")).signWithKeys(alice))
                ready.engine.save(first)
                ready.engine.save(second)
            }
            val f8 = first.id().toHex().take(8)
            val s8 = second.id().toHex().take(8)

            showPoll(f8)
            rule.waitUntil(15_000) { nodes("poll-footer-$f8").isNotEmpty() }
            rule.onNodeWithTag("poll-option-$f8-o1").performClick()
            rule.waitUntil(5_000) { nodes("poll-first-vote").isNotEmpty() }
            rule.onNodeWithTag("poll-first-vote-confirm").performClick()
            rule.waitUntil(15_000) { nodes("poll-bar-$f8-o1-mine").isNotEmpty() }
            assertTrue("the vote reached the relay", relay.events.any { it.contains("\"kind\":1018") && it.contains(first.id().toHex()) })

            // the sheet came once: the next vote goes out directly
            showPoll(s8)
            rule.onNodeWithTag("poll-option-$s8-o2").performClick()
            rule.waitUntil(15_000) { nodes("poll-bar-$s8-o2-mine").isNotEmpty() }
            assertTrue("no second sheet", nodes("poll-first-vote").isEmpty())
        }
    }
}
