package com.monostr.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/**
 * Spec 8: profile edit roundtrip; a foreign key of the stored kind 0 survives. On a loopback relay with a fresh key per run
 * (2026-11-02): with the shared test key on relay.monostr.com it depended on the network and on others writing that key.
 */
@RunWith(AndroidJUnit4::class)
class ProfileEditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val secret = me.secretKey().toHex()
    /** Every read and write of the test account stays on the emulator; it records the events the app sends. */
    private val relay = LoopbackRelay()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
        relay.close()
    }

    private fun reachFeed() {
        rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() || nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("login-secret").isNotEmpty()) {
            runBlocking { entry.session().applyRelays(listOf(relay.url)) }
            rule.onNodeWithTag("login-secret").performTextInput(secret)
            rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
    }

    @Test
    fun removeButtonEmptiesThePictureField() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        rule.onNodeWithTag("feed-profile").performClick()
        rule.waitUntil(15_000) { nodes("profile-edit").isNotEmpty() }
        rule.onNodeWithTag("profile-edit").performClick()
        rule.waitUntil(15_000) { nodes("edit-picture").isNotEmpty() }
        rule.onNodeWithTag("edit-picture").performTextClearance()
        rule.onNodeWithTag("edit-picture").performTextInput("https://example.com/p.jpg")
        rule.waitUntil(5_000) { nodes("profile-picture-remove").isNotEmpty() }
        rule.onNodeWithTag("profile-picture-remove").performScrollTo().performClick() // below the keyboard
        rule.waitUntil(5_000) { nodes("profile-picture-remove").isEmpty() }
        val text = rule.onNodeWithTag("edit-picture").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals("", text)
    }

    @Test
    fun editNameKeepsForeignKeys() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val ts = System.currentTimeMillis()
        val newName = "monostr-test-$ts"
        runBlocking {
            ready.engine.save(EventBuilder(Kind(0u), """{"name":"monostr-test","about":"seed","monostr_test":"$ts"}""").signWithKeys(me))
            ready.profiles.invalidate(me.publicKey().toHex())
        }
        rule.onNodeWithTag("feed-profile").performClick()
        rule.waitUntil(15_000) { nodes("profile-edit").isNotEmpty() }
        rule.onNodeWithTag("profile-edit").performClick()
        rule.waitUntil(15_000) { nodes("edit-name").isNotEmpty() }
        rule.onNodeWithTag("edit-name").performTextClearance()
        rule.onNodeWithTag("edit-name").performTextInput(newName)
        rule.onNodeWithTag("edit-save").performScrollTo().performClick() // the button sits below the fields, behind the keyboard
        // saved = back on the own profile, which already shows the new name
        rule.waitUntil(30_000) { nodes("profile-edit").isNotEmpty() && rule.onAllNodesWithText(newName).fetchSemanticsNodes().isNotEmpty() }
        // what the app sent: the newest kind 0 the relay received
        val sent = relay.events.mapNotNull { frame ->
            runCatching { Json.parseToJsonElement(frame).jsonArray[1].jsonObject }.getOrNull()
        }.filter { it["kind"]?.jsonPrimitive?.content == "0" }.maxByOrNull { it["created_at"]!!.jsonPrimitive.content.toLong() }
        assertNotNull("the edited profile never reached the relay: ${relay.events}", sent)
        val fromRelay = sent!!["content"]!!.jsonPrimitive.content
        val json = Json.parseToJsonElement(fromRelay).jsonObject
        assertEquals(newName, json["name"]!!.jsonPrimitive.content)
        assertEquals("seed", json["about"]!!.jsonPrimitive.content)
        assertEquals(ts.toString(), json["monostr_test"]!!.jsonPrimitive.content)
    }
}
