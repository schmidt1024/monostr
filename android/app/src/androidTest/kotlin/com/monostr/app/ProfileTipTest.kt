package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import com.monostr.app.work.TipCheckWorker
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Tips II: the profile has a tip button. With "tip anonymously" on and the recipient's watcher
 * unreachable, the sheet reports it and nothing is published, least of all a public tip. With the
 * switch off, the intent goes out under the user's key, without an `e` tag, and the button shows
 * the pending tip. Runs against a loopback relay with a fresh account; nothing leaves the device
 * (the search tab still connects its search relays). The anonymous send itself cannot run here:
 * the watcher client insists on TLS for `/v1/info`; JVM tests and the mainnet acceptance cover it.
 */
@RunWith(AndroidJUnit4::class)
class ProfileTipTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private val settings get() = EntryPointAccessors.fromApplication(ctx, TipCheckWorker.Deps::class.java).settings()
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Before fun stubWallet() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After fun restore() {
        Intents.release()
        runBlocking {
            runCatching { settings.setAnonymous(true) }
            runCatching { entry.searchRelays().reset() }
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

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
        rule.waitUntil(30_000) { nodes("profile-tip").isNotEmpty() }
    }

    private fun intentsTo(ready: Ready, recipient: PublicKey): List<Event> =
        runBlocking { ready.engine.query(Filter().kind(Kind(9738u)).pubkey(recipient)) }

    @Test
    fun anonymousNeedsTheWatcherAndAPublicProfileTipHasNoNote() {
        val alice = Keys.generate()
        val watcher = Keys.generate()
        val address = MoneroKeys.generate().address(Network.MAINNET)
        LoopbackRelay().use { relay ->
            runBlocking {
                runCatching { entry.session().logout() }
                entry.session().applyRelays(listOf(relay.url))
                settings.setAnonymous(true)
            }
            loginFresh()
            val ready = entry.session().requireReady()
            runBlocking {
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
            openProfile(alice.publicKey())
            rule.onNodeWithTag("profile-tip").performClick()
            rule.waitUntil(15_000) { nodes("tip-send").isNotEmpty() }

            // anonymous (the default) needs the watcher's relays; the watcher is unreachable
            rule.onNodeWithTag("tip-anonymous").assertIsOn()
            rule.onNodeWithTag("tip-send").performClick()
            rule.waitUntil(20_000) { shown(ctx.getString(R.string.tip_error_anon_unavailable)) }
            assertEquals("nothing was published in its place", 0, intentsTo(ready, alice.publicKey()).size)
            assertTrue(relay.events.none { it.contains("\"kind\":9738") })

            // the user's own choice: a public profile tip
            rule.onNodeWithTag("tip-anonymous").performClick()
            rule.onNodeWithTag("tip-anonymous").assertIsOff()
            rule.onNodeWithTag("tip-send").performClick()
            rule.waitUntil(30_000) { nodes("tip-done").isNotEmpty() }
            val intents = intentsTo(ready, alice.publicKey())
            assertEquals(1, intents.size)
            val tags = intents[0].tags().toVec().map { it.asVec() }
            assertTrue("a profile tip names no note", tags.none { it[0] == "e" })
            assertTrue(tags.none { it[0] == "anon" })
            assertEquals(me.publicKey().toHex(), intents[0].author().toHex())
            assertEquals("", intents[0].content())
            assertEquals("tip", tags.first { it[0] == "type" }[1])
            intended(allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith("monero:4"))))

            rule.onNodeWithTag("tip-done").performClick()
            val pending = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ctx.getString(R.string.note_tip_pending))
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("profile-tip").assert(pending) }.isSuccess }
        }
    }
}
