package com.monostr.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode
import com.monostr.app.ui.settings.AppearanceSection
import com.monostr.app.ui.theme.MonostrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** v0.12.7: five accent choices with their names, the hue slider only for the custom one. */
@RunWith(AndroidJUnit4::class)
class AppearanceSectionTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun fiveChoicesWithNamesAndTheSliderOnlyForCustom() {
        var accent by mutableStateOf(Accent.MONO)
        var hue by mutableStateOf(200f)
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, accent, hue) {
                Box(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp).fillMaxWidth().testTag("appearance-root")) {
                    AppearanceSection(ThemeMode.LIGHT, accent, hue, onMode = {}, onAccent = { accent = it }, onHue = { hue = it })
                }
            }
        }
        assertEquals(listOf("mono", "orange", "blue", "purple", "custom"), Accent.entries.map { it.name.lowercase() })
        for (a in Accent.entries) rule.onNodeWithTag("appearance-accent-${a.name.lowercase()}").assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.settings_accent_orange), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("appearance-accent-mono").assertIsSelected()
        assertEquals(0, nodes("appearance-hue").size)
        rule.onNodeWithTag("appearance-accent-custom").performClick()
        rule.waitUntil(5_000) { nodes("appearance-hue").isNotEmpty() }
        rule.onNodeWithTag("appearance-accent-custom").assertIsSelected()
        // a picture for the eye: pulled with `run-as com.monostr.app.debug cat files/appearance.png`
        val bitmap = rule.onNodeWithTag("appearance-root").captureToImage().asAndroidBitmap()
        File(ctx.filesDir, "appearance.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
