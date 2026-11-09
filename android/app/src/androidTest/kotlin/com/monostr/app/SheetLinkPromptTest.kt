package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.GuardedAlertDialog
import com.monostr.app.ui.common.GuardedDropdownMenu
import com.monostr.app.ui.common.GuardedModalBottomSheet
import com.monostr.app.ui.common.LinkPromptHost
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 2026-11-06 section 6: a web link asks first wherever it is tapped. A sheet, dialog or menu has a window of its own whose
 * ComposeView provides the platform LocalUriHandler again; a link inside one must still reach the prompt, and the prompt's Open the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SheetLinkPromptTest {
    @get:Rule val rule = createComposeRule()

    private val url = "https://sheet-${System.nanoTime()}.example/page"
    private val view = allOf(hasAction(Intent.ACTION_VIEW), hasDataString(startsWith(url)))

    // read from the recorder, not with intended(): that checks a root view, and the window left open has the focus
    private fun opened() = Intents.getIntents().count { view.matches(it) }
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Before fun stub() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After fun release() { runCatching { Intents.release() } }

    @Composable
    private fun WindowLink() {
        val handler = LocalUriHandler.current
        TextButton(onClick = { handler.openUri(url) }, modifier = Modifier.testTag("window-link")) { Text("link") }
    }

    private fun show(window: @Composable () -> Unit) {
        rule.setContent { MaterialTheme { LinkPromptHost(alwaysOpen = false, onAlwaysOpen = {}) { window() } } }
        rule.waitUntil(10_000) { nodes("window-link").isNotEmpty() }
    }

    /** The tap asks; Open in the prompt (itself a sheet) opens the browser once and does not ask again. */
    private fun tapAsksThenOpensOnce() {
        rule.onNodeWithTag("window-link", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isNotEmpty() }
        assertEquals(0, opened())
        rule.onNodeWithTag("link-open").performClick()
        rule.waitUntil(10_000) { nodes("link-prompt").isEmpty() && opened() == 1 }
        rule.waitForIdle()
        assertTrue("the prompt's own Open does not ask again", nodes("link-prompt").isEmpty())
        assertEquals(1, opened())
    }

    @Test
    fun aLinkInsideABottomSheetAsksFirst() {
        show { GuardedModalBottomSheet(onDismissRequest = {}) { WindowLink() } }
        tapAsksThenOpensOnce()
    }

    @Test
    fun aLinkInsideAnAlertDialogAsksFirst() {
        show { GuardedAlertDialog(onDismissRequest = {}, confirmButton = { WindowLink() }) }
        tapAsksThenOpensOnce()
    }

    @Test
    fun aLinkInsideADropdownMenuAsksFirst() {
        show { GuardedDropdownMenu(expanded = true, onDismissRequest = {}) { WindowLink() } }
        tapAsksThenOpensOnce()
    }

    @Test
    fun aLinkInAMenuInsideASheetAsksFirst() {
        show { GuardedModalBottomSheet(onDismissRequest = {}) { GuardedDropdownMenu(expanded = true, onDismissRequest = {}) { WindowLink() } } }
        tapAsksThenOpensOnce()
    }
}
