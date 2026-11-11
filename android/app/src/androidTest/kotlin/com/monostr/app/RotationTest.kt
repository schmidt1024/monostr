package com.monostr.app

import android.content.pm.ActivityInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.media.AllowRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RotationTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun appIsPortraitAndTheViewerAllowsRotationWhileShown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var orientation = -1
            fun read() = scenario.onActivity { orientation = it.requestedOrientation }
            rule.waitForIdle()
            read()
            assertEquals("portrait by default", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)

            var shown by mutableStateOf(true)
            scenario.onActivity { it.setContent { if (shown) AllowRotation() } }
            rule.waitForIdle()
            read()
            assertNotEquals("rotation allowed while shown", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)

            shown = false
            rule.waitForIdle()
            read()
            assertEquals("portrait restored on leaving", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
        }
    }
}
