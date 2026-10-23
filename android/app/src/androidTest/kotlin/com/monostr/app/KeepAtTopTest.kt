package com.monostr.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.KeepAtTop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bug 25: a list standing at its top shows what arrives above (a live notification); one scrolled down keeps its place. */
@RunWith(AndroidJUnit4::class)
class KeepAtTopTest {
    @get:Rule val rule = createComposeRule()

    private val rows = mutableStateOf((1..30).map { "old $it" })

    private fun show() = rule.setContent {
        val state = rememberLazyListState()
        KeepAtTop(state, rows.value.firstOrNull())
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("list")) {
            items(rows.value, key = { it }) { Text(it, Modifier.height(80.dp)) }
        }
    }

    @Test
    fun atTheTopANewFirstRowIsShown() {
        show()
        rule.runOnIdle { rows.value = listOf("new 1") + rows.value }
        rule.onNodeWithText("new 1").assertIsDisplayed()
    }

    @Test
    fun scrolledDownTheReadingPlaceStays() {
        show()
        rule.onNodeWithTag("list").performScrollToIndex(10)
        rule.runOnIdle { rows.value = listOf("new 1") + rows.value }
        rule.waitForIdle()
        rule.onNodeWithText("old 11").assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("new 1")).fetchSemanticsNodes().isEmpty())
    }
}
