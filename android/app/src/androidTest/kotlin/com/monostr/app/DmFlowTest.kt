package com.monostr.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/**
 * Spec 10b end to end on the real relays (relay.monostr.com requires NIP-42 AUTH for kind 1059,
 * the app authenticates on its own): B opens A's profile via its npub and sends a DM, A sees it
 * (possibly under "Requests", A does not follow B) and replies, B sees the reply in the main list
 * (B has an own message there, so no request). Needs network. Each run uses two fresh keys
 * (spec 7.7 of Plan 10d); texts still carry a timestamp.
 */
@RunWith(AndroidJUnit4::class)
class DmFlowTest {
    companion object {
        // the messages tab asks for POST_NOTIFICATIONS on Android 13+; granted up front so no system dialog covers the app
        @JvmStatic
        @BeforeClass
        fun grantNotifications() {
            if (Build.VERSION.SDK_INT >= 33) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    // spec 7.7 of Plan 10d: fresh keys per run, so an earlier run's conversation, kind 10050 or request state never leaks in
    private val keysA = Keys.generate()
    private val keysB = Keys.generate()
    private val secretA = keysA.secretKey().toHex()
    private val secretB = keysB.secretKey().toHex()
    private val pubA = keysA.publicKey()
    private val pubB = keysB.publicKey()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private val sending get() = ctx.getString(R.string.chat_status_sending)
    private val failed get() = ctx.getString(R.string.chat_status_failed)

    // relays back to the defaults; logout deletes the account's DM database, i.e. the test conversation, and the next class starts at the login screen
    @After fun cleanup() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun nodes(matcher: SemanticsMatcher) = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    // excludes the input field, which holds the text until it is sent
    private fun textShown(text: String) = nodes(hasText(text, substring = true) and !hasSetTextAction()).isNotEmpty()

    private fun loginAs(secret: String) {
        // generous: the first launch after the install restores the session and opens the engine's database
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

    private fun openMessagesTab() {
        rule.onNodeWithTag("tab-messages").performClick()
        rule.waitUntil(30_000) { nodes("messages-list").isNotEmpty() }
    }

    /** Types [text], sends it and waits until the own bubble is SENT: no provisional id and no "sending" icon left, no "failed" icon. */
    private fun sendAndAwaitSent(text: String) {
        rule.waitUntil(30_000) { nodes("chat-input").isNotEmpty() }
        rule.onNodeWithTag("chat-input").performTextInput(text)
        rule.onNodeWithTag("chat-send").performClick()
        // the bubble appears with the SENDING row (provisional id "pending-…") before the relay round trip
        rule.waitUntil(15_000) { textShown(text) }
        rule.waitUntil(30_000) {
            nodes(hasContentDescription(failed)).isNotEmpty() ||
                (nodes(hasContentDescription(sending)).isEmpty() && nodes("chat-status-pending-").isEmpty())
        }
        assertTrue("the DM failed to send", nodes(hasContentDescription(failed)).isEmpty())
    }

    @Test
    fun bSendsAReplyArrivesAndBothSeeTheChat() {
        val stamp = System.currentTimeMillis()
        val fromB = "hello from B $stamp"
        val fromA = "hello from A $stamp"

        // 1. B opens A's profile through the search (npub) and sends a DM
        loginAs(secretB)
        openMessagesTab()
        rule.onNodeWithTag("tab-search").performClick()
        rule.waitUntil(10_000) { nodes("search-field").isNotEmpty() }
        rule.onNodeWithTag("search-field").performTextInput(pubA.toBech32())
        rule.onNodeWithTag("search-field").performImeAction()
        rule.waitUntil(30_000) { nodes("profile-message").isNotEmpty() }
        rule.onNodeWithTag("profile-message").performClick()
        sendAndAwaitSent(fromB)

        // 2. A finds B's conversation (under "Requests" unless A follows B) and replies
        runBlocking { entry.session().logout() }
        loginAs(secretA)
        openMessagesTab()
        val b8 = "conversation-${pubB.toHex().take(8)}"
        rule.waitUntil(90_000) { nodes(b8).isNotEmpty() || nodes("messages-requests").isNotEmpty() }
        if (nodes(b8).isEmpty()) {
            rule.onNodeWithTag("messages-requests").performClick()
            rule.waitUntil(30_000) { nodes(b8).isNotEmpty() }
        }
        rule.onNodeWithTag(b8).performClick()
        rule.waitUntil(60_000) { textShown(fromB) }
        sendAndAwaitSent(fromA)

        // 3. B sees the reply; the conversation is in the main list (the requests section stays closed)
        runBlocking { entry.session().logout() }
        loginAs(secretB)
        openMessagesTab()
        val a8 = "conversation-${pubA.toHex().take(8)}"
        rule.waitUntil(90_000) { nodes(a8).isNotEmpty() && textShown(fromA) }
        rule.onNodeWithTag(a8).performClick()
        rule.waitUntil(30_000) { textShown(fromA) && textShown(fromB) }
    }
}
