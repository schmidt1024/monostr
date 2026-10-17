package com.monostr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.monero.PersonalStep
import com.monostr.app.ui.monero.SetupStep
import com.monostr.app.ui.monero.WatcherStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec §12: progress, slow hint, Cancel and "Try again" of the watcher checks. */
@RunWith(AndroidJUnit4::class)
class SetupProgressTest {
    @get:Rule val rule = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val url = "https://watcher.monostr.com"

    private fun watcher(step: SetupStep.Watcher, error: String? = null, onCancel: () -> Unit = {}) = rule.setContent {
        MaterialTheme { Column { WatcherStep(step, false, error, {}, {}, onCancel, {}, {}) } }
    }

    @Test
    fun runningCheckShowsProgressAndSpinnerLabel() {
        watcher(SetupStep.Watcher(url, loading = true, checking = true))
        rule.onNodeWithTag("setup-progress").assertIsDisplayed()
        rule.onNodeWithTag("setup-load").assertIsNotEnabled().assertTextContains(str(R.string.setup_checking))
        rule.onNodeWithTag("setup-slow-hint").assertDoesNotExist()
        rule.onNodeWithTag("setup-cancel-check").assertDoesNotExist()
    }

    @Test
    fun slowCheckOffersCancel() {
        var cancels = 0
        watcher(SetupStep.Watcher(url, loading = true, checking = true, slow = true), onCancel = { cancels++ })
        rule.onNodeWithTag("setup-slow-hint").assertIsDisplayed().assertTextContains(str(R.string.setup_slow_hint))
        rule.onNodeWithTag("setup-cancel-check").assertIsDisplayed().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun timeoutTurnsTheButtonIntoTryAgain() {
        watcher(SetupStep.Watcher(url, retry = true), error = str(R.string.setup_err_timeout))
        rule.onNodeWithTag("setup-progress").assertDoesNotExist()
        rule.onNodeWithTag("setup-load").assertTextContains(str(R.string.setup_try_again))
        rule.onNodeWithText(str(R.string.setup_err_timeout)).assertIsDisplayed()
    }

    @Test
    fun startPhaseShowsProgressWithLoadingLabel() {
        watcher(SetupStep.Watcher(url, loading = true))
        rule.onNodeWithTag("setup-progress").assertIsDisplayed()
        rule.onNodeWithTag("setup-load").assertIsNotEnabled().assertTextContains(str(R.string.state_loading))
        rule.onNodeWithTag("setup-slow-hint").assertDoesNotExist()
    }

    @Test
    fun slowStartPhaseShowsTheHintButNoCancel() {
        watcher(SetupStep.Watcher(url, loading = true, slow = true))
        rule.onNodeWithTag("setup-progress").assertIsDisplayed()
        rule.onNodeWithTag("setup-slow-hint").assertIsDisplayed()
        rule.onNodeWithTag("setup-cancel-check").assertDoesNotExist()
        rule.onNodeWithTag("setup-load").assertTextContains(str(R.string.state_loading))
    }

    @Test
    fun personalCheckShowsTheSameProgress() {
        var cancels = 0
        rule.setContent { MaterialTheme { Column { PersonalStep(SetupStep.Personal("nprofile1xyz", "", loading = true, slow = true), null, {}, {}, {}, {}, { cancels++ }) } } }
        rule.onNodeWithTag("setup-personal-progress").assertIsDisplayed()
        rule.onNodeWithTag("setup-personal-load").assertTextContains(str(R.string.setup_checking))
        rule.onNodeWithTag("setup-personal-cancel-check").performClick()
        assertEquals(1, cancels)
    }
}
