package com.monostr.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode
import com.monostr.app.translate.DeviceBackend
import com.monostr.app.translate.DeviceReply
import com.monostr.app.translate.DeviceTranslator
import com.monostr.app.translate.TranslateBackend
import com.monostr.app.translate.TranslateResult
import com.monostr.app.translate.TranslationMode
import com.monostr.app.translate.TranslationSettingsStore
import com.monostr.app.translate.TranslationState
import com.monostr.app.translate.Translations
import com.monostr.app.ui.common.NoteTranslationViewModel
import com.monostr.app.ui.common.TranslateConsentHost
import com.monostr.app.ui.common.TranslateRow
import com.monostr.app.ui.common.openDeviceTranslatorStore
import com.monostr.app.ui.settings.TranslationSection
import com.monostr.app.ui.theme.MonostrTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Release 0.42.0 (backlog 89), UI of the on-device mode: the settings row with its F-Droid link while Offline Translator
 * is missing, the consent sheet's «Use Offline Translator instead» (mode DEVICE, then the waiting note is translated on
 * the device, never by a server) and the device states' rows. The installed check and the translator are fakes.
 */
@RunWith(AndroidJUnit4::class)
class TranslationDeviceModeTest {
    @get:Rule val rule = createComposeRule()
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() {
        scope.cancel()
        runCatching { Intents.release() }
    }

    /** Like the app's store: consent is per server, the device needs none. */
    private class FakeSettings(mode: TranslationMode) : TranslationSettingsStore {
        val modeFlow = MutableStateFlow(mode)
        private val consent = MutableStateFlow(false)
        override val mode = modeFlow
        override val customUrl = MutableStateFlow("")
        override val consentGiven = modeFlow.map { it == TranslationMode.DEVICE || consent.value }
        override suspend fun setMode(m: TranslationMode) { modeFlow.value = m }
        override suspend fun setCustomUrl(u: String) = true
        override suspend fun setConsentGiven() { if (modeFlow.value != TranslationMode.DEVICE) consent.value = true }
    }

    private fun section(installed: Boolean, modes: MutableList<TranslationMode>, getApp: (() -> Unit)? = null) {
        val mode = mutableStateOf(TranslationMode.MONOSTR)
        rule.setContent {
            val context = LocalContext.current
            MonostrTheme(ThemeMode.LIGHT, Accent.MONO) {
                TranslationSection(
                    mode.value, "", deviceInstalled = installed,
                    onMode = { modes += it; mode.value = it }, onSaveUrl = { true },
                    onGetDeviceApp = getApp ?: { openDeviceTranslatorStore(context) },
                )
            }
        }
    }

    @Test
    fun deviceRow_notInstalled_opensStoreAndKeepsMode() {
        Intents.init()
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        val modes = mutableListOf<TranslationMode>()
        section(installed = false, modes)
        rule.onNodeWithText("Get Offline Translator on F-Droid", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("translate-mode-device").performClick()
        rule.waitForIdle()
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("market://details?id=dev.davidv.translator")))
        assertTrue("the mode stays", modes.isEmpty())
        rule.onNodeWithTag("translate-mode-monostr").assertIsSelected()
    }

    @Test
    fun deviceRow_installed_selectsDevice() {
        val modes = mutableListOf<TranslationMode>()
        var store = 0
        section(installed = true, modes) { store++ }
        rule.onNodeWithText("Nothing leaves your phone", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("translate-mode-device").performClick()
        rule.waitForIdle()
        assertEquals(listOf(TranslationMode.DEVICE), modes)
        assertEquals("no store link when installed", 0, store)
        rule.onNodeWithTag("translate-mode-device").assertIsSelected()
    }

    private fun consentSetup(
        installed: Boolean,
        mode: TranslationMode = TranslationMode.MONOSTR,
        reply: () -> TranslateResult? = { null },
    ): Triple<NoteTranslationViewModel, FakeSettings, MutableList<TranslateBackend>> {
        val settings = FakeSettings(mode)
        val used = Collections.synchronizedList(mutableListOf<TranslateBackend>())
        val translations = Translations(
            settings,
            detect = { _, _ -> "fr" },
            translator = { b, q, _, _ -> used += b; reply() ?: TranslateResult.Ok(q.map { "TRANSLATED" }) },
            target = { "en" },
            scope = scope,
            detectDispatcher = Dispatchers.Default,
        )
        val device = DeviceTranslator({ _, _, _ -> DeviceReply.Text("unused") }, { installed })
        val vm = NoteTranslationViewModel(translations, settings, device)
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.MONO) {
                TranslateConsentHost(vm, onSettings = {})
                val state by vm.state(NOTE).collectAsState()
                TranslateRow(state, NOTE.take(8), onTranslate = { vm.translate(NOTE) }, onOriginal = {}, onTranslation = {})
            }
        }
        vm.prepare(NOTE, "Bonjour à tous, aujourd'hui il fait très beau.", null)
        rule.waitUntil(10_000) { vm.state(NOTE).value is TranslationState.Offer }
        return Triple(vm, settings, used)
    }

    @Test
    fun consentSheet_useDevice_switchesModeAndTranslates() {
        val (vm, settings, used) = consentSetup(installed = true)
        vm.translate(NOTE)
        rule.waitUntil(5_000) { nodes("translate-consent-device").isNotEmpty() }
        assertTrue("nothing sent before the choice", used.isEmpty())
        rule.onNodeWithTag("translate-consent-device").performClick()
        rule.waitUntil(10_000) { vm.state(NOTE).value is TranslationState.Shown }
        assertEquals(TranslationMode.DEVICE, runBlocking { settings.mode.first() })
        assertEquals("Offline Translator", (vm.state(NOTE).value as TranslationState.Shown).host)
        assertEquals("only the device translated", listOf<TranslateBackend>(DeviceBackend), used.toList())
        rule.waitUntil(5_000) { nodes("translate-consent").isEmpty() }
    }

    @Test
    fun consentSheet_notInstalled_noDeviceButton() {
        val (vm, settings, used) = consentSetup(installed = false)
        vm.translate(NOTE)
        rule.waitUntil(5_000) { nodes("translate-consent").isNotEmpty() }
        assertTrue(nodes("translate-consent-device").isEmpty())
        assertEquals(TranslationMode.MONOSTR, runBlocking { settings.mode.first() })
        assertTrue(used.isEmpty())
    }

    @Test
    fun packMissing_retryAfterInstallTranslates() {
        var pack = false
        val (vm, _, used) = consentSetup(installed = true, mode = TranslationMode.DEVICE, reply = { if (pack) null else TranslateResult.PackMissing })
        val id8 = NOTE.take(8)
        vm.translate(NOTE)
        rule.waitUntil(10_000) { nodes("translate-pack-missing-$id8").isNotEmpty() }
        assertTrue("device mode: no consent sheet", nodes("translate-consent").isEmpty())
        pack = true // the pack was downloaded in Offline Translator
        rule.onNodeWithTag("translate-retry-$id8", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { vm.state(NOTE).value is TranslationState.Shown }
        assertEquals(listOf<TranslateBackend>(DeviceBackend, DeviceBackend), used.toList())
    }

    @Test
    fun deviceStates_showTheirRows() {
        val state = mutableStateOf<TranslationState>(TranslationState.PackMissing)
        rule.setContent {
            MonostrTheme(ThemeMode.LIGHT, Accent.MONO) { TranslateRow(state.value, "abcd1234", onTranslate = {}, onOriginal = {}, onTranslation = {}) }
        }
        rule.waitUntil(5_000) { nodes("translate-pack-missing-abcd1234").isNotEmpty() }
        rule.onNodeWithText("Language pack missing", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Open Offline Translator", substring = true, useUnmergedTree = true).assertExists()
        assertTrue("retry on the pack-missing row", nodes("translate-retry-abcd1234").isNotEmpty())
        state.value = TranslationState.AppMissing
        rule.waitUntil(5_000) { nodes("translate-app-missing-abcd1234").isNotEmpty() }
        rule.onNodeWithText("Offline Translator not installed", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Get it", substring = true, useUnmergedTree = true).assertExists()
        assertTrue("retry on the app-missing row", nodes("translate-retry-abcd1234").isNotEmpty())
        assertTrue(nodes("translate-failed-abcd1234").isEmpty())
    }

    private companion object {
        const val NOTE = "f00dbabe00000000000000000000000000000000000000000000000000000000"
    }
}
