package com.monostr.app

import com.monostr.app.ui.settings.SettingsPage
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
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
import com.monostr.app.data.GifMode
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/** Opening a web link asks first (the site learns the IP); Cancel opens nothing, Open once, Always open remembers. */
@RunWith(AndroidJUnit4::class)
class LinkPromptTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    // a key of its own and no animated pictures: Compose's waitForIdle, which has no limit, hung for 16 min over a well-known key's real feed with the main thread busy.
    // That a GIF kept it busy is inferred from the stack, not proven: the hang was not reproduced
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Before fun ask() {
        runBlocking {
            entry.uiSettings().setLinkAlwaysOpen(false)
            entry.uiSettings().setGifMode(GifMode.OFF)
        }
    }

    @After fun restore() {
        runCatching { Intents.release() }
        runBlocking {
            runCatching { entry.torSwitchControl().turnOff() }
            runCatching { entry.uiSettings().setLinkAlwaysOpen(false) }
            runCatching { entry.uiSettings().setGifMode(GifMode.AUTO) }
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

    private fun toggle(tag: String): ToggleableState? =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.ToggleableState)

    private fun showInFeed(text: String) {
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
        rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag("feed-list").performScrollToNode(hasText(text, substring = true)) }.isSuccess }
    }

    private fun post(text: String) {
        val alice = Keys.generate()
        val ready = entry.session().requireReady()
        runBlocking {
            entry.session().applyRelays(listOf("ws://127.0.0.1:9"))
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-link-${System.nanoTime()}"}""")).signWithKeys(alice))
            ready.engine.save(EventBuilder.textNote(text).signWithKeys(alice))
        }
    }

    private fun tapLink(marker: String) {
        showInFeed(marker)
        rule.onNodeWithTag("feed-list").performScrollToNode(hasText(marker, substring = true))
        rule.onAllNodesWithText(marker, substring = true)[0].performClick()
    }

    @Test
    fun cancelOpensNothingOpenOpensOnceAlwaysRemembers() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val url = "https://links-${System.currentTimeMillis()}.example/page"
        post("read it $url")
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        val view = allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith(url.substringBefore("/page"))))

        tapLink(url.substringAfter("://").substringBefore("/"))
        rule.waitUntil(10_000) { nodes("link-prompt").isNotEmpty() }
        rule.onNodeWithText(url.substringAfter("://").substringBefore("/")).assertIsDisplayed()
        rule.onNodeWithTag("link-cancel").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() }
        intended(view, times(0))

        tapLink(url.substringAfter("://").substringBefore("/"))
        rule.waitUntil(10_000) { nodes("link-open").isNotEmpty() }
        rule.onNodeWithTag("link-open").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() }
        intended(view, times(1))

        tapLink(url.substringAfter("://").substringBefore("/"))
        rule.waitUntil(10_000) { nodes("link-always").isNotEmpty() }
        rule.onNodeWithTag("link-always").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() }
        intended(view, times(2))

        tapLink(url.substringAfter("://").substringBefore("/"))
        rule.waitUntil(10_000) { runCatching { intended(view, times(3)) }.isSuccess }
        assertTrue("no sheet once always open is on", nodes("link-prompt").isEmpty())
    }

    @Test
    fun withTorOnAStoredAlwaysOpenStillAsksAndNamesTheBrowser() {
        runBlocking { runCatching { entry.session().logout() }; entry.uiSettings().setLinkAlwaysOpen(true) }
        reachFeed()
        runBlocking { entry.torSwitchControl().turnOn() }
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() }
        val marker = "tor-${System.currentTimeMillis()}.example"
        post("read it https://$marker/page")
        tapLink(marker)
        rule.waitUntil(10_000) { nodes("link-prompt").isNotEmpty() }
        rule.onNodeWithTag("link-tor-note").assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.link_prompt_tor)).assertIsDisplayed()
        assertTrue("under Tor it asks every time", nodes("link-always").isEmpty())
        rule.onNodeWithTag("link-cancel").performClick()
    }

    @Test
    fun aVeryLongLinkStillShowsTheButtons() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val marker = "long-${System.currentTimeMillis()}.example"
        post("read it https://$marker/" + "a".repeat(4000))
        tapLink(marker)
        rule.waitUntil(10_000) { nodes("link-open").isNotEmpty() }
        rule.onNodeWithTag("link-open").assertIsDisplayed()
        rule.onNodeWithTag("link-cancel").assertIsDisplayed()
    }

    @Test
    fun alwaysOpenCanBeUndoneInTheSettings() {
        runBlocking { runCatching { entry.session().logout() }; entry.uiSettings().setLinkAlwaysOpen(true) }
        reachFeed()
        val marker = "undo-${System.currentTimeMillis()}.example"
        post("read it https://$marker/page")
        rule.openSettingsPage(ctx, SettingsPage.PRIVACY)
        rule.waitUntil(15_000) { nodes("settings-ask-links").isNotEmpty() }
        // askLinks starts as true until DataStore delivers the stored value (off); a click before that sets the wrong one
        rule.waitUntil(15_000) { toggle("settings-ask-links") == ToggleableState.Off }
        rule.onNodeWithTag("settings-ask-links").performScrollTo().performClick()
        rule.waitUntil(15_000) { toggle("settings-ask-links") == ToggleableState.On }
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("settings-cat-privacy").isNotEmpty() } // the overview lies under the page
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("feed-list").isNotEmpty() }
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        tapLink(marker)
        rule.waitUntil(10_000) { nodes("link-prompt").isNotEmpty() }
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith("https://$marker"))), times(0))
    }

    @Test
    fun theWholeHostShowsSoALongSubdomainCannotHideTheRealDomain() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val host = "paypal.com.account.verify.secure.login.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.evil.example"
        post("read it https://$host/")
        tapLink("paypal.com.account")
        rule.waitUntil(10_000) { nodes("link-host").isNotEmpty() }
        val shown = rule.onNodeWithTag("link-host", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(host, shown)
        // wrapped, not clipped: the text layout has no visual overflow
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("link-host", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertTrue("the host is not clipped", layout.isNotEmpty() && !layout[0].hasVisualOverflow && layout[0].lineCount > 1)
        rule.onNodeWithTag("link-open").assertIsDisplayed()
    }
}
