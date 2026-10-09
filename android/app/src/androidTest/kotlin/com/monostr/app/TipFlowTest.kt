package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.work.TipCheckWorker
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.monero.hexToBytes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/**
 * Spec 7.4: tapping the tip icon publishes a tip intent and hands a `monero:` URI to
 * ACTION_VIEW; no like goes out with it. Runs in the real app: relays are pointed at an
 * unreachable loopback port so nothing leaves the device; the recipient, her note and her
 * payment info are written straight into the engine database. Works whether or not `SmokeTest`
 * already logged in during this run.
 */
@RunWith(AndroidJUnit4::class)
class TipFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"

    @Before fun stubWallet() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { EntryPointAccessors.fromApplication(ctx, TipCheckWorker.Deps::class.java).settings().setAnonymous(false) }
    }

    @After fun release() {
        Intents.release()
        // the test pointed the session at a dead relay; the next test (and the tester) get the defaults back
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val session = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java).session()
        runBlocking { runCatching { session.applyRelays(PrefsRelayStore.DEFAULT_RELAYS) } }
        runBlocking { runCatching { EntryPointAccessors.fromApplication(ctx, TipCheckWorker.Deps::class.java).settings().setAnonymous(true) } }
    }

    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

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
    fun tapOnTipIconPublishesIntentAndOpensWallet() {
        reachFeed()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val session = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java).session()
        val ready = session.requireReady()
        val me = Keys.parse(secret)
        val alice = Keys.generate()
        val watcher = Keys.generate()
        val address = MoneroKeys.generate().address(Network.MAINNET)
        val note = EventBuilder.textNote("Tip me please ${System.currentTimeMillis()}").signWithKeys(alice)
        runBlocking {
            session.applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(note)
            ready.engine.save(
                EventBuilder(Kind(10037u), "").tags(
                    listOf(
                        Tag.parse(listOf("address", address.encode())),
                        Tag.parse(listOf("watcher", "https://127.0.0.1:1", watcher.publicKey().toHex())),
                        Tag.parse(listOf("network", "mainnet")),
                    ),
                ).signWithKeys(alice),
            )
        }
        // pull-to-refresh re-reads the contact list and shows alice's note (local first)
        val text = note.content()
        var attempts = 0
        while (rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && attempts < 3) {
            rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
            attempts++
            runCatching { rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        }
        // other followed authors' notes (fetched from live relays before the relays were replaced) may sit above it
        rule.onNodeWithTag("feed-list").performScrollToNode(hasText(text))
        rule.onAllNodesWithText(text)[0].assertIsDisplayed()
        val id8 = note.id().toHex().take(8)
        rule.onNodeWithTag("note-tip-$id8").performClick()
        rule.waitUntil(15_000) { nodes("tip-send").isNotEmpty() }
        rule.onNodeWithTag("tip-preset-1").performClick()
        rule.onNodeWithTag("tip-send").performClick()
        rule.waitUntil(30_000) { nodes("tip-done").isNotEmpty() }

        val intents = runBlocking { ready.engine.query(Filter().kind(Kind(9738u))) }.filter { ev ->
            ev.tags().toVec().map { it.asVec() }.any { it[0] == "e" && it[1] == note.id().toHex() }
        }
        assertEquals(1, intents.size)
        val tags = intents[0].tags().toVec().map { it.asVec() }
        assertEquals(alice.publicKey().toHex(), tags.first { it[0] == "p" }[1])
        assertEquals("1000000000", tags.first { it[0] == "amount" }[1])
        assertEquals("tip", tags.first { it[0] == "type" }[1])
        val pid = tags.first { it[0] == "pid" }[1]
        assertEquals(16, pid.length)
        val expectedUri = "monero:${address.integrated(pid.hexToBytes())}?tx_amount=0.001"
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith(expectedUri))))
        val likes = runBlocking { ready.engine.query(Filter().kind(Kind(7u))) }.filter { ev ->
            ev.tags().toVec().map { it.asVec() }.any { it[0] == "e" && it[1] == note.id().toHex() }
        }
        assertEquals("a tip publishes no like", 0, likes.size)

        rule.onNodeWithTag("tip-done").performClick()
        rule.waitUntil(10_000) { nodes("note-pending-$id8").isNotEmpty() }
        rule.onNodeWithTag("note-pending-$id8").assertIsDisplayed()
    }
}
