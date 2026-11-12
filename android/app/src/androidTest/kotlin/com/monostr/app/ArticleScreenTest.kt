package com.monostr.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.nostr.model.AddressRef
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertTextContains
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.monostr.app.data.monostrPrefs
import com.monostr.app.translate.Languages
import com.monostr.app.translate.TranslationMode
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Tag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/**
 * Spec 2026-10-29 article reader: a tap on an article card in the feed opens the reader (title and a heading of
 * the Markdown body), and the like publishes a kind 7 with the article's `a` tag to the loopback relay. The article
 * sits in the local database; nothing leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class ArticleScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    private fun reachFeed() {
        rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() || nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("login-secret").isNotEmpty()) {
            rule.onNodeWithTag("login-secret").performTextInput(secret)
            rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    private fun showInFeed(text: String) {
        var attempts = 0
        while (rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && attempts < 4) {
            rule.onNodeWithTag("feed-refresh").performTouchInput { swipeDown() }
            attempts++
            runCatching { rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        }
    }

    @Test
    fun cardOpensReaderAndLikePublishesWithCoordinate() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val alice = Keys.generate()
        val bob = Keys.generate()
        val ts = System.currentTimeMillis()
        val d = "reader-$ts"
        val article = EventBuilder(Kind(30023u), "# Heading $ts\n\nSome **bold** body text.")
            .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "Reader article $ts"))))
            .signWithKeys(bob)
        val ref = AddressRef(30023, bob.publicKey().toHex(), d)
        val note = EventBuilder.textNote("read this $ts\n\nnostr:${ref.toBech32()}").signWithKeys(alice)
        LoopbackRelay().use { relay ->
            runBlocking {
                entry.session().applyRelays(listOf(relay.url))
                ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-art-$ts"}""")).signWithKeys(alice))
                ready.engine.save(article)
                ready.engine.save(note)
            }
            showInFeed("read this $ts")
            val card = "address-card-${d.take(8)}"
            rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(card))
            rule.waitUntil(15_000) { nodes("address-card-generic").isNotEmpty() }
            // the card frame (not the person row) opens the reader: tapped at its top edge, since the person row sits
            // right above the bottom edge and took a tap 4 dp above it (2026-10-30: the test opened bob's profile)
            rule.onNodeWithTag(card).performTouchInput { click(Offset(centerX, 4.dp.toPx())) }
            rule.waitUntil(15_000) { nodes("article-screen").isNotEmpty() && nodes("article-title").isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("Reader article $ts").fetchSemanticsNodes().isNotEmpty())
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Heading $ts").fetchSemanticsNodes().isNotEmpty() }

            val like = "note-like-${article.id().toHex().take(8)}"
            rule.onNodeWithTag("article-body").performScrollToNode(hasTestTag(like))
            rule.onNodeWithTag(like).performClick()
            rule.waitUntil(15_000) { relay.events.any { it.contains("\"kind\":7") && it.contains(ref.coordinate) } }
            assertTrue("the like names the author", relay.events.any { it.contains("\"kind\":7") && it.contains(bob.publicKey().toHex()) })
        }
    }

    /**
     * Spec 2026-11-03 «Articles»: a French article offers «Translate» under the author row; after the consent sheet the
     * title and the first paragraph show the translation (a MockWebServer as the own server), the Markdown link inside it
     * still opens alice's profile, and its URL never went to the server.
     */
    @Test
    fun frenchArticleTranslatesWithWorkingLink() {
        val l = LocaleListCompat.getAdjustedDefault()
        val target = Languages.target((0 until l.size()).mapNotNull { l[it] })
        assumeTrue("the reader's language is French", target != "fr")
        val words = mapOf(
            "Le marché du matin" to "Der Markt am Morgen",
            "Bonjour à tous" to "Hallo zusammen",
            "aujourd'hui il fait très beau et nous allons tous ensemble au marché ce matin" to "heute ist das Wetter schön",
            "Le pain est encore chaud et les fromages sentent très bon dans toute la rue" to "das Brot ist noch warm",
        )
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                bodies += body
                val q = JSONObject(body).getJSONArray("q")
                val out = JSONArray()
                for (i in 0 until q.length()) out.put(words.entries.fold(q.getString(i)) { t, (fr, de) -> t.replace(fr, de) })
                return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("translatedText", out).toString())
            }
        }
        server.start()
        runBlocking { runCatching { entry.session().logout() } }
        try {
            runBlocking {
                ctx.monostrPrefs.edit { it.remove(booleanPreferencesKey("translate.consent")) }
                entry.translationSettings().setCustomUrl("http://127.0.0.1:${server.port}")
                entry.translationSettings().setMode(TranslationMode.CUSTOM)
            }
            reachFeed()
            val ready = entry.session().requireReady()
            val alice = Keys.generate()
            val bob = Keys.generate()
            val ts = System.currentTimeMillis()
            val d = "fr-$ts"
            val npub = alice.publicKey().toBech32()
            val content = "[Bonjour à tous](nostr:$npub) aujourd'hui il fait très beau et nous allons tous ensemble au marché ce matin.\n\n" +
                "Le pain est encore chaud et les fromages sentent très bon dans toute la rue.\n\n```\nval code = 1\n```"
            val article = EventBuilder(Kind(30023u), content)
                .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "Le marché du matin"))))
                .signWithKeys(bob)
            val id8 = article.id().toHex().take(8)
            val ref = AddressRef(30023, bob.publicKey().toHex(), d)
            val note = EventBuilder.textNote("lisez $ts\n\nnostr:${ref.toBech32()}").signWithKeys(alice)
            LoopbackRelay().use { relay ->
                runBlocking {
                    entry.session().applyRelays(listOf(relay.url))
                    ready.engine.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                    ready.engine.save(EventBuilder.metadata(Metadata.fromJson("""{"name":"alice-fr-$ts"}""")).signWithKeys(alice))
                    ready.engine.save(article)
                    ready.engine.save(note)
                }
                showInFeed("lisez $ts")
                val card = "address-card-${d.take(8)}"
                rule.onNodeWithTag("feed-list").performScrollToNode(hasTestTag(card))
                rule.waitUntil(15_000) { nodes("address-card-generic").isNotEmpty() }
                rule.onNodeWithTag(card).performTouchInput { click(Offset(centerX, 4.dp.toPx())) }
                rule.waitUntil(15_000) { nodes("article-title").isNotEmpty() }

                // detected on the device; the first tap asks for consent and sends nothing
                rule.waitUntil(15_000) { nodes("translate-$id8").isNotEmpty() }
                rule.onNodeWithTag("translate-$id8", useUnmergedTree = true).performClick()
                rule.waitUntil(5_000) { nodes("translate-consent").isNotEmpty() }
                val articleBody = ctx.getString(R.string.translate_consent_body_article, "127.0.0.1")
                assertTrue("the sheet speaks of the article", rule.onAllNodesWithText(articleBody).fetchSemanticsNodes().isNotEmpty())
                assertEquals(0, server.requestCount)
                rule.onNodeWithTag("translate-consent-translate").performClick()

                rule.waitUntil(15_000) { nodes("translated-by-$id8").isNotEmpty() }
                rule.onNodeWithTag("article-title").assertTextContains("Der Markt am Morgen")
                rule.waitUntil(5_000) { rule.onAllNodesWithText("Hallo zusammen", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(rule.onAllNodesWithText("das Brot ist noch warm", substring = true).fetchSemanticsNodes().isNotEmpty())
                assertTrue("partial note only when something failed", nodes("translate-partial-$id8").isEmpty())
                assertFalse("the link's URL never leaves the phone", bodies.any { npub in it })
                assertFalse("code blocks are not sent", bodies.any { "val code" in it })
                assertTrue(bodies.any { "<a data-i=" in it })

                // the link inside the translated paragraph still opens the profile
                rule.onNode(androidx.compose.ui.test.hasText("Hallo zusammen", substring = true)).performTouchInput { click(Offset(8.dp.toPx(), 10.dp.toPx())) }
                rule.waitUntil(15_000) { nodes("profile-menu").isNotEmpty() }
            }
        } finally {
            runBlocking {
                entry.translationSettings().setMode(TranslationMode.MONOSTR)
                entry.translationSettings().setCustomUrl("")
                ctx.monostrPrefs.edit { it.remove(booleanPreferencesKey("translate.consent")) }
            }
            server.shutdown()
        }
    }
}
