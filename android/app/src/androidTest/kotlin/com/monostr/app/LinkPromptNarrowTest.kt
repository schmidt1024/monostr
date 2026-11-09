package com.monostr.app

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.common.TorBrowserFinder
import com.monostr.app.ui.common.LinkPromptHost
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Torbrowser brief 2026-11-07 point 4: a 320 dp wide screen at font scale 2 with a 4000-character link still shows every button.
 * The window, not a composition local, is narrowed: the prompt is a sheet, whose window provides its own density and size again.
 */
@RunWith(AndroidJUnit4::class)
class LinkPromptNarrowTest {
    companion object {
        private var oldScale = "1.0"

        private fun shell(cmd: String): String =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }.trim()
            }

        @BeforeClass @JvmStatic fun narrow() {
            oldScale = shell("settings get system font_scale").takeIf { it.toFloatOrNull() != null } ?: "1.0"
            val m = Resources.getSystem().displayMetrics
            shell("wm density ${minOf(m.widthPixels, m.heightPixels) * 160 / 320}")
            shell("settings put system font_scale 2.0")
        }

        @AfterClass @JvmStatic fun restore() {
            shell("wm density reset")
            shell("settings put system font_scale $oldScale")
        }
    }

    @get:Rule val rule = createComposeRule()
    private val url = "https://narrow-${System.nanoTime()}.example/" + "a".repeat(4000)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun underTorWithTorBrowserEveryButtonStaysOnScreen() {
        var widthDp = 0
        var scale = 0f
        rule.setContent {
            widthDp = LocalConfiguration.current.screenWidthDp
            scale = LocalConfiguration.current.fontScale
            MaterialTheme {
                LinkPromptHost(alwaysOpen = false, onAlwaysOpen = {}, torOn = { true }, torBrowser = TorBrowserFinder { "org.torproject.torbrowser" }) {
                    val handler = LocalUriHandler.current
                    TextButton(onClick = { handler.openUri(url) }, modifier = Modifier.testTag("tap-link")) { Text("link") }
                }
            }
        }
        rule.waitUntil(10_000) { nodes("tap-link").isNotEmpty() }
        assertTrue("narrow: $widthDp dp", widthDp in 300..330)
        assertTrue("font scale: $scale", scale >= 1.99f)
        rule.onNodeWithTag("tap-link").performClick()
        rule.waitUntil(10_000) { nodes("link-torbrowser").isNotEmpty() }
        rule.waitForIdle()
        for (tag in listOf("link-cancel", "link-copy", "link-torbrowser", "link-open")) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            // whole, not just partly: unclipped bounds inside the sheet's window
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
            val root = node.findRootCoordinates()
            val b = root.localBoundingBoxOf(node, clipBounds = false)
            assertTrue("$tag whole on screen: $b in ${root.size}", b.left >= 0f && b.top >= 0f && b.right <= root.size.width && b.bottom <= root.size.height)
        }
    }
}
