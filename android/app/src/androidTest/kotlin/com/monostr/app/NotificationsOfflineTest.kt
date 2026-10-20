package com.monostr.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys

/** Spec perf 3.6 / 6: notifications show what the device has at once while the only relay never answers. */
@RunWith(AndroidJUnit4::class)
class NotificationsOfflineTest {
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
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun texts(text: String) = rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

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
    fun theEmptyStateAndStoredRowsShowWhileTheRelaysNeverAnswer() {
        reachFeed()
        val ready = entry.session().requireReady()
        LoopbackRelay(silent = true).use { relay ->
            runBlocking { entry.session().applyRelays(listOf(relay.url)) }
            rule.onNodeWithTag("tab-notifications").performClick()
            // the old screen showed a spinner until the relays answered (8 s fetch + 3 s deletions on this relay)
            rule.waitUntil(3_000) { texts(ctx.getString(R.string.notifications_empty)).isNotEmpty() }
            // a pull asks the silent relay: the empty state says so while it waits
            rule.onNodeWithTag("notifications-refresh").performTouchInput { swipeDown() }
            rule.waitUntil(3_000) { nodes("notifications-checking").isNotEmpty() }
            // a reply stored meanwhile shows on the next open, without the relay
            val alice = Keys.generate()
            val mine = EventBuilder.textNote("offline question").signWithKeys(me)
            val reply = EventBuilder.textNoteReply("offline answer", mine, null, null).signWithKeys(alice)
            runBlocking { ready.engine.save(mine); ready.engine.save(reply) }
            rule.onNodeWithTag("tab-feed").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
            rule.onNodeWithTag("tab-notifications").performClick()
            rule.waitUntil(3_000) { nodes("notification-${reply.id().toHex()}").isNotEmpty() }
        }
    }
}
