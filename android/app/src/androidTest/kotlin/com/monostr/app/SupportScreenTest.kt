package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.settings.SupportContent
import com.monostr.app.ui.settings.SupportFallbackDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 4: text, Monero button, contributors and repository link; empty values hide their section; the no-wallet fallback. */
@RunWith(AndroidJUnit4::class)
class SupportScreenTest {
    @get:Rule val rule = createComposeRule()
    private val address = "8ADYcUEb8YkXkfDVYFv1hm5FzxueStAyhH39wBAGxQMtG1YzfV3He7vWoMyR9WZF4reJ7wh9TyQHZ5SfScBXvAve4RFq7vd"
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun allSectionsShowAndTheButtonsCallBack() {
        var donated = 0
        var repo = 0
        rule.setContent {
            MaterialTheme { SupportContent(address, "https://github.com/schmidt1024/monostr", listOf("Schmidt", "Alice"), onDonate = { donated++ }, onOpenRepo = { repo++ }) }
        }
        rule.onNodeWithTag("support-text").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.support_text)).assertIsDisplayed()
        rule.onNodeWithTag("support-donate").performClick()
        rule.onNodeWithTag("support-contributors").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("support-repo").performScrollTo().performClick()
        assertEquals(1, donated)
        assertEquals(1, repo)
    }

    @Test
    fun emptyValuesHideTheirSections() {
        rule.setContent { MaterialTheme { SupportContent("", "", emptyList(), onDonate = {}, onOpenRepo = {}) } }
        rule.onNodeWithTag("support-text").assertIsDisplayed()
        rule.onNodeWithTag("support-donate").assertDoesNotExist()
        rule.onNodeWithTag("support-contributors").assertDoesNotExist()
        rule.onNodeWithTag("support-repo").assertDoesNotExist()
    }

    @Test
    fun theNoWalletFallbackShowsTheAddress() {
        rule.setContent { MaterialTheme { SupportFallbackDialog(address, onDismiss = {}) } }
        rule.onNodeWithText(str(R.string.tip_no_wallet_title)).assertIsDisplayed()
        rule.onNodeWithTag("support-fallback-address").assertIsDisplayed()
        rule.onNodeWithText(address).assertIsDisplayed()
    }
}
