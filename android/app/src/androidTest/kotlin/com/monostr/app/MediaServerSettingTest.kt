package com.monostr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.data.MediaServer
import com.monostr.app.ui.settings.MediaServerSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 5.4: the media server field takes an https host, refuses anything else, and goes back to the default. */
@RunWith(AndroidJUnit4::class)
class MediaServerSettingTest {
    @get:Rule val rule = createComposeRule()
    // unmerged: the error text sits inside the text field, whose semantics swallow the tag of a child
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun savesAValidAddressRefusesAnInvalidOneAndResets() {
        var stored by mutableStateOf(MediaServer.DEFAULT)
        val saved = ArrayList<String>()
        rule.setContent {
            MaterialTheme {
                // several siblings: without a Column they would lie on top of each other
                Column {
                    MediaServerSetting(
                        current = stored,
                        onSave = { input -> MediaServer.normalize(input)?.let { stored = it; saved += it; true } ?: false },
                        onReset = { stored = MediaServer.DEFAULT },
                    )
                }
            }
        }
        rule.onNodeWithTag("settings-media-server").assertTextContains(MediaServer.DEFAULT)
        assertTrue(nodes("settings-media-server-reset").isEmpty()) // nothing to reset while the default is set

        rule.onNodeWithTag("settings-media-server").performTextClearance()
        rule.onNodeWithTag("settings-media-server").performTextInput("http://plain.example")
        rule.onNodeWithTag("settings-media-server-save").performClick()
        rule.onNodeWithTag("settings-media-server-error", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(saved.isEmpty())

        rule.onNodeWithTag("settings-media-server").performTextClearance()
        rule.onNodeWithTag("settings-media-server").performTextInput("https://Blossom.Example/")
        rule.onNodeWithTag("settings-media-server-save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://blossom.example"), saved)
        assertTrue(nodes("settings-media-server-error").isEmpty())
        rule.onNodeWithTag("settings-media-server").assertTextContains("https://blossom.example")

        rule.onNodeWithTag("settings-media-server-reset").performClick()
        rule.waitForIdle()
        assertEquals(MediaServer.DEFAULT, stored)
        rule.onNodeWithTag("settings-media-server").assertTextContains(MediaServer.DEFAULT)
    }
}
