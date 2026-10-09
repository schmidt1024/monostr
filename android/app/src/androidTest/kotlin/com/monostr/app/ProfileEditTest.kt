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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.time.Duration

/** Spec 8: profile edit roundtrip with the test key on relay.monostr.com; a foreign key of the stored kind 0 survives. Needs network. */
@RunWith(AndroidJUnit4::class)
class ProfileEditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val secret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val me = Keys.parse(secret)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

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
            rule.onNodeWithTag("login-secret-button").performClick()
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
    fun editNameKeepsForeignKeysOnRelayMonostr() {
        runBlocking { runCatching { entry.session().logout() } }
        reachFeed()
        val ready = entry.session().requireReady()
        val ts = System.currentTimeMillis()
        val newName = "monostr-test-$ts"
        runBlocking {
            // only relay.monostr.com gets the kind 0 of the shared test key
            entry.session().applyRelays(listOf("wss://relay.monostr.com"))
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
        val filter = Filter().kind(Kind(0u)).author(me.publicKey()).limit(1u)
        val fromRelay = runBlocking { ready.engine.fetchFrom(listOf("wss://relay.monostr.com"), filter, Duration.ofSeconds(10)) }
            .maxByOrNull { it.createdAt().asSecs() }
        assertNotNull(fromRelay)
        val json = Json.parseToJsonElement(fromRelay!!.content()).jsonObject
        assertEquals(newName, json["name"]!!.jsonPrimitive.content)
        assertEquals("seed", json["about"]!!.jsonPrimitive.content)
        assertEquals(ts.toString(), json["monostr_test"]!!.jsonPrimitive.content)
    }
}
