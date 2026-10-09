package com.monostr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.settings.RelayListActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three actions on the whole relay list (v0.8.5): each replaces a list for good, so each asks
 * first, and says in which direction it goes. v0.8.4 had "publish" and "adopt" far apart under
 * similar names; the wrong one replaced the app's relays by a published list full of dead relays.
 */
@RunWith(AndroidJUnit4::class)
class RelayListActionsTest {
    @get:Rule val rule = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val calls = ArrayList<String>()

    private fun show(isDefault: Boolean = false, busy: Boolean = false) = rule.setContent {
        MaterialTheme {
            // a column as in the settings screen: without one the three buttons lie on top of each other
            Column {
                RelayListActions(
                    isDefault = isDefault, busy = busy,
                    onPublish = { calls += "publish" }, onAdopt = { calls += "adopt" }, onReset = { calls += "reset" },
                )
            }
        }
    }

    @Test
    fun publishAsksFirstAndOnlyTheConfirmationPublishes() {
        show()
        rule.onNodeWithTag("settings-publish-relays-confirm").assertDoesNotExist()
        rule.onNodeWithTag("settings-publish-relays").performClick()
        // the hint also stands under the button, so the dialog is told by its own buttons
        rule.onNodeWithTag("settings-publish-relays-confirm").assertIsDisplayed()
        rule.onNodeWithTag("settings-confirm-cancel").performClick()
        rule.onNodeWithTag("settings-publish-relays-confirm").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("settings-publish-relays").performClick()
        rule.onNodeWithTag("settings-publish-relays-confirm").performClick()
        rule.onNodeWithTag("settings-publish-relays-confirm").assertDoesNotExist()
        assertEquals(listOf("publish"), calls)
    }

    @Test
    fun adoptAsksFirstAndSaysWhatItReplaces() {
        show()
        rule.onNodeWithTag("settings-adopt-relays").performClick()
        rule.onNodeWithText(str(R.string.settings_adopt_relays_hint)).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("settings-confirm-cancel").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("settings-adopt-relays").performClick()
        rule.onNodeWithTag("settings-adopt-relays-confirm").performClick()
        assertEquals(listOf("adopt"), calls)
    }

    @Test
    fun resetIsOfferedAwayFromTheDefaultsAndAsksFirst() {
        show()
        rule.onNodeWithTag("settings-reset-relays").assertIsDisplayed().performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText(str(R.string.settings_reset_relays_hint)).assertIsDisplayed()
        rule.onNodeWithTag("settings-reset-relays-confirm").performClick()
        assertEquals(listOf("reset"), calls)
    }

    @Test
    fun withTheDefaultRelaysThereIsNothingToReset() {
        show(isDefault = true)
        rule.onNodeWithTag("settings-reset-relays").assertDoesNotExist()
        rule.onNodeWithTag("settings-publish-relays").assertIsDisplayed()
    }

    @Test
    fun whileOneActionRunsNoOtherCanStart() {
        show(busy = true)
        rule.onNodeWithTag("settings-publish-relays").assertIsNotEnabled()
        rule.onNodeWithTag("settings-adopt-relays").assertIsNotEnabled()
        rule.onNodeWithTag("settings-reset-relays").assertIsNotEnabled()
    }
}
