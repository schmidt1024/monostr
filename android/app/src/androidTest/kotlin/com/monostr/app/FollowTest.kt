package com.monostr.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag

/**
 * Spec 2/12: follow writes a kind 3 with the new `p` on top of the relay's list (content and every
 * other tag kept), unfollow writes it without; with no reachable relay the write fails and the stored
 * list stays. A fresh key per run (the public test key has a real kind 3 on the default relays that
 * could be newer than the test's list), and the loopback relay is set before the login, so the
 * account's reads and writes stay on the emulator (the search tab still connects its search relays;
 * the profile opens from the npub without a search).
 */
@RunWith(AndroidJUnit4::class)
class FollowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @After fun restore() {
        runBlocking {
            runCatching { entry.searchRelays().reset() }
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    /** Logs [secret] in (logging out whoever is logged in first), like `DmFlowTest.loginAs`. */
    private fun loginFresh() {
        rule.waitUntil(60_000) { nodes("login-secret").isNotEmpty() || nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("login-secret").isEmpty()) {
            runBlocking { entry.session().logout() }
            rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        }
        rule.onNodeWithTag("login-secret").performTextInput(secret)
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    private fun openProfile(pubkey: PublicKey) {
        rule.onNodeWithTag("tab-search").performClick()
        rule.waitUntil(10_000) { nodes("search-field").isNotEmpty() }
        rule.onNodeWithTag("search-field").performTextInput(pubkey.toBech32())
        rule.onNodeWithTag("search-field").performImeAction()
        rule.waitUntil(30_000) { nodes("profile-follow").isNotEmpty() }
    }

    private fun newest(ready: Ready): Event? =
        runBlocking { ready.engine.query(Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u)) }.firstOrNull()

    private fun Event.tagList() = tags().toVec().map { it.asVec() }

    @Test
    fun followAndUnfollowWriteKind3AndWithoutARelayTheListStays() {
        val alice = Keys.generate().publicKey()
        val bob = Keys.generate().publicKey()
        val base = EventBuilder(Kind(3u), """{"wss://old.example":{"read":true,"write":true}}""")
            .tags(listOf(Tag.parse(listOf("p", bob.toHex(), "wss://bob.example", "bob")), Tag.parse(listOf("t", "keep"))))
            .signWithKeys(me)
        val baseTags = base.tagList()
        LoopbackRelay(serve = listOf(base.asJson())).use { relay ->
            // the loopback relay is the only relay before the new account's engine ever starts
            runBlocking {
                runCatching { entry.session().logout() }
                entry.session().applyRelays(listOf(relay.url))
            }
            loginFresh()
            val ready = entry.session().requireReady()
            rule.waitUntil(15_000) { runBlocking { ready.engine.connectedNormalRelayUrls() }.isNotEmpty() }
            openProfile(alice)
            rule.waitUntil(15_000) { runCatching { rule.onNodeWithTag("profile-follow").assertIsEnabled() }.isSuccess }

            rule.onNodeWithTag("profile-follow").performClick()
            rule.waitUntil(15_000) { newest(ready)?.tagList()?.contains(listOf("p", alice.toHex())) == true }
            val followed = newest(ready)!!
            assertEquals(base.content(), followed.content())
            assertEquals(baseTags + listOf(listOf("p", alice.toHex())), followed.tagList())
            rule.waitUntil(5_000) { relay.events.any { it.contains(followed.id().toHex()) } }
            rule.waitUntil(5_000) { shown(ctx.getString(R.string.profile_following)) }

            rule.onNodeWithTag("profile-follow").performClick()
            rule.waitUntil(5_000) { nodes("profile-unfollow-confirm").isNotEmpty() }
            rule.onNodeWithTag("profile-unfollow-confirm").performClick()
            rule.waitUntil(15_000) { newest(ready)?.tagList()?.none { it.getOrNull(1) == alice.toHex() } == true }
            assertEquals(baseTags, newest(ready)!!.tagList())
            rule.waitUntil(5_000) { shown(ctx.getString(R.string.profile_follow)) }

            // no reachable relay: the write fails with a message and the stored list is the same event as before
            val before = newest(ready)!!.id().toHex()
            runBlocking { entry.session().applyRelays(listOf("ws://127.0.0.1:9")) }
            rule.onNodeWithTag("profile-follow").performClick()
            rule.waitUntil(20_000) { shown(ctx.getString(R.string.error_send_failed)) || shown(ctx.getString(R.string.follow_no_list)) }
            assertEquals(before, newest(ready)!!.id().toHex())
            rule.onNodeWithTag("profile-follow").assertIsDisplayed()
        }
    }
}
