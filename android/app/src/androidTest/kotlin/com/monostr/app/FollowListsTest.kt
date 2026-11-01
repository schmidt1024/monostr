package com.monostr.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata

/**
 * Spec 2026-10-30 follow lists: the profile's numbers open both lists; rows show name, bio and the follow button
 * (Following for whom the user follows, Follow for the others); a follow from a row writes the contact list.
 */
@RunWith(AndroidJUnit4::class)
class FollowListsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val carol = Keys.generate()
    private val bob = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun row(keys: Keys) = "follows-person-${keys.publicKey().toHex().take(8)}"
    private fun button(keys: Keys) = "follows-follow-${keys.publicKey().toHex().take(8)}"

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    private fun profile(keys: Keys, name: String, about: String) =
        EventBuilder.metadata(Metadata.fromJson("""{"name":"$name","about":"$about"}""")).signWithKeys(keys)

    @Test
    fun theNumbersOpenBothListsWithBiosAndFollowButtons() {
        val ts = System.currentTimeMillis()
        val mine = EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null), Contact(carol.publicKey(), null, null))).signWithKeys(me)
        // bob follows the user; the relay hands his list to the followers request
        val bobs = EventBuilder.contactList(listOf(Contact(me.publicKey(), null, null))).signWithKeys(bob)
        val served = listOf(mine, bobs, profile(alice, "alice-$ts", "alice writes about monero $ts"), profile(carol, "carol-$ts", "carol"), profile(bob, "bob-$ts", "bob"))
        LoopbackRelay(serve = served.map { it.asJson() }).use { relay ->
            runBlocking {
                runCatching { entry.session().logout() }
                entry.session().applyRelays(listOf(relay.url))
            }
            rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() }
            rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
            if (nodes("setup-skip").isNotEmpty()) {
                rule.onNodeWithTag("setup-skip").performClick()
                rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
            }
            val ready = entry.session().requireReady()
            runBlocking { served.forEach { ready.engine.save(it) } }

            rule.onNodeWithTag("feed-profile").performClick()
            rule.waitUntil(20_000) { nodes("profile-follow-counts").isNotEmpty() }
            // the first half of the line is «2 Following»: its link opens the first tab
            rule.onNodeWithTag("profile-follow-counts").assertTextContains("2 ${ctx.getString(R.string.profile_following_label)}", substring = true)
            rule.onNodeWithTag("profile-follow-counts").performFirstLinkClick()
            rule.waitUntil(15_000) { nodes("follows-screen").isNotEmpty() }

            rule.waitUntil(15_000) { nodes(row(alice)).isNotEmpty() && nodes(row(carol)).isNotEmpty() }
            rule.waitUntil(15_000) { shown("alice writes about monero $ts") }
            rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(button(alice)).assertTextContains(ctx.getString(R.string.profile_following)) }.isSuccess }

            rule.onNodeWithTag("follows-tab-followers").performClick()
            rule.waitUntil(30_000) { nodes(row(bob)).isNotEmpty() }
            rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(button(bob)).assertTextContains(ctx.getString(R.string.profile_follow)) }.isSuccess }
            rule.waitUntil(30_000) { nodes("follows-end").isNotEmpty() }

            // a follow from the row writes the contact list, and the button turns to Following
            rule.onNodeWithTag(button(bob)).performClick()
            rule.waitUntil(15_000) {
                runBlocking { ready.engine.query(Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u)) }.firstOrNull()
                    ?.tags()?.toVec()?.any { it.asVec() == listOf("p", bob.publicKey().toHex()) } == true
            }
            rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag(button(bob)).assertTextContains(ctx.getString(R.string.profile_following)) }.isSuccess }

            // a tap on the row opens the profile
            rule.onNodeWithTag(row(bob)).performClick()
            rule.waitUntil(15_000) { nodes("profile-follow").isNotEmpty() }
        }
    }
}
