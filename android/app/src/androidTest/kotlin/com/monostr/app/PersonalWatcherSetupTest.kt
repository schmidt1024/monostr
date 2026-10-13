package com.monostr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.monero.PersonalConfirmStep
import com.monostr.app.ui.monero.PersonalStep
import com.monostr.app.ui.monero.SetupStep
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.WatcherStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalWatcherSetupTest {
    @get:Rule val rule = createComposeRule()
    private fun str(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private val address = MoneroKeys.generate().address(Network.MAINNET)
    private val status = WatcherStatus("a".repeat(64), "b".repeat(64), address, Network.MAINNET, 3_512_345, "0.4.0", 1_000)

    @Test
    fun personalStepCollectsInputAndShowsTheError() {
        val inputs = ArrayList<String>()
        var loads = 0
        rule.setContent {
            var step by remember { mutableStateOf(SetupStep.Personal("", "")) }
            MaterialTheme { Column { PersonalStep(step, str(R.string.setup_personal_err_not_found), { inputs += it; step = step.copy(input = it) }, {}, { loads++ }, {}) } }
        }
        rule.onNodeWithTag("setup-personal-input").performTextInput("nprofile1xyz")
        rule.onNodeWithTag("setup-personal-load").performClick()
        assertEquals(listOf("nprofile1xyz"), inputs)
        assertEquals(1, loads)
        rule.onNodeWithText(str(R.string.setup_personal_err_not_found)).assertIsDisplayed()
    }

    @Test
    fun confirmShowsWatcherNetworkHeightAndAddress() {
        var confirmed = false
        rule.setContent { MaterialTheme { Column { PersonalConfirmStep(SetupStep.PersonalConfirm(status, listOf("wss://w.example")), null, { confirmed = true }, {}) } } }
        val a = address.encode()
        rule.onNodeWithTag("setup-personal-info").assertTextContains(a.take(8), substring = true).assertTextContains(a.takeLast(6), substring = true).assertTextContains("3512345", substring = true)
        rule.onNodeWithTag("setup-personal-confirm").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun unknownHeightIsSaidSo() {
        rule.setContent { MaterialTheme { Column { PersonalConfirmStep(SetupStep.PersonalConfirm(status.copy(height = 0), listOf("wss://w.example")), null, {}, {}) } } }
        rule.onNodeWithTag("setup-personal-info").assertTextContains(str(R.string.setup_personal_height_unknown), substring = true)
    }
}
