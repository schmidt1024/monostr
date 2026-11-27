package com.monostr.app

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.nav.back
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner, Pixel 2026-11-26 14:08: a second tap where a back arrow had been (the feed's wordmark sits there) left the app
 * black. A bare `popBackStack()` pops the start destination too; [back] never does and ignores a tap during a transition.
 */
@RunWith(AndroidJUnit4::class)
class NavBackTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun show() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "feed") {
                composable("feed") { Text("feed", Modifier.testTag("feed")) }
                composable("profile") { Text("profile", Modifier.testTag("profile")) }
            }
        }
        rule.runOnUiThread { nav.navigate("profile") }
        rule.waitUntil(5_000) { nav.currentBackStackEntry?.destination?.route == "profile" && nav.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
    }

    @Test
    fun aBarePopTwiceLeavesNothingOnTheStack() {
        show()
        rule.runOnUiThread { nav.popBackStack(); nav.popBackStack() }
        rule.waitForIdle()
        assertNull("this is the black screen", nav.currentBackStackEntry)
    }

    @Test
    fun backTwiceInARowStopsAtTheFeed() {
        show()
        rule.runOnUiThread { nav.back(); nav.back() }
        rule.waitForIdle()
        assertEquals("feed", nav.currentBackStackEntry?.destination?.route)
        rule.onNodeWithTag("feed").assertExists()
        // and once more at rest: the last screen never goes
        rule.waitUntil(5_000) { nav.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        rule.runOnUiThread { nav.back() }
        rule.waitForIdle()
        assertEquals("feed", nav.currentBackStackEntry?.destination?.route)
    }
}
