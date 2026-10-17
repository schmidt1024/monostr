package com.monostr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.monostr.app.ui.common.ShowBarsWhenStuck
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.common.HideOnScrollFooter
import com.monostr.app.ui.common.HideOnScrollHeader
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.rememberHideOnScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 2026-10-16 §2: the bottom bar hides with the header while scrolling down and returns on scrolling up; the FAB follows. */
@RunWith(AndroidJUnit4::class)
class BottomBarHideTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun barAndFabFollowTheScroll() {
        var dividerColor = Color.Unspecified
        rule.setContent {
            MaterialTheme {
                dividerColor = DividerDefaults.color
                val hide = rememberHideOnScroll()
                Scaffold(
                    modifier = Modifier.nestedScroll(hide.connection),
                    topBar = { HideOnScrollHeader(hide) { Text("header", Modifier.padding(16.dp)) } },
                    floatingActionButton = { FloatingActionButton(onClick = {}, modifier = Modifier.testTag("fab-compose")) { Text("+") } },
                    bottomBar = {
                        HideOnScrollFooter(hide) {
                            MonostrBottomBar(BottomTab.FEED, unread = 0, dmUnread = 0, onFeed = hide::show, onSearch = {}, onBookmarks = {}, onMessages = {}, onNotifications = {}, windowInsets = WindowInsets(0))
                        }
                    },
                ) { padding ->
                    LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("list")) {
                        items(200) { Text("row $it", Modifier.padding(16.dp)) }
                    }
                }
            }
        }
        val footer = rule.onNodeWithTag("hide-on-scroll-footer")
        val divider = rule.onNodeWithTag("bottom-bar-divider")
        val shown = footer.getBoundsInRoot().let { it.bottom - it.top }
        val fabTop = rule.onNodeWithTag("fab-compose").getBoundsInRoot().top
        assertTrue(shown > 40.dp)
        // user ruling: a thin line like the one between notes sits on the bar's top edge
        divider.assertIsDisplayed()
        assertEquals(footer.getBoundsInRoot().top, divider.getBoundsInRoot().top)
        val line = divider.captureToImage().toPixelMap()
        assertEquals(dividerColor.toArgb(), line[line.width / 2, line.height / 2].toArgb())

        rule.onNodeWithTag("list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        val hidden = footer.getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("footer still $hidden high", hidden < 1.dp)
        assertTrue("the FAB did not follow", rule.onNodeWithTag("fab-compose").getBoundsInRoot().top > fabTop + shown / 2)
        divider.assertIsNotDisplayed() // the line leaves with the bar

        rule.onNodeWithTag("list").performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f) }
        rule.waitForIdle()
        val back = footer.getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("footer only $back of $shown back", back > shown - 1.dp)
        rule.onNodeWithTag("tab-feed").assertExists()
        divider.assertIsDisplayed()
        assertEquals(footer.getBoundsInRoot().top, divider.getBoundsInRoot().top)
    }

    /** Hidden bars with nothing left to scroll would leave the tabs out of reach: they come back on their own. */
    @Test
    fun barComesBackWhenTheContentStopsScrolling() {
        var count by mutableIntStateOf(200)
        rule.setContent {
            MaterialTheme {
                val hide = rememberHideOnScroll()
                val listState = rememberLazyListState()
                ShowBarsWhenStuck(hide, listState, hasList = count > 0)
                Scaffold(
                    modifier = Modifier.nestedScroll(hide.connection),
                    bottomBar = {
                        HideOnScrollFooter(hide) {
                            MonostrBottomBar(BottomTab.BOOKMARKS, unread = 0, dmUnread = 0, onFeed = {}, onSearch = {}, onBookmarks = hide::show, onMessages = {}, onNotifications = {}, windowInsets = WindowInsets(0))
                        }
                    },
                ) { padding ->
                    if (count == 0) {
                        Box(Modifier.padding(padding).fillMaxSize()) { Text("empty") }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize().testTag("list")) {
                            items(count) { Text("row $it", Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        val footer = rule.onNodeWithTag("hide-on-scroll-footer")
        val shown = footer.getBoundsInRoot().let { it.bottom - it.top }
        fun hideBar() {
            rule.onNodeWithTag("list").performTouchInput { swipeUp() }
            rule.waitForIdle()
            val h = footer.getBoundsInRoot().let { it.bottom - it.top }
            assertTrue("footer still $h high", h < 1.dp)
        }
        fun assertBack(case: String) {
            rule.waitForIdle()
            val h = footer.getBoundsInRoot().let { it.bottom - it.top }
            assertTrue("$case: footer only $h of $shown back", h > shown - 1.dp)
            rule.onNodeWithTag("tab-bookmarks").assertIsDisplayed()
        }

        hideBar()
        count = 3 // the list shrinks and fits on screen
        assertBack("short list")

        count = 200
        rule.waitForIdle()
        hideBar()
        count = 0 // the last entry is removed: an empty state replaces the list
        assertBack("empty state")
    }
}
