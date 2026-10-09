package com.monostr.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys

/** The profile header counts the own contact list; the follower number depends on the network and is not asserted. */
@RunWith(AndroidJUnit4::class)
class FollowCountsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @After fun logout() { runBlocking { runCatching { entry.session().logout() }; entry.uiSettings().setPrimalStats(true) } }

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

    @Test
    fun theOwnProfileCountsTheContactList() {
        reachFeed()
        val ready = entry.session().requireReady()
        val contacts = List(3) { Contact(Keys.generate().publicKey(), null, null) }
        runBlocking { ready.engine.save(EventBuilder.contactList(contacts).signWithKeys(me)) }
        rule.onNodeWithTag("feed-profile").performClick()
        rule.waitUntil(15_000) { nodes("profile-edit").isNotEmpty() }
        rule.waitUntil(20_000) { nodes("profile-follow-counts").isNotEmpty() }
        rule.onNodeWithTag("profile-follow-counts").assertTextContains("3 ${ctx.getString(R.string.profile_following_label)}", substring = true)
    }

    @Test
    fun withPrimalSwitchedOffTheOwnListIsStillCounted() {
        runBlocking { entry.uiSettings().setPrimalStats(false) }
        theOwnProfileCountsTheContactList()
    }
}
