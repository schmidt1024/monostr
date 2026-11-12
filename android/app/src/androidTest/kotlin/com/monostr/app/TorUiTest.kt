package com.monostr.app

import com.monostr.app.ui.settings.SettingsPage
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.net.TorRestartSeam
import com.monostr.app.net.TorState
import com.monostr.app.net.TorSwitchError
import com.monostr.app.net.TorTurn
import com.monostr.app.net.TorUi
import com.monostr.app.ui.common.LinkPromptHost
import com.monostr.app.ui.common.LocalCanSend
import com.monostr.app.nav.Routes
import com.monostr.app.ui.common.LocalTorSheets
import com.monostr.app.ui.common.ProvideTorUi
import com.monostr.app.ui.common.TorReturnEffect
import com.monostr.app.ui.common.TorSheets
import com.monostr.app.ui.common.TorBanner
import com.monostr.app.ui.common.TorBannerSlot
import com.monostr.app.ui.common.TorDetailsContent
import com.monostr.app.ui.common.TorReturnForget
import com.monostr.app.ui.common.TorIcon
import com.monostr.app.ui.common.TorLoginRow
import com.monostr.app.ui.common.TorSettingsRow
import com.monostr.app.ui.common.reducedAnimations
import com.monostr.app.ui.common.torAwareEmptyText
import com.monostr.app.ui.feed.FeedTopBar
import com.monostr.app.ui.theme.MonostrTheme
import com.monostr.app.ui.theme.StatusColors
import com.monostr.app.ui.tips.TipPhase
import com.monostr.app.ui.tips.TipSheetContent
import com.monostr.app.ui.tips.TipSheetState
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode
import com.monostr.nostr.model.RelayState
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.SessionState
import com.monostr.nostr.net.NetworkMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.test.performSemanticsAction

/** The screens against a fake Tor: what each state shows, and that there is no quick way back to direct. */
@RunWith(AndroidJUnit4::class)
class TorUiTest {
    @get:Rule val rule = createComposeRule()

    private class FakeTorUi(on: TorState? = null, tips: Int = 0) : TorUi {
        override val active = MutableStateFlow(on != null)
        override val state = MutableStateFlow(on)
        override val turning = MutableStateFlow<TorTurn?>(null)
        override val error = MutableStateFlow<TorSwitchError?>(null)
        var ons = 0
        var offs = 0
        var retries = 0
        val tipCount = tips
        var tipsFail = false
        override fun turnOn() { ons++; turning.value = TorTurn.ON }
        override fun turnOff() { offs++; turning.value = TorTurn.OFF }
        var retryIsASwitch = false
        override fun retry(): Boolean { retries++; return retryIsASwitch }
        override fun clearError() { error.value = null }
        override suspend fun tipsToDrop(): Int { if (tipsFail) error("unreadable"); return tipCount }
        var restarts = false
        override val restartsOnSwitch get() = restarts
        val armed = ArrayList<String?>()
        override fun armRestartReturn(route: String?) { armed += route }
        var stored: String? = null
        override fun takeRestartReturn(): String? = stored.also { stored = null }
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private fun show(ui: FakeTorUi, width: Int = 360, fontScale: Float = 1f) {
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    ProvideTorUi(ui) {
                        Column(Modifier.width(width.dp)) {
                            FeedTopBar(listOf(RelayState.CONNECTED), null, {}, {}, {})
                            TorBannerSlot()
                            TorReturnEffect {}
                        }
                    }
                }
            }
        }
    }

    private val TOR_CREDIT = "Tor is a trademark of The Tor Project; all rights reserved. This product is not endorsed or sponsored by, or affiliated with, the Tor Project."

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
    private fun centre(tag: String) = bounds(tag).let { (it.left + it.right).value / 2 }
    private fun near(a: Float, b: Float, by: Float = 1f) = assertTrue("$a vs $b", abs(a - b) <= by)

    @Test
    fun switchOffShowsNoIconNoBannerAndReadsNothingOfTor() {
        val ui = FakeTorUi()
        show(ui)
        rule.onNodeWithTag("tor-icon").assertDoesNotExist()
        rule.onNodeWithTag("tor-banner").assertDoesNotExist()
        assertEquals("nothing collects Tor's state with the switch off", 0, ui.state.subscriptionCount.value)
        assertEquals(0, ui.turning.subscriptionCount.value)
        assertEquals(0, ui.error.subscriptionCount.value)
        // the bar as it was, by centres as the buttons draw less than their 48 dp: 4 dp from the right, the avatar, the settings, the 34 dp dot
        near(360f - 4 - 24, centre("feed-profile"))
        near(360f - 4 - 72, centre("feed-settings"))
        near(360f - 4 - 96 - 17, centre("relay-dot"))
    }

    @Test
    fun connectingShowsTheBannerAndAnnouncesIt() {
        show(FakeTorUi(TorState.Connecting(null)))
        rule.onNodeWithTag("tor-icon").assertIsDisplayed().assertHasClickAction().assertContentDescriptionEquals(str(R.string.tor_desc_connecting))
        rule.onNodeWithText("Tor connecting, stored content only").assertIsDisplayed()
    }

    @Test
    fun connectedHasNoBannerAndIsTheRelayDotsGreen() {
        show(FakeTorUi(TorState.Connected))
        rule.onNodeWithTag("tor-banner").assertDoesNotExist()
        rule.onNodeWithTag("tor-icon").assertContentDescriptionEquals(str(R.string.tor_desc_connected))
        val map = rule.onNodeWithTag("tor-icon").captureToImage().toPixelMap()
        val colours = (0 until map.width).flatMap { x -> (0 until map.height).map { y -> map[x, y] } }.toSet()
        assertTrue("the relay dot's green", Color(StatusColors.LightOk) in colours)
    }

    @Test
    fun failedOffersTryAgainAndDetailsAndNoWayToDirectInOneTap() {
        val ui = FakeTorUi(TorState.Failed, tips = 3)
        show(ui)
        rule.onNodeWithTag("tor-icon").assertContentDescriptionEquals(str(R.string.tor_desc_failed))
        rule.onNodeWithText("Tor not connected, nothing is loaded").assertIsDisplayed()
        rule.onNodeWithTag("tor-off").assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, ui.retries)
        rule.onNodeWithTag("tor-banner").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-retry-sheet").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("tor-off").performClick()
        rule.waitForIdle()
        assertEquals("Details is not the way out by itself", 0, ui.offs)
        rule.onNodeWithText("Turn off Tor?").assertIsDisplayed()
        rule.onNodeWithText("Relays and servers will see your IP address.").assertIsDisplayed()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("tor-off-count")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("3 pending tips will be dropped.").assertIsDisplayed()
        rule.onNodeWithTag("tor-off-cancel").performClick()
        rule.waitForIdle()
        assertEquals("Cancel keeps Tor", 0, ui.offs)
        rule.onNodeWithTag("tor-off-confirm").assertDoesNotExist()
    }

    @Test
    fun theIconOpensDetailsAndTheConfirmationTurnsOff() {
        val ui = FakeTorUi(TorState.Connecting(40))
        show(ui)
        rule.onNodeWithTag("tor-icon").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-progress").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tor_details_progress, 40)).assertIsDisplayed()
        rule.onNodeWithTag("tor-off").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-off-count").assertDoesNotExist()
        rule.onNodeWithTag("tor-off-confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, ui.offs)
    }

    @Test
    fun aFailedTurnOffShowsTheErrorWithTorStillOnAndAWayOut() {
        val ui = FakeTorUi(TorState.Connected).apply { error.value = TorSwitchError.OFF_FAILED }
        show(ui)
        rule.onNodeWithTag("tor-icon").assertIsDisplayed()
        rule.onNodeWithText("Tor could not be turned off. Tor stays on.").assertIsDisplayed()
        rule.onNodeWithTag("tor-banner-chevron", useUnmergedTree = true).assertExists()
        val line = rule.onNodeWithTag("tor-error", useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals("the failure is announced", LiveRegionMode.Polite, line.getOrNull(SemanticsProperties.LiveRegion))
        rule.onNodeWithTag("tor-banner").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-off").assertIsDisplayed()
    }

    @Test
    fun theBannerShowsItCanBeTappedInEveryState() {
        val ui = FakeTorUi(TorState.Connecting(null))
        show(ui)
        rule.onNodeWithTag("tor-banner-chevron", useUnmergedTree = true).assertExists()
        ui.state.value = TorState.Failed
        rule.waitForIdle()
        rule.onNodeWithTag("tor-banner-chevron", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theFailedBannerAt320TakesTwoLinesInGermanAndTheOthersAreMeasured() {
        val tags = listOf("en", "de", "es", "fr", "it", "pt", "ru", "tr")
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                Column(Modifier.width(320.dp)) {
                    for (tag in tags) {
                        val config = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
                        val local = ctx.createConfigurationContext(config)
                        CompositionLocalProvider(LocalContext provides local, LocalConfiguration provides config, LocalResources provides local.resources) {
                            TorBanner(TorState.Failed, null, null, {}, {})
                        }
                    }
                }
            }
        }
        val lines = tags.mapIndexed { i, tag ->
            val results = ArrayList<TextLayoutResult>()
            rule.onAllNodes(hasTestTag("tor-banner-state"), useUnmergedTree = true)[i].fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
            tag to results.first().lineCount
        }.toMap()
        android.util.Log.i("TorUiTest", "failed banner lines at 320 dp: $lines")
        assertTrue("de at most two lines: $lines", lines.getValue("de") <= 2)
        assertTrue("en at most two lines: $lines", lines.getValue("en") <= 2)
    }

    @Test
    fun theShortLabelIsForTheNarrowPlacesAndOnlyGermanNeedsOne() {
        for (tag in listOf("es", "fr", "it", "pt", "ru", "tr")) {
            val local = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertEquals(tag, local.getString(R.string.tor_try_again), local.getString(R.string.tor_try_again_short))
        }
        val de = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(Locale.GERMAN) })
        assertEquals("Erneut versuchen", de.getString(R.string.tor_try_again))
        assertEquals("Erneut", de.getString(R.string.tor_try_again_short))
    }

    @Test
    fun aLogoutForgetsWhereASwitchWouldHaveReturnedTo() {
        val ui = FakeTorUi()
        var loggedOut by mutableStateOf(false)
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) {
                    sheets = LocalTorSheets.current
                    if (loggedOut) TorReturnForget()
                }
            }
        }
        rule.runOnIdle { sheets.returnTo = "messages" }
        loggedOut = true
        rule.waitForIdle()
        assertEquals(null, sheets.returnTo)
    }

    @Test
    fun whileASwitchIsArmedTheEffectWatchesAndThenLetsGo() {
        val ui = FakeTorUi()
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) {
                    sheets = LocalTorSheets.current
                    TorReturnEffect {}
                }
            }
        }
        rule.waitForIdle()
        assertEquals(0, ui.turning.subscriptionCount.value)
        ui.turning.value = TorTurn.ON
        rule.runOnIdle { sheets.returnRoute = "messages"; sheets.armReturn() }
        rule.waitForIdle()
        assertEquals(1, ui.turning.subscriptionCount.value)
        ui.turning.value = null
        rule.waitForIdle()
        assertEquals(null, sheets.returnTo)
        assertEquals(0, ui.turning.subscriptionCount.value)
    }

    @Test
    fun aTipCountThatCannotBeReadLeavesTheLineOutAndTheQuestionStands() {
        val ui = FakeTorUi(TorState.Connected, tips = 3).apply { tipsFail = true }
        show(ui)
        rule.onNodeWithTag("tor-icon").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-off").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-off-confirm").assertIsDisplayed()
        rule.onNodeWithTag("tor-off-count").assertDoesNotExist()
    }

    @Test
    fun theBannerFitsOneRowItsTryAgainWaitsForASwitchAndItsLineIsAnnounced() {
        val ui = FakeTorUi(TorState.Failed)
        show(ui)
        val banner = bounds("tor-banner")
        assertTrue("about one row", banner.bottom - banner.top <= 64.dp)
        val live = rule.onNodeWithTag("tor-banner-state", useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, live.getOrNull(SemanticsProperties.LiveRegion))
        ui.turning.value = TorTurn.ON
        rule.waitForIdle()
        rule.onNodeWithTag("tor-retry").assertIsNotEnabled()
    }

    @Test
    fun theExplanationSaysTheRestartOnlyWhenOneWillHappen() {
        val ui = FakeTorUi()
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) { sheets = LocalTorSheets.current }
            }
        }
        rule.runOnIdle { sheets.explain = true }
        rule.onNodeWithTag("tor-explain").assertExists()
        rule.onNodeWithTag("tor-explain-restart").assertDoesNotExist()
        rule.onNodeWithTag("tor-explain-cancel").performClick()
        rule.runOnIdle { ui.restarts = true; sheets.explain = true }
        rule.onNodeWithTag("tor-explain-restart").assertExists()
        rule.onNodeWithText(str(R.string.tor_restart_once)).assertExists()
    }

    @Test
    fun confirmingHandsTheRouteOverBeforeTheSwitchStarts() {
        val ui = FakeTorUi()
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) { sheets = LocalTorSheets.current }
            }
        }
        rule.runOnIdle { sheets.returnRoute = "messages"; sheets.explain = true }
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("messages"), ui.armed)
        assertEquals(1, ui.ons)
    }

    @Test
    fun aRouteStoredBeforeARestartIsTakenOnceByTheFirstHost() {
        val ui = FakeTorUi().apply { stored = "settings?section=privacy" }
        val went = ArrayList<String>()
        var host by mutableStateOf(1)
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) { key(host) { TorReturnEffect { went += it } } }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf("settings?section=privacy"), went)
        host = 2
        rule.waitForIdle()
        assertEquals(1, went.size)
        assertEquals(null, ui.stored)
    }

    @Test
    fun aSwitchReturnsOnceToTheScreenItWasAskedOnAndALaterStartDoesNot() {
        val ui = FakeTorUi()
        val went = ArrayList<String>()
        var host by mutableStateOf(1)
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) {
                    sheets = LocalTorSheets.current
                    // the nav host: a new one with each start of the session
                    key(host) { TorReturnEffect { went += it } }
                }
            }
        }
        rule.runOnIdle { sheets.returnRoute = "settings?section=privacy"; sheets.explain = true }
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, ui.ons)
        assertEquals(emptyList<String>(), went)
        host = 2
        rule.waitForIdle()
        assertEquals(listOf("settings?section=privacy"), went)
        host = 3
        rule.waitForIdle()
        assertEquals("a later start stays where it is", 1, went.size)
    }

    @Test
    fun aSwitchThatRebuiltNothingArmsNothingForLater() {
        val ui = FakeTorUi()
        val went = ArrayList<String>()
        var host by mutableStateOf(1)
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) {
                    sheets = LocalTorSheets.current
                    key(host) { TorReturnEffect { went += it } }
                }
            }
        }
        rule.runOnIdle { sheets.returnRoute = "messages"; sheets.armReturn() }
        // the turnOn fails before anything changed: the host stands, the error comes
        ui.error.value = TorSwitchError.ON_FAILED
        rule.waitForIdle()
        host = 2
        rule.waitForIdle()
        assertEquals(emptyList<String>(), went)
    }

    @Test
    fun aRetryThatIsASwitchReturnsToo() {
        val ui = FakeTorUi(TorState.Failed).apply { retryIsASwitch = true }
        lateinit var sheets: TorSheets
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) {
                    sheets = LocalTorSheets.current
                    TorBannerSlot()
                }
            }
        }
        rule.runOnIdle { sheets.returnRoute = "search" }
        rule.onNodeWithTag("tor-retry").performClick()
        rule.waitForIdle()
        assertEquals("search", sheets.returnTo)
        ui.retryIsASwitch = false
        rule.runOnIdle { sheets.returnTo = null }
        rule.onNodeWithTag("tor-retry").performClick()
        rule.waitForIdle()
        assertEquals("a plain retry rebuilds nothing", null, sheets.returnTo)
    }

    @Test
    fun theRoutesASwitchReturnsTo() {
        assertEquals("settings?section=privacy", Routes.afterTorSwitch("settings?section={section}"))
        assertEquals("settings?section=privacy", Routes.afterTorSwitch("settings/{page}?focus={focus}"))
        for (tab in listOf(Routes.MESSAGES, Routes.BOOKMARKS, Routes.NOTIFICATIONS)) assertEquals(tab, Routes.afterTorSwitch(tab))
        assertEquals("search", Routes.afterTorSwitch(Routes.SEARCH))
        for (other in listOf(Routes.FEED, "thread/{id}", "profile/{pubkey}", null)) assertEquals(null, Routes.afterTorSwitch(other))
    }

    @Test
    fun theBarFitsAt320WithEvenGapsAlsoAtLargeFontScale() {
        show(FakeTorUi(TorState.Connected), width = 320, fontScale = 2f)
        val mark = bounds("feed-wordmark")
        val onion = bounds("tor-icon")
        val dot = bounds("relay-dot")
        val gear = bounds("feed-settings")
        val avatar = bounds("feed-profile")
        assertTrue("the logo is not under the onion", mark.right <= onion.left)
        assertTrue("the logo stays readable", mark.right - mark.left >= 90.dp)
        assertTrue("the bar ends inside the width", avatar.right <= 320.dp)
        rule.onNodeWithTag("tor-icon").assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        // the mark's node has 4 dp of air on each side
        assertTrue("the logo's ink stays out of the onion's target", mark.right - 4.dp <= 320.dp - 4.dp - 48.dp * 3 - 34.dp)
        // visual gaps, edge to edge, from the centres: onion and gear draw 24 dp, the dot 10, the avatar 28
        near(24f, centre("relay-dot") - centre("tor-icon") - 12 - 5)
        near(24f, centre("feed-settings") - centre("relay-dot") - 5 - 12)
        near(22f, centre("feed-profile") - centre("feed-settings") - 12 - 14, 2f)
        assertTrue(gear.left > dot.right && avatar.left > gear.right && dot.left > onion.right)
    }

    @Test
    fun theStateIsAnnouncedInEveryLanguage() {
        val ids = listOf(R.string.tor_desc_connecting, R.string.tor_desc_connected, R.string.tor_desc_failed)
        val english = ids.map { str(it) }
        assertEquals(3, english.toSet().size)
        for (tag in listOf("de", "es", "fr", "it", "pt", "ru", "tr")) {
            val config = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val local = ctx.createConfigurationContext(config)
            val said = ids.map { local.getString(it) }
            assertEquals(tag, 3, said.toSet().size)
            said.forEachIndexed { i, s -> assertNotEquals(tag, english[i], s); assertTrue(s, "Tor" in s) }
        }
    }

    private fun inkOverTime(reduced: Boolean): Set<Int> {
        rule.mainClock.autoAdvance = false
        rule.setContent { MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) { TorIcon(TorState.Connecting(null), {}, reduced = reduced) } }
        return List(4) {
            rule.mainClock.advanceTimeBy(450)
            val map = rule.onNodeWithTag("tor-icon").captureToImage().toPixelMap()
            (0 until map.width).sumOf { x -> (0 until map.height).sumOf { y -> ((1f - map[x, y].green) * 255).toInt() } }
        }.toSet()
    }

    @Test
    fun theConnectingIconPulses() = assertTrue(inkOverTime(reduced = false).size > 1)

    @Test
    fun theConnectingIconStaysStillWithReducedAnimations() = assertEquals(1, inkOverTime(reduced = true).size)

    @Test
    fun theSystemSettingDecidesAboutReducedAnimations() {
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun run(cmd: String) = shell.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString().trim() }
        val before = run("settings get global animator_duration_scale")
        try {
            run("settings put global animator_duration_scale 0")
            assertTrue(reducedAnimations(ctx))
            run("settings put global animator_duration_scale 1")
            assertFalse(reducedAnimations(ctx))
        } finally {
            run(if (before == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $before")
        }
    }

    @Test
    fun settingsExplainFirstThenTurnOn() {
        val ui = FakeTorUi()
        rule.setContent { MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) { ProvideTorUi(ui) { Column { TorSettingsRow() } } } }
        rule.onNodeWithText(str(R.string.tor_orbot)).assertIsDisplayed()
        rule.onNodeWithTag("settings-tor").performClick()
        rule.waitForIdle()
        assertEquals("the explanation comes first", 0, ui.ons)
        rule.onNodeWithText("Tor hides your IP address from relays and servers. It does not hide your account, your wallet or your browser.").assertIsDisplayed()
        rule.onNodeWithTag("tor-explain-cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, ui.ons)
        rule.onNodeWithTag("settings-tor").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, ui.ons)
    }

    @Test
    fun settingsSayWhatIsGoingOnWhileSwitchingAndWhenFailed() {
        val ui = FakeTorUi(TorState.Failed)
        rule.setContent { MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) { ProvideTorUi(ui) { Column { TorSettingsRow() } } } }
        rule.onNodeWithText(str(R.string.tor_banner_failed)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tor_try_again)).performClick()
        assertEquals(1, ui.retries)
        // off only with the question
        rule.onNodeWithTag("settings-tor").performClick()
        rule.waitForIdle()
        assertEquals(0, ui.offs)
        rule.onNodeWithTag("tor-off-cancel").performClick()
        rule.waitForIdle()
        ui.turning.value = TorTurn.OFF
        rule.waitForIdle()
        rule.onNodeWithTag("settings-tor").assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.tor_turning_off)).assertIsDisplayed()
        // a failed turnOn: stays off, says so
        ui.turning.value = null
        ui.active.value = false
        ui.state.value = null
        ui.error.value = TorSwitchError.ON_FAILED
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.tor_error_on)).assertIsDisplayed()
    }

    @Test
    fun theLoginRowExplainsFirstAndShowsTheFailedStateOnceOn() {
        val ui = FakeTorUi()
        rule.setContent { MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) { ProvideTorUi(ui) { Column { TorLoginRow() } } } }
        rule.onNodeWithTag("login-tor").performClick()
        rule.waitForIdle()
        assertEquals(0, ui.ons)
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, ui.ons)
        ui.turning.value = null
        ui.state.value = TorState.Failed
        ui.active.value = true
        rule.waitForIdle()
        rule.onNodeWithTag("login-tor").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tor_login_failed)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tor_try_again)).performClick()
        assertEquals(1, ui.retries)
    }

    @Test
    fun theLoginRowKeepsItsHeightInEveryState() {
        val ui = FakeTorUi()
        rule.setContent { MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) { ProvideTorUi(ui) { Column { TorLoginRow() } } } }
        fun height() = bounds("login-tor").let { it.bottom - it.top }
        val off = height()
        for (state in listOf(TorState.Connecting(null), TorState.Failed, TorState.Connected)) {
            ui.active.value = true
            ui.state.value = state
            rule.waitForIdle()
            assertEquals(state.toString(), off, height())
        }
    }

    @Test
    fun settingsNameTheConnectionWithoutRepeatingTheTitle() {
        val ui = FakeTorUi(TorState.Connected)
        rule.setContent { MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) { ProvideTorUi(ui) { Column { TorSettingsRow() } } } }
        rule.onNodeWithText(str(R.string.tor_connect)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tor_settings_connected)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tor_connected)).assertDoesNotExist()
    }

    @Test
    fun theEmptyFeedDoesNotClaimThereAreNoNotesWhileTorIsDown() {
        val ui = FakeTorUi(TorState.Connecting(null))
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                ProvideTorUi(ui) { Text(torAwareEmptyText(R.string.feed_empty), Modifier.testTag("empty")) }
            }
        }
        rule.onNodeWithText(str(R.string.feed_empty_tor)).assertIsDisplayed()
        ui.state.value = TorState.Connected
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.feed_empty)).assertIsDisplayed()
        ui.state.value = null
        ui.active.value = false
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.feed_empty)).assertIsDisplayed()
    }

    @Test
    fun withTorOnALinkAlwaysAsksAndNamesTheBrowser() {
        var torOn = false
        val opened = ArrayList<String>()
        val base = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                CompositionLocalProvider(LocalUriHandler provides base) {
                    LinkPromptHost(alwaysOpen = true, onAlwaysOpen = {}, torOn = { torOn }) {
                        val handler = LocalUriHandler.current
                        TextButton(onClick = { handler.openUri("https://example.com/") }, modifier = Modifier.fillMaxWidth().testTag("tap")) { Text("x") }
                    }
                }
            }
        }
        rule.onNodeWithTag("tap").performClick()
        rule.waitForIdle()
        assertEquals("stored always-open, Tor off", listOf("https://example.com/"), opened)
        rule.onNodeWithTag("link-prompt").assertDoesNotExist()
        torOn = true
        rule.onNodeWithTag("tap").performClick()
        rule.waitForIdle()
        assertEquals(1, opened.size)
        rule.onNodeWithTag("link-tor-note").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.link_prompt_tor)).assertIsDisplayed()
        rule.onNodeWithTag("link-always").assertDoesNotExist()
    }

    @Test
    fun theTipSheetNamesTheWalletOnceAndRefusesWithAReason() {
        var state by mutableStateOf(TipSheetState(phase = TipPhase.Ready, walletNote = true))
        var canSend by mutableStateOf(true)
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                CompositionLocalProvider(LocalCanSend provides canSend) {
                    TipSheetContent(state, {}, {}, {}, {}, {}, {})
                }
            }
        }
        rule.onNodeWithTag("tip-wallet-note").assertIsDisplayed()
        rule.onNodeWithText("Your wallet does not use Monostr's Tor.").assertIsDisplayed()
        state = state.copy(walletNote = false)
        rule.waitForIdle()
        rule.onNodeWithTag("tip-wallet-note").assertDoesNotExist()
        canSend = false
        rule.waitForIdle()
        rule.onNodeWithTag("tip-send").assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.send_blocked_tor)).assertIsDisplayed()
    }

    @Test
    fun theCreditIsInDetailsAndItsLinkAsksFirst() {
        val ui = FakeTorUi(TorState.Connected)
        val opened = ArrayList<String>()
        val base = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        lateinit var sheets: TorSheets
        // the order of MainActivity
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                CompositionLocalProvider(LocalUriHandler provides base) {
                    LinkPromptHost(alwaysOpen = false, onAlwaysOpen = {}) {
                        ProvideTorUi(ui) { sheets = LocalTorSheets.current }
                    }
                }
            }
        }
        rule.runOnIdle { sheets.details = true }
        rule.onNodeWithTag("tor-off").assertIsDisplayed()
        rule.onNodeWithTag("tor-credit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(TOR_CREDIT).assertExists()
        rule.onNodeWithText("torproject.org").assertExists()
        rule.onNodeWithTag("tor-credit-link").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("the prompt asks first", emptyList<String>(), opened)
        rule.onNodeWithTag("link-prompt").assertExists()
        rule.onNodeWithTag("link-open").performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://torproject.org"), opened)
    }

    @Test
    fun theCreditPushesNoButtonOffScreenAt320WithLargeFont() {
        val ui = mutableStateOf<TorState>(TorState.Connected)
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.entries.first(), 0f) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                    Column(Modifier.width(320.dp).height(480.dp)) {
                        TorDetailsContent(state = ui.value, turning = null, error = null, onRetry = {}, onTurnOff = {}, onCreditLink = {})
                    }
                }
            }
        }
        for (state in listOf(TorState.Connected, TorState.Failed)) {
            ui.value = state
            rule.waitForIdle()
            rule.onNodeWithTag("tor-off").performScrollTo()
            rule.onNodeWithTag("tor-off").assertIsDisplayed()
            if (state == TorState.Failed) rule.onNodeWithTag("tor-retry-sheet").assertIsDisplayed()
            assertTrue("the credit comes after the buttons", bounds("tor-off").bottom <= bounds("tor-credit").top)
            rule.onNodeWithTag("tor-credit").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("tor-credit-link").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test
    fun theCreditStaysEnglishInEveryLanguage() {
        assertEquals(TOR_CREDIT, str(R.string.tor_credit))
        for (tag in listOf("de", "es", "fr", "it", "pt", "ru", "tr")) {
            val local = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertEquals(tag, TOR_CREDIT, local.getString(R.string.tor_credit))
        }
    }

    @Test
    fun copyIsVerbatim() {
        assertEquals("With Orbot's VPN on, you don't need this; both together are slow.", str(R.string.tor_orbot))
        assertEquals("1 pending tip will be dropped.", ctx.resources.getQuantityString(R.plurals.tor_off_tips, 1, 1))
        assertEquals("%d pending tips will be dropped.".format(2), ctx.resources.getQuantityString(R.plurals.tor_off_tips, 2, 2))
    }
}

/** The real app, logged out: the row needs no session, and tapping it changes nothing before the answer. */
@RunWith(AndroidJUnit4::class)
class TorUiLoggedOutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun loginAndCreateAccountOfferTheRowWithoutASession() {
        runBlocking { entry.session().logout() }
        rule.waitUntil(30_000) { has("login-secret") }
        assertTrue(entry.session().state.value is SessionState.LoggedOut)
        assertFalse(entry.torSwitchStore().enabled.value)
        rule.onNodeWithTag("login-tor").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("login-tor").onChildAt(0).performClick()
        rule.waitUntil(5_000) { has("tor-explain-cancel") }
        rule.onNodeWithTag("tor-explain-cancel").performClick()
        rule.waitForIdle()
        assertFalse("cancel leaves the switch alone", entry.torSwitchStore().enabled.value)
        assertEquals(NetworkMode.Direct, entry.networkGate().mode.value)
        rule.onNodeWithTag("login-create").performScrollTo().performClick()
        rule.waitUntil(5_000) { has("create-welcome") }
        rule.onNodeWithTag("login-tor").assertIsDisplayed()
    }

    @Test
    fun withTheSwitchOffTheVisibleAppLoadsNothingOfTor() {
        rule.waitUntil(30_000) { has("login-secret") || has("fab-compose") }
        rule.waitForIdle()
        assertFalse(entry.torSwitchStore().enabled.value)
        assertFalse("libtor is mapped", "libtor" in File("/proc/self/maps").readText())
        val processes = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }
            .mapNotNull { runCatching { File(it, "cmdline").readText().trim('\u0000') }.getOrNull() }
        assertFalse(processes.any { it.endsWith(":tor") })
    }
}

/** The real app and the real switch: Tor need not connect, only the switch and the session's rebuild count here. */
@RunWith(AndroidJUnit4::class)
class TorUiRealSwitchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @After fun off() {
        runBlocking {
            runCatching { entry.torSwitchControl().turnOff() }
            runCatching { entry.session().logout() }
        }
    }

    @Test
    fun confirmingInSettingsEndsInSettingsAtPrivacyAndALaterStartStaysPut() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(30_000) { has("login-secret") }
        rule.onNodeWithTag("login-secret").performTextInput("0000000000000000000000000000000000000000000000000000000000000003")
        rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(30_000) { has("fab-compose") || has("setup-skip") }
        if (has("setup-skip")) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { has("fab-compose") }
        }
        rule.openSettingsPage(ctx, SettingsPage.PRIVACY)
        rule.waitUntil(15_000) { has("settings-tor") }
        rule.onNodeWithTag("settings-tor").performScrollTo().performClick()
        rule.waitUntil(5_000) { has("tor-explain-confirm") }
        // a direct session ran, so the sheet says so, and in the real app this confirmation would end the process
        rule.onNodeWithTag("tor-explain-restart").assertExists()
        val ends = TorRestartSeam.recorded.get()
        val before = (entry.session().state.value as SessionState.Active).ready
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        // back in Settings, at Privacy, with no scrolling of ours, while the line about the switch still stands
        rule.waitUntil(30_000) { (entry.session().state.value as? SessionState.Active)?.ready.let { it != null && it !== before } }
        rule.waitUntil(15_000) { rule.waitForIdle(); has("settings-tor") }
        assertTrue(entry.networkGate().mode.value is NetworkMode.Tor)
        assertEquals(ends + 1, TorRestartSeam.recorded.get())
        rule.onNodeWithTag("settings-tor").assertIsDisplayed()
        // a start of the session that no switch asked for, as after background work: the host starts at the feed and stays there
        runBlocking { entry.session().release() }
        rule.waitUntil(30_000) { has("fab-compose") }
        rule.waitForIdle()
        assertFalse(has("settings-tor"))
    }
}
