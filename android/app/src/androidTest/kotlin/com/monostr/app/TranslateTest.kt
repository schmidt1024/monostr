package com.monostr.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.monostrPrefs
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.translate.Languages
import com.monostr.app.translate.TranslationMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata

/**
 * Spec 2026-11-03 note translation, UI: the button on foreign notes only, the consent sheet before anything is sent,
 * the translated text with a working mention, «Show original», the «off» setting and «Retry» after a failure.
 *
 * The translate server is a MockWebServer in this process, set as the own server (mode CUSTOM, http://localhost is
 * allowed for test hosts). The reader's language is the device's (Languages.target); the "same language" note is
 * written in it (en or de), the foreign one in French, so the test runs on an English or a German device alike.
 */
@RunWith(AndroidJUnit4::class)
class TranslateTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
    private val relay = LoopbackRelay()
    private val server = MockWebServer()
    @Volatile private var fail = false
    private val alice = Keys.generate()

    private val target = run {
        val l = LocaleListCompat.getAdjustedDefault()
        Languages.target((0 until l.size()).mapNotNull { l[it] })
    }
    private val sameLanguage = mapOf(
        "en" to "Hello everyone, today the weather is lovely and we are all going to the market together this morning.",
        "de" to "Hallo zusammen, heute ist das Wetter wunderschön und wir gehen heute Morgen alle zusammen auf den Markt.",
    )
    private val french = "Bonjour à tous, aujourd'hui il fait très beau et nous allons tous ensemble au marché ce matin."
    private val translated = "TRANSLATED the weather is lovely"

    @Before fun start() {
        assumeTrue("device language $target has no sample", target in sameLanguage)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (fail) return MockResponse().setResponseCode(500)
                val q = JSONObject(request.body.readUtf8()).getJSONArray("q").getString(0)
                // the French prose becomes the "translation"; the shielded mention marker before it stays as sent
                val out = q.replace(Regex("Bonjour[^<]*"), translated)
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("translatedText", JSONArray().put(out)).toString())
            }
        }
        server.start()
        runBlocking {
            ctx.monostrPrefs.edit { it.remove(booleanPreferencesKey("translate.consent")) }
            entry.translationSettings().setCustomUrl("http://localhost:${server.port}")
            entry.translationSettings().setMode(TranslationMode.CUSTOM)
        }
    }

    @After fun stop() {
        runBlocking {
            runCatching { entry.session().logout() }
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            entry.translationSettings().setMode(TranslationMode.MONOSTR)
            entry.translationSettings().setCustomUrl("")
            ctx.monostrPrefs.edit { it.remove(booleanPreferencesKey("translate.consent")) }
        }
        relay.close()
        server.shutdown()
    }

    private fun reachFeed() {
        runBlocking { runCatching { entry.session().logout() } }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        runBlocking { entry.session().applyRelays(listOf(relay.url)) }
        rule.onNodeWithTag("login-secret").performTextInput(secret)
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
        entry.session().requireReady().discoverDecision.dismiss()
        val ready = entry.session().requireReady()
        runBlocking {
            ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-tr"}""")).signWithKeys(alice))
        }
    }

    private fun seedAndShow(vararg events: Event) {
        val ready = entry.session().requireReady()
        runBlocking { events.forEach { ready.engine.save(it) } }
        rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
    }

    private fun id8(e: Event) = e.id().toHex().take(8)

    /** A French note that starts with a mention of alice, so a tap at the text's start hits the mention. */
    private fun frenchNote() = EventBuilder.textNote("nostr:${alice.publicKey().toBech32()} $french").signWithKeys(alice)

    @Test
    fun foreignNoteTranslatesAfterConsentAndTogglesBack() {
        reachFeed()
        val fr = frenchNote()
        val same = EventBuilder.textNote(sameLanguage.getValue(target)).signWithKeys(alice)
        seedAndShow(fr, same)
        rule.waitUntil(30_000) { nodes("note-text-${id8(fr)}").isNotEmpty() && nodes("note-text-${id8(same)}").isNotEmpty() }
        rule.waitUntil(15_000) { nodes("translate-${id8(fr)}").isNotEmpty() } // detected on the device, in the background
        Thread.sleep(1_000)
        assertTrue("no button on a note in the reader's language", nodes("translate-${id8(same)}").isEmpty())

        // first tap: the consent sheet, nothing sent yet
        rule.onNodeWithTag("translate-${id8(fr)}", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { nodes("translate-consent").isNotEmpty() }
        assertEquals(0, server.requestCount)
        rule.onNodeWithTag("translate-consent-translate").performClick()

        rule.waitUntil(10_000) { nodes("translated-by-${id8(fr)}").isNotEmpty() }
        assertEquals(1, server.requestCount)
        rule.onNodeWithTag("note-text-${id8(fr)}").assertTextContains(translated, substring = true)
        rule.onNodeWithTag("note-text-${id8(fr)}").assertTextContains("@alice-tr", substring = true)
        rule.onNodeWithTag("translated-by-${id8(fr)}", useUnmergedTree = true).assertIsDisplayed()

        // the mention still opens the profile
        rule.onNodeWithTag("note-text-${id8(fr)}").performTouchInput { click(Offset(6.dp.toPx(), 8.dp.toPx())) }
        rule.waitUntil(15_000) { nodes("profile-menu").isNotEmpty() }
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("translated-by-${id8(fr)}").isNotEmpty() } // the session keeps the translation

        rule.onNodeWithTag("translate-original-${id8(fr)}", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { nodes("translate-show-${id8(fr)}").isNotEmpty() }
        rule.onNodeWithTag("note-text-${id8(fr)}").assertTextContains("Bonjour", substring = true)
        rule.onNodeWithTag("translate-show-${id8(fr)}", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { nodes("translate-original-${id8(fr)}").isNotEmpty() }
        rule.onNodeWithTag("note-text-${id8(fr)}").assertTextContains(translated, substring = true)
        assertEquals("toggling needs no request", 1, server.requestCount)
    }

    @Test
    fun settingOffHidesTheButton() {
        reachFeed()
        val fr = frenchNote()
        seedAndShow(fr)
        rule.waitUntil(30_000) { nodes("translate-${id8(fr)}").isNotEmpty() }
        runBlocking { entry.translationSettings().setMode(TranslationMode.OFF) }
        rule.waitUntil(5_000) { nodes("translate-${id8(fr)}").isEmpty() }
        assertTrue(nodes("note-text-${id8(fr)}").isNotEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun consentSettingsOpensTheSectionWhereOffSticks() {
        reachFeed()
        val fr = frenchNote()
        seedAndShow(fr)
        rule.waitUntil(30_000) { nodes("translate-${id8(fr)}").isNotEmpty() }
        rule.onNodeWithTag("translate-${id8(fr)}", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { nodes("translate-consent").isNotEmpty() }
        rule.onNodeWithTag("translate-consent-settings").performClick()
        // the settings open scrolled to the section; the own server's address field shows (mode CUSTOM)
        rule.waitUntil(10_000) { nodes("translate-mode-off").isNotEmpty() }
        rule.onNodeWithTag("translate-mode-custom").assertIsDisplayed()
        rule.onNodeWithTag("translate-url").assertIsDisplayed()
        rule.onNodeWithTag("translate-url").performTextClearance()
        rule.onNodeWithTag("translate-url").performTextInput("http://example.com")
        rule.onNodeWithTag("translate-url-save").performClick()
        rule.waitUntil(5_000) { nodes("translate-url-error").isNotEmpty() } // plain http only for local test hosts
        rule.onNodeWithTag("translate-mode-off").performClick()
        rule.waitUntil(5_000) { nodes("translate-url").isEmpty() }
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("settings-cat-privacy").isNotEmpty() } // the overview lies under the page
        Espresso.pressBack()
        rule.waitUntil(15_000) { nodes("note-text-${id8(fr)}").isNotEmpty() }
        assertTrue("off: no button", nodes("translate-${id8(fr)}").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aServerErrorOffersRetry() {
        runBlocking { entry.translationSettings().setConsentGiven() }
        reachFeed()
        val fr = frenchNote()
        seedAndShow(fr)
        rule.waitUntil(30_000) { nodes("translate-${id8(fr)}").isNotEmpty() }
        fail = true
        rule.onNodeWithTag("translate-${id8(fr)}", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { nodes("translate-failed-${id8(fr)}").isNotEmpty() }
        assertTrue("consent given once: no sheet", nodes("translate-consent").isEmpty())
        assertTrue(rule.onAllNodesWithText("Bonjour", substring = true).fetchSemanticsNodes().isNotEmpty())
        fail = false
        rule.onNodeWithTag("translate-retry-${id8(fr)}", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { nodes("translated-by-${id8(fr)}").isNotEmpty() }
        rule.onNodeWithTag("note-text-${id8(fr)}").assertTextContains(translated, substring = true)
    }
}
