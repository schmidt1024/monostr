package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.monero.SeedCopyButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Seed copy is opt-in: a warning dialog first, the words reach the clipboard callback only on confirm. */
@RunWith(AndroidJUnit4::class)
class SeedCopyTest {
    @get:Rule val rule = createComposeRule()

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun copyAsksFirstAndHandsOverTheWordsOnConfirm() {
        val copied = ArrayList<String>()
        rule.setContent { MaterialTheme { SeedCopyButton("alpha beta gamma") { copied += it } } }
        rule.onNodeWithText(str(R.string.seed_copy_warning_title)).assertDoesNotExist()
        rule.onNodeWithTag("seed-copy").performClick()
        rule.onNodeWithText(str(R.string.seed_copy_warning_title)).assertIsDisplayed()
        assertEquals(emptyList<String>(), copied)
        rule.onNodeWithTag("seed-copy-confirm").performClick()
        assertEquals(listOf("alpha beta gamma"), copied)
        rule.onNodeWithText(str(R.string.seed_copy_warning_title)).assertDoesNotExist()
    }

    @Test
    fun cancelCopiesNothing() {
        val copied = ArrayList<String>()
        rule.setContent { MaterialTheme { SeedCopyButton("alpha beta gamma") { copied += it } } }
        rule.onNodeWithTag("seed-copy").performClick()
        rule.onNodeWithTag("seed-copy-cancel").performClick()
        assertEquals(emptyList<String>(), copied)
        rule.onNodeWithText(str(R.string.seed_copy_warning_title)).assertDoesNotExist()
    }
}
