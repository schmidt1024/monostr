package com.monostr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import com.monostr.app.ui.accounts.AccountSheet
import com.monostr.app.ui.common.GuardedModalBottomSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pixel finding (2026-11-25): the switcher opened in a fresh activity measured without its rows (they load a moment later), and
 * when they arrived as the opening animation ended the sheet stayed where the short sheet would sit: its end lay behind the
 * navigation bar and the sheet could not be dragged. Content that grows at any frame of the opening re-anchors the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SheetGrowTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var open by mutableStateOf(false)
    private var rows by mutableIntStateOf(0)
    private var value: SheetValue? = null

    /** The screen's bottom edge above the navigation bar, in screen pixels. */
    private fun usableBottom(): Float {
        val decor = rule.activity.window.decorView
        val nav = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val at = IntArray(2).also(decor::getLocationOnScreen)
        return (at[1] + decor.height - nav).toFloat()
    }

    @Test
    fun contentThatGrowsWhileOpeningIsReachable() {
        rule.setContent {
            if (open) {
                val state = rememberModalBottomSheetState()
                value = state.currentValue
                GuardedModalBottomSheet(onDismissRequest = {}, sheetState = state) {
                    Text("Accounts")
                    repeat(rows) { Box(Modifier.fillMaxWidth().height(72.dp).testTag("row-$it")) }
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("end"))
                }
            }
        }
        val failures = mutableListOf<String>()
        // the rows arrive k frames after the sheet starts to open; the opening lasts well under 60 frames
        for (k in 0..60) {
            rule.mainClock.autoAdvance = true
            open = false
            rows = 0
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            open = true
            repeat(k) { rule.mainClock.advanceTimeByFrame() }
            rows = 3
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(3_000)
            rule.waitForIdle()
            val end = rule.onNodeWithTag("end").fetchSemanticsNode()
            val bottom = end.positionOnScreen.y + end.size.height
            if (bottom > usableBottom() + 1f || value != SheetValue.Expanded) failures += "k=$k end=$bottom usable=${usableBottom()} value=$value"
        }
        assertTrue("end behind the navigation bar or sheet not settled when rows arrived after frames $failures", failures.isEmpty())
        assertEquals(1, rule.onAllNodes(hasTestTag("end")).fetchSemanticsNodes().size)
    }

    /** The account sheets: content taller than the screen scrolls, its end comes above the navigation bar. */
    @Test
    fun aTallAccountSheetScrollsToItsEnd() {
        rule.setContent {
            AccountSheet(onDismiss = {}, tag = "tall") {
                repeat(40) { Box(Modifier.fillMaxWidth().height(72.dp).testTag("row-$it")) }
                Box(Modifier.fillMaxWidth().height(56.dp).testTag("end"))
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("end")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("end").performScrollTo().assertIsDisplayed()
        rule.waitForIdle()
        val end = rule.onNodeWithTag("end").fetchSemanticsNode()
        val bottom = end.positionOnScreen.y + end.size.height
        assertTrue("end at $bottom, usable bottom ${usableBottom()}", bottom <= usableBottom() + 1f)
    }
}
