package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.intent.Intents
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
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/**
 * The wallet websites in the tip sheet's pay-by-hand block are web links in a sheet's own window: they ask first (spec 2026-11-06 section 6).
 * Only web links are stubbed, so the `monero:` URI finds no wallet on the emulator and the block shows. Relays point at a dead loopback port.
 */
@RunWith(AndroidJUnit4::class)
class TipWalletLinkPromptTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val me = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private val web = allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith("http")))
    // read from the recorder, not with intended(): that checks a root view, and the tip sheet's window has the focus
    private fun cake() = Intents.getIntents().count { allOf(web, hasDataString(startsWith("https://cakewallet.com"))).matches(it) }
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Before fun stubWeb() {
        runBlocking { runCatching { entry.session().logout() }; entry.uiSettings().setLinkAlwaysOpen(false) }
        // an anonymous tip needs the watcher's relays, which this test has none of
        runBlocking { EntryPointAccessors.fromApplication(ctx, TipCheckWorker.Deps::class.java).settings().setAnonymous(false) }
        Intents.init()
        intending(web).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After fun restore() {
        runCatching { Intents.release() }
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
            runCatching { EntryPointAccessors.fromApplication(ctx, TipCheckWorker.Deps::class.java).settings().setAnonymous(true) }
        }
    }

    private fun reachFeed() {
        rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() || nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("login-secret").isNotEmpty()) {
            rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
            rule.onNodeWithTag("login-secret-button").performClick()
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    @Test
    fun aWalletWebsiteInTheTipSheetAsksFirst() {
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val note = EventBuilder.textNote("Tip me by hand ${System.nanoTime()}").signWithKeys(alice)
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(note)
            ready.engine.save(
                EventBuilder(Kind(10037u), "").tags(
                    listOf(
                        Tag.parse(listOf("address", MoneroKeys.generate().address(Network.MAINNET).encode())),
                        Tag.parse(listOf("watcher", "https://127.0.0.1:1", Keys.generate().publicKey().toHex())),
                        Tag.parse(listOf("network", "mainnet")),
                    ),
                ).signWithKeys(alice),
            )
        }
        val text = note.content()
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
        rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag("feed-list").performScrollToNode(hasText(text)) }.isSuccess }
        rule.onNodeWithTag("note-tip-${note.id().toHex().take(8)}").performClick()
        rule.waitUntil(15_000) { nodes("tip-send").isNotEmpty() }
        rule.onNodeWithTag("tip-preset-1").performClick()
        rule.onNodeWithTag("tip-send").performClick()
        rule.waitUntil(30_000) { nodes("tip-fallback").isNotEmpty() }

        rule.onNodeWithText("Cake Wallet").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isNotEmpty() }
        assertEquals(0, cake())
        rule.onNodeWithTag("link-cancel").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() }
        assertEquals(0, cake())
    }
}
