package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.StartScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 3: logo centre at 38.2 % of the height, the spinner only after 1.5 s (test clock), never on a fast restore. */
@RunWith(AndroidJUnit4::class)
class StartScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun theSpinnerAppearsOnlyAfterOneAndAHalfSeconds() {
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme { StartScreen() } }
        rule.mainClock.advanceTimeBy(1_400)
        rule.onNodeWithTag("start-spinner").assertDoesNotExist()
        rule.onNodeWithTag("start-logo").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("start-spinner").assertIsDisplayed()
    }

    @Test
    fun aRestoreFasterThanTheDelayNeverShowsTheSpinner() {
        rule.mainClock.autoAdvance = false
        var loading by mutableStateOf(true)
        rule.setContent { MaterialTheme { if (loading) StartScreen() else Text("feed") } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("start-screen").assertExists()
        rule.onNodeWithTag("start-spinner").assertDoesNotExist()
        loading = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("start-spinner").assertDoesNotExist()
        rule.onNodeWithTag("start-screen").assertDoesNotExist()
    }

    @Test
    fun theLogoCentreSitsAtTheGoldenSection() {
        rule.setContent { MaterialTheme { StartScreen() } }
        val root = rule.onRoot().getBoundsInRoot()
        val logo = rule.onNodeWithTag("start-logo").getBoundsInRoot()
        val centre = (logo.top + logo.bottom) / 2
        val expected = (root.bottom - root.top) * 0.382f
        assertEquals(expected.value, centre.value, 1.dp.value)
        assertEquals(96.dp.value, (logo.bottom - logo.top).value, 0.5f)
    }
}
