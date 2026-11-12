package com.monostr.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.session.NostrSessionEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Tag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/** GitHub #4: with a minimum set, a thread keeps the replies with enough proof of work and the replies of follows. */
@RunWith(AndroidJUnit4::class)
class PowFilterTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val stranger = Keys.generate()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @After fun restore() {
        runBlocking {
            runCatching { entry.uiSettings().setPowFilterBits(0) }
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
    }

    @Test
    fun aThreadHidesWeakRepliesOfStrangersOnly() {
        val ts = System.currentTimeMillis()
        val root = EventBuilder.textNote("root $ts").signWithKeys(alice)
        val markers = listOf(Tag.parse(listOf("e", root.id().toHex(), "", "root")), Tag.parse(listOf("p", alice.publicKey().toHex())))
        val weak = EventBuilder.textNote("weak $ts").tags(markers).signWithKeys(stranger)
        val strong = EventBuilder.textNote("strong $ts").tags(markers).pow(8u).signWithKeys(stranger)
        val friend = EventBuilder.textNote("friend $ts").tags(markers).signWithKeys(alice)
        val mine = EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me)
        val all = listOf(mine, root, weak, strong, friend)
        LoopbackRelay(serve = all.map { it.asJson() }).use { relay ->
            runBlocking {
                runCatching { entry.session().logout() }
                entry.session().applyRelays(listOf(relay.url))
                entry.uiSettings().setPowFilterBits(8)
            }
            rule.waitUntil(30_000) { nodes("login-secret").isNotEmpty() }
            rule.onNodeWithTag("login-secret").performTextInput(me.secretKey().toHex())
            rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
            if (nodes("setup-skip").isNotEmpty()) {
                rule.onNodeWithTag("setup-skip").performClick()
                rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
            }
            runBlocking { all.forEach { entry.session().requireReady().engine.save(it) } }

            rule.waitUntil(30_000) { shown("root $ts") }
            rule.onNodeWithText("root $ts").performClick()
            rule.waitUntil(20_000) { shown("strong $ts") && shown("friend $ts") }
            rule.waitForIdle()
            assertTrue("a weak reply of a stranger is hidden", !shown("weak $ts"))
        }
    }
}
