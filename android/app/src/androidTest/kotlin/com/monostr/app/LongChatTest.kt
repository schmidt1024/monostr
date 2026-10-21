package com.monostr.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys

/** Spec perf2 D5: a 300-message chat opens at its newest message and pages older ones in on scroll. Relays never answer; seeded text is generated. */
@RunWith(AndroidJUnit4::class)
class LongChatTest {
    companion object {
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
    private val me = Keys.generate()
    private val peer = Keys.generate().publicKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun shown(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() } // deletes the seeded DM database
        }
    }

    @Test
    fun aLongChatOpensAtTheNewestMessageAndPagesOlderOnesIn() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        LoopbackRelay(silent = true).use { relay ->
            runBlocking { entry.session().applyRelays(listOf(relay.url)) }
            rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
            if (nodes("setup-skip").isNotEmpty()) {
                rule.onNodeWithTag("setup-skip").performClick()
                rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
            }
            val store = entry.session().requireReady().dmStore
            val base = System.currentTimeMillis() / 1000 - 10_000
            runBlocking {
                (1..300).forEach { i ->
                    // the newest is our own, so the conversation is a chat, not a request
                    store.upsert(DmMessage("%064x".format(i), peer, outgoing = i == 300, content = "seeded message $i", createdAt = base + i, receivedAt = base + i,
                        status = if (i == 300) DmStatus.SENT else DmStatus.RECEIVED, wrapId = "%063xw".format(i)))
                }
            }
            rule.onNodeWithTag("tab-messages").performClick()
            rule.waitUntil(15_000) { nodes("conversation-${peer.take(8)}").isNotEmpty() }
            rule.onNodeWithTag("conversation-${peer.take(8)}").performClick()
            rule.waitUntil(10_000) { shown("seeded message 300") }
            // only the newest page is loaded; scrolling up brings the rest in
            rule.waitUntil(30_000) {
                runCatching { rule.onNodeWithTag("chat-list").performScrollToNode(hasText("seeded message 1")) }.isSuccess
            }
        }
    }
}
