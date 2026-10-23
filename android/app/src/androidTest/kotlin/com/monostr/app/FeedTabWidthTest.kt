package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.feed.FeedTabRow
import com.monostr.app.ui.feed.HomeTab
import com.monostr.app.ui.feed.HomeTabsState
import com.monostr.app.ui.feed.ListStatus
import com.monostr.nostr.model.ListAddress
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A list named with one emoji gets the same sides as a word: no 90 dp minimum width that pads it into a wide gap. */
@RunWith(AndroidJUnit4::class)
class FeedTabWidthTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun anEmojiTabIsAsNarrowAsItsLabel() {
        val list = HomeTab.Pinned(ListAddress(30000, "a".repeat(64), "x"), "😂", ListStatus.READY)
        rule.setContent {
            MaterialTheme {
                FeedTabRow(HomeTabsState(tabs = listOf(HomeTab.Following, list)), onSelect = {}, onOpenList = {}, onUnpin = {}, onMove = { _, _ -> })
            }
        }
        val bounds = rule.onNodeWithTag("feed-tab-1").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        // the emoji plus 16 dp on each side, well under the 90 dp minimum of a Material tab
        assertTrue("emoji tab is $width wide", width < 80.dp)
    }
}
