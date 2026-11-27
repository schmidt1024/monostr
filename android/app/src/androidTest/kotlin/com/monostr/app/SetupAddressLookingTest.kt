package com.monostr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.monero.AddressStep
import com.monostr.app.ui.monero.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backlog 121: while the relays are asked what the account already has, the address step says so under its field
 * (the owner took the empty form for the final word). The form stays usable and nothing moves when the answer is in.
 */
@RunWith(AndroidJUnit4::class)
class SetupAddressLookingTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theStepSaysItIsLookingAndNothingMovesWhenItEnds() {
        var looking by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                Column { AddressStep(SetupStep.Address(""), intro = true, looking = looking, error = null, onInput = {}, onPublish = {}, onCreateWallet = {}, onSkip = {}) }
            }
        }
        rule.onNodeWithTag("setup-address-looking").assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.setup_address_looking)).assertIsDisplayed()
        rule.onNodeWithTag("setup-payto-address").assertIsEnabled()
        rule.onNodeWithTag("setup-skip").assertIsEnabled()
        val before = rule.onNodeWithTag("setup-skip").getUnclippedBoundsInRoot()
        looking = false
        rule.waitForIdle()
        rule.onNodeWithTag("setup-address-looking").assertDoesNotExist()
        rule.onNodeWithText(ctx.getString(R.string.setup_address_hint)).assertIsDisplayed()
        assertEquals("«Later» stays where it was", before, rule.onNodeWithTag("setup-skip").getUnclippedBoundsInRoot())
    }
}
