package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.common.LinkPromptHost
import com.monostr.app.ui.common.TorBrowserFinder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Torbrowser brief 2026-11-07: under Tor the link prompt offers «Open in Tor Browser» when Tor Browser can open the link, and
 * «Copy link» always. Both act at once; neither ever falls back to the default browser.
 */
@RunWith(AndroidJUnit4::class)
class TorBrowserLinkTest {
    @get:Rule val rule = createComposeRule()

    private val stable = "org.torproject.torbrowser"
    private val url = "https://tb-${System.nanoTime()}.example/page"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val asked = AtomicInteger()
    private fun finder(found: String?) = TorBrowserFinder { asked.incrementAndGet(); found }
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
    private fun views() = Intents.getIntents().filter { it.action == Intent.ACTION_VIEW }

    private fun stubViews() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After fun release() { runCatching { Intents.release() } }

    private fun show(torOn: Boolean, found: String?) {
        rule.setContent {
            MaterialTheme {
                LinkPromptHost(alwaysOpen = false, onAlwaysOpen = {}, torOn = { torOn }, torBrowser = finder(found)) {
                    val handler = LocalUriHandler.current
                    TextButton(onClick = { handler.openUri(url) }, modifier = Modifier.testTag("tap-link")) { Text("link") }
                }
            }
        }
        rule.waitUntil(10_000) { nodes("tap-link").isNotEmpty() }
        rule.onNodeWithTag("tap-link").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isNotEmpty() && nodes("link-open").isNotEmpty() }
    }

    @Test
    fun torBrowserFoundShowsTheButtonAndOneTapSendsExactlyOneIntentToIt() {
        stubViews()
        show(torOn = true, found = stable)
        rule.waitUntil(10_000) { nodes("link-torbrowser").isNotEmpty() }
        // order: Cancel, Copy link, Open in Tor Browser, Open (reading order, the row may wrap)
        val order = listOf("link-cancel", "link-copy", "link-torbrowser", "link-open")
        val placed = order.sortedWith(compareBy({ bounds(it).top.value }, { bounds(it).left.value }))
        assertEquals(order, placed)
        rule.onNodeWithTag("link-torbrowser").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() && views().isNotEmpty() }
        rule.waitForIdle()
        val sent = views()
        assertEquals("exactly one view intent", 1, sent.size)
        assertEquals(stable, sent[0].`package`)
        assertEquals(url, sent[0].dataString)
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    @Test
    fun torBrowserNotInstalledShowsNoButton() {
        stubViews()
        show(torOn = true, found = null)
        rule.waitUntil(10_000) { asked.get() > 0 }
        rule.waitForIdle()
        assertTrue(nodes("link-torbrowser").isEmpty())
        rule.onNodeWithTag("link-copy").assertIsDisplayed()
        rule.onNodeWithTag("link-open").assertIsDisplayed()
    }

    @Test
    fun copyLinkPutsTheUrlOnTheClipboardAndClosesThePrompt() {
        stubViews()
        show(torOn = true, found = null)
        rule.onNodeWithTag("link-copy").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() }
        var text: String? = null
        var label: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
            text = clip?.getItemAt(0)?.text?.toString()
            label = clip?.description?.label?.toString()
        }
        assertEquals(url, text)
        assertEquals("Monostr link", label)
        assertTrue("copying opens nothing", views().isEmpty())
    }

    @Test
    fun torOffShowsNeitherAndDoesNotLookForTorBrowser() {
        stubViews()
        show(torOn = false, found = stable)
        rule.waitForIdle()
        assertTrue(nodes("link-torbrowser").isEmpty())
        assertTrue(nodes("link-copy").isEmpty())
        rule.onNodeWithTag("link-always").assertIsDisplayed()
        assertEquals("no package check without Tor", 0, asked.get())
    }

    /** Not stubbed: the found package is not installed here, so the start fails for real. No crash, no default browser. */
    @Test
    fun aFailedStartNeitherCrashesNorFallsBackToTheDefaultBrowser() {
        Intents.init()
        show(torOn = true, found = stable)
        rule.waitUntil(10_000) { nodes("link-torbrowser").isNotEmpty() }
        rule.onNodeWithTag("link-torbrowser").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() }
        rule.waitForIdle()
        val sent = views()
        assertTrue("never the default browser: ${sent.map { it.`package` }}", sent.all { it.`package` == stable })
        rule.onNodeWithTag("tap-link").assertIsDisplayed()
    }
}
