package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.settings.AboutContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 5: version and build, the Beta badge, feedback profile, issues link, licence; empty values hide their line. */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val feedback = "1".repeat(64)

    @Test
    fun everyLineShowsAndTheLinksCallBack() {
        val opened = ArrayList<String>()
        var issues = 0
        rule.setContent {
            MaterialTheme {
                AboutContent("0.7.0", 412, beta = true, feedbackPubkey = feedback, issuesUrl = "https://github.com/schmidt1024/monostr/issues", license = "MIT", onFeedback = { opened += it }, onIssues = { issues++ })
            }
        }
        rule.onNodeWithText(ctx.getString(R.string.about_version, "0.7.0", 412)).assertIsDisplayed()
        rule.onNodeWithTag("about-beta").assertIsDisplayed()
        rule.onNodeWithTag("about-feedback").performScrollTo().performClick()
        rule.onNodeWithTag("about-issues").performScrollTo().performClick()
        rule.onNodeWithText(ctx.getString(R.string.about_license, "MIT")).performScrollTo().assertIsDisplayed()
        assertEquals(listOf(feedback), opened)
        assertEquals(1, issues)
    }

    @Test
    fun emptyValuesHideTheirLinesAndNoBetaAfterOnePointZero() {
        rule.setContent {
            MaterialTheme { AboutContent("1.0.0", 500, beta = false, feedbackPubkey = null, issuesUrl = "", license = "", onFeedback = {}, onIssues = {}) }
        }
        rule.onNodeWithTag("about-version").assertIsDisplayed()
        rule.onNodeWithTag("about-beta").assertDoesNotExist()
        rule.onNodeWithTag("about-feedback").assertDoesNotExist()
        rule.onNodeWithTag("about-issues").assertDoesNotExist()
        rule.onNodeWithTag("about-license").assertDoesNotExist()
    }

    @Test
    fun theMediaLineNamesTheServerAndIsAbsentWithoutOne() {
        var host by androidx.compose.runtime.mutableStateOf("media.monostr.com")
        rule.setContent {
            MaterialTheme {
                AboutContent("0.9.0", 500, beta = false, feedbackPubkey = null, issuesUrl = "", license = "", onFeedback = {}, onIssues = {}, mediaHost = host)
            }
        }
        rule.onNodeWithTag("about-media").performScrollTo().assertIsDisplayed().assertTextContains("media.monostr.com", substring = true)
        host = ""
        rule.waitForIdle()
        org.junit.Assert.assertTrue(rule.onAllNodes(hasTestTag("about-media")).fetchSemanticsNodes().isEmpty())
    }
}
