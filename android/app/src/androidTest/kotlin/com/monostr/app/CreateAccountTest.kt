package com.monostr.app

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.Draft
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import com.monostr.app.session.NewKeys
import com.monostr.nostr.LocalSigner
import kotlinx.coroutines.flow.first
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.PendingSetup
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 2026-10-17 §3/§4/§11: the four steps from the login screen publish kind 0, 10002, 10050 and 3
 * to the session's relays. The loopback relay is the only relay before "Let's go", so nothing reaches
 * public relays; the published events are read back from it.
 */
@RunWith(AndroidJUnit4::class)
class CreateAccountTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry get() = EntryPointAccessors.fromApplication(ctx, NostrSessionEntryPoint::class.java)
    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
    private lateinit var relay: LoopbackRelay

    @Before fun setUp() {
        relay = LoopbackRelay()
        runBlocking {
            entry.session().logout()
            entry.session().applyRelays(listOf(relay.url))
        }
        rule.waitUntil(30_000) { nodes("login-create").isNotEmpty() }
    }

    @After fun restore() {
        runBlocking {
            runCatching { entry.session().applyRelays(PrefsRelayStore.DEFAULT_RELAYS) }
            runCatching { entry.session().logout() }
        }
        relay.close()
    }

    private fun secure(): Boolean {
        var flags = 0
        rule.runOnUiThread { flags = rule.activity.window.attributes.flags }
        return (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }

    private fun nsecShown(): String =
        rule.onNodeWithTag("create-nsec").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The recorded `["EVENT", {…}]` messages as event objects. */
    private fun published(): List<JSONObject> = relay.events.mapNotNull {
        runCatching { JSONArray(it).takeIf { a -> a.getString(0) == "EVENT" }?.getJSONObject(1) }.getOrNull()
    }

    private fun kinds(): Set<Int> = relay.events.mapNotNull { KIND.find(it)?.groupValues?.get(1)?.toInt() }.toSet()

    @Test
    fun creatingAnAccountPublishesTheFourPartsToTheRelay() {
        rule.onNodeWithTag("login-create").performClick()
        rule.waitUntil(5_000) { nodes("create-welcome").isNotEmpty() }
        rule.onNodeWithTag("create-next").performClick()
        rule.waitUntil(5_000) { nodes("create-nsec").isNotEmpty() }
        rule.waitForIdle()
        assertTrue(secure())
        // the key survives an activity recreation (view model), and the window stays secure
        val nsec = nsecShown()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { nodes("create-nsec").isNotEmpty() }
        rule.waitForIdle()
        assertEquals(nsec, nsecShown())
        assertTrue(nodes("create-backup").isNotEmpty())
        assertTrue(secure())
        rule.onNodeWithTag("create-next").assertIsNotEnabled()
        rule.onNodeWithTag("create-saved").performScrollTo().performClick()
        rule.onNodeWithTag("create-next").performClick()
        rule.waitUntil(5_000) { nodes("create-name").isNotEmpty() }
        rule.waitForIdle()
        assertFalse(secure())
        rule.onNodeWithTag("create-name").performTextInput("Testperson")
        rule.onNodeWithTag("create-next").performClick()
        rule.waitUntil(5_000) { nodes("create-follows").isNotEmpty() }
        rule.onNodeWithTag("create-follows").performScrollToNode(hasTestTag("create-follow-82341f88"))
        rule.onNodeWithTag("create-follow-82341f88").performClick()
        rule.onNodeWithTag("create-finish").performClick()

        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        if (nodes("setup-skip").isNotEmpty()) {
            rule.onNodeWithTag("setup-skip").performClick()
            rule.waitUntil(15_000) { nodes("fab-compose").isNotEmpty() }
        }
        rule.waitUntil(15_000) { kinds().containsAll(listOf(0, 10002, 10050, 3)) }

        val pk = entry.session().requireReady().pubkey
        val profile = published().first { it.getInt("kind") == 0 && it.getString("pubkey") == pk }
        assertEquals("Testperson", JSONObject(profile.getString("content")).getString("name"))
        val contacts = published().first { it.getInt("kind") == 3 && it.getString("pubkey") == pk }
        val tags = contacts.getJSONArray("tags")
        val ps = (0 until tags.length()).map { tags.getJSONArray(it) }.filter { it.getString(0) == "p" }.map { it.getString(1) }
        assertEquals(13, ps.size)
        assertFalse(ps.any { it.startsWith("82341f88") })
        rule.waitUntil(15_000) { runBlocking { entry.pendingSetup().load(pk) } == null }

        // logout removes a pending record of the account (§7): put one back right before it
        runBlocking { entry.pendingSetup().save(pk, PendingSetup("Testperson", emptyList(), emptyList(), emptyList())) }
        assertNotNull(runBlocking { entry.pendingSetup().load(pk) })
        runBlocking { entry.session().logout() }
        rule.waitUntil(15_000) { nodes("login-secret").isNotEmpty() }
        assertFalse(secure())
        assertNull(runBlocking { entry.pendingSetup().load(pk) })
    }

    /** Spec 2026-10-29 ncryptsec: the Backup step offers the password backup. */
    @Test
    fun passwordBackupShowsAnNcryptsec() {
        rule.onNodeWithTag("login-create").performClick()
        rule.waitUntil(5_000) { nodes("create-welcome").isNotEmpty() }
        rule.onNodeWithTag("create-next").performClick()
        rule.waitUntil(5_000) { nodes("create-nsec").isNotEmpty() }
        rule.onNodeWithTag("create-backup-password").performScrollTo().performClick()
        rule.waitUntil(5_000) { nodes("backup-password").isNotEmpty() }
        rule.onNodeWithTag("backup-password").performTextInput("password1")
        rule.onNodeWithTag("backup-password-repeat").performTextInput("password1")
        rule.onNodeWithTag("backup-password-create").performClick()
        rule.waitUntil(30_000) { nodes("backup-ncryptsec").isNotEmpty() }
        assertTrue(rule.onNodeWithTag("backup-ncryptsec").fetchSemanticsNode().config[SemanticsProperties.Text].first().text.startsWith("ncryptsec1"))
    }

    @Test
    fun backOnWelcomeAsksAndReturnsToLogin() {
        rule.onNodeWithTag("login-create").performClick()
        rule.waitUntil(5_000) { nodes("create-welcome").isNotEmpty() }
        Espresso.pressBack()
        rule.onNodeWithText(ctx.getString(R.string.create_cancel_text)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.create_cancel_yes)).performClick()
        rule.waitUntil(5_000) { nodes("login-secret").isNotEmpty() }
        assertTrue(nodes("create-welcome").isEmpty())
    }

    private fun orphan() = PendingSetup("Orphan", listOf(relay.url), listOf(relay.url), listOf("1".repeat(64)))

    /** Final review I1: a record left by an interrupted "Let's go" never reaches an existing account pasted later. */
    @Test
    fun aKeyLoginDropsAnOrphanRecordAndPublishesNothing() {
        val key = NewKeys.generate()
        runBlocking { entry.pendingSetup().save(key.pubkey, orphan()) }
        rule.onNodeWithTag("login-secret").performTextInput(key.nsec)
        rule.onNodeWithTag("login-secret-button").performClick()
        rule.waitUntil(30_000) { nodes("fab-compose").isNotEmpty() || nodes("setup-skip").isNotEmpty() }
        assertNull(runBlocking { entry.pendingSetup().load(key.pubkey) })
        Thread.sleep(5_000) // a resumed setup would have gone out by now
        val own = published().filter { it.getString("pubkey") == key.pubkey && it.getInt("kind") in listOf(0, 3, 10002, 10050) }
        assertTrue(own.isEmpty())
    }

    /** Final review I2: a session kept after a failed restore is another account's; its state goes before the new login. */
    @Test
    fun aKeptSessionOfAnotherAccountIsClearedBeforeTheLogin() {
        val a = NewKeys.generate()
        val b = NewKeys.generate()
        runBlocking {
            entry.sessions().save(SessionInfo(a.pubkey, SignerType.LOCAL, null))
            entry.drafts().save(a.pubkey, "new", Draft("from a", updatedAt = System.currentTimeMillis() / 1000))
            entry.pendingSetup().save(a.pubkey, orphan())
            entry.session().loginLocal(LocalSigner(b.secretHex))
        }
        assertEquals(b.pubkey, runBlocking { entry.sessions().session.first() }?.pubkey)
        assertTrue(runBlocking { entry.drafts().all(a.pubkey) }.isEmpty())
        assertNull(runBlocking { entry.pendingSetup().load(a.pubkey) })
    }

    /** Spec 2026-10-17 §7: a restart with the persisted session publishes the account's open record. */
    @Test
    fun restoreResumesAnOpenRecord() {
        val key = NewKeys.generate()
        runBlocking {
            entry.session().loginLocal(LocalSigner(key.secretHex))
            entry.session().release()
            entry.pendingSetup().save(key.pubkey, PendingSetup("Restored", listOf(relay.url), listOf(relay.url), emptyList()))
            entry.session().restore()
        }
        rule.waitUntil(30_000) { runBlocking { entry.pendingSetup().load(key.pubkey) } == null }
        val profile = published().first { it.getInt("kind") == 0 && it.getString("pubkey") == key.pubkey }
        assertEquals("Restored", JSONObject(profile.getString("content")).getString("name"))
    }

    private companion object {
        val KIND = Regex(""""kind"\s*:\s*(\d+)""")
    }
}
