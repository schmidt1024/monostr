package com.monostr.app

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.tips.TipPhase
import com.monostr.app.ui.tips.TipSheetContent
import com.monostr.app.ui.tips.TipSheetController
import com.monostr.app.ui.tips.TipSheetState
import com.monostr.app.ui.tips.TipTarget
import com.monostr.nostr.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Spec 7.4: presets, free-amount validation, "no Monero" and "no wallet" states of the TipSheet. */
@RunWith(AndroidJUnit4::class)
class TipSheetTest {
    @get:Rule val rule = createComposeRule()

    private val note = Note("1".repeat(64), "a".repeat(64), "tip me", 0, 1, null, null, null, emptyList())

    private fun str(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun render(state: TipSheetState, onSelect: (Long) -> Unit = {}, onCustom: (String) -> Unit = {}, onSend: () -> Unit = {}, onAnonymous: (Boolean) -> Unit = {}) {
        rule.setContent { MaterialTheme { TipSheetContent(state, onSelect, onCustom, onAnonymous, onSend, {}, {}) } }
    }

    @Test
    fun presetsAreShownAndSelectable() {
        val picked = ArrayList<Long>()
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Ready, selected = 1_000_000_000L), onSelect = { picked += it })
        rule.onNodeWithText(str(R.string.tip_amount)).assertIsDisplayed()
        rule.onNodeWithText("0.0001").assertIsDisplayed()
        rule.onNodeWithText("0.001").assertIsDisplayed()
        rule.onNodeWithText("0.01").assertIsDisplayed()
        rule.onNodeWithTag("tip-preset-1").performClick()
        assertEquals(listOf(1_000_000_000L), picked)
        rule.onNodeWithTag("tip-send").assertIsEnabled()
    }

    @Test
    fun freeAmountIsForwardedAndErrorsAreShown() {
        val typed = ArrayList<String>()
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Ready, custom = "", error = TipSheetController.ERROR_AMOUNT), onCustom = { typed += it })
        rule.onNodeWithTag("tip-custom").performTextInput("0,005")
        assertEquals(listOf("0,005"), typed)
        rule.onNodeWithText(str(R.string.tip_error_amount)).assertIsDisplayed()
    }

    @Test
    fun noMoneroStateHasNoSendButton() {
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.NoMonero))
        rule.onNodeWithTag("tip-no-monero").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tip_no_monero)).assertIsDisplayed()
        rule.onAllNodesWithTag("tip-send").assertCountEquals(0)
    }

    @Test
    fun sendingDisablesTheButton() {
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Sending, selected = 1_000_000_000L))
        rule.onNodeWithTag("tip-send").assertIsNotEnabled()
    }

    @Test
    fun fallbackDialogShowsAddressAndAmount() {
        val address = "4" + "A".repeat(105)
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Pay("monero:$address?tx_amount=0.005", address, 5_000_000_000L, walletMissing = true)))
        rule.onNodeWithTag("tip-fallback").assertIsDisplayed()
        rule.onNodeWithTag("tip-fallback-amount").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tip_xmr_amount, "0.005")).assertIsDisplayed()
        rule.onNodeWithTag("tip-fallback-address").assertIsDisplayed()
        rule.onNodeWithTag("tip-copy-address").assertIsDisplayed()
        rule.onNodeWithTag("tip-open-wallet").assertIsDisplayed()
        rule.onAllNodesWithTag("tip-sent").assertCountEquals(0)
    }

    @Test
    fun anonymousSwitchIsOnByDefaultAndForwardsTheToggle() {
        val toggled = ArrayList<Boolean>()
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Ready, selected = 1_000_000_000L), onAnonymous = { toggled += it })
        rule.onNodeWithTag("tip-anonymous").assertIsOn()
        rule.onNodeWithText(str(R.string.tip_anonymous_switch)).assertIsDisplayed()
        rule.onNodeWithTag("tip-anonymous").performClick()
        assertEquals(listOf(false), toggled)
    }

    @Test
    fun aProfileTipHasNoCommentField() {
        render(TipSheetState(target = TipTarget.Profile("a".repeat(64)), phase = TipPhase.Ready, selected = 1_000_000_000L))
        rule.onAllNodesWithTag("tip-comment").assertCountEquals(0)
        rule.onNodeWithTag("tip-send").assertIsEnabled()
    }

    @Test
    fun anonymousUnavailableIsShownBelowTheSwitchNotAsAnAmountError() {
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Ready, selected = 1_000_000_000L, error = TipSheetController.ERROR_ANON_UNAVAILABLE))
        rule.onNodeWithTag("tip-error").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tip_error_anon_unavailable)).assertIsDisplayed()
        val switchBottom = rule.onNodeWithTag("tip-anonymous").getBoundsInRoot().bottom
        val errorTop = rule.onNodeWithTag("tip-error").getBoundsInRoot().top
        assertTrue("the error belongs below the switch", errorTop >= switchBottom)
    }

    @Test
    fun arrivalReplacesTheWaitingText() {
        val address = "4" + "A".repeat(105)
        render(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.Pay("monero:$address?tx_amount=0.005", address, 5_000_000_000L, walletMissing = true, arrived = true), notice = TipSheetController.NOTICE_OFFLINE))
        rule.onNodeWithTag("tip-arrived").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tip_arrived)).assertIsDisplayed()
        rule.onAllNodesWithTag("tip-sent").assertCountEquals(0)
        rule.onAllNodesWithTag("tip-fallback").assertCountEquals(0)
        rule.onAllNodesWithText(str(R.string.tip_notice_offline)).assertCountEquals(0)
        rule.onNodeWithTag("tip-done").assertIsDisplayed()
    }

    @Test
    fun germanConfigurationShowsGermanTexts() {
        rule.setContent {
            val base = LocalContext.current
            val current = LocalConfiguration.current
            val config = Configuration(current).apply { setLocale(Locale.GERMANY) }
            val localized = base.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                MaterialTheme { TipSheetContent(TipSheetState(target = TipTarget.Note(note), phase = TipPhase.NoMonero), {}, {}, {}, {}, {}, {}) }
            }
        }
        rule.onNodeWithText("Empfänger hat kein Monero eingerichtet").assertIsDisplayed()
        rule.onNodeWithText("Schließen").assertIsDisplayed()
    }
}
